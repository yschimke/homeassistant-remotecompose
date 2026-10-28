@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.terrazzo.wear.ui

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.player.core.platform.BitmapLoader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.*
import ee.schimke.ha.rc.StablePictures
import ee.schimke.ha.rc.cards.cardVisibilitySignature
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.CLASSIC_WEAR_SURFACE
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaControlLockMode
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.HaWearLevel
import ee.schimke.ha.rc.components.RemoteWearContainer
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.wearBackground
import ee.schimke.ha.rc.components.wearLevelLook
import ee.schimke.terrazzo.wear.data.toEntityState
import ee.schimke.terrazzo.wear.sync.WearOfflineStore
import ee.schimke.terrazzo.wear.sync.WearTapDispatcher
import ee.schimke.terrazzo.wear.sync.WearTapOutcome
import ee.schimke.terrazzo.wear.widget.SlotContent
import ee.schimke.terrazzo.wear.widget.WearWidgetFrame
import ee.schimke.terrazzo.wear.widget.WearWidgetTemplate
import ee.schimke.terrazzo.wear.widget.neutralWidgetTemplate
import ee.schimke.terrazzo.wear.widget.syncedPicture
import ee.schimke.terrazzo.wear.widget.wearAppContainer
import ee.schimke.terrazzo.wear.widget.wearCardDataTier
import ee.schimke.terrazzo.wear.widget.wearDesignProperties
import ee.schimke.terrazzo.wear.widget.wearDesignTemplate
import ee.schimke.terrazzo.wear.widget.wearLevel
import ee.schimke.terrazzo.wear.widget.wearWidgetCardStyle
import ee.schimke.terrazzo.wear.widget.wearWidgetColors
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

internal data class WearCardLook(
  val palette: ThemeStyle = ThemeStyle.TerrazzoHome,
  val style: String = HaUberTheme.Classic.id,
)

/** A missing/unknown value uses mobile's default, never an obsolete watch preference. */
internal fun ee.schimke.terrazzo.wearsync.proto.WearSettings?.wearCardLook(): WearCardLook =
  WearCardLook(
    palette =
      runCatching { ThemeStyle.valueOf(this?.themeStyle.orEmpty()) }
        .getOrDefault(ThemeStyle.Material3),
    style = HaUberTheme.byId(this?.cardStyle ?: "classic").id,
  )

/** Invalidates disk-backed templates when either their index or bytes arrive, in either order. */
internal val LocalWearDesignRevision = staticCompositionLocalOf { 0L }

internal val LocalWearCardLook = staticCompositionLocalOf { WearCardLook() }

/**
 * A card in the Wear app, drawn exactly as its Wear widget draws it: in the widget container's
 * frame (Small or Large, [wearAppContainer]; the sizes this watch's widget host uses, see
 * [WearWidgetFrame]), from the same container-neutral template ([neutralWidgetTemplate]), under the
 * widget's colours and size mode. A card added as a widget then looks the same on the watch face as
 * it did in the app. Unlike the widget, which bakes its values, the app plays the template's
 * bindings, so values update live.
 *
 * With [onOpen], a long press on the card, a tap the card doesn't take itself, or the card's own
 * more-info tap opens it full screen ([WearCardScreen]). [fullScreen] draws it there: in the Large
 * container for a card that has one ([wearFullScreenContainer]), scaled up to fill the round screen
 * ([fullScreenScale], [scaledFrame]). [taps] shares a dispatcher with controls outside the card,
 * such as the full-screen card's action button.
 */
@Composable
internal fun WearRemoteCard(
  summary: CardSummary,
  values: WearValues,
  onOpen: (() -> Unit)? = null,
  fullScreen: Boolean = false,
  taps: WearCardTaps = rememberWearCardTaps(summary.rawJson, onOpen),
) {
  // Only this card's entities are read: a delta for another card's entity leaves this one alone.
  WearRemoteCardContent(
    summary,
    rememberCardValues(summary.rawJson, values),
    onOpen,
    fullScreen,
    taps,
  )
}

@Composable
private fun WearRemoteCardContent(
  summary: CardSummary,
  values: Map<String, EntityValue>,
  onOpen: (() -> Unit)?,
  fullScreen: Boolean,
  taps: WearCardTaps,
) {
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
  val design = CardDesignTemplates.designCard(card, snapshot, registry)
  val revision = LocalWearDesignRevision.current
  val store = remember(context) { WearOfflineStore(context.applicationContext) }
  val frame =
    remember(card, snapshot, fullScreen) {
      WearWidgetFrame.forContainer(
        context,
        if (fullScreen) wearFullScreenContainer(card)
        else wearAppContainer(card, snapshot, registry),
      )
    }
  // The card's state tints its container, as on the widget: warm for heating, amber for a light.
  val tint = remember(card, snapshot, style) { ContainerTint.argbFor(card, snapshot, style) }
  val scheme = MaterialTheme.colorScheme
  val paletteKey = wearPaletteKey(look.palette, scheme)
  val colors = remember(scheme) { wearWidgetColors(scheme) }
  // The template's own shape: a toggle turning on or off changes it (a Wear tier resolves its icon
  // at capture, see WEAR_ON_PROPERTY) without changing the design, frame or, untinted, the tint.
  val shape = remember(design, snapshot) { wearTemplateShape(design, snapshot, registry) }
  val synced = remember(card, frame, revision) { store.syncedCardDesign(card, frame.container) }
  val syncedSha = synced?.let { ee.schimke.ha.client.design.Sha256.hex(it) }
  val template =
    remember(design, shape, look, style, frame, tint, syncedSha, paletteKey) {
      (synced?.let { syncedWearAppTemplate(it, frame, tint) }
          ?: runBlocking {
            wearAppTemplate(context, design, snapshot, look, style, frame, tint, registry)
          })
        ?.let { bytes -> CardDesignTemplates.playback(design, bytes, registry) }
    }
  // Pictures come from the ones the phone synced to disk, by entity, as on the widget: no network,
  // and a camera's rotating token doesn't matter.
  val latestSnapshot = rememberUpdatedState(snapshot)
  val pictures =
    remember(context) {
      val store = WearOfflineStore(context.applicationContext)
      BitmapLoader { url ->
        syncedPicture(store, latestSnapshot.value, url)?.let(::ByteArrayInputStream)
          ?: throw IOException("no synced picture for $url")
      }
    }
  // Binding cards seed current values before frame one; baked and structural changes recapture.
  val tick = relativeTimeTickFor(cardEntityIds(card), snapshot)
  val signature = remember(card, snapshot, tick) { registry.cardDataSignature(card, snapshot) }
  val key =
    listOf(
      "wear-app-widget",
      syncedSha,
      card,
      look,
      paletteKey,
      style.id,
      frame,
      tint,
      signature,
      cardIconSignature(card, snapshot),
      cardVisibilitySignature(card, snapshot),
      // Whether its container's level has a reading: a card drawn without a template keeps the
      // last pushed one, since a host pushes no number for an unavailable state.
      wearLevel(card, snapshot)?.value?.isFinite(),
    )
  val view = LocalView.current
  val configuration = LocalConfiguration.current
  val gestures =
    if (onOpen == null) Modifier
    else
      Modifier.wearCardGestures(
        onLongPress = {
          view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
          onOpen()
        },
        onUnhandledTap = onOpen,
      )
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      val scale =
        if (!fullScreen) 1f
        else
          fullScreenScale(
            frame.widthDp,
            frame.heightDp,
            maxWidth.value,
            maxHeight.value,
            configuration.screenWidthDp.toFloat(),
            configuration.isScreenRound,
          )
      val sizing =
        if (scale > 1f) {
          // Full screen: the widget's layout at its own size, scaled up to what the round screen
          // holds (taps scale with it), rather than a frame the template was never laid out for.
          // Never scaled down: that would shrink its touch targets below their minimum.
          Modifier.scaledFrame(frame.widthDp.dp, frame.heightDp.dp, scale)
        } else {
          // A watch narrower than the frame (before its host has reported one) keeps the frame's
          // height.
          Modifier.size(minOf(frame.widthDp.dp, maxWidth), frame.heightDp.dp)
        }
      CompositionLocalProvider(LocalHaActionDispatcher provides taps.dispatcher) {
        CachedCardPreview(
          cacheKey = key,
          profile = androidXExperimentalWrap,
          modifier = sizing.then(gestures),
          card = card,
          snapshot = snapshot,
          liveBindings = signature == null,
          bitmapLoader = pictures,
          template = template,
          // A camera's new frame or rotated token loads in place: the capture names pictures by
          // entity.
          stablePictures = true,
        ) {
          // No template (a card with no template shape): the widget's direct document, framed
          // alike.
          WearAppFrame(frame, tint, style, wearLevel(card, snapshot)) {
            SlotContent(
              registry,
              colors,
              card,
              StablePictures.snapshot(card, snapshot),
              template = false,
              cardStyle = style,
              // In a list a card with a slider is brushed by every scroll: its controls start
              // locked. Full screen, it was opened to be used.
              controlLock =
                if (!fullScreen && cardHasDragControls(card)) HaControlLockMode.Locked
                else HaControlLockMode.Off,
            )
          }
        }
      }
    }
    taps.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
  }
}

/** The phone-selected template, shared by pinned, section and dashboard cards. */
internal fun WearOfflineStore.syncedCardDesign(card: CardConfig, container: String): ByteArray? {
  val key = ee.schimke.ha.client.design.cardFingerprint(card)
  return readSlotDesigns()
    ?.designs
    ?.firstOrNull { it.cardKey == key && it.container == container }
    ?.let { readDesign(it.sha256) }
}

internal fun syncedWearAppTemplate(
  bytes: ByteArray,
  frame: WearWidgetFrame,
  tint: Int?,
): ByteArray? {
  val neutral =
    if (WearWidgetTemplate.namesFrame(bytes)) bytes
    else ee.schimke.terrazzo.wear.widget.CardDesignWearTemplate.template(bytes) ?: bytes
  return WearWidgetTemplate.forApp(neutral, frame, tint)
}

/**
 * Sends a watch card's taps to the phone: a [LocalWearTapSender] while it allows them, else none
 * (the card's taps then say how to turn them on). Kept while the card shows, so a keypad's code
 * survives a value update.
 */
internal val LocalWearTapSender =
  staticCompositionLocalOf<(suspend (card: String, action: HaAction) -> Unit)?> { null }

/** A watch card's tap dispatcher and the line it shows under the card while one is in flight. */
internal class WearCardTaps(val dispatcher: HaActionDispatcher, val enabled: () -> Boolean) {
  var status: String? by mutableStateOf(null)
}

/**
 * The [WearTapDispatcher] for the card [rawJson], with the watch's feedback: a tick as the tap is
 * sent (a key tap for a keypad key), a confirm when the phone ran it, a reject and the phone's
 * reason under the card when it didn't — the message card's error style. A more-info tap runs
 * [onMoreInfo], when there is one.
 *
 * Taps act on Home Assistant through the phone; the card says how one went.
 */
@Composable
internal fun rememberWearCardTaps(rawJson: String, onMoreInfo: (() -> Unit)? = null): WearCardTaps {
  val sender = rememberUpdatedState(LocalWearTapSender.current)
  val moreInfo = rememberUpdatedState(onMoreInfo)
  val view = LocalView.current
  val scope = rememberCoroutineScope()
  return remember(rawJson, scope, view) {
    lateinit var taps: WearCardTaps
    val dispatcher =
      WearTapDispatcher(
        card = rawJson,
        scope = scope,
        enabled = { sender.value != null },
        send = { card, action ->
          (sender.value ?: error(WearTapDispatcher.TAPS_OFF)).invoke(card, action)
        },
        onOutcome = { _, outcome ->
          view.performHapticFeedback(wearTapHaptic(outcome))
          when (outcome) {
            WearTapOutcome.Sent -> taps.status = "Sending…"
            WearTapOutcome.Done -> taps.status = null
            WearTapOutcome.Key,
            WearTapOutcome.Failed -> Unit
          }
        },
        onFailure = { taps.status = it },
        onMoreInfo = { moreInfo.value?.invoke() },
      )
    WearCardTaps(dispatcher, enabled = { sender.value != null }).also { taps = it }
  }
}

/** The haptic for a tap's [outcome]. */
internal fun wearTapHaptic(outcome: WearTapOutcome): Int =
  when (outcome) {
    WearTapOutcome.Sent -> HapticFeedbackConstants.CLOCK_TICK
    WearTapOutcome.Key -> HapticFeedbackConstants.KEYBOARD_TAP
    WearTapOutcome.Done -> HapticFeedbackConstants.CONFIRM
    WearTapOutcome.Failed -> HapticFeedbackConstants.REJECT
  }

/**
 * The document [design] plays in the Wear app in [frame]: its container-neutral widget template
 * with the frame's values filled in ([WearWidgetTemplate.forApp]). Null when the card has no
 * template shape or the template can't be captured; the card then draws its widget's direct
 * document. Shared with [ee.schimke.terrazzo.wear.sync.WearCardWarmup], which captures the same
 * templates ahead of the app.
 */
internal suspend fun wearAppTemplate(
  context: Context,
  design: CardConfig,
  snapshot: HaSnapshot,
  look: WearCardLook,
  style: HaUberTheme,
  frame: WearWidgetFrame,
  tint: Int?,
  registry: CardRegistry,
): ByteArray? {
  val converter = registry.converterFor(design.type) ?: return null
  val properties = converter.wearDesignProperties(design, snapshot) ?: return null
  val input = converter.wearDesignTemplate(properties) ?: return null
  val scheme = terrazzoWearColorScheme(look.palette, context)
  val neutral =
    neutralWidgetTemplate(
      context,
      registry,
      wearWidgetColors(scheme),
      wearPaletteKey(look.palette, scheme),
      design.type,
      properties.shape,
      input,
      style,
    ) ?: return null
  return WearWidgetTemplate.forApp(neutral, frame, tint)
}

/**
 * The generation shape of [design]'s Wear template in [snapshot] ([wearDesignProperties]): what
 * [wearAppTemplate] captures from, so a change to it must capture again. Null without a template.
 */
internal fun wearTemplateShape(
  design: CardConfig,
  snapshot: HaSnapshot,
  registry: CardRegistry,
): String? = registry.converterFor(design.type)?.wearDesignProperties(design, snapshot)?.shape

/**
 * [content] in [frame]'s container, as a widget host draws one: rounded, filled and padded, in
 * [cardStyle]'s Wear background, with [level]'s reading filling it (the template's own container,
 * [RemoteWearContainer], with the frame's values).
 */
@Composable
private fun WearAppFrame(
  frame: WearWidgetFrame,
  tint: Int?,
  cardStyle: HaUberTheme,
  level: HaWearLevel?,
  content: @Composable () -> Unit,
) {
  val background = cardStyle.wearBackground()
  val fill =
    tint?.let { androidx.compose.ui.graphics.Color(it) } ?: background?.base ?: WIDGET_SURFACE
  RemoteWearContainer(
    background,
    fill.rc,
    frame.cornerRadiusDp.rdp,
    level,
    cardStyle.wearLevelLook(),
  ) {
    RemoteBox(
      modifier =
        RemoteModifier.fillMaxSize()
          .padding(frame.horizontalPaddingDp.rdp, frame.verticalPaddingDp.rdp)
    ) {
      content()
    }
  }
}

/**
 * The container [card] takes full screen: Large, the biggest a Wear widget has, for a card with a
 * Large tier; a card that only fills a Small one keeps it rather than floating in the Large frame.
 */
internal fun wearFullScreenContainer(card: CardConfig): String =
  if (wearCardDataTier(card.type).supportsLarge) ee.schimke.ha.design.DesignKey.WEAR_LARGE
  else ee.schimke.ha.design.DesignKey.WEAR_SMALL

/**
 * How much a full-screen card's [frameWidth] × [frameHeight] frame (dp) is scaled: as large as fits
 * [maxWidth] × [maxHeight] (the space it's given; the height unbounded in a list) and, on a round
 * screen of [screenWidth], the circle's inscribed width for a widget's shape, at most 1.6 times.
 * Below 1 the card isn't scaled at all (a smaller copy would shrink its touch targets): it is laid
 * out narrower, as in a list.
 */
internal fun fullScreenScale(
  frameWidth: Float,
  frameHeight: Float,
  maxWidth: Float,
  maxHeight: Float,
  screenWidth: Float,
  round: Boolean,
): Float {
  val widthLimit = minOf(maxWidth, if (round) screenWidth * ROUND_INSCRIBED_WIDTH else screenWidth)
  val byWidth = widthLimit / frameWidth
  val byHeight = if (maxHeight.isFinite()) maxHeight / frameHeight else Float.MAX_VALUE
  return minOf(byWidth, byHeight).coerceAtMost(MAX_FULL_SCREEN_SCALE)
}

/**
 * The share of a round screen's width a 200 × 112 widget fits at its widest, centred: its corners
 * then touch the circle (cos of atan(112 / 200)).
 */
private const val ROUND_INSCRIBED_WIDTH = 0.87f
private const val MAX_FULL_SCREEN_SCALE = 1.6f

/**
 * Lays its content out at [width] × [height] and draws it [scale] times larger, taking the scaled
 * size: the card keeps the layout its template was captured for, only bigger. Pointer input is
 * transformed with the layer, so taps land where they're drawn.
 */
internal fun Modifier.scaledFrame(width: Dp, height: Dp, scale: Float): Modifier =
  layout { measurable, _ ->
    val w = width.roundToPx()
    val h = height.roundToPx()
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout((w * scale).roundToInt(), (h * scale).roundToInt()) {
      placeable.placeWithLayer(0, 0) {
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(0f, 0f)
      }
    }
  }

/** The container colour a widget host fills for an untinted widget. */
private val WIDGET_SURFACE = CLASSIC_WEAR_SURFACE

/** The snapshot the watch's cards render from: its last synced entity values. */
internal fun wearSnapshot(values: Map<String, EntityValue>): HaSnapshot =
  HaSnapshot(states = values.mapValues { (id, v) -> v.toEntityState(id) })

internal fun decodeWearCard(raw: String): CardConfig? = runCatching {
  Json.decodeFromString(CardConfig.serializer(), raw)
}
  .getOrNull()
