@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.terrazzo.wear.widget

import android.app.Application
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.google.android.horologist.tiles.composable.ServiceComposableBitmapRenderer
import ee.schimke.terrazzo.wear.data.WearPrefs
import ee.schimke.terrazzo.wear.data.displayState
import ee.schimke.terrazzo.wear.sync.WearOfflineStore
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Off-by-default capture path that exercises rendering a card widget to a PNG file in the wear
 * app's internal storage. Driven by [WearPrefs.previewCaptureEnabled] — the flag stays off in
 * shipped builds until a Glance Wear API exposes a runtime hook for swapping the system
 * widget-picker preview image. While we can produce the bitmap, the manifest's
 * `previewImage="@drawable/…"` is parsed at install time only, so the captured PNG isn't surfaced
 * anywhere yet.
 *
 * Wiring is here so the capture pipeline can be smoke-tested end-to-end on real Wear OS hardware:
 * flip the pref, pin a card on the phone, and confirm
 * `filesDir/terrazzo/wear/widget-previews/card-<cardKey>-<size>.png` shows up with sensible bytes.
 * When the lib gains the override hook, this controller becomes the real surfacing path with
 * minimal change.
 *
 * Capture composable is a plain Compose surrogate (Text in a Column) matching what [SlotContent]
 * renders today. It deliberately doesn't route through the RemoteCompose player — that path needs
 * the captured `WearWidgetData.captureRawContent` bytes fed into a `RemoteDocPreview`, which is a
 * separate (preview-only) artifact we'd want to validate independently before depending on at
 * runtime.
 */
class WearWidgetPreviewCapturer(
  private val application: Application,
  private val scope: CoroutineScope,
  private val prefs: WearPrefs,
  private val pinnedFlow: kotlinx.coroutines.flow.Flow<PinnedCardSet>,
) {
  private val renderer: ServiceComposableBitmapRenderer by lazy {
    ServiceComposableBitmapRenderer(application)
  }

  private val outputDir: File by lazy {
    File(application.filesDir, "terrazzo/wear/widget-previews").apply { mkdirs() }
  }

  fun start() {
    scope.launch {
      combine(prefs.previewCaptureEnabled, pinnedFlow) { enabled, pinned -> enabled to pinned }
        .distinctUntilChanged()
        .collectLatest { (enabled, pinned) ->
          if (!enabled) return@collectLatest
          runCaptureRound(pinned)
        }
    }
  }

  private suspend fun runCaptureRound(pinned: PinnedCardSet) {
    val values = WearOfflineStore(application).readValues()?.values.orEmpty()
    for (card in pinned.cards) {
      if (card.cardKey.isEmpty()) continue
      val title = card.card.title.ifEmpty { card.card.primaryEntityId }.ifEmpty { "Card" }
      val state =
        values[card.card.primaryEntityId]?.displayState(card.card.primaryEntityId).orEmpty()
      captureAndSave(card.cardKey, "small", SMALL_SIZE, title, state)
      captureAndSave(card.cardKey, "large", LARGE_SIZE, title, state)
    }
  }

  private suspend fun captureAndSave(
    cardKey: String,
    sizeLabel: String,
    size: DpSize,
    title: String,
    state: String,
  ) {
    val bitmap: ImageBitmap? = runCatching {
      renderer.renderComposableToBitmap(canvasSize = size) { CaptureSurrogate(title, state) }
    }
      .getOrNull()
    if (bitmap == null) {
      Log.w(TAG, "renderComposableToBitmap returned null for card=$cardKey size=$sizeLabel")
      return
    }
    val safeKey = cardKey.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val file = File(outputDir, "card-$safeKey-$sizeLabel.png")
    runCatching {
      FileOutputStream(file).use { out ->
        bitmap.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
      }
      Log.i(TAG, "Captured card $cardKey ($sizeLabel) → ${file.absolutePath}")
    }
      .onFailure { Log.w(TAG, "Failed to write $file", it) }
  }

  companion object {
    private const val TAG = "WearWidgetPreview"

    // Dimensions match the Glance Wear container types declared in
    // wear_slot_widget_provider_{small,large}.xml.
    private val SMALL_SIZE = DpSize(width = 200.dp, height = 60.dp)
    private val LARGE_SIZE = DpSize(width = 200.dp, height = 112.dp)
  }
}

/**
 * Plain-Compose stand-in for [SlotContent]. Mirrors the card widget's visible content (title +
 * value). RemoteCompose primitives are authoring-only and don't render under the virtual display,
 * so we use Material3 Text instead. Tracking parity with [SlotContent] by eye for now.
 */
@Composable
private fun CaptureSurrogate(title: String, state: String) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Text(text = title, style = MaterialTheme.typography.titleSmall)
    if (state.isNotEmpty()) {
      Text(text = state, style = MaterialTheme.typography.bodyMedium)
    }
  }
}
