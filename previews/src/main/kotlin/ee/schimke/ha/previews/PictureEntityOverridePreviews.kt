@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "RestrictedApi")

package ee.schimke.ha.previews

import android.graphics.Bitmap
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
import ee.schimke.ha.rc.components.ProvideHaTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Picture-entity previews that drive the **named-bitmap override path**
 * end-to-end inside Robolectric — the same flow the device runs in
 * `DashboardViewScreen`:
 *
 *  1. `PictureEntityCardConverter` renders `RemoteHaPictureEntity`,
 *     which captures `RemoteHaImageNamed(name, placeholder)` into the
 *     `.rc` document. The placeholder is a fixed-size blank bitmap so
 *     the `BitmapData` op carries non-zero width / height.
 *  2. `CachedCardPreview` walks the card for picture-entity ids, reads
 *     `entity_picture` from the snapshot, calls the
 *     `LocalRemoteImageResolver` for a fresh bitmap, and pushes via
 *     `StateUpdater.setUserLocalBitmap`.
 *  3. The player swaps the named-bitmap slot to the pushed bytes and
 *     re-renders.
 *
 * The fake resolver returns a known visible bitmap (yellow disc on a
 * blue field) — anything *but* a gray placeholder. If the rendered
 * preview shows the disc, the override pipeline is working at slot
 * level; if it stays gray, dimensions / encoding remain the suspect.
 *
 * The "icon" variant exercises the `imageUrl = null` branch so the
 * preview confirms the icon fallback path also still renders.
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
  name = "picture-entity override (light)",
  showBackground = false,
  widthDp = PICTURE_TILE_WIDTH_DP,
  heightDp = PICTURE_TILE_HEIGHT_DP,
)
@Composable
fun PictureEntity_Override_Light() {
  PictureEntityOverrideHost(
    theme = HaTheme.Light,
    card = pictureSampleCard,
    snapshot = pictureSampleSnapshotWithImage,
  )
}

@Preview(
  name = "picture-entity override (dark)",
  showBackground = false,
  widthDp = PICTURE_TILE_WIDTH_DP,
  heightDp = PICTURE_TILE_HEIGHT_DP,
)
@Composable
fun PictureEntity_Override_Dark() {
  PictureEntityOverrideHost(
    theme = HaTheme.Dark,
    card = pictureSampleCard,
    snapshot = pictureSampleSnapshotWithImage,
  )
}

@Preview(
  name = "picture-entity placeholder-only (light)",
  showBackground = false,
  widthDp = PICTURE_TILE_WIDTH_DP,
  heightDp = PICTURE_TILE_HEIGHT_DP,
)
@Composable
fun PictureEntity_Placeholder_Light() {
  // No resolver wired — the picture-entity card captures the
  // placeholder bitmap into the doc but nothing pushes an override.
  // The slot should render the placeholder (transparent → backdrop
  // shows through).
  Box(
    modifier =
      Modifier.uiFillMaxWidth().uiHeight(PICTURE_TILE_HEIGHT_DP.dp),
  ) {
    CachedCardPreview(
      cacheKey =
        PictureOverridePreviewKey(pictureSampleCard, HaTheme.Light, withOverride = false),
      profile = androidXExperimentalWrap,
      modifier = Modifier.uiFillMaxWidth(),
      card = pictureSampleCard,
      snapshot = pictureSampleSnapshotWithImage,
    ) {
      ProvideCardRegistry(defaultRegistry()) {
        ProvideHaTheme(HaTheme.Light) {
          RenderChild(pictureSampleCard, pictureSampleSnapshotWithImage, RemoteModifier.rcFillMaxWidth())
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
        PictureOverridePreviewKey(
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
private fun PictureEntityOverrideHost(
  theme: HaTheme,
  card: CardConfig,
  snapshot: HaSnapshot,
) {
  val resolver = remember { fakePictureResolver() }
  Box(modifier = Modifier.uiFillMaxWidth().uiHeight(PICTURE_TILE_HEIGHT_DP.dp)) {
    CompositionLocalProvider(LocalRemoteImageResolver provides resolver) {
      CachedCardPreview(
        cacheKey = PictureOverridePreviewKey(card, theme, withOverride = true),
        profile = androidXExperimentalWrap,
        modifier = Modifier.uiFillMaxWidth(),
        card = card,
        snapshot = snapshot,
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

private data class PictureOverridePreviewKey(
  val card: CardConfig,
  val theme: HaTheme,
  val withOverride: Boolean,
)
