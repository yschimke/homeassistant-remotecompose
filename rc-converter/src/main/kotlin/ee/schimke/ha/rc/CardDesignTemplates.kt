@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import android.content.Context
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.RemoteDensity
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.drawWithContent
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.composeai.rcplayer.edit.hardcodeDensity
import ee.schimke.composeai.rcplayer.edit.optimize
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.design.CardBindingRecipe
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.LocalTemplateBindings
import ee.schimke.ha.rc.components.ProvideCardStyle
import ee.schimke.ha.rc.components.TemplateBindings
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.cardPalette
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
    cardStyle: HaUberTheme = HaUberTheme.Classic,
  ): DesignKey? {
    if (surface != DesignSurface.MobileApp) return null
    val design = designCard(card, snapshot, registry)
    val shape =
      registry.converterFor(design.type)?.framedDesignProperties(design, snapshot)?.shape
        ?: return null
    return DesignKey(
      cardType = design.type,
      surface = surface,
      rcApiLevel = MOBILE_APP_API_LEVEL,
      shape = shape,
      theme = bakedTheme(style, dark, cardStyle),
    )
  }

  /**
   * The `theme` of a design key. Classic is baked in the app palette, `baked:<palette>:<mode>`. Any
   * other card style brings its own palette ([cardPalette]), so its theme names only the style and
   * the mode it is drawn in, `style:<id>:<mode>`: one design per shape whichever app palette is
   * picked, and a style with a single mode has one design in either.
   */
  fun bakedTheme(style: ThemeStyle, dark: Boolean, cardStyle: HaUberTheme): String =
    if (cardStyle.id == HaUberTheme.Classic.id) DesignKey.bakedTheme(style.name, dark)
    else DesignKey.styledTheme(cardStyle.id, drawnDark(cardStyle, dark))

  /** The mode [cardStyle] draws in when the host asks for [dark]: its only one, if it has one. */
  private fun drawnDark(cardStyle: HaUberTheme, dark: Boolean): Boolean =
    when {
      cardStyle.light == null -> true
      cardStyle.dark == null -> false
      else -> dark
    }

  /** The modes [cardStyle] draws in, as `dark` flags: what a library captures it in. */
  fun modesOf(cardStyle: HaUberTheme): List<Boolean> =
    listOf(false, true).map { drawnDark(cardStyle, it) }.distinct()

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

  /** A template's capture canvas: 400 × 800 dp at mdpi, whatever machine captures it. */
  private val TEMPLATE_DISPLAY = RemoteCreationDisplayInfo(400, 800, 160)

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
          tapActionOf(card, obj?.get("tap_action") as? JsonObject, id)
        }
      } else if (card.raw["cards"] is JsonArray) {
        // A container: every child slot's tap is resolved by the container (designAction).
        List(CardDesignSlots.slotEntities(card)?.size ?: 0) { HaAction.None }
      } else {
        listOfNotNull(
          entityId?.let { tapActionOf(card, card.raw["tap_action"] as? JsonObject, it) }
        )
      }
    return CardTemplatePlayback(
      bytes = bytes,
      bindings = { snapshot, clock ->
        val slots = CardDesignSlots.slotBindings(card, snapshot)
        converter?.designBindings(card, snapshot, clock)?.let { slots + it } ?: slots
      },
      tapAction = tapActionOf(card, card.raw["tap_action"] as? JsonObject, entityId),
      slotTapActions = slotTaps,
      bitmapUrl = { url, snapshot -> CardDesignSlots.resolvePictureUrl(url, card, snapshot) },
      slotAction = { snapshot, slot, action ->
        converter?.designAction(card, snapshot, slot, action)
      },
    )
  }

  private fun tapActionOf(card: CardConfig, config: JsonObject?, entityId: String?) =
    if (config != null) parseHaAction(config, entityId) else defaultTapActionFor(card, entityId)

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
    val (style, dark, cardStyle) = parseBakedTheme(key.theme)
    val converter =
      requireNotNull(registry.converterFor(key.cardType)) { "no converter: ${key.cardType}" }
    val (card, snapshot) =
      requireNotNull(converter.framedDesignTemplate(DesignProperties.parse(key.shape))) {
          "${key.cardType} has no template for ${key.shape}"
        }
        // The placeholder converter templates its own type; the key names the card's.
        .let {
          if (it.card.type == key.cardType) it else it.copy(card = it.card.ofType(key.cardType))
        }
    val palette = cardStyle.cardPalette(style, dark)
    val bytes = runBlocking {
      // Host density: every dp and sp reads the player's display, never the capture's, so a design
      // captured once (on the design server) plays at any phone's density and font scale. The
      // device folds its own display in once (forDisplay).
      captureSingleRemoteDocument(
          context = context,
          // The header records a canvas; a fixed one keeps the bytes the same on every machine
          // (a wrap-mode card lays out for the width its host gives it, not this).
          creationDisplayInfo = TEMPLATE_DISPLAY,
          remoteDensity = RemoteDensity.Host,
          profile = androidXExperimentalWrap,
        ) {
          // Bound name / title text instead of literals: see LocalTemplateBindings.
          CompositionLocalProvider(LocalTemplateBindings provides TemplateBindings()) {
            ProvideCardRegistry(registry) {
              ProvideCardStyle(cardStyle, palette, dark) {
                RenderChild(
                  card,
                  snapshot,
                  RemoteModifier.fillMaxWidth()
                    .declaringRecipe(converter.designRecipe(DesignProperties.parse(key.shape))),
                )
              }
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

  /**
   * [template] (a Host-density design) with this display's [density] and [fontScale] written in and
   * the document optimised: the expressions that read the player's display become constants, so a
   * phone pays for them once rather than on every frame. Folded once per template and display;
   * [template] itself when it can't be folded, which still plays correctly.
   */
  fun forDisplay(template: ByteArray, density: Float, fontScale: Float): ByteArray {
    val display = density to fontScale
    synchronized(folded) {
      folded[template]
        ?.takeIf { it.first == display }
        ?.let {
          return it.second ?: template
        }
    }
    val bytes = runCatching {
      RcDocumentCodec.encode(
        RcDocumentCodec.decode(template, RcWireLimits())
          .hardcodeDensity(density, fontScale)
          .optimize()
      )
    }
      .getOrNull()
    // Null for "can't be folded": the map must never hold its own key strongly.
    synchronized(folded) { folded[template] = display to bytes }
    return bytes ?: template
  }

  /**
   * Folded templates, by the template's own array: designs and library templates keep one array per
   * key, so a key is folded once, and a template dropped from both goes from here too.
   */
  private val folded = java.util.WeakHashMap<ByteArray, Pair<Pair<Float, Float>, ByteArray?>>()

  /** Build every spike template. */
  fun buildAll(context: Context): List<BuiltDesign> = spikeKeys().map { capture(context, it) }

  /**
   * This modifier, also declaring [recipe] in the document being captured as the named string
   * [CardBindingRecipe.BINDING], for hosts that compute card-level bindings from the template alone
   * (the JavaScript embed). Nothing draws it. It is written from the draw pass because the capture
   * forbids document writes during composition; with no recipe the modifier is unchanged, so such
   * templates' bytes are too.
   */
  private fun RemoteModifier.declaringRecipe(recipe: CardBindingRecipe?): RemoteModifier {
    if (recipe == null) return this
    val text =
      RemoteString.createNamedRemoteString(
        CardBindingRecipe.BINDING,
        recipe.toJson().toString(),
        RemoteState.Domain.User,
      )
    return drawWithContent {
      text.getIdForCreationState(remoteComposeCreationState)
      drawContent()
    }
  }

  /** Registry used when the caller doesn't pass its own; designs only need the built-in cards. */
  internal val defaultTemplateRegistry: CardRegistry by lazy {
    ee.schimke.ha.rc.cards.defaultRegistry()
  }

  private fun parseBakedTheme(theme: String): Triple<ThemeStyle, Boolean, HaUberTheme> {
    val parts = theme.split(':')
    // A card style's own palette: the app palette doesn't change it, so any will do.
    if (parts.size == 3 && parts[0] == "style") {
      return Triple(ThemeStyle.entries.first(), parts[2] == "dark", HaUberTheme.byId(parts[1]))
    }
    require(parts.size in 3..4 && parts[0] == "baked") { "not a baked theme: $theme" }
    val cardStyle = parts.getOrNull(3)?.let(HaUberTheme::byId) ?: HaUberTheme.Classic
    return Triple(ThemeStyle.valueOf(parts[1]), parts[2] == "dark", cardStyle)
  }
}

/**
 * The local design library: every template the app can build from its own converters. It is the
 * fallback under the design server: a card renders from the server's design when one is cached for
 * its key, and from this library otherwise. Both are the same kind of document played the same way,
 * so there is one rendering path.
 *
 * A template is captured on first use, on the calling thread like any local card capture, and kept
 * for the process. With a [store] it is also kept on disk, so a key is captured once per store
 * rather than on every start: the host scopes the store to what a capture depends on (this build of
 * the app, the display's density and font scale).
 */
object CardDesignLibrary {
  private val templates = java.util.concurrent.ConcurrentHashMap<DesignKey, ByteArray>()

  /** Where captured templates persist, or null to keep them for the process only. */
  @Volatile var store: LocalDesignStore? = null

  fun template(
    context: Context,
    key: DesignKey,
    registry: CardRegistry = CardDesignTemplates.defaultTemplateRegistry,
  ): ByteArray =
    templates.getOrPut(key) {
      val store = store
      store?.read(key)
        ?: CardDesignTemplates.capture(context, key, registry).bytes.also { store?.write(key, it) }
    }
}

/** Persisted [CardDesignLibrary] templates. Reads and writes must not throw. */
interface LocalDesignStore {
  fun read(key: DesignKey): ByteArray?

  fun write(key: DesignKey, bytes: ByteArray)
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
