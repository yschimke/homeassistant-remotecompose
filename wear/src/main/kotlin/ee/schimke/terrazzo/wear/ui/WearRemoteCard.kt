@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.terrazzo.wear.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.wear.compose.material3.Text
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.*
import ee.schimke.ha.rc.cards.cardVisibilitySignature
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ProvideCardStyle
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.cardPalette
import ee.schimke.terrazzo.wear.data.toEntityState
import ee.schimke.terrazzo.wear.widget.wearWidgetCardStyle
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import kotlinx.serialization.json.Json

internal data class WearCardLook(
  val palette: ThemeStyle = ThemeStyle.TerrazzoHome,
  val style: String = HaUberTheme.Classic.id,
)

internal val LocalWearCardLook = staticCompositionLocalOf { WearCardLook() }

/**
 * The in-app Wear player has the same capabilities as the phone's Compose player. Do not use Glance
 * Wear's constrained capture profile here: its fixed canvas is only for system widgets. Raw configs
 * (including containers), styles and entity values all survive offline sync.
 */
@Composable
internal fun WearRemoteCard(summary: CardSummary, values: Map<String, EntityValue>) {
  val card = remember(summary.rawJson) { decodeWearCard(summary.rawJson) }
  if (card == null) {
    Column {
      // Old or malformed offline records can still identify the card, but a container type is
      // never content. Keep this explicitly marked as a fallback rather than a selected design.
      summary.title.takeIf { it.isNotBlank() && it != summary.type }?.let { Text(it) }
      Text("Card unavailable")
    }
    return
  }
  val registry = remember { defaultRegistry().withEnhancedShutter() }
  val snapshot = remember(values) { wearSnapshot(values) }
  val look = LocalWearCardLook.current
  val style = wearWidgetCardStyle(summary.cardStyleOverride, look.style)
  val context = LocalContext.current
  val density = LocalDensity.current
  val design = CardDesignTemplates.designCard(card, snapshot, registry)
  val designKey = wearDesignKey(design, snapshot, look, style, registry)
  val template =
    remember(design, designKey, density) {
      designKey
        ?.let { wearTemplateBytes(context, it, registry, density.density, density.fontScale) }
        ?.let { bytes -> CardDesignTemplates.playback(design, bytes, registry) }
    }
  // Binding cards seed current values before frame one; baked and structural changes recapture.
  val tick = relativeTimeTickFor(cardEntityIds(card), snapshot)
  val signature = remember(card, snapshot, tick) { registry.cardDataSignature(card, snapshot) }
  val key =
    listOf(
      "wear-app",
      card,
      look,
      style.id,
      density,
      signature,
      cardIconSignature(card, snapshot),
      cardVisibilitySignature(card, snapshot),
    )
  CachedCardPreview(
    cacheKey = key,
    profile = androidXExperimentalWrap,
    modifier = Modifier.fillMaxWidth(),
    card = card,
    snapshot = snapshot,
    liveBindings = signature == null,
    template = template,
  ) {
    ProvideCardRegistry(registry) {
      ProvideCardStyle(style, style.cardPalette(look.palette, darkTheme = true), darkTheme = true) {
        RenderChild(card, snapshot, RemoteModifier.fillMaxWidth())
      }
    }
  }
}

/**
 * The local library's design key [design] plays with on the watch, in [look]'s palette and [style];
 * null when the card has no template shape. Shared with
 * [ee.schimke.terrazzo.wear.sync.WearCardWarmup], so the cards it prepares are the ones the app
 * shows.
 */
internal fun wearDesignKey(
  design: CardConfig,
  snapshot: HaSnapshot,
  look: WearCardLook,
  style: HaUberTheme,
  registry: CardRegistry,
): DesignKey? =
  CardDesignTemplates.keyFor(design, snapshot, look.palette, true, registry, cardStyle = style)

/**
 * [key]'s template, folded for a display of [density] and [fontScale]: what the player plays. Null
 * when the template can't be captured, and the card draws through its converter.
 */
internal fun wearTemplateBytes(
  context: Context,
  key: DesignKey,
  registry: CardRegistry,
  density: Float,
  fontScale: Float,
): ByteArray? =
  CardDesignLibrary.templateOrNull(context, key, registry)?.let {
    CardDesignTemplates.forDisplay(it, density, fontScale)
  }

/** The snapshot the watch's cards render from: its last synced entity values. */
internal fun wearSnapshot(values: Map<String, EntityValue>): HaSnapshot =
  HaSnapshot(states = values.mapValues { (id, v) -> v.toEntityState(id) })

internal fun decodeWearCard(raw: String): CardConfig? = runCatching {
  Json.decodeFromString(CardConfig.serializer(), raw)
}
  .getOrNull()
