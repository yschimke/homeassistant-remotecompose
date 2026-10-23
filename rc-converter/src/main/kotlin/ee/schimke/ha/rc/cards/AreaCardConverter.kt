package ee.schimke.ha.rc.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Window
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalFixedCanvasAxis
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaAreaAction
import ee.schimke.ha.rc.components.HaAreaCardData
import ee.schimke.ha.rc.components.HaAreaStat
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaArea
import ee.schimke.ha.rc.components.RemoteHaSummaryLine
import ee.schimke.ha.rc.components.cardText
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.formatValueWithUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `area` card. The card config carries an `area:` id that we can't resolve without a registry
 * channel. We accept an optional `entities:` list (HA also accepts that as an override) —
 * temperature / humidity sensors light the stat row, light/cover/fan entities become action chips.
 * Tap fires the entity's domain toggle.
 */
class AreaCardConverter : CardConverter {
  override val cardType: String = CardTypes.AREA

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 140

  /**
   * An area card renders from a card design: its name is `card.name`, each stat's label is its
   * entity's state binding and each action's chrome follows `is_on`. What it shows for a row —
   * nothing, a temperature or humidity stat, or an action chip in its domain's icon — is decided by
   * the row's domain and `device_class`, so those are the generation properties, per row.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (!TEMPLATE_CONFIG_KEYS.containsAll(card.raw.keys)) return null
    val rows =
      card.raw["entities"]?.let { it as? kotlinx.serialization.json.JsonArray ?: return null }
    val ids =
      rows.orEmpty().map { row ->
        when (row) {
          is kotlinx.serialization.json.JsonPrimitive -> row.content
          is JsonObject -> row["entity"]?.jsonPrimitive?.content ?: return null
          else -> return null
        }
      }
    // A row the converter skips (missing entity) has no slot to bind; keep the converter.
    val entities = ids.map { snapshot.states[it] ?: return null }
    return DesignProperties.build {
      enum(COUNT, ids.size)
      entities.forEachIndexed { i, entity ->
        string("e$i.domain", entity.entityId.substringBefore('.'))
        string("e$i.device_class", entity.attributes["device_class"]?.jsonPrimitive?.content)
      }
    }
  }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? {
    val count = properties[COUNT]?.toIntOrNull() ?: return null
    val entities =
      (0 until count).map { i ->
        val domain = properties["e$i.domain"] ?: return null
        CardDesignSlots.templateEntity(
          CardDesignSlots.slotId(domain, i),
          placeholderState = "off",
          attributes =
            listOfNotNull(properties["e$i.device_class"]?.let { "device_class" to it }).toMap(),
        )
      }
    val card =
      CardConfig(
        type = cardType,
        raw =
          buildJsonObject {
            put("type", cardType)
            put("name", "Area")
            putJsonArray("entities") { entities.forEach { add(it.entityId) } }
          },
      )
    return DesignTemplateInput(card, HaSnapshot(states = entities.associateBy { it.entityId }))
  }

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings =
    CardSnapshotBindings(mapOf("card.name" to nameOf(card)), emptyMap(), emptyMap(), emptyMap())

  /**
   * A template tap runs the tapped row's own toggle: the converter's only action, rebuilt against
   * the real entity.
   */
  override fun designAction(
    card: CardConfig,
    snapshot: HaSnapshot?,
    slot: Int,
    templateAction: HaAction,
  ): HaAction? {
    if (templateAction !is HaAction.Toggle) return null
    val id = CardDesignSlots.slotEntities(card)?.getOrNull(slot) ?: return null
    return HaAction.Toggle(id)
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val name = nameOf(card)

    val explicitEntities = card.raw["entities"] as? kotlinx.serialization.json.JsonArray
    val entityIds =
      explicitEntities?.mapNotNull {
        when (it) {
          is kotlinx.serialization.json.JsonPrimitive -> it.content
          is JsonObject -> it["entity"]?.jsonPrimitive?.content
          else -> null
        }
      } ?: emptyList()

    val stats = entityIds.mapNotNull { id ->
      val entity = snapshot.states[id] ?: return@mapNotNull null
      val deviceClass = entity.attributes["device_class"]?.jsonPrimitive?.content
      val unit = entity.attributes["unit_of_measurement"]?.jsonPrimitive?.content
      when (deviceClass) {
        "temperature" ->
          HaAreaStat(
            icon = Icons.Filled.Thermostat,
            label = LiveValues.state(id, areaStat(entity, snapshot, unit, "°C")),
          )
        "humidity",
        "moisture" ->
          HaAreaStat(
            icon = Icons.Filled.WaterDrop,
            label = LiveValues.state(id, areaStat(entity, snapshot, unit, "%")),
          )
        else -> null
      }
    }

    val actions = entityIds.mapNotNull { id ->
      val entity = snapshot.states[id] ?: return@mapNotNull null
      val domain = id.substringBefore('.')
      val (icon, accent) =
        when (domain) {
          "light" -> Icons.Filled.Lightbulb to Color(0xFFFFBE3E)
          "switch",
          "input_boolean" -> Icons.Filled.PowerSettingsNew to Color(0xFF2196F3)
          "cover" -> Icons.Filled.Window to Color(0xFF00897B)
          "fan" -> Icons.Filled.Air to Color(0xFF00BFA5)
          else -> return@mapNotNull null
        }
      HaAreaAction(
        entityId = id,
        icon = icon,
        accent = accent,
        initiallyActive = HaLiveBindings.isOn(id, entity.state),
        tapAction = HaAction.Toggle(id),
      )
    }

    val data = HaAreaCardData(name = name, stats = stats, actions = actions)
    if (
      LocalCardSizeMode.current == CardSizeMode.Fixed &&
        LocalFixedCanvasAxis.current == BreakpointAxis.Height
    ) {
      // A Wear slot: the Small container holds the area's name over its first reading, the Large
      // one the full card.
      RemoteSizeBreakpoint(
        thresholdsDp = intArrayOf(WEAR_SMALL_MAX_HEIGHT_DP),
        modifier = modifier,
        axis = BreakpointAxis.Height,
      ) { tier ->
        if (tier == 0) {
          RemoteHaSummaryLine(
            if (stats.isEmpty()) "Area".rs else cardText("name", name),
            stats.firstOrNull()?.label ?: cardText("name", name),
          )
        } else {
          RemoteHaArea(data, modifier = RemoteModifier.fillMaxWidth())
        }
      }
      return
    }
    RemoteHaArea(data, modifier = modifier)
  }
}

private val TEMPLATE_CONFIG_KEYS = setOf("type", "name", "area", "entities")
private const val COUNT = "count"

private fun nameOf(card: CardConfig): String =
  card.raw["name"]?.jsonPrimitive?.content
    ?: card.raw["area"]?.jsonPrimitive?.content?.replace('_', ' ')?.replaceFirstChar {
      it.uppercaseChar()
    }
    ?: "Area"

/**
 * An area stat's label under `<id>.state`: the host pushes `formatState` there, so an entity with
 * its own unit bakes exactly that. Only a reading without a unit gets the stat's [fallbackUnit].
 */
private fun areaStat(
  entity: EntityState,
  snapshot: HaSnapshot,
  unit: String?,
  fallbackUnit: String,
): String =
  if (unit != null) formatState(entity, snapshot)
  else formatValueWithUnit(entity.state, fallbackUnit)
