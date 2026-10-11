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
import ee.schimke.ha.model.HaSnapshot
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
   * The [DesignKey] a live [card] renders with on [surface], or null when the card has no template
   * shape (it then renders through the local converter). [snapshot] supplies the entity attributes
   * that are part of the shape (`device_class`, `icon`); values never are.
   */
  fun keyFor(
    card: CardConfig,
    snapshot: HaSnapshot,
    style: ThemeStyle,
    dark: Boolean,
    surface: DesignSurface = DesignSurface.MobileApp,
  ): DesignKey? {
    if (surface != DesignSurface.MobileApp) return null
    val shape = CardDesignSlots.shapeOf(card, snapshot) ?: return null
    return DesignKey(
      cardType = card.type,
      surface = surface,
      rcApiLevel = MOBILE_APP_API_LEVEL,
      shape = shape,
      theme = DesignKey.bakedTheme(style.name, dark),
    )
  }

  /**
   * Template playback for [card] with [bytes] from the design repository. Bindings and the tap
   * action both come from the card's own config; nothing but layout is taken from the template.
   */
  fun playback(card: CardConfig, bytes: ByteArray): CardTemplatePlayback {
    val entityId = CardDesignSlots.slotEntities(card)?.firstOrNull()
    val tapCfg = card.raw["tap_action"] as? kotlinx.serialization.json.JsonObject
    return CardTemplatePlayback(
      bytes = bytes,
      bindings = { snapshot -> CardDesignSlots.slotBindings(card, snapshot) },
      tapAction =
        if (tapCfg != null) parseHaAction(tapCfg, entityId) else defaultTapActionFor(entityId),
    )
  }

  /**
   * The keys the spike publishes to a server: tile × domain × theme style × light/dark, mobile app
   * only, for entities with no `device_class` or `icon` attribute. Every other shape is served by
   * the local [CardDesignLibrary].
   */
  fun spikeKeys(): List<DesignKey> = buildList {
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
          add(key)
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
    registry: CardRegistry = ee.schimke.ha.rc.cards.defaultRegistry(),
  ): BuiltDesign {
    require(key.surface == DesignSurface.MobileApp) { "spike only builds mobile-app templates" }
    val (style, dark) = parseBakedTheme(key.theme)
    val card = CardDesignSlots.templateCard(key.cardType, key.shape)
    val snapshot = CardDesignSlots.templateSnapshot(key.shape)
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
  fun buildAll(context: Context): List<BuiltDesign> = spikeKeys().map { capture(context, it) }

  private fun parseBakedTheme(theme: String): Pair<ThemeStyle, Boolean> {
    val parts = theme.split(':')
    require(parts.size == 3 && parts[0] == "baked") { "not a baked theme: $theme" }
    return ThemeStyle.valueOf(parts[1]) to (parts[2] == "dark")
  }
}

/**
 * The local design library: every template the app can build from its own converters, captured on
 * first use and kept for the process. It is the fallback under the design server: a card renders
 * from the server's design when one is cached for its key, and from this library otherwise. Both
 * are the same kind of document played the same way, so there is one rendering path.
 *
 * Capture runs on the calling thread, like any local card capture.
 */
object CardDesignLibrary {
  private val templates = java.util.concurrent.ConcurrentHashMap<DesignKey, ByteArray>()

  fun template(context: Context, key: DesignKey): ByteArray =
    templates.getOrPut(key) { CardDesignTemplates.capture(context, key).bytes }
}
