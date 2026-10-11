@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import android.content.Context
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import kotlinx.coroutines.runBlocking

/**
 * True while a converter is capturing a **card design template** (see
 * `docs/architecture/card-design-server.md`) rather than a live card. Converters that support
 * templates bind config-derived text they would otherwise bake — today the tile name, as
 * `<slot>.name` — so the client can fill it in. False for every live capture, so dashboards,
 * widgets and previews are unchanged.
 */
val LocalCardTemplateCapture = staticCompositionLocalOf { false }

/** Card design templates: build them (pipeline side) and key them (client side). */
object CardDesignTemplates {

  /** RC document API level the mobile-app template targets — the capture profile's level. */
  val MOBILE_APP_API_LEVEL: Int = CoreDocument.DOCUMENT_API_LEVEL

  /**
   * The [DesignKey] a live [card] would render with on [surface], or null when the card has no
   * template shape (it then renders through the local converter).
   */
  fun keyFor(
    card: CardConfig,
    style: ThemeStyle,
    dark: Boolean,
    surface: DesignSurface = DesignSurface.MobileApp,
  ): DesignKey? {
    if (surface != DesignSurface.MobileApp) return null
    val shape = CardDesignSlots.shapeOf(card) ?: return null
    return DesignKey(
      cardType = card.type,
      surface = surface,
      rcApiLevel = MOBILE_APP_API_LEVEL,
      shape = shape,
      theme = DesignKey.bakedTheme(style.name, dark),
    )
  }

  /** Every key the spike publishes: tile × domain × theme style × light/dark, mobile app only. */
  fun spikeKeys(): List<Pair<DesignKey, String>> = buildList {
    for (domain in CardDesignSlots.TILE_DOMAINS) {
      for (style in ThemeStyle.entries) {
        for (dark in listOf(false, true)) {
          val key =
            DesignKey(
              cardType = ee.schimke.ha.model.CardTypes.TILE,
              surface = DesignSurface.MobileApp,
              rcApiLevel = MOBILE_APP_API_LEVEL,
              shape = "domain=$domain",
              theme = DesignKey.bakedTheme(style.name, dark),
            )
          add(key to domain)
        }
      }
    }
  }

  /**
   * Capture the template for [key]: the card's converter, run against slot entity ids and a
   * placeholder snapshot, inside [LocalCardTemplateCapture]. Wrap mode at the dashboard's capture
   * profile, so the bytes play exactly where a live tile would.
   */
  fun capture(
    context: Context,
    key: DesignKey,
    domain: String,
    registry: CardRegistry = ee.schimke.ha.rc.cards.defaultRegistry(),
  ): BuiltDesign {
    require(key.surface == DesignSurface.MobileApp) { "spike only builds mobile-app templates" }
    val (style, dark) = parseBakedTheme(key.theme)
    val card = CardDesignSlots.templateCard(key.cardType, domain)
    val snapshot = CardDesignSlots.templateSnapshot(domain)
    val theme = haThemeFor(style, dark)
    val bytes = runBlocking {
      captureSingleRemoteDocument(context = context, profile = androidXExperimentalWrap) {
          CompositionLocalProvider(LocalCardTemplateCapture provides true) {
            ProvideCardRegistry(registry) {
              ProvideHaTheme(theme) { RenderChild(card, snapshot, RemoteModifier.fillMaxWidth()) }
            }
          }
        }
        .bytes
    }
    return BuiltDesign(key = key, slots = 1, bytes = bytes)
  }

  /** Build every spike template. */
  fun buildAll(context: Context): List<BuiltDesign> =
    spikeKeys().map { (key, domain) -> capture(context, key, domain) }

  private fun parseBakedTheme(theme: String): Pair<ThemeStyle, Boolean> {
    val parts = theme.split(':')
    require(parts.size == 3 && parts[0] == "baked") { "not a baked theme: $theme" }
    return ThemeStyle.valueOf(parts[1]) to (parts[2] == "dark")
  }
}
