package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.toTyped
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.components.HaPictureEntityData
import ee.schimke.ha.rc.components.HaToggleAccent
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaPictureEntity
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `picture-entity` card. RemoteCompose alpha08 can't pull a live camera stream into a `.rc`
 * document, so the renderer paints a tinted placeholder area + name/state bottom bar; tap forwards
 * to the entity's default action (more-info / toggle).
 */
class PictureEntityCardConverter : CardConverter {
  override val cardType: String = CardTypes.PICTURE_ENTITY

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 160

  /**
   * A picture-entity card renders from a card design. The image is the slot's picture URL
   * (`CardDesignSlots.pictureUrl`), which playback resolves to the entity's own `entity_picture`;
   * the name and state are bindings. Structure: the entity's [EntityDesign] properties (icon and
   * accent behind or instead of the image), whether it has a picture at all, and which strip lines
   * show.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (!TEMPLATE_CONFIG_KEYS.containsAll(card.raw.keys)) return null
    val entityId = card.raw["entity"]?.jsonPrimitive?.contentOrNull ?: return null
    val entity = snapshot.states[entityId] ?: return null
    var ok = false
    val properties = DesignProperties.build {
      with(EntityDesign) {
        ok =
          entity(
            "",
            entityId,
            card.raw["icon"]?.jsonPrimitive?.contentOrNull,
            snapshot,
            PICTURE_DOMAINS,
          )
      }
      enum(HAS_PICTURE, if (entity.attributes["entity_picture"] != null) 1 else 0)
      enum(SHOW_NAME, if (flag(card, "show_name")) 1 else 0)
      enum(SHOW_STATE, if (flag(card, "show_state")) 1 else 0)
    }
    return properties.takeIf { ok }
  }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? {
    val base = EntityDesign.templateEntity(properties, "", 0) ?: return null
    val entity =
      if (properties[HAS_PICTURE] == "1") {
        base.copy(
          attributes =
            kotlinx.serialization.json.JsonObject(
              base.attributes +
                ("entity_picture" to
                  kotlinx.serialization.json.JsonPrimitive(
                    CardDesignSlots.pictureUrl(base.entityId)
                  ))
            )
        )
      } else base
    val card =
      CardConfig(
        type = cardType,
        raw =
          buildJsonObject {
            put("type", cardType)
            put("entity", entity.entityId)
            properties[EntityDesign.CARD_ICON]?.let { put("icon", it) }
            if (properties[SHOW_NAME] == "0") put("show_name", false)
            if (properties[SHOW_STATE] == "0") put("show_state", false)
          },
      )
    return DesignTemplateInput(card, HaSnapshot(states = mapOf(entity.entityId to entity)))
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    val name =
      card.raw["name"]?.jsonPrimitive?.content
        ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
        ?: entityId
        ?: "(no entity)"
    val showName = flag(card, "show_name")
    val showState = flag(card, "show_state")
    val tapCfg = card.raw["tap_action"]?.jsonObject
    val tapAction =
      if (tapCfg != null) parseHaAction(tapCfg, entityId) else defaultTapActionFor(entityId)

    val isActive = entity?.toTyped()?.isActive
    val icons = HaIconMap.entityIcons(card.raw["icon"]?.jsonPrimitive?.content, entity)
    val imageUrl = entity?.attributes?.get("entity_picture")?.jsonPrimitive?.content
    val data =
      HaPictureEntityData(
        entityId = entityId,
        name = name,
        state = LiveValues.state(entityId, formatState(entity)),
        icon = icons.icon,
        accent =
          HaToggleAccent(
            activeAccent = HaStateColor.activeFor(entity).rc,
            inactiveAccent = HaStateColor.inactiveFor(entity).rc,
            initiallyOn = isActive ?: false,
            toggleable = isActive != null,
            activeIcon = icons.activeIcon,
            activeIconWhenUnavailable = icons.activeWhenUnavailable,
            initiallyUnavailable = icons.unavailable,
          ),
        showName = showName,
        showState = showState,
        imageUrl = imageUrl,
        tapAction = tapAction,
      )

    when (LocalCardSizeMode.current) {
      // Dashboard: the HA reference band (fixed natural height).
      CardSizeMode.Wrap -> RemoteHaPictureEntity(data, modifier = modifier)
      // Launcher / Wear: the image is the identity edge-to-edge —
      // it fills and crops the whole cell at every size (no
      // letterbox). A single width gate drops the name at the
      // smallest cell so the state badge alone reads, and keeps the
      // full name+state scrim on wider cells. Single threshold —
      // alpha010 collapses nested ladders to tier 0 (#224).
      CardSizeMode.Fixed ->
        RemoteSizeBreakpoint(
          thresholdsDp = intArrayOf(PICTURE_IDENTITY_THRESHOLD_DP),
          modifier = modifier,
          axis = BreakpointAxis.Width,
        ) { tier ->
          val tierData = if (tier == 0) data.copy(showName = false) else data
          RemoteHaPictureEntity(
            tierData,
            modifier = RemoteModifier.fillMaxSize(),
            fillHeight = true,
          )
        }
    }
  }
}

/**
 * Width gate (dp) for the picture-entity identity tier. Below it (a `2×N` launcher cell) the name
 * strip drops so only the state badge sits over the image; at or above it the full name+state scrim
 * returns. Same ~72 dp cell maths as the bulk family ([BULK_IDENTITY_THRESHOLD_DP]).
 */
private const val PICTURE_IDENTITY_THRESHOLD_DP: Int = 180

private val TEMPLATE_CONFIG_KEYS =
  setOf("type", "entity", "name", "icon", "show_name", "show_state")

/** Picture entities: cameras and images, plus the entity domains every templated card supports. */
private val PICTURE_DOMAINS = EntityDesign.DOMAINS + listOf("camera", "image")

private const val HAS_PICTURE = "has_picture"
private const val SHOW_NAME = "show_name"
private const val SHOW_STATE = "show_state"

private fun flag(card: CardConfig, key: String): Boolean =
  card.raw[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
