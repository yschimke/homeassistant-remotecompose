@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth as uiFillMaxWidth
import androidx.compose.foundation.layout.height as uiHeight
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.tooling.preview.RemotePreview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.RemoteHaImageInline
import ee.schimke.ha.rc.components.RemoteHaImageNamed
import ee.schimke.ha.rc.components.RemoteHaImageUrl
import ee.schimke.ha.rc.ui.HaEmbeddedPlayer

/**
 * Image samples — exercise the three ways RemoteCompose carries
 * images into a `.rc` document:
 *
 * - `RemoteHaImageInline` — anonymous bytes baked in. Renders
 *   identically in `RemotePreview` (no loader needed).
 * - `RemoteHaImageNamed` — bytes baked in with a registry name.
 *   Renders without a loader; the name is the channel for state
 *   updates.
 * - `RemoteHaImageUrl` — URL only. The player calls
 *   `BitmapLoader.loadBitmap(url)` at playback. Hosts **must** wire
 *   a loader (`RemotePreview`'s default `AndroidBitmapLoader` calls
 *   `URL.openStream()` and crashes in offline / preview contexts);
 *   use [HaEmbeddedPlayer] + a fake `CoilBitmapLoader` (see
 *   [previewCoilBitmapLoader]) in previews / tests.
 *
 * Bitmaps are programmatically drawn so renders don't depend on
 * drawable resources.
 */
private const val IMAGE_BOX_HEIGHT_DP = 96
private const val SAMPLE_BITMAP_PX = 96
private const val SAMPLE_NAME = "samples/weather-now"
private const val SAMPLE_URL = "https://example.test/samples/weather-now.png"

private const val SAMPLE_URL_FRAME_0 = "https://example.test/samples/weather-now.png?frame=0"
private const val SAMPLE_URL_FRAME_1 = "https://example.test/samples/weather-now.png?frame=1"

private fun sampleBitmap(): ImageBitmap {
    val size = SAMPLE_BITMAP_PX.toFloat()
    val bmp = ImageBitmap(SAMPLE_BITMAP_PX, SAMPLE_BITMAP_PX)
    val canvas = Canvas(bmp)
    val paint = Paint()
    paint.color = Color(0xFF1565C0)
    canvas.drawRect(0f, 0f, size, size, paint)
    paint.color = Color(0xFFFFC107)
    canvas.drawCircle(Offset(size / 2f, size / 2f), size * 0.32f, paint)
    return bmp
}

/**
 * Distinct from [sampleBitmap] so the URL fake-coil preview can
 * prove the loader actually resolved (vs. a default fallback).
 */
private fun resolvedSampleBitmap(): ImageBitmap {
    val size = SAMPLE_BITMAP_PX.toFloat()
    val bmp = ImageBitmap(SAMPLE_BITMAP_PX, SAMPLE_BITMAP_PX)
    val canvas = Canvas(bmp)
    val paint = Paint()
    paint.color = Color(0xFF2E7D32)
    canvas.drawRect(0f, 0f, size, size, paint)
    paint.color = Color(0xFFFFFFFF)
    canvas.drawCircle(Offset(size / 2f, size / 2f), size * 0.4f, paint)
    paint.color = Color(0xFF2E7D32)
    canvas.drawCircle(Offset(size / 2f, size / 2f), size * 0.18f, paint)
    return bmp
}

// ─── inline (anonymous, baked) ──────────────────────────────────────

@Preview(
    name = "image-inline (light)",
    showBackground = false,
    widthDp = 200,
    heightDp = IMAGE_BOX_HEIGHT_DP,
)
@Composable
fun ImageInline_Light() =
    ImageHost(HaTheme.Light) {
        RemoteHaImageInline(
            bitmap = sampleBitmap(),
            contentDescription = "inline sample".rs,
            modifier = RemoteModifier.fillMaxWidth().height(IMAGE_BOX_HEIGHT_DP.rdp),
        )
    }

@Preview(
    name = "image-inline (dark)",
    showBackground = false,
    widthDp = 200,
    heightDp = IMAGE_BOX_HEIGHT_DP,
)
@Composable
fun ImageInline_Dark() =
    ImageHost(HaTheme.Dark) {
        RemoteHaImageInline(
            bitmap = sampleBitmap(),
            contentDescription = "inline sample".rs,
            modifier = RemoteModifier.fillMaxWidth().height(IMAGE_BOX_HEIGHT_DP.rdp),
        )
    }

// ─── named (baked, addressable) ─────────────────────────────────────

@Preview(
    name = "image-named (light)",
    showBackground = false,
    widthDp = 200,
    heightDp = IMAGE_BOX_HEIGHT_DP,
)
@Composable
fun ImageNamed_Light() =
    ImageHost(HaTheme.Light) {
        RemoteHaImageNamed(
            name = SAMPLE_NAME,
            bitmap = sampleBitmap(),
            contentDescription = "named sample".rs,
            modifier = RemoteModifier.fillMaxWidth().height(IMAGE_BOX_HEIGHT_DP.rdp),
        )
    }

@Preview(
    name = "image-named (dark)",
    showBackground = false,
    widthDp = 200,
    heightDp = IMAGE_BOX_HEIGHT_DP,
)
@Composable
fun ImageNamed_Dark() =
    ImageHost(HaTheme.Dark) {
        RemoteHaImageNamed(
            name = SAMPLE_NAME,
            bitmap = sampleBitmap(),
            contentDescription = "named sample".rs,
            modifier = RemoteModifier.fillMaxWidth().height(IMAGE_BOX_HEIGHT_DP.rdp),
        )
    }

// ─── URL (loader-resolved, fake-coil-backed) ────────────────────────

@Preview(
    name = "image-url fake-coil (light)",
    showBackground = false,
    widthDp = 200,
    heightDp = IMAGE_BOX_HEIGHT_DP,
)
@Composable
fun ImageUrl_FakeCoil_Light() = FakeCoilUrlHost(HaTheme.Light)

@Preview(
    name = "image-url fake-coil (dark)",
    showBackground = false,
    widthDp = 200,
    heightDp = IMAGE_BOX_HEIGHT_DP,
)
@Composable
fun ImageUrl_FakeCoil_Dark() = FakeCoilUrlHost(HaTheme.Dark)

@Composable
private fun FakeCoilUrlHost(theme: HaTheme) {
    val context = LocalContext.current
    val resolved = remember { resolvedSampleBitmap() }
    val bitmapLoader =
        remember(context, resolved) {
            previewCoilBitmapLoader(context, mappings = mapOf(SAMPLE_URL to resolved))
        }
    Box(modifier = Modifier.uiFillMaxWidth().uiHeight(IMAGE_BOX_HEIGHT_DP.dp)) {
        HaEmbeddedPlayer(profile = androidXExperimental, bitmapLoader = bitmapLoader) {
            ProvideHaTheme(theme) {
                RemoteHaImageUrl(
                    url = SAMPLE_URL,
                    contentDescription = "url sample".rs,
                    modifier = RemoteModifier.fillMaxWidth().height(IMAGE_BOX_HEIGHT_DP.rdp),
                )
            }
        }
    }
}


// ─── URL refresh probe (fixed-frame preview variants) ───────────────

@Preview(
    name = "image-url probe frame=0",
    showBackground = false,
    widthDp = 200,
    heightDp = IMAGE_BOX_HEIGHT_DP,
)
@Composable
fun ImageUrl_Probe_Frame0() = ProbeUrlHost(theme = HaTheme.Light, url = SAMPLE_URL_FRAME_0)

@Preview(
    name = "image-url probe frame=1",
    showBackground = false,
    widthDp = 200,
    heightDp = IMAGE_BOX_HEIGHT_DP,
)
@Composable
fun ImageUrl_Probe_Frame1() = ProbeUrlHost(theme = HaTheme.Light, url = SAMPLE_URL_FRAME_1)

@Composable
private fun ProbeUrlHost(theme: HaTheme, url: String) {
    val context = LocalContext.current
    val frame0 = remember { sampleBitmap() }
    val frame1 = remember { resolvedSampleBitmap() }
    val bitmapLoader =
        remember(context, frame0, frame1) {
            RecordingBitmapLoader(
                previewCoilBitmapLoader(
                    context,
                    mappings = mapOf(SAMPLE_URL_FRAME_0 to frame0, SAMPLE_URL_FRAME_1 to frame1),
                ),
            )
        }
    Box(modifier = Modifier.uiFillMaxWidth().uiHeight(IMAGE_BOX_HEIGHT_DP.dp)) {
        HaEmbeddedPlayer(profile = androidXExperimental, bitmapLoader = bitmapLoader) {
            ProvideHaTheme(theme) {
                RemoteHaImageUrl(
                    url = url,
                    contentDescription = "url probe sample".rs,
                    modifier = RemoteModifier.fillMaxWidth().height(IMAGE_BOX_HEIGHT_DP.rdp),
                )
            }
        }
    }
}

@Composable
private fun ImageHost(theme: HaTheme, content: @Composable @RemoteComposable () -> Unit) {
    Box(modifier = Modifier.uiFillMaxWidth().uiHeight(IMAGE_BOX_HEIGHT_DP.dp)) {
        RemotePreview(profile = androidXExperimental) {
            ProvideHaTheme(theme) { content() }
        }
    }
}
