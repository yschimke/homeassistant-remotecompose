@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.toTyped
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.CardWidthClass
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.LocalGridSquareCells
import ee.schimke.ha.rc.RemoteMinSizeGate
import ee.schimke.ha.rc.components.HaButtonData
import ee.schimke.ha.rc.components.HaToggleAccent
import ee.schimke.ha.rc.components.RemoteHaButton
import ee.schimke.ha.rc.components.RemoteHaButtonIcon
import ee.schimke.ha.rc.components.RemoteHaToggleButton
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `button` card. Toggleable entities (lights / switches / locks / covers / media-players etc)
 * render through [RemoteHaToggleButton] so tap crossfades the accent when the host writes the new
 * state back; read-only entities go through the simpler [RemoteHaButton].
 *
 * In [CardSizeMode.Fixed] the converter encodes a 2-tier ladder driven by the runtime canvas
 * (mirrors the button ladder in `docs/architecture/adaptive-card-layouts.md` §button):
 *
 * * **icon + name** — the full button, self-centred via `fillMaxSize` so it fills / centres rather
 *   than pinning top-left on big cells.
 * * **icon only** — [RemoteHaButtonIcon], the tinted-chip identity tier for cells too small for the
 *   label (`1×1` launcher, Wear S).
 *
 * The gate is a **single** boolean off a single float expression `min(w, h ·
 * minW/minH).isGreaterThanOrEqualTo(minW)`, which is true only when the cell is both wide enough
 * (`w ≥ minW`) and tall enough (`h ≥ minH`). Encoding the 2-D test as one `min` expression (rather
 * than `w.isGreaterThanOrEqualTo(..) .and(h.isGreaterThanOrEqualTo(..))`) sidesteps the alpha010
 * quirk where `RemoteBoolean .and()` and nested width/height state-layouts collapse to `false` at
 * playback (#224, see `GaugeCardConverter`). It lets the narrow-tall `1×1` cell and the wide-short
 * Wear S chip *both* fall to the icon-only tier while the wide-and-tall `2×1` / `3×2` / Wear L
 * cells keep the name — something no single width-only or height-only gate can do.
 */
class ButtonCardConverter : CardConverter {
  override val cardType: String = CardTypes.BUTTON

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 84

  override fun naturalWidthClass(card: CardConfig, snapshot: HaSnapshot): CardWidthClass =
    CardWidthClass.Compact

  /** The icon chip plus its padding, with a few characters of name: narrower and it's no button. */
  override fun gridMinCellWidthDp(card: CardConfig, snapshot: HaSnapshot): Int = MinGridCellWidthDp

  /** HA's square grid cell: the button centres its icon and name in it. */
  override fun gridSquareCell(card: CardConfig, snapshot: HaSnapshot): Boolean = true

  /**
   * A plain entity button renders from a card design: the [EntityDesign] properties of its entity
   * plus `show_name`, which decides whether the name line exists at all.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? =
    EntityDesign.single(card, snapshot, TEMPLATE_CONFIG_KEYS) {
      enum(SHOW_NAME, if (showName(card)) 1 else 0)
    }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? =
    EntityDesign.singleTemplate(cardType, properties) {
      if (properties[SHOW_NAME] == "0") put("show_name", false)
    }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val data = buttonData(card, snapshot)
    when (LocalCardSizeMode.current) {
      // In a square grid cell (as narrow as MinGridCellWidthDp, so as short) the compact body
      // keeps the icon and name inside the square.
      CardSizeMode.Wrap -> RenderButton(data, modifier, compact = LocalGridSquareCells.current)
      CardSizeMode.Fixed -> AdaptiveButton(data, modifier)
    }
  }

  @Composable
  private fun AdaptiveButton(data: HaButtonData, modifier: RemoteModifier) {
    RemoteMinSizeGate(
      minWidthDp = MinIconNameWidthDp,
      minHeightDp = MinIconNameHeightDp,
      modifier = modifier.fillMaxSize(),
    ) { roomy ->
      if (roomy) {
        RenderButton(data, RemoteModifier.fillMaxSize(), compact = true)
      } else {
        RemoteHaButtonIcon(data, RemoteModifier.fillMaxSize())
      }
    }
  }

  @Composable
  private fun RenderButton(data: HaButtonData, modifier: RemoteModifier, compact: Boolean = false) {
    if (data.accent.toggleable) {
      RemoteHaToggleButton(data, modifier, compact)
    } else {
      RemoteHaButton(data, modifier, compact)
    }
  }

  private companion object {
    val TEMPLATE_CONFIG_KEYS = setOf("type", "entity", "name", "icon", "show_name")

    const val SHOW_NAME = "show_name"

    /** Smallest cell width that fits the icon + name tier. */
    const val MinIconNameWidthDp = 96

    /** Smallest cell height that fits the icon + reserved label line. */
    const val MinIconNameHeightDp = 72

    /** Narrowest `grid` cell: the compact body (44 dp chip + 12 dp sides) plus a short name. */
    const val MinGridCellWidthDp = 80
  }
}

/** Build the [HaButtonData] payload from card config + live snapshot. */
@Composable
private fun buttonData(card: CardConfig, snapshot: HaSnapshot): HaButtonData {
  val entityId = card.raw["entity"]?.jsonPrimitive?.content
  val entity = entityId?.let { snapshot.states[it] }
  val name =
    card.raw["name"]?.jsonPrimitive?.content
      ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
      ?: entityId
      ?: "Button"
  val showName = showName(card)
  val tapCfg = card.raw["tap_action"]?.jsonObject
  val tapAction =
    if (tapCfg != null) parseHaAction(tapCfg, entityId) else defaultTapActionFor(entityId)

  val isActive = entity?.toTyped()?.isActive
  val toggleable = isActive != null
  return HaButtonData(
    entityId = entityId,
    name = name,
    icon = HaIconMap.resolve(card.raw["icon"]?.jsonPrimitive?.content, entity),
    accent =
      HaToggleAccent(
        activeAccent = HaStateColor.activeFor(entity).rc,
        inactiveAccent = HaStateColor.inactiveFor(entity).rc,
        initiallyOn = isActive ?: false,
        toggleable = toggleable,
      ),
    showName = showName,
    tapAction = tapAction,
  )
}

private fun showName(card: CardConfig): Boolean =
  card.raw["show_name"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
