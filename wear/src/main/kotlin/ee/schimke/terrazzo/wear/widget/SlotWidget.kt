@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.terrazzo.wear.widget

import android.content.Context
import android.util.Log
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.glance.wear.AssociateWithGlanceWearWidget
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.GlanceWearWidgetService
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WearWidgetRawContent
import androidx.wear.compose.remote.material3.RemoteColorScheme
import androidx.wear.compose.remote.material3.RemoteMaterialTheme
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.CardRegistry
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.LauncherDesigns
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.ProvideCardSizeMode
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.LocalPictureImageStrategy
import ee.schimke.ha.rc.components.LocalTemplateBindings
import ee.schimke.ha.rc.components.PictureImageStrategy
import ee.schimke.ha.rc.components.ProvideCardChrome
import ee.schimke.ha.rc.components.ProvideFlowLayoutSupport
import ee.schimke.ha.rc.components.RemoteHaTheme
import ee.schimke.ha.rc.components.TemplateBindings
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.asHaTheme
import ee.schimke.ha.rc.converterFor
import ee.schimke.terrazzo.wear.data.WearPrefs
import ee.schimke.terrazzo.wear.sync.WearOfflineStore
import ee.schimke.terrazzo.wear.ui.terrazzoWearColorScheme
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.LiveValues
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Per-slot wear widget. Ten concrete subclasses ([Slot0SmallWidget] / [Slot0LargeWidget] …
 * [Slot4SmallWidget] / [Slot4LargeWidget]); each binds a fixed `slotIndex` and reads the matching
 * slot from disk at render time. Both small and large for the same slot share their `cardKey` and
 * therefore render the same card; the size split exists only at the manifest / service level so the
 * picker advertises both container types.
 *
 * Render path mirrors the phone widget ([ee.schimke.terrazzo.widget.TerrazzoWidgetProvider]):
 * 1. [WearOfflineStore.readSlots] → find this slot's `cardKey`.
 * 2. [WearOfflineStore.readPinned] → resolve the [PinnedCard] and decode `rawJson` to a
 *    [CardConfig].
 * 3. [WearOfflineStore.readValues] → build an [HaSnapshot] from the pushed [LiveValues] (state +
 *    friendly_name + unit + device_class).
 * 4. Render via the rc-converter registry inside the [WearWidgetDocument] content lambda; Glance
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
 * Empty slots / un-paired widgets short-circuit to a placeholder label; the matching service
 * component is normally disabled via [ee.schimke.terrazzo.wear.sync.WearSlotsController] before the
 * picker can surface them, but the fallback keeps a transient race from showing an empty card.
 */
abstract class SlotWidget(internal val slotIndex: Int) : GlanceWearWidget() {

  override suspend fun provideWidgetData(
    context: Context,
    params: WearWidgetParams,
  ): WearWidgetData {
    val app = context.applicationContext
    val store = WearOfflineStore(app)
    val slot = store.readSlots()?.slots?.firstOrNull { it.slotIndex == slotIndex }
    val pinned =
      slot
        ?.cardKey
        ?.takeIf { it.isNotEmpty() }
        ?.let { key -> store.readPinned()?.cards?.firstOrNull { it.cardKey == key } }

    val card = pinned?.card?.rawJson?.takeIf { it.isNotEmpty() }?.toCardConfigOrNull()
    val snapshot = store.readValues().toSnapshot()

    val style = WearPrefs(app).themeStyle.first()
    val registry = defaultRegistry().withEnhancedShutter()
    val wearColors = RemoteColorScheme(terrazzoWearColorScheme(style))
    // Keep Remote Material's WearM3.* named states, but define the two HA-specific semantic slots:
    // tertiary is link text and secondary is the unknown-state accent.
    val updatedColors =
      wearColors.copy(tertiary = wearColors.primary, secondary = wearColors.onSurfaceVariant)

    return slotWidgetData(registry, updatedColors, style, card, snapshot, slotIndex) { url ->
      syncedPicture(store, snapshot, url)
    }
  }
}

/**
 * The widget data for a slot showing [card]. Design first: the card's Wear template, captured by
 * Glance Wear itself (its profile and canvas) and baked with the card's values — a Wear widget
 * can't receive bindings. A slot picture is embedded from [fetchImage] (the phone syncs them: the
 * watch can't fetch one). Cards with no design, or one that can't be baked (a picture not synced
 * yet), keep the direct document. [themeKey] names [colors] in the template cache.
 */
internal fun slotWidgetData(
  registry: CardRegistry,
  colors: RemoteColorScheme,
  themeKey: Any,
  card: CardConfig?,
  snapshot: HaSnapshot,
  slotIndex: Int,
  fetchImage: (url: String) -> ByteArray? = { null },
): WearWidgetData {
  val direct = slotDocument(registry, colors, card, snapshot, slotIndex)
  if (card == null) return direct
  val design = CardDesignTemplates.designCard(card, snapshot, registry)
  val converter = registry.converterFor(design.type) ?: return direct
  val properties = converter.designProperties(design, snapshot) ?: return direct
  val input = converter.designTemplate(properties) ?: return direct
  return object : WearWidgetData {
    override suspend fun captureRawContent(
      context: Context,
      params: WearWidgetParams,
      isPreview: Boolean,
    ): WearWidgetRawContent {
      val key =
        WearTemplateKey(
          cardType = design.type,
          shape = properties.shape,
          theme = themeKey,
          containerType = params.containerType,
          widthDp = params.widthDp,
          heightDp = params.heightDp,
          renderer = params.rendererVersion.toString(),
        )
      val template =
        wearTemplates[key]
          ?: slotDocument(registry, colors, input.card, input.snapshot, slotIndex, template = true)
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

/** The direct (not design) document for a slot: what [slotWidgetData] falls back to. */
internal fun slotDirectDocument(
  registry: CardRegistry,
  colors: RemoteColorScheme,
  card: CardConfig?,
  snapshot: HaSnapshot,
  slotIndex: Int,
): WearWidgetData = slotDocument(registry, colors, card, snapshot, slotIndex)

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

/** One captured Wear template per card shape, theme and container: see [SlotWidget]. */
private data class WearTemplateKey(
  val cardType: String,
  val shape: String,
  val theme: Any,
  val containerType: Int,
  val widthDp: Float,
  val heightDp: Float,
  val renderer: String,
)

private val NO_PICTURES = PictureImageStrategy.Inline { null }

private val wearTemplates =
  java.util.concurrent.ConcurrentHashMap<WearTemplateKey, WearWidgetRawContent>()

/**
 * A slot's widget document: [card] under the Wear theme, Fixed size mode, without the card's own
 * chrome. With [template] it is captured as a design template: names and titles bound, not baked.
 */
private fun slotDocument(
  registry: CardRegistry,
  colors: RemoteColorScheme,
  card: CardConfig?,
  snapshot: HaSnapshot,
  slotIndex: Int,
  template: Boolean = false,
): WearWidgetDocument =
  WearWidgetDocument(
    background = WearWidgetBrush.Companion,
    content = {
      // The watch's widget host has no image loader. A template names its slot pictures by URL,
      // and baking embeds the phone-synced bytes; a direct capture can't, so it shows the card's
      // icon.
      CompositionLocalProvider(
        LocalTemplateBindings provides if (template) TemplateBindings() else null,
        LocalPictureImageStrategy provides
          if (template) PictureImageStrategy.DefaultAppUrl else NO_PICTURES,
      ) {
        ProvideCardRegistry(registry) {
          RemoteMaterialTheme(colorScheme = colors) {
            val theme = RemoteMaterialTheme.colorScheme.asHaTheme()
            ProvideCardSizeMode(CardSizeMode.Fixed) {
              // The Glance Wear container already supplies its
              // own shape + brush via WearWidgetBrush, so
              // suppress the per-card rounded clip + divider
              // border that would otherwise double up.
              //
              // Glance Wear's capture profile is fixed by
              // AndroidX to a stable op set that rejects
              // FlowLayout (op 240). Tell HA card components
              // to use a non-wrapping row fallback so cards
              // that lean on `RemoteFlowRow` (glance / grid /
              // entity-filter) capture instead of throwing.
              ProvideCardChrome(enabled = false) {
                ProvideFlowLayoutSupport(enabled = false) {
                  if (card != null) {
                    RenderChild(card, snapshot, RemoteModifier.fillMaxWidth())
                  } else {
                    EmptySlotPlaceholder(slotIndex, theme)
                  }
                }
              }
            }
          }
        }
      }
    },
  )

/**
 * Tiny placeholder rendered when a slot has no assigned card or its `rawJson` failed to decode.
 * Keeps the watch surface non-empty during the transient window where the slot is configured but
 * the matching `PinnedCard` hasn't synced yet.
 */
@Composable
private fun EmptySlotPlaceholder(slotIndex: Int, theme: RemoteHaTheme) {
  RemoteBox(modifier = RemoteModifier.fillMaxWidth()) {
    RemoteText(text = "Slot ${slotIndex + 1}".rs, color = theme.primaryText)
  }
}

private val cardJson = Json { ignoreUnknownKeys = true }

private fun String.toCardConfigOrNull(): CardConfig? = runCatching {
  cardJson.decodeFromString(CardConfig.serializer(), this)
}
  .onFailure { Log.w("SlotWidget", "card decode failed", it) }
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

private fun EntityValue.toEntityState(entityId: String): EntityState {
  val attrs =
    buildMap<String, JsonPrimitive> {
      if (friendlyName.isNotEmpty()) put("friendly_name", JsonPrimitive(friendlyName))
      if (unit.isNotEmpty()) put("unit_of_measurement", JsonPrimitive(unit))
      if (deviceClass.isNotEmpty()) put("device_class", JsonPrimitive(deviceClass))
      if (entityPicture.isNotEmpty()) put("entity_picture", JsonPrimitive(entityPicture))
    }
  return EntityState(entityId = entityId, state = state, attributes = JsonObject(attrs))
}

// One concrete widget per (slot, size) pair. The widget classes
// themselves don't read the size — both Small and Large for slot N
// resolve the same row from the offline store. The split exists so
// the manifest can advertise distinct container types per service,
// which is what the system widget picker filters on.

class Slot0SmallWidget : SlotWidget(slotIndex = 0)

class Slot0LargeWidget : SlotWidget(slotIndex = 0)

class Slot1SmallWidget : SlotWidget(slotIndex = 1)

class Slot1LargeWidget : SlotWidget(slotIndex = 1)

class Slot2SmallWidget : SlotWidget(slotIndex = 2)

class Slot2LargeWidget : SlotWidget(slotIndex = 2)

class Slot3SmallWidget : SlotWidget(slotIndex = 3)

class Slot3LargeWidget : SlotWidget(slotIndex = 3)

class Slot4SmallWidget : SlotWidget(slotIndex = 4)

class Slot4LargeWidget : SlotWidget(slotIndex = 4)

/**
 * One [GlanceWearWidgetService] per (slot, size) pair. The system binds these via
 * `androidx.glance.wear.action.BIND_WIDGET_PROVIDER` (or the legacy
 * `androidx.wear.tiles.action.BIND_TILE_PROVIDER` for tile-compat surfaces). The `widget` property
 * exposed by [GlanceWearWidgetService] (synthesised from its Java `getWidget()` accessor) returns a
 * stable instance per service.
 *
 * Per-size manifest entries point at distinct `wearwidget-provider` XML descriptors
 * (`@xml/wear_slot_widget_provider_small` vs `@xml/wear_slot_widget_provider_large`) so the picker
 * filters each service to one container type.
 */
@AssociateWithGlanceWearWidget(Slot0SmallWidget::class)
class Slot0SmallWidgetService : GlanceWearWidgetService() {
  override val widget: GlanceWearWidget = Slot0SmallWidget()
}

@AssociateWithGlanceWearWidget(Slot0LargeWidget::class)
class Slot0LargeWidgetService : GlanceWearWidgetService() {
  override val widget: GlanceWearWidget = Slot0LargeWidget()
}

@AssociateWithGlanceWearWidget(Slot1SmallWidget::class)
class Slot1SmallWidgetService : GlanceWearWidgetService() {
  override val widget: GlanceWearWidget = Slot1SmallWidget()
}

@AssociateWithGlanceWearWidget(Slot1LargeWidget::class)
class Slot1LargeWidgetService : GlanceWearWidgetService() {
  override val widget: GlanceWearWidget = Slot1LargeWidget()
}

@AssociateWithGlanceWearWidget(Slot2SmallWidget::class)
class Slot2SmallWidgetService : GlanceWearWidgetService() {
  override val widget: GlanceWearWidget = Slot2SmallWidget()
}

@AssociateWithGlanceWearWidget(Slot2LargeWidget::class)
class Slot2LargeWidgetService : GlanceWearWidgetService() {
  override val widget: GlanceWearWidget = Slot2LargeWidget()
}

@AssociateWithGlanceWearWidget(Slot3SmallWidget::class)
class Slot3SmallWidgetService : GlanceWearWidgetService() {
  override val widget: GlanceWearWidget = Slot3SmallWidget()
}

@AssociateWithGlanceWearWidget(Slot3LargeWidget::class)
class Slot3LargeWidgetService : GlanceWearWidgetService() {
  override val widget: GlanceWearWidget = Slot3LargeWidget()
}

@AssociateWithGlanceWearWidget(Slot4SmallWidget::class)
class Slot4SmallWidgetService : GlanceWearWidgetService() {
  override val widget: GlanceWearWidget = Slot4SmallWidget()
}

@AssociateWithGlanceWearWidget(Slot4LargeWidget::class)
class Slot4LargeWidgetService : GlanceWearWidgetService() {
  override val widget: GlanceWearWidget = Slot4LargeWidget()
}
