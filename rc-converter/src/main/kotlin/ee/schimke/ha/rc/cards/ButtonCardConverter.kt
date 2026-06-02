@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteStateLayout
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.toTyped
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.CardWidthClass
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.components.HaButtonData
import ee.schimke.ha.rc.components.HaToggleAccent
import ee.schimke.ha.rc.components.RemoteHaButton
import ee.schimke.ha.rc.components.RemoteHaButtonIcon
import ee.schimke.ha.rc.components.RemoteHaToggleButton
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.parseHaAction
import java.text.DecimalFormat
import java.util.UUID
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
 * The gate is a **single** boolean off a single float expression `min(w, h · minW/minH).ge(minW)`,
 * which is true only when the cell is both wide enough (`w ≥ minW`) and tall enough (`h ≥ minH`).
 * Encoding the 2-D test as one `min` expression (rather than `w.ge(..) .and(h.ge(..))`) sidesteps
 * the alpha010 quirk where `RemoteBoolean .and()` and nested width/height state-layouts collapse to
 * `false` at playback (#224, see `GaugeCardConverter`). It lets the narrow-tall `1×1` cell and the
 * wide-short Wear S chip *both* fall to the icon-only tier while the wide-and-tall `2×1` / `3×2` /
 * Wear L cells keep the name — something no single width-only or height-only gate can do.
 */
class ButtonCardConverter : CardConverter {
  override val cardType: String = CardTypes.BUTTON

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 84

  override fun naturalWidthClass(card: CardConfig, snapshot: HaSnapshot): CardWidthClass =
    CardWidthClass.Compact

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val data = buttonData(card, snapshot)
    when (LocalCardSizeMode.current) {
      CardSizeMode.Wrap -> RenderButton(data, modifier)
      CardSizeMode.Fixed -> AdaptiveButton(data, modifier)
    }
  }

  @Composable
  private fun AdaptiveButton(data: HaButtonData, modifier: RemoteModifier) {
    val gateName = remember { "__button_gate_${UUID.randomUUID()}" }
    // ratio = minW/minH, so `h · ratio ≥ minW` ⇔ `h ≥ minH`.
    val ratio = MinIconNameWidthDp.toFloat() / MinIconNameHeightDp.toFloat()
    val gate =
      RemoteFloat.createNamedRemoteFloatExpression(gateName, RemoteState.Domain.User) {
        componentWidth().min(componentHeight() * ratio.rf)
      }
    val hasRoomForName = gate.ge(MinIconNameWidthDp.rdp.toPx())

    RemoteBox(modifier = modifier.fillMaxSize()) {
      // Materialise the gate expression in the document — the
      // state-layout reads it via a derived boolean, which alone
      // doesn't pull the named float into the capture (#224).
      RemoteText(text = gate.toRemoteString(IntFormat), color = Color.Transparent.rc)
      RemoteStateLayout(hasRoomForName) { roomy ->
        if (roomy) {
          RenderButton(data, RemoteModifier.fillMaxSize(), compact = true)
        } else {
          RemoteHaButtonIcon(data, RemoteModifier.fillMaxSize())
        }
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
    /** Smallest cell width that fits the icon + name tier. */
    const val MinIconNameWidthDp = 96

    /** Smallest cell height that fits the icon + reserved label line. */
    const val MinIconNameHeightDp = 72
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
  val showName = card.raw["show_name"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
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

private val IntFormat = DecimalFormat("0")
