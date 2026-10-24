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
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
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
import ee.schimke.terrazzo.wear.widget.wearDesignProperties
import ee.schimke.terrazzo.wear.widget.wearDesignTemplate
import ee.schimke.terrazzo.wear.widget.wearWidgetCardStyle
import ee.schimke.terrazzo.wear.widget.wearWidgetColors
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

internal data class WearCardLook(
  val palette: ThemeStyle = ThemeStyle.TerrazzoHome,
  val style: String = HaUberTheme.Classic.id,
)

internal val LocalWearCardLook = staticCompositionLocalOf { WearCardLook() }

/**
 * A card in the Wear app, drawn exactly as its Wear widget draws it: in the widget container's
 * frame (Small or Large, [wearAppContainer]; the sizes this watch's widget host uses, see
 * [WearWidgetFrame]), from the same container-neutral template ([neutralWidgetTemplate]), under the
 * widget's colours and size mode. A card added as a widget then looks the same on the watch face as
 * it did in the app. Unlike the widget, which bakes its values, the app plays the template's
 * bindings, so values update live.
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
  val design = CardDesignTemplates.designCard(card, snapshot, registry)
  val frame =
    remember(card, snapshot) {
      WearWidgetFrame.forContainer(context, wearAppContainer(card, snapshot, registry))
    }
  // The card's state tints its container, as on the widget: warm for heating, amber for a light.
  val tint = remember(card, snapshot) { ContainerTint.argbFor(card, snapshot) }
  val colors = remember(look.palette) { wearWidgetColors(look.palette) }
  // The template's own shape: a toggle turning on or off changes it (a Wear tier resolves its icon
  // at capture, see WEAR_ON_PROPERTY) without changing the design, frame or, untinted, the tint.
  val shape = remember(design, snapshot) { wearTemplateShape(design, snapshot, registry) }
  val template =
    remember(design, shape, look, style, frame, tint) {
      runBlocking { wearAppTemplate(context, design, snapshot, look, style, frame, tint, registry) }
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
      card,
      look,
      style.id,
      frame,
      tint,
      signature,
      cardIconSignature(card, snapshot),
      cardVisibilitySignature(card, snapshot),
    )
  // Taps act on Home Assistant through the phone ([WearTapDispatcher]); the card says how one went.
  val taps = rememberWearCardTaps(summary.rawJson)
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      // A watch narrower than the frame (before its host has reported one) keeps the frame's
      // height.
      val width = minOf(frame.widthDp.dp, maxWidth)
      CompositionLocalProvider(LocalHaActionDispatcher provides taps.dispatcher) {
        CachedCardPreview(
          cacheKey = key,
          profile = androidXExperimentalWrap,
          modifier = Modifier.size(width, frame.heightDp.dp),
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
          WearAppFrame(frame, tint) {
            SlotContent(
              registry,
              colors,
              card,
              StablePictures.snapshot(card, snapshot),
              template = false,
              cardStyle = style,
            )
          }
        }
      }
    }
    taps.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
  }
}

/**
 * Sends a watch card's taps to the phone: a [LocalWearTapSender] while it allows them, else none
 * (the card's taps then say how to turn them on). Kept while the card shows, so a keypad's code
 * survives a value update.
 */
internal val LocalWearTapSender =
  staticCompositionLocalOf<(suspend (card: String, action: HaAction) -> Unit)?> { null }

/** A watch card's tap dispatcher and the line it shows under the card while one is in flight. */
internal class WearCardTaps(val dispatcher: HaActionDispatcher) {
  var status: String? by mutableStateOf(null)
}

/**
 * The [WearTapDispatcher] for the card [rawJson], with the watch's feedback: a tick as the tap is
 * sent (a key tap for a keypad key), a confirm when the phone ran it, a reject and the phone's
 * reason under the card when it didn't — the message card's error style.
 */
@Composable
private fun rememberWearCardTaps(rawJson: String): WearCardTaps {
  val sender = rememberUpdatedState(LocalWearTapSender.current)
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
      )
    WearCardTaps(dispatcher).also { taps = it }
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
  val neutral =
    neutralWidgetTemplate(
      context,
      registry,
      wearWidgetColors(look.palette),
      look.palette,
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

/** [content] in [frame]'s container, as a widget host draws one: rounded, filled and padded. */
@Composable
private fun WearAppFrame(frame: WearWidgetFrame, tint: Int?, content: @Composable () -> Unit) {
  RemoteBox(
    modifier =
      RemoteModifier.fillMaxSize()
        .clip(RemoteRoundedCornerShape(frame.cornerRadiusDp.rdp))
        .background((tint?.let { androidx.compose.ui.graphics.Color(it) } ?: WIDGET_SURFACE).rc)
        .padding(frame.horizontalPaddingDp.rdp, frame.verticalPaddingDp.rdp)
  ) {
    content()
  }
}

/** The container colour a widget host fills for an untinted widget. */
private val WIDGET_SURFACE = androidx.compose.ui.graphics.Color(39, 36, 48)

/** The snapshot the watch's cards render from: its last synced entity values. */
internal fun wearSnapshot(values: Map<String, EntityValue>): HaSnapshot =
  HaSnapshot(states = values.mapValues { (id, v) -> v.toEntityState(id) })

internal fun decodeWearCard(raw: String): CardConfig? = runCatching {
  Json.decodeFromString(CardConfig.serializer(), raw)
}
  .getOrNull()
