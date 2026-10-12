@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import android.content.Context
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.LocalTemplateBindings
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.TemplateBindings
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Card design templates: build them (pipeline side) and key them (client side). */
object CardDesignTemplates {

  /** RC document API level the mobile-app template targets — the capture profile's level. */
  val MOBILE_APP_API_LEVEL: Int = CoreDocument.DOCUMENT_API_LEVEL

  /**
   * The [DesignKey] a live [card] renders with on [surface], or null when the card has no template
   * shape (it then renders through the local converter). The shape is the card converter's
   * [CardConverter.designProperties]: discrete generation inputs, never values.
   */
  fun keyFor(
    card: CardConfig,
    snapshot: HaSnapshot,
    style: ThemeStyle,
    dark: Boolean,
    registry: CardRegistry = defaultTemplateRegistry,
    surface: DesignSurface = DesignSurface.MobileApp,
  ): DesignKey? {
    if (surface != DesignSurface.MobileApp) return null
    val design = designCard(card, snapshot, registry)
    val shape =
      registry.converterFor(design.type)?.designProperties(design, snapshot)?.shape ?: return null
    return DesignKey(
      cardType = design.type,
      surface = surface,
      rcApiLevel = MOBILE_APP_API_LEVEL,
      shape = shape,
      theme = DesignKey.bakedTheme(style.name, dark),
    )
  }

  /**
   * The card whose design [card] renders as for [snapshot]: [card] itself, or the card its
   * converter delegates to ([CardConverter.designDelegate]), followed to the end. Callers key and
   * play the result, so a delegate's bindings and taps are its own.
   */
  fun designCard(
    card: CardConfig,
    snapshot: HaSnapshot,
    registry: CardRegistry = defaultTemplateRegistry,
  ): CardConfig {
    var current = card
    repeat(MAX_DELEGATION) {
      current =
        registry.converterFor(current.type)?.designDelegate(current, snapshot) ?: return current
    }
    return current
  }

  private const val MAX_DELEGATION = 8

  /**
   * Template playback for [card] with [bytes] from the design repository. Bindings and tap actions
   * all come from the card's own config; nothing but layout is taken from the template.
   */
  fun playback(
    card: CardConfig,
    bytes: ByteArray,
    registry: CardRegistry = defaultTemplateRegistry,
  ): CardTemplatePlayback {
    val converter = registry.converterFor(card.type)
    val entityId = card.raw["entity"]?.jsonPrimitive?.contentOrNull
    val rows = card.raw["entities"] as? JsonArray
    val slotTaps =
      if (rows != null) {
        // Same rows, in the same order, as CardDesignSlots.slotEntries.
        rows.mapNotNull { row ->
          val obj = row as? JsonObject
          val id =
            (obj?.get("entity") as? JsonPrimitive)?.contentOrNull
              ?: (row as? JsonPrimitive)?.contentOrNull
              ?: return@mapNotNull null
          tapActionOf(obj?.get("tap_action") as? JsonObject, id)
        }
      } else if (card.raw["cards"] is JsonArray) {
        // A container: every child slot's tap is resolved by the container (designAction).
        List(CardDesignSlots.slotEntities(card)?.size ?: 0) { HaAction.None }
      } else {
        listOfNotNull(entityId?.let { tapActionOf(card.raw["tap_action"] as? JsonObject, it) })
      }
    return CardTemplatePlayback(
      bytes = bytes,
      bindings = { snapshot ->
        val slots = CardDesignSlots.slotBindings(card, snapshot)
        converter?.designBindings(card, snapshot)?.let { slots + it } ?: slots
      },
      tapAction = tapActionOf(card.raw["tap_action"] as? JsonObject, entityId),
      slotTapActions = slotTaps,
      bitmapUrl = { url, snapshot -> CardDesignSlots.resolvePictureUrl(url, card, snapshot) },
      slotAction = { snapshot, slot, action ->
        converter?.designAction(card, snapshot, slot, action)
      },
    )
  }

  private fun tapActionOf(config: JsonObject?, entityId: String?) =
    if (config != null) parseHaAction(config, entityId) else defaultTapActionFor(entityId)

  /**
   * The keys the spike publishes to a server: tile × domain × theme style × light/dark, mobile app
   * only, for entities with no `device_class` or `icon` attribute. Every other shape is served by
   * the local [CardDesignLibrary].
   */
  fun spikeKeys(): List<DesignKey> = buildList {
    for (domain in ee.schimke.ha.rc.cards.TileCardConverter.TEMPLATE_DOMAINS) {
      for (style in ThemeStyle.entries) {
        for (dark in listOf(false, true)) {
          val key =
            DesignKey(
              cardType = ee.schimke.ha.model.CardTypes.TILE,
              surface = DesignSurface.MobileApp,
              rcApiLevel = MOBILE_APP_API_LEVEL,
              shape =
                DesignProperties.build {
                    string(ee.schimke.ha.rc.cards.TileCardConverter.DOMAIN, domain)
                  }
                  .shape,
              theme = DesignKey.bakedTheme(style.name, dark),
            )
          add(key)
        }
      }
    }
  }

  /**
   * Capture the template for [key]: the card's converter, run against slot entity ids and a
   * placeholder snapshot, with [LocalTemplateBindings] set. Wrap mode at the dashboard's capture
   * profile, so the bytes play exactly where a live tile would.
   */
  fun capture(
    context: Context,
    key: DesignKey,
    registry: CardRegistry = defaultTemplateRegistry,
  ): BuiltDesign {
    require(key.surface == DesignSurface.MobileApp) { "spike only builds mobile-app templates" }
    val (style, dark) = parseBakedTheme(key.theme)
    val converter =
      requireNotNull(registry.converterFor(key.cardType)) { "no converter: ${key.cardType}" }
    val (card, snapshot) =
      requireNotNull(converter.designTemplate(DesignProperties.parse(key.shape))) {
          "${key.cardType} has no template for ${key.shape}"
        }
        // The placeholder converter templates its own type; the key names the card's.
        .let {
          if (it.card.type == key.cardType) it else it.copy(card = it.card.ofType(key.cardType))
        }
    val theme = haThemeFor(style, dark)
    val bytes = runBlocking {
      captureSingleRemoteDocument(context = context, profile = androidXExperimentalWrap) {
          // Bound name / title text instead of literals: see LocalTemplateBindings.
          CompositionLocalProvider(LocalTemplateBindings provides TemplateBindings()) {
            ProvideCardRegistry(registry) {
              ProvideHaTheme(theme) { RenderChild(card, snapshot, RemoteModifier.fillMaxWidth()) }
            }
          }
        }
        .bytes
    }
    return BuiltDesign(
      key = key,
      slots = CardDesignSlots.slotEntities(card)?.size ?: 0,
      bytes = bytes,
    )
  }

  /** Build every spike template. */
  fun buildAll(context: Context): List<BuiltDesign> = spikeKeys().map { capture(context, it) }

  /** Registry used when the caller doesn't pass its own; designs only need the built-in cards. */
  internal val defaultTemplateRegistry: CardRegistry by lazy {
    ee.schimke.ha.rc.cards.defaultRegistry()
  }

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

  fun template(
    context: Context,
    key: DesignKey,
    registry: CardRegistry = CardDesignTemplates.defaultTemplateRegistry,
  ): ByteArray =
    templates.getOrPut(key) { CardDesignTemplates.capture(context, key, registry).bytes }
}

/** This card as [type]: its config's `type:` and its [CardConfig.type] both. */
internal fun CardConfig.ofType(type: String): CardConfig =
  CardConfig(
    type = type,
    raw =
      kotlinx.serialization.json.JsonObject(
        raw + ("type" to kotlinx.serialization.json.JsonPrimitive(type))
      ),
  )
