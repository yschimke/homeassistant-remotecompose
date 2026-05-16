@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "RestrictedApi")

package ee.schimke.ha.previews

import android.graphics.Bitmap
import androidx.compose.remote.player.core.platform.BitmapLoader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth as uiFillMaxWidth
import androidx.compose.foundation.layout.height as uiHeight
import androidx.compose.foundation.layout.size as uiSize
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.LocalRemoteImageResolver
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RemoteImageResolver
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.LocalPictureImageStrategy
import ee.schimke.ha.rc.components.PictureImageStrategy
import ee.schimke.ha.rc.components.ProvideHaTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Picture-entity previews that exercise both [PictureImageStrategy]
 * modes end-to-end inside Robolectric:
 *
 *  - **App mode** (`AppMode_Light/Dark`) — captures the URL-form
 *    named bitmap via [rememberLocalNamedRemoteBitmap] with explicit
 *    dimensions. The host wires a fake Coil-backed `BitmapLoader`
 *    that maps the URL to a known yellow-on-blue bitmap; the player
 *    resolves it at playback exactly like the device's
 *    `HaImageStack.imageLoader`. Renders the disc.
 *  - **Widget mode** (`WidgetMode_Light`) — provides
 *    [PictureImageStrategy.Inline] with a pre-fetched [ImageBitmap].
 *    The converter bakes the bytes into the captured doc; no
 *    `BitmapLoader` is wired, mirroring the widget runtime where no
 *    loader is available. Also renders the disc — proves the same
 *    visual outcome regardless of strategy.
 *  - **Icon fallback** (`IconFallback_Light`) — snapshot has no
 *    `entity_picture`, so the converter takes the [RemoteIcon] path
 *    (vector ops, no `BitmapData`). Renders a camera icon.
 *
 * Confirms the upstream gray-tile bug (#277) is bypassed via the
 * local `addNamedBitmapUrlSized` writer workaround, and that the
 * strategy abstraction routes correctly between the two production
 * surfaces.
 */

private const val PICTURE_TILE_WIDTH_DP = 360
private const val PICTURE_TILE_HEIGHT_DP = 160
private const val SAMPLE_PICTURE_BITMAP_PX = 512

private val pictureSampleCard: CardConfig =
  card(
    """{"type":"picture-entity","entity":"camera.preview_cam","name":"Preview cam","show_name":true,"show_state":true}""",
  )

private val pictureSampleCardNoEntity: CardConfig =
  card(
    """{"type":"picture-entity","entity":"camera.offline_cam","name":"Offline cam","show_name":true,"show_state":false}""",
  )

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
                  "entity_picture" to
                    JsonPrimitive(
                      "/api/camera_proxy/camera.preview_cam?token=preview-token",
                    ),
                ),
              ),
          ),
      ),
  )

private val pictureSampleSnapshotNoImage: HaSnapshot =
  HaSnapshot(
    states =
      mapOf(
        "camera.offline_cam" to
          EntityState(
            entityId = "camera.offline_cam",
            state = "idle",
            attributes =
              JsonObject(mapOf("friendly_name" to JsonPrimitive("Offline cam"))),
          ),
      ),
  )

/** Visible test bitmap — yellow disc on blue. Asserts "override happened". */
private fun fakeOverrideBitmap(sizePx: Int): Bitmap {
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

/**
 * Resolver fixed to one canned bitmap, captured at the picture-entity
 * placeholder size so the host-side `Bitmap.createScaledBitmap` in
 * `CachedCardPreview` is a no-op.
 */
private fun fakePictureResolver(): RemoteImageResolver = RemoteImageResolver { url ->
  fakeOverrideBitmap(SAMPLE_PICTURE_BITMAP_PX)
}

@Preview(
  name = "picture-entity app-mode (light)",
  showBackground = false,
  widthDp = PICTURE_TILE_WIDTH_DP,
  heightDp = PICTURE_TILE_HEIGHT_DP,
)
@Composable
fun PictureEntity_AppMode_Light() {
  PictureEntityAppModeHost(
    theme = HaTheme.Light,
    card = pictureSampleCard,
    snapshot = pictureSampleSnapshotWithImage,
  )
}

@Preview(
  name = "picture-entity app-mode (dark)",
  showBackground = false,
  widthDp = PICTURE_TILE_WIDTH_DP,
  heightDp = PICTURE_TILE_HEIGHT_DP,
)
@Composable
fun PictureEntity_AppMode_Dark() {
  PictureEntityAppModeHost(
    theme = HaTheme.Dark,
    card = pictureSampleCard,
    snapshot = pictureSampleSnapshotWithImage,
  )
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
  // The rendered output should be the yellow disc — proves the
  // widget path renders without a loader.
  val prefetched = remember { fakeOverrideBitmap(SAMPLE_PICTURE_BITMAP_PX).asImageBitmap() }
  val strategy = remember(prefetched) {
    PictureImageStrategy.Inline { url -> prefetched }
  }
  Box(modifier = Modifier.uiFillMaxWidth().uiHeight(PICTURE_TILE_HEIGHT_DP.dp)) {
    CachedCardPreview(
      cacheKey =
        PicturePreviewKey(pictureSampleCard, HaTheme.Light, withOverride = false),
      profile = androidXExperimentalWrap,
      modifier = Modifier.uiFillMaxWidth(),
      card = pictureSampleCard,
      snapshot = pictureSampleSnapshotWithImage,
    ) {
      // The strategy provider must live INSIDE the
      // `captureSingleRemoteDocument` sub-composition for the
      // converter to see it — CompositionLocals from the outer
      // tree don't propagate into the capture pass.
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
  // Snapshot has no `entity_picture` — the converter takes the
  // RemoteIcon branch, which encodes vector paths (no BitmapData op
  // involved). Confirms the dimension issue is bitmap-specific.
  Box(
    modifier =
      Modifier.uiFillMaxWidth().uiHeight(PICTURE_TILE_HEIGHT_DP.dp),
  ) {
    CachedCardPreview(
      cacheKey =
        PicturePreviewKey(
          pictureSampleCardNoEntity,
          HaTheme.Light,
          withOverride = false,
        ),
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
private fun PictureEntityAppModeHost(
  theme: HaTheme,
  card: CardConfig,
  snapshot: HaSnapshot,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val resolver = remember { fakePictureResolver() }
  // Wire a fake Coil-backed BitmapLoader so the URL-form named
  // bitmap (which the converter now emits via the local
  // `addNamedBitmapUrlSized` workaround) actually resolves in the
  // preview env. Maps the snapshot's `entity_picture` URL to the
  // same yellow-disc bitmap the resolver returns so the URL fetch
  // and the override push produce the same pixels — the preview
  // can't tell them apart by pixel, only by which path lit them up.
  val resolvedUrl =
    snapshot.states.values.firstNotNullOfOrNull {
      it.attributes["entity_picture"]?.let { v ->
        (v as? JsonPrimitive)?.contentOrNull
      }
    }
  val sampleBitmap = remember { fakeOverrideBitmap(SAMPLE_PICTURE_BITMAP_PX) }
  val bitmapLoader =
    remember(context, resolvedUrl, sampleBitmap) {
      if (resolvedUrl == null) {
        BitmapLoader.UNSUPPORTED
      } else {
        previewCoilBitmapLoader(
          context,
          mappings = mapOf(resolvedUrl to sampleBitmap.asImageBitmap()),
        )
      }
    }
  Box(modifier = Modifier.uiFillMaxWidth().uiHeight(PICTURE_TILE_HEIGHT_DP.dp)) {
    CompositionLocalProvider(LocalRemoteImageResolver provides resolver) {
      CachedCardPreview(
        cacheKey = PicturePreviewKey(card, theme, withOverride = true),
        profile = androidXExperimentalWrap,
        modifier = Modifier.uiFillMaxWidth(),
        card = card,
        snapshot = snapshot,
        bitmapLoader = bitmapLoader,
      ) {
        ProvideCardRegistry(defaultRegistry()) {
          ProvideHaTheme(theme) {
            RenderChild(card, snapshot, RemoteModifier.rcFillMaxWidth())
          }
        }
      }
    }
  }
}

private data class PicturePreviewKey(
  val card: CardConfig,
  val theme: HaTheme,
  val withOverride: Boolean,
)
