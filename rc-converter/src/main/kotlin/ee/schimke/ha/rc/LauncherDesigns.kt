@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import android.content.Context
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.composeai.rcplayer.edit.RcHardcodedValue
import ee.schimke.composeai.rcplayer.edit.bitmapUrls
import ee.schimke.composeai.rcplayer.edit.collapseConstantStateLayouts
import ee.schimke.composeai.rcplayer.edit.embedBitmapUrls
import ee.schimke.composeai.rcplayer.edit.hardcodeNamedValues
import ee.schimke.composeai.rcplayer.edit.optimize
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.LocalTemplateBindings
import ee.schimke.ha.rc.components.ProvideCardChrome
import ee.schimke.ha.rc.components.ProvideSystemHaTheme
import ee.schimke.ha.rc.components.ProvideWidgetActionRegistry
import ee.schimke.ha.rc.components.RemoteHaWidgetSurface
import ee.schimke.ha.rc.components.TemplateBindings
import ee.schimke.ha.rc.components.WidgetActionRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

/**
 * The launcher-widget content of [card]: the same tree for a live capture and a design template.
 * The widget surface paints the themed card background across the whole canvas and suppresses the
 * card's own chrome; taps are numeric host actions collected in [widgetActions].
 */
@Composable
fun LauncherWidgetContent(
  registry: CardRegistry,
  profile: Profile,
  dark: Boolean,
  widgetActions: WidgetActionRegistry,
  card: CardConfig,
  snapshot: HaSnapshot,
  captureSizeDp: WidgetCaptureSizeDp? = null,
) {
  CompositionLocalProvider(
    LocalWidgetCaptureSizeDp provides captureSizeDp,
    ee.schimke.ha.rc.components.LocalSupportsFlowLayout provides
      (androidx.compose.remote.core.Operations.LAYOUT_FLOW in profile.supportedOperations),
  ) {
    ProvideCardRegistry(registry) {
      ProvideSystemHaTheme(
        profile = profile,
        fallbackTheme = if (dark) HaTheme.Dark else HaTheme.Light,
      ) {
        ProvideCardSizeMode(CardSizeMode.Fixed) {
          ProvideCardChrome(enabled = false) {
            ProvideWidgetActionRegistry(widgetActions) {
              RemoteHaWidgetSurface { RenderChild(card, snapshot, RemoteModifier.fillMaxWidth()) }
            }
          }
        }
      }
    }
  }
}

/**
 * Card designs for launcher widgets. A launcher can't receive pushed bindings, so a widget is
 * baked: its design template — captured once per key, from the same tree as a live widget — gets
 * the card's values written in ([bake], with `rc-player-edit`'s `hardcodeNamedValues`), then its
 * now-constant state layouts collapsed and the document optimised. The template stays values-free;
 * only the baked copy, made on the device, holds values.
 *
 * Keys add the widget's size (the canvas the template is laid out on) and its theme (system colours
 * on hosts that resolve them, else the light / dark fallback) to the card's own shape.
 */
object LauncherDesigns {

  /** A captured launcher template: its bytes and the host actions it registered, by id. */
  class Template(val bytes: ByteArray, val actions: Map<Int, String>)

  /** A widget ready for `RemoteViews`: baked bytes and each host action's card-local action. */
  class Baked(val bytes: ByteArray, val actions: Map<Int, HaAction>)

  /**
   * The launcher [DesignKey] for [card] at [widthDp] × [heightDp], or null when it has no design
   * (it then captures directly).
   */
  fun keyFor(
    card: CardConfig,
    snapshot: HaSnapshot,
    widthDp: Int,
    heightDp: Int,
    dark: Boolean,
    systemTheme: Boolean,
    registry: CardRegistry = CardDesignTemplates.defaultTemplateRegistry,
  ): DesignKey? {
    val design = CardDesignTemplates.designCard(card, snapshot, registry)
    val shape =
      registry.converterFor(design.type)?.designProperties(design, snapshot)?.shape ?: return null
    return DesignKey(
      cardType = design.type,
      variant = "${widthDp}x$heightDp",
      surface = DesignSurface.Launcher,
      rcApiLevel = WIDGETS_DESIGN_API_LEVEL,
      shape = shape,
      theme =
        "widget:${if (systemTheme) "system" else "fallback"}:${if (dark) "dark" else "light"}",
    )
  }

  /** Capture the template for [key] at [densityDpi], from the card converter's own template. */
  fun capture(
    context: Context,
    key: DesignKey,
    densityDpi: Int,
    registry: CardRegistry = CardDesignTemplates.defaultTemplateRegistry,
  ): Template {
    require(key.surface == DesignSurface.Launcher) { "not a launcher key: $key" }
    val (widthDp, heightDp) = key.variant.split('x').map { it.toInt() }
    val parts = key.theme.split(':')
    val profile = if (parts.getOrNull(1) == "system") systemThemedWidgetsProfile else widgetsProfile
    val dark = parts.getOrNull(2) == "dark"
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
    val actions = WidgetActionRegistry(emptyMap())
    val widthPx = dpToPx(widthDp, densityDpi)
    val heightPx = dpToPx(heightDp, densityDpi)
    val bytes = runBlocking {
      captureSingleRemoteDocument(
          context = context,
          creationDisplayInfo = RemoteCreationDisplayInfo(widthPx, heightPx, densityDpi),
          profile = profile,
        ) {
          CompositionLocalProvider(LocalTemplateBindings provides TemplateBindings()) {
            LauncherWidgetContent(
              registry,
              profile,
              dark,
              actions,
              card,
              snapshot,
              WidgetCaptureSizeDp(widthDp, heightDp),
            )
          }
        }
        .bytes
    }
    return Template(bytes, actions.entries.toMap())
  }

  /**
   * [template] with [card]'s values on [snapshot] written in, or null when it can't be baked (an
   * image to fetch, or a binding the document can't hold as a constant) — the caller then captures
   * directly. [card] is the design card ([CardDesignTemplates.designCard]).
   */
  fun bake(
    template: Template,
    card: CardConfig,
    snapshot: HaSnapshot,
    registry: CardRegistry = CardDesignTemplates.defaultTemplateRegistry,
    fetchImage: (url: String) -> ByteArray? = { null },
  ): Baked? {
    val playback = CardDesignTemplates.playback(card, template.bytes, registry)
    val bytes = bakeValues(template.bytes, playback, snapshot, fetchImage) ?: return null
    val templateActions = template.actions.mapValues { (_, payload) -> decodeHaAction(payload) }
    // Each host action the template registered runs the card's own action for it. A PIN typed into
    // the widget resolves to an empty AlarmPin on the real entity; the receiver takes the digits
    // from the tap's metadata.
    val actions = templateActions.mapValues { (_, action) -> playback.resolveTap(action, snapshot) }
    return Baked(bytes, actions)
  }

  /**
   * The baked launcher document for [card], or null when it has no design or can't be baked. The
   * template comes from the process-wide [library], captured on first use.
   */
  fun render(
    context: Context,
    card: CardConfig,
    snapshot: HaSnapshot,
    widthDp: Int,
    heightDp: Int,
    densityDpi: Int,
    dark: Boolean,
    systemTheme: Boolean,
    registry: CardRegistry = CardDesignTemplates.defaultTemplateRegistry,
    fetchImage: (url: String) -> ByteArray? = { null },
  ): Baked? {
    val key = keyFor(card, snapshot, widthDp, heightDp, dark, systemTheme, registry) ?: return null
    val template =
      library.getOrPut(key to densityDpi) { capture(context, key, densityDpi, registry) }
    return bake(
      template,
      CardDesignTemplates.designCard(card, snapshot, registry),
      snapshot,
      registry,
      fetchImage,
    )
  }

  /**
   * [templateBytes] with [playback]'s bindings for [snapshot] written in, its slot pictures fetched
   * and embedded, its now-constant state layouts collapsed and the document optimised; null when it
   * can't be (a picture that can't be resolved or fetched, a binding the document can't hold as a
   * constant). For any host that can't receive bindings: launcher widgets, Wear widgets.
   */
  fun bakeValues(
    templateBytes: ByteArray,
    playback: CardTemplatePlayback,
    snapshot: HaSnapshot,
    fetchImage: (url: String) -> ByteArray? = { null },
  ): ByteArray? {
    val bindings = playback.bindings(snapshot)
    val values = buildMap {
      for ((name, v) in bindings.strings) put(name, RcHardcodedValue.Text(v))
      for ((name, v) in bindings.booleans) put(name, RcHardcodedValue.BooleanValue(v))
      for ((name, v) in bindings.ints) put(name, RcHardcodedValue.Integer(v))
      for ((name, v) in bindings.floats) put(name, RcHardcodedValue.FloatValue(v))
    }
    return runCatching {
      val document = RcDocumentCodec.decode(templateBytes, RcWireLimits())
      // Such a host can't load an image URL, so each one is fetched and embedded. A template's
      // URLs are slot pictures (`rc-slot://…`), resolved through the card's own entities.
      val images =
        document.bitmapUrls.associateWith { url ->
          val real = playback.bitmapUrl(url, snapshot) ?: return null
          fetchImage(real) ?: return null
        }
      val embedded = if (images.isEmpty()) document else document.embedBitmapUrls(images)
      RcDocumentCodec.encode(
        embedded.hardcodeNamedValues(values).collapseConstantStateLayouts().optimize()
      )
    }
      .getOrNull()
  }

  private val library = ConcurrentHashMap<Pair<DesignKey, Int>, Template>()

  private fun dpToPx(dp: Int, densityDpi: Int): Int = (dp * densityDpi / 160f).toInt()

  /** The widget profile's document API level: what a launcher template targets. */
  const val WIDGETS_DESIGN_API_LEVEL: Int = 7
}
