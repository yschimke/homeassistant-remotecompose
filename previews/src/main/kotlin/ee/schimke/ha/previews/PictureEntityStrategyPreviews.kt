@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "RestrictedApi")

package ee.schimke.ha.previews

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth as uiFillMaxWidth
import androidx.compose.foundation.layout.height as uiHeight
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth as rcFillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.LocalPictureImageStrategy
import ee.schimke.ha.rc.components.PictureImageStrategy
import ee.schimke.ha.rc.components.ProvideHaTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Picture-entity previews that exercise both [PictureImageStrategy] modes end-to-end inside
 * Robolectric — the side-by-side proof that the URL-form 1×1 bug from #277 is bypassed:
 *
 * - **App mode** (`AppMode_Light/Dark`) — captures the URL form via
 *   `rememberLocalNamedRemoteBitmap` with explicit 512×512 dimensions. The host wires a fake
 *   Coil-backed `BitmapLoader` that maps the URL to a known yellow-on-blue bitmap; the player
 *   resolves it at playback exactly like the device's `HaImageStack.imageLoader`. Renders the disc.
 * - **Widget mode** (`WidgetMode_Light`) — provides [PictureImageStrategy.Inline] with a
 *   pre-fetched [ImageBitmap]. The converter bakes the bytes into the captured doc; no
 *   `BitmapLoader` is wired, mirroring the widget runtime where no loader is available. Also
 *   renders the disc — proves the same visual outcome from a self-contained doc.
 * - **Icon fallback** (`IconFallback_Light`) — snapshot has no `entity_picture`, so the converter
 *   takes the `RemoteIcon` path (vector ops, no `BitmapData`). Renders a camera icon.
 *
 * The strategy `CompositionLocalProvider` must live **inside** the `CachedCardPreview` content
 * block — `captureSingleRemoteDocument` creates a sub-composition that doesn't inherit
 * `CompositionLocal`s from the outer tree.
 */
private const val PICTURE_TILE_WIDTH_DP = 360
private const val PICTURE_TILE_HEIGHT_DP = 160
private const val SAMPLE_PICTURE_BITMAP_PX = 512

private val pictureSampleCard: CardConfig =
  card(
    """{"type":"picture-entity","entity":"camera.preview_cam","name":"Preview cam","show_name":true,"show_state":true}"""
  )

private val pictureSampleCardNoEntity: CardConfig =
  card(
    """{"type":"picture-entity","entity":"camera.offline_cam","name":"Offline cam","show_name":true,"show_state":false}"""
  )

private const val SAMPLE_PICTURE_URL = "/api/camera_proxy/camera.preview_cam?token=preview-token"

private val pictureSampleSnapshotWithImage: HaSnapshot =
  HaSnapshot(
    states =
      mapOf(
        "camera.preview_cam" to
          EntityState(
            entityId = "camera.preview_cam",
            state = "streaming",
            attributes =
              JsonObject(
                mapOf(
                  "friendly_name" to JsonPrimitive("Preview cam"),
                  "entity_picture" to JsonPrimitive(SAMPLE_PICTURE_URL),
                )
              ),
          )
      )
  )

private val pictureSampleSnapshotNoImage: HaSnapshot =
  HaSnapshot(
    states =
      mapOf(
        "camera.offline_cam" to
          EntityState(
            entityId = "camera.offline_cam",
            state = "idle",
            attributes = JsonObject(mapOf("friendly_name" to JsonPrimitive("Offline cam"))),
          )
      )
  )

/** Visible test bitmap — yellow disc on blue. */
private fun sampleBitmap(sizePx: Int): Bitmap {
  val size = sizePx.toFloat()
  val composeBitmap = ImageBitmap(sizePx, sizePx)
  val canvas = Canvas(composeBitmap)
  val paint = Paint()
  paint.color = Color(0xFF1565C0)
  canvas.drawRect(0f, 0f, size, size, paint)
  paint.color = Color(0xFFFFC107)
  canvas.drawCircle(Offset(size / 2f, size / 2f), size * 0.32f, paint)
  return composeBitmap.asAndroidBitmap()
}

@Preview(
  name = "picture-entity app-mode (light)",
  showBackground = false,
  widthDp = PICTURE_TILE_WIDTH_DP,
  heightDp = PICTURE_TILE_HEIGHT_DP,
)
@Composable
fun PictureEntity_AppMode_Light() {
  AppModeHost(theme = HaTheme.Light)
}

@Preview(
  name = "picture-entity app-mode (dark)",
  showBackground = false,
  widthDp = PICTURE_TILE_WIDTH_DP,
  heightDp = PICTURE_TILE_HEIGHT_DP,
)
@Composable
fun PictureEntity_AppMode_Dark() {
  AppModeHost(theme = HaTheme.Dark)
}

@Preview(
  name = "picture-entity widget-mode (light)",
  showBackground = false,
  widthDp = PICTURE_TILE_WIDTH_DP,
  heightDp = PICTURE_TILE_HEIGHT_DP,
)
@Composable
fun PictureEntity_WidgetMode_Light() {
  // Widget mode: no BitmapLoader at playback. The host pre-fetches
  // the URL and exposes the bytes via PictureImageStrategy.Inline;
  // the converter bakes them into the captured doc via
  // `rememberLocalNamedRemoteBitmap(name) { bitmap }` (bytes form).
  val prefetched = remember { sampleBitmap(SAMPLE_PICTURE_BITMAP_PX).asImageBitmap() }
  val strategy = remember(prefetched) { PictureImageStrategy.Inline { prefetched } }
  Box(modifier = Modifier.uiFillMaxWidth().uiHeight(PICTURE_TILE_HEIGHT_DP.dp)) {
    CachedCardPreview(
      cacheKey = PicturePreviewKey(pictureSampleCard, HaTheme.Light, widget = true),
      profile = androidXExperimentalWrap,
      modifier = Modifier.uiFillMaxWidth(),
      card = pictureSampleCard,
      snapshot = pictureSampleSnapshotWithImage,
    ) {
      // The provider must live INSIDE the capture sub-composition;
      // CompositionLocals from the outer tree don't propagate into
      // `captureSingleRemoteDocument`.
      CompositionLocalProvider(LocalPictureImageStrategy provides strategy) {
        ProvideCardRegistry(defaultRegistry()) {
          ProvideHaTheme(HaTheme.Light) {
            RenderChild(
              pictureSampleCard,
              pictureSampleSnapshotWithImage,
              RemoteModifier.rcFillMaxWidth(),
            )
          }
        }
      }
    }
  }
}

@Preview(
  name = "picture-entity icon-fallback (light)",
  showBackground = false,
  widthDp = PICTURE_TILE_WIDTH_DP,
  heightDp = PICTURE_TILE_HEIGHT_DP,
)
@Composable
fun PictureEntity_IconFallback_Light() {
  Box(modifier = Modifier.uiFillMaxWidth().uiHeight(PICTURE_TILE_HEIGHT_DP.dp)) {
    CachedCardPreview(
      cacheKey = PicturePreviewKey(pictureSampleCardNoEntity, HaTheme.Light, widget = false),
      profile = androidXExperimentalWrap,
      modifier = Modifier.uiFillMaxWidth(),
      card = pictureSampleCardNoEntity,
      snapshot = pictureSampleSnapshotNoImage,
    ) {
      ProvideCardRegistry(defaultRegistry()) {
        ProvideHaTheme(HaTheme.Light) {
          RenderChild(
            pictureSampleCardNoEntity,
            pictureSampleSnapshotNoImage,
            RemoteModifier.rcFillMaxWidth(),
          )
        }
      }
    }
  }
}

@Composable
private fun AppModeHost(theme: HaTheme) {
  val context = LocalContext.current
  val resolved = remember { sampleBitmap(SAMPLE_PICTURE_BITMAP_PX).asImageBitmap() }
  // Fake Coil loader that maps the snapshot's `entity_picture` URL
  // to the test bitmap. Mirrors the device's `HaImageStack` wiring,
  // which routes the URL through Coil before handing bytes to the
  // player.
  val bitmapLoader =
    remember(context, resolved) {
      previewCoilBitmapLoader(context, mappings = mapOf(SAMPLE_PICTURE_URL to resolved))
    }
  Box(modifier = Modifier.uiFillMaxWidth().uiHeight(PICTURE_TILE_HEIGHT_DP.dp)) {
    CachedCardPreview(
      cacheKey = PicturePreviewKey(pictureSampleCard, theme, widget = false),
      profile = androidXExperimentalWrap,
      modifier = Modifier.uiFillMaxWidth(),
      card = pictureSampleCard,
      snapshot = pictureSampleSnapshotWithImage,
      bitmapLoader = bitmapLoader,
    ) {
      // App mode uses the default `PictureImageStrategy.Url` so no
      // provider is needed here.
      ProvideCardRegistry(defaultRegistry()) {
        ProvideHaTheme(theme) {
          RenderChild(
            pictureSampleCard,
            pictureSampleSnapshotWithImage,
            RemoteModifier.rcFillMaxWidth(),
          )
        }
      }
    }
  }
}

private data class PicturePreviewKey(val card: CardConfig, val theme: HaTheme, val widget: Boolean)
