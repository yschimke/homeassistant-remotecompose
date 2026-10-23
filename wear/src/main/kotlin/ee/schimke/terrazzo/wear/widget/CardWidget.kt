@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.terrazzo.wear.widget

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.glance.wear.AssociateWithGlanceWearWidget
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.GlanceWearWidgetManager
import androidx.glance.wear.GlanceWearWidgetService
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.color
import androidx.glance.wear.core.ActiveWearWidgetHandle
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WearWidgetRawContent
import androidx.wear.compose.remote.material3.RemoteColorScheme
import androidx.wear.compose.remote.material3.RemoteMaterialTheme
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.CardRegistry
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.ContainerTint
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LauncherDesigns
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.ProvideCardSizeMode
import ee.schimke.ha.rc.ProvideFixedCanvasAxis
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.LocalPictureImageStrategy
import ee.schimke.ha.rc.components.LocalTemplateBindings
import ee.schimke.ha.rc.components.PictureImageStrategy
import ee.schimke.ha.rc.components.ProvideCardChrome
import ee.schimke.ha.rc.components.ProvideExpressionNames
import ee.schimke.ha.rc.components.ProvideFlowLayoutSupport
import ee.schimke.ha.rc.components.ProvideHaUberStyle
import ee.schimke.ha.rc.components.RemoteHaTheme
import ee.schimke.ha.rc.components.TemplateBindings
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.asHaTheme
import ee.schimke.ha.rc.converterFor
import ee.schimke.terrazzo.wear.data.WearPrefs
import ee.schimke.terrazzo.wear.data.toEntityState
import ee.schimke.terrazzo.wear.sync.WearDirectAccess
import ee.schimke.terrazzo.wear.sync.WearOfflineStore
import ee.schimke.terrazzo.wear.sync.syncDesignsFromDataLayer
import ee.schimke.terrazzo.wear.sync.syncPicturesFromDataLayer
import ee.schimke.terrazzo.wear.sync.syncWidgetStateFromDataLayer
import ee.schimke.terrazzo.wear.ui.terrazzoWearColorScheme
import ee.schimke.terrazzo.wearsync.proto.LiveValues
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * A Terrazzo Wear widget: one card the phone sent to the watch, chosen on the watch for this widget
 * instance. Two concrete subclasses, [CardSmallWidget] and [CardLargeWidget], one per Glance Wear
 * container type: the split exists only at the manifest / service level so the system widget picker
 * offers both sizes. Each placed widget reads its own card by its instance id.
 *
 * Render path mirrors the phone widget ([ee.schimke.terrazzo.widget.TerrazzoWidgetProvider]):
 * 1. [WearWidgetCardStore.read] → the card chosen for this instance ([WearWidgetConfigActivity] or
 *    the Wear app's Widgets screen), resolved against what the phone sends now
 *    ([currentWidgetCard]), and its `rawJson` decoded to a [CardConfig].
 * 2. [WearOfflineStore.readValues] → build an [HaSnapshot] from the pushed [LiveValues] (state +
 *    friendly_name + unit + device_class).
 * 3. Render via the rc-converter registry inside the [WearWidgetDocument] content lambda; Glance
 *    Wear captures the composition to RemoteCompose bytes for the watch's RC runtime.
 *
 * Wear colors come from Remote Material 3's named `WearM3.*` state, so the widget host can update
 * them without recapturing the document. The user's selected [ThemeStyle] (read from [WearPrefs])
 * supplies fallback colors for runtimes that do not inject those names.
 *
 * Limitations: cards that need history / statistics / forecasts won't have that data — only the
 * latest entity state flows over the data layer today. Cards that lean on ops outside the Glance
 * Wear capture profile may fail to capture; the registry's [ee.schimke.ha.rc.UNSUPPORTED_CARD_TYPE]
 * fallback handles unknown types but not in-range ops mismatches.
 *
 * A widget with no card chosen yet draws a short placeholder asking for one: a Wear widget has no
 * tap action to open the picker, so the card is chosen in the Terrazzo app (or by the system's
 * configuration step, where the host offers it).
 */
abstract class CardWidget : GlanceWearWidget() {

  override suspend fun provideWidgetData(
    context: Context,
    params: WearWidgetParams,
  ): WearWidgetData {
    val app = context.applicationContext
    // The host's frame for this container: the Wear app draws its cards in it.
    WearWidgetFrame.remember(app, params)
    val store = WearOfflineStore(app)
    // Pins, sections and values from the Data Layer: the watch app may not have been running to
    // store them as they arrived.
    syncWidgetStateFromDataLayer(app, store)
    val chosen = WearWidgetCardStore(app).read(params.instanceId.id)
    // The card's values from Home Assistant itself when the phone has sent none lately.
    chosen?.let { refreshCardValuesDirectly(app, store, it) }
    // The pinned cards' Wear widget designs from the design server, synced by the phone.
    syncDesignsFromDataLayer(app, store)
    return slotWidgetDataFromStore(app, store, chosen)
  }

  /** The widget was removed from the watch: forget the card chosen for it. */
  override suspend fun onRemoved(context: Context, widgetHandle: ActiveWearWidgetHandle) {
    WearWidgetCardStore(context.applicationContext).remove(widgetHandle.instanceId.id)
  }
}

/**
 * Reads the values of [chosen]'s card from Home Assistant when the phone's are stale
 * ([WearDirectAccess]); bounded, so a widget never waits long on the network.
 */
private suspend fun refreshCardValuesDirectly(
  app: Context,
  store: WearOfflineStore,
  chosen: WearWidgetCard,
) {
  val raw =
    currentWidgetCard(chosen, store.readPinned(), store.readSections()).rawJson.takeIf {
      it.isNotEmpty()
    } ?: return
  withTimeoutOrNull(DIRECT_VALUES_TIMEOUT_MS) {
    runCatching {
      WearDirectAccess(app, offline = store).refresh(listOf(raw), store.readSettings())
    }
      .onFailure { if (it is CancellationException) throw it }
  }
}

private const val DIRECT_VALUES_TIMEOUT_MS = 20_000L

/**
 * The offline rendering path for a widget showing [chosen] (null: no card chosen yet), also used
 * after a best-effort Data Layer refresh.
 */
internal suspend fun slotWidgetDataFromStore(
  app: Context,
  store: WearOfflineStore,
  chosen: WearWidgetCard?,
): WearWidgetData {
  val summary = chosen?.let { currentWidgetCard(it, store.readPinned(), store.readSections()) }

  val card = summary?.rawJson?.takeIf { it.isNotEmpty() }?.toCardConfigOrNull()
  val snapshot = store.readValues().toSnapshot()
  // The card's pictures, from the Data Layer: the watch app may not have been running to store
  // them as they arrived.
  card?.let { c ->
    val pictured =
      CardDesignSlots.slotEntities(c).orEmpty().filter {
        snapshot.states[it]?.attributes?.containsKey("entity_picture") == true
      }
    syncPicturesFromDataLayer(app, store, pictured)
  }

  val style = WearPrefs(app).themeStyle.first()
  val cardStyle = wearWidgetCardStyle(summary?.cardStyleOverride, WearPrefs(app).cardStyle.first())
  val registry = defaultRegistry().withEnhancedShutter()
  val updatedColors = wearWidgetColors(style)

  // The card's Wear widget designs, as synced from the phone (for pinned cards, by their key).
  val designs =
    store.readSlotDesigns()?.designs.orEmpty().filter {
      chosen != null && it.cardKey == chosen.cardKey
    }

  return slotWidgetData(
    registry,
    updatedColors,
    style,
    card,
    snapshot,
    cardStyle,
    fetchImage = { url -> syncedPicture(store, snapshot, url) },
    wearDesign = { container ->
      designs.firstOrNull { it.container == container }?.let { store.readDesign(it.sha256) }
    },
  )
}

/**
 * A widget's colours in the watch's [style]: Remote Material's WearM3.* named states, with the two
 * HA-specific semantic slots defined: tertiary is link text and secondary is the unknown-state
 * accent.
 */
internal fun wearWidgetColors(style: ThemeStyle): RemoteColorScheme {
  val wearColors = RemoteColorScheme(terrazzoWearColorScheme(style))
  return wearColors.copy(tertiary = wearColors.primary, secondary = wearColors.onSurfaceVariant)
}

/** Unknown ids from a newer phone fall back to this watch's preference. */
internal fun wearWidgetCardStyle(override: String?, watchStyle: String): HaUberTheme =
  HaUberTheme.all.firstOrNull { it.id == override } ?: HaUberTheme.byId(watchStyle)

/**
 * The widget data for a widget showing [card] (null: none chosen, the placeholder). Design first:
 * the card's Wear template, baked with the card's values — a Wear widget can't receive bindings.
 * The template is the design server's Wear widget design for the container ([wearDesign], by
 * [DesignKey.WEAR_SMALL] / [DesignKey.WEAR_LARGE]: the card's own design rooted on the Wear
 * container, synced by the phone) when there is one, else the card's design captured by Glance Wear
 * itself (its profile and canvas). A card's picture is embedded from [fetchImage] (the phone syncs
 * them: the watch can't fetch one). Cards with no design, or one that can't be baked (a picture not
 * synced yet), keep the direct document. [themeKey] names [colors] in the template cache.
 */
internal fun slotWidgetData(
  registry: CardRegistry,
  colors: RemoteColorScheme,
  themeKey: Any,
  card: CardConfig?,
  snapshot: HaSnapshot,
  cardStyle: HaUberTheme = HaUberTheme.Classic,
  fetchImage: (url: String) -> ByteArray? = { null },
  wearDesign: (container: String) -> ByteArray? = { null },
): WearWidgetData {
  // The card's state tints its container: warm for heating, amber for a light that is on.
  val tint = ContainerTint.argbFor(card, snapshot)
  val direct = slotDocument(registry, colors, card, snapshot, cardStyle = cardStyle, tint = tint)
  if (card == null) return direct
  val design = CardDesignTemplates.designCard(card, snapshot, registry)
  val converter = registry.converterFor(design.type) ?: return direct
  val properties = converter.wearDesignProperties(design, snapshot) ?: return direct
  val input = converter.wearDesignTemplate(properties) ?: return direct
  return object : WearWidgetData {
    override suspend fun captureRawContent(
      context: Context,
      params: WearWidgetParams,
      isPreview: Boolean,
    ): WearWidgetRawContent {
      // The server's Wear design for this container: no chrome of its own, the container's. One
      // that leaves its container frame named is a container-neutral template like the card's own
      // below, made into this widget's document first; one that bakes a frame plays as it is.
      containerOf(params.containerType)?.let(wearDesign)?.let { synced ->
        val widget =
          if (WearWidgetTemplate.namesFrame(synced))
            WearWidgetTemplate.instantiate(context, synced, params, isPreview, tint)
          else null
        val document = widget?.rcDocument ?: synced
        val playback = CardDesignTemplates.playback(design, document, registry)
        LauncherDesigns.bakeValues(document, playback, snapshot, fetchImage)?.let {
          return WearWidgetRawContent(it, widget?.extras ?: Bundle(), widget?.widgetTitle)
        }
      }
      // The card's container-neutral template, made into this widget's document on the watch.
      neutralWidgetTemplate(
          context,
          registry,
          colors,
          themeKey,
          design.type,
          properties.shape,
          input,
          cardStyle,
        )
        ?.let { WearWidgetTemplate.instantiate(context, it, params, isPreview, tint) }
        ?.let { widget ->
          val playback = CardDesignTemplates.playback(design, widget.rcDocument, registry)
          LauncherDesigns.bakeValues(widget.rcDocument, playback, snapshot, fetchImage)?.let {
            return WearWidgetRawContent(it, widget.extras, widget.widgetTitle)
          }
        }
      // Otherwise Glance Wear captures the template for this container itself.
      val key =
        WearTemplateKey(
          cardType = design.type,
          shape = properties.shape,
          theme = themeKey to cardStyle.id,
          containerType = params.containerType,
          widthDp = params.widthDp,
          heightDp = params.heightDp,
          horizontalPaddingDp = params.horizontalPaddingDp,
          verticalPaddingDp = params.verticalPaddingDp,
          cornerRadiusDp = params.cornerRadiusDp,
          renderer = params.rendererVersion.toString(),
        )
      val template =
        wearTemplates[key]
          ?: slotDocument(
              registry,
              colors,
              input.card,
              input.snapshot,
              template = true,
              cardStyle = cardStyle,
            )
            .captureRawContent(context, params, isPreview)
            .also { wearTemplates[key] = it }
      val playback = CardDesignTemplates.playback(design, template.rcDocument, registry)
      val baked =
        LauncherDesigns.bakeValues(template.rcDocument, playback, snapshot, fetchImage)
          ?: return direct.captureRawContent(context, params, isPreview)
      return WearWidgetRawContent(baked, template.extras, template.widgetTitle)
    }
  }
}

/** The [DesignKey.container] of a Glance Wear container type, or null for another one. */
internal fun containerOf(containerType: Int): String? =
  when (containerType) {
    ContainerInfo.CONTAINER_TYPE_SMALL -> DesignKey.WEAR_SMALL
    ContainerInfo.CONTAINER_TYPE_LARGE -> DesignKey.WEAR_LARGE
    else -> null
  }

/** The direct (not design) document for a widget: what [slotWidgetData] falls back to. */
internal fun slotDirectDocument(
  registry: CardRegistry,
  colors: RemoteColorScheme,
  card: CardConfig?,
  snapshot: HaSnapshot,
): WearWidgetData = slotDocument(registry, colors, card, snapshot)

/**
 * The synced picture for [url] — an entity's `entity_picture` in [snapshot]. A camera's URL changes
 * with its token faster than pictures sync, so the entity's last synced picture stands in for it.
 */
private fun syncedPicture(store: WearOfflineStore, snapshot: HaSnapshot, url: String): ByteArray? {
  val entity =
    snapshot.states.values.firstOrNull {
      (it.attributes["entity_picture"] as? JsonPrimitive)?.content == url
    } ?: return null
  return store.readPicture(entity.entityId)?.png?.takeIf { it.isNotEmpty() }
}

/** One captured Wear template per card shape, theme and container: see [CardWidget]. */
private data class WearTemplateKey(
  val cardType: String,
  val shape: String,
  val theme: Any,
  val containerType: Int,
  val widthDp: Float,
  val heightDp: Float,
  val horizontalPaddingDp: Float,
  val verticalPaddingDp: Float,
  val cornerRadiusDp: Float,
  val renderer: String,
)

private val NO_PICTURES = PictureImageStrategy.Inline { null }

/**
 * The container-neutral Wear template for a card of [cardType] and [shape] ([input] is its design
 * template input), in [colors] and [cardStyle]: captured once per shape and theme, and shared by
 * the widgets and the Wear app's own cards, so both draw the same layout. Null when the capture
 * fails.
 */
internal suspend fun neutralWidgetTemplate(
  context: Context,
  registry: CardRegistry,
  colors: RemoteColorScheme,
  themeKey: Any,
  cardType: String,
  shape: String,
  input: DesignTemplateInput,
  cardStyle: HaUberTheme,
): ByteArray? {
  val key = WearNeutralTemplateKey(cardType, shape, themeKey to cardStyle.id)
  neutralTemplates[key]?.let {
    return it
  }
  return runCatching {
    neutralTemplateCaptures.incrementAndGet()
    WearWidgetTemplate.capture(context) {
      SlotContent(
        registry,
        colors,
        input.card,
        input.snapshot,
        template = true,
        cardStyle = cardStyle,
      )
    }
  }
    .onFailure { Log.w("CardWidget", "neutral template capture failed", it) }
    .getOrNull()
    ?.also { neutralTemplates[key] = it }
}

/** One container-neutral Wear template per card shape and theme: see [WearWidgetTemplate]. */
private data class WearNeutralTemplateKey(val cardType: String, val shape: String, val theme: Any)

private val neutralTemplates =
  java.util.concurrent.ConcurrentHashMap<WearNeutralTemplateKey, ByteArray>()

/** How many neutral templates this process has captured: for tests of who captures them. */
internal val neutralTemplateCaptures = java.util.concurrent.atomic.AtomicInteger()

private val wearTemplates =
  java.util.concurrent.ConcurrentHashMap<WearTemplateKey, WearWidgetRawContent>()

/**
 * A widget's document: [card] under the Wear theme, Fixed size mode, without the card's own chrome,
 * in Glance Wear's container for the widget's params. With [template] it is captured as a design
 * template: names and titles bound, not baked.
 */
internal fun slotDocument(
  registry: CardRegistry,
  colors: RemoteColorScheme,
  card: CardConfig?,
  snapshot: HaSnapshot,
  template: Boolean = false,
  cardStyle: HaUberTheme = HaUberTheme.Classic,
  tint: Int? = null,
): WearWidgetDocument =
  WearWidgetDocument(
    background = tint?.let { WearWidgetBrush.color(Color(it).rc) } ?: WearWidgetBrush.Companion,
    content = { SlotContent(registry, colors, card, snapshot, template, cardStyle) },
  )

/** What a widget shows inside its container: see [slotDocument]. */
@Composable
internal fun SlotContent(
  registry: CardRegistry,
  colors: RemoteColorScheme,
  card: CardConfig?,
  snapshot: HaSnapshot,
  template: Boolean,
  cardStyle: HaUberTheme,
  // What draws the card: its converter, or a card design authored in UI Builder captured for the
  // card's key (server-owned-card-designs.md, "One source, every surface").
  content: @Composable (RemoteModifier) -> Unit = { RenderChild(card!!, snapshot, it) },
) {
  // The watch's widget host has no image loader. A template names its slot pictures by URL, and
  // baking embeds the phone-synced bytes; a direct capture can't, so it shows the card's icon.
  CompositionLocalProvider(
    LocalTemplateBindings provides if (template) TemplateBindings() else null,
    LocalPictureImageStrategy provides
      if (template) PictureImageStrategy.DefaultAppUrl else NO_PICTURES,
  ) {
    ProvideCardRegistry(registry) {
      RemoteMaterialTheme(colorScheme = colors) {
        val theme = RemoteMaterialTheme.colorScheme.asHaTheme()
        // The user's card style brings its instruments, type and shapes; the watch keeps its own
        // palette (the WearM3 colours the widget host updates).
        ProvideHaUberStyle(cardStyle, darkTheme = true) {
          ProvideCardSizeMode(CardSizeMode.Fixed) {
            // The Glance Wear container already supplies its own shape + brush via
            // WearWidgetBrush, so suppress the per-card rounded clip + divider border that would
            // otherwise double up.
            //
            // Glance Wear's capture profile is fixed by AndroidX to a stable op set that rejects
            // FlowLayout (op 240). Tell HA card components to use a non-wrapping row fallback so
            // cards that lean on `RemoteFlowRow` (glance / grid / entity-filter) capture instead
            // of throwing.
            ProvideCardChrome(enabled = false) {
              ProvideFixedCanvasAxis(BreakpointAxis.Height) {
                ProvideFlowLayoutSupport(enabled = false, compactEntities = true) {
                  if (card != null) {
                    // One card per slot document: its expressions are named in composition
                    // order, as its template's are.
                    ProvideExpressionNames { content(RemoteModifier.fillMaxWidth()) }
                  } else {
                    ChooseCardPlaceholder(theme)
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

/**
 * What a widget draws with no card chosen yet (or one whose `rawJson` failed to decode): where to
 * choose one. A Wear widget can't open the picker on a tap, so it names the app.
 */
@Composable
private fun ChooseCardPlaceholder(theme: RemoteHaTheme) {
  RemoteBox(modifier = RemoteModifier.fillMaxWidth()) {
    RemoteText(text = CHOOSE_CARD_PLACEHOLDER.rs, color = theme.primaryText)
  }
}

/** The placeholder's text: see [ChooseCardPlaceholder]. */
internal const val CHOOSE_CARD_PLACEHOLDER = "Choose a card in the Terrazzo app"

private val cardJson = Json { ignoreUnknownKeys = true }

private fun String.toCardConfigOrNull(): CardConfig? = runCatching {
  cardJson.decodeFromString(CardConfig.serializer(), this)
}
  .onFailure { Log.w("CardWidget", "card decode failed", it) }
  .getOrNull()

/**
 * Project the proto [LiveValues] snapshot into the [HaSnapshot] shape card converters expect. Only
 * the fields the wire format carries survive the round-trip — state, friendly_name,
 * unit_of_measurement, device_class, entity_picture. Cards reading other attributes will see them
 * as absent.
 */
private fun LiveValues?.toSnapshot(): HaSnapshot {
  val live = this ?: return HaSnapshot()
  val states = live.values.mapValues { (id, v) -> v.toEntityState(id) }
  return HaSnapshot(states = states)
}

// One concrete widget per container size. The widget classes themselves don't read the size: each
// instance draws the card chosen for it. The split exists so the manifest can advertise distinct
// container types per service, which is what the system widget picker filters on.

class CardSmallWidget : CardWidget()

class CardLargeWidget : CardWidget()

/**
 * Ask the host to re-render every placed Terrazzo widget, after a change that only the next render
 * reads — the watch theme or card style, new pins or designs. Each render reads the preferences
 * afresh, and the template cache is keyed by both, so the widgets come back in the new look.
 */
suspend fun refreshAllCardWidgets(context: Context) {
  listOf(CardSmallWidget(), CardLargeWidget()).forEach { widget ->
    runCatching { widget.triggerUpdateAll(context) }
  }
}

/** Ask the host to re-render widget [instanceId] of [provider]: its card was just chosen. */
suspend fun refreshCardWidget(context: Context, provider: ComponentName?, instanceId: Int) {
  val widgets =
    when (provider?.className) {
      CardSmallWidgetService::class.java.name -> listOf(CardSmallWidget())
      CardLargeWidgetService::class.java.name -> listOf(CardLargeWidget())
      // Not one of ours or not known: whichever size the instance is.
      else -> listOf(CardSmallWidget(), CardLargeWidget())
    }
  for (widget in widgets) {
    val instance = runCatching {
      GlanceWearWidgetManager(context).fetchActiveWidgets(widget::class)
    }
      .getOrNull()
      ?.firstOrNull { it.instanceId.id == instanceId }
      ?.instanceId
    if (instance != null) {
      runCatching { widget.triggerUpdate(context, instance) }
      return
    }
  }
  // Not listed (yet): update them all.
  refreshAllCardWidgets(context)
}

/**
 * One [GlanceWearWidgetService] per container size. The system binds these via
 * `androidx.glance.wear.action.BIND_WIDGET_PROVIDER` (or the legacy
 * `androidx.wear.tiles.action.BIND_TILE_PROVIDER` for tile-compat surfaces). The `widget` property
 * exposed by [GlanceWearWidgetService] (synthesised from its Java `getWidget()` accessor) returns a
 * stable instance per service.
 *
 * Each manifest entry points at its own `wearwidget-provider` XML descriptor
 * (`@xml/wear_slot_widget_provider_small` vs `@xml/wear_slot_widget_provider_large`) so the picker
 * filters each service to one container type. Every instance of either shows its own card.
 */
@AssociateWithGlanceWearWidget(CardSmallWidget::class)
class CardSmallWidgetService : GlanceWearWidgetService() {
  override val widget: GlanceWearWidget = CardSmallWidget()
}

@AssociateWithGlanceWearWidget(CardLargeWidget::class)
class CardLargeWidgetService : GlanceWearWidgetService() {
  override val widget: GlanceWearWidget = CardLargeWidget()
}
