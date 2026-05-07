@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.layout.Box
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.tooling.preview.RemotePreview
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth as uiFillMaxWidth
import androidx.compose.foundation.layout.height as uiHeight
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.RemoteHaImageInline
import ee.schimke.ha.rc.components.RemoteHaImageNamed

/**
 * Image samples — exercise the two ways RemoteCompose carries images:
 *
 * - `RemoteHaImageInline` bakes the bitmap bytes into the `.rc`
 *   document. Renders identically in `RemotePreview` (no loader
 *   needed) and in any player.
 * - `RemoteHaImageNamed` references the image by name. Players that
 *   provide a `BitmapLoader` resolve the bytes at playback; everyone
 *   else (including these previews) sees the placeholder bitmap.
 *
 * Both previews use a programmatically-generated [sampleBitmap] so
 * they don't depend on a drawable resource — making the document
 * size visible (~few KB inline vs. just-the-name for the named form
 * is a deliberate part of the demo).
 */

private const val IMAGE_BOX_HEIGHT_DP = 96
private const val SAMPLE_BITMAP_PX = 96

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

@Preview(name = "image-inline (light)", showBackground = false, widthDp = 200, heightDp = IMAGE_BOX_HEIGHT_DP)
@Composable
fun ImageInline_Light() = ImageHost(HaTheme.Light) {
    RemoteHaImageInline(
        bitmap = sampleBitmap(),
        contentDescription = "inline sample".rs,
        modifier = RemoteModifier.fillMaxWidth().height(IMAGE_BOX_HEIGHT_DP.rdp),
    )
}

@Preview(name = "image-inline (dark)", showBackground = false, widthDp = 200, heightDp = IMAGE_BOX_HEIGHT_DP)
@Composable
fun ImageInline_Dark() = ImageHost(HaTheme.Dark) {
    RemoteHaImageInline(
        bitmap = sampleBitmap(),
        contentDescription = "inline sample".rs,
        modifier = RemoteModifier.fillMaxWidth().height(IMAGE_BOX_HEIGHT_DP.rdp),
    )
}

@Preview(name = "image-named (light)", showBackground = false, widthDp = 200, heightDp = IMAGE_BOX_HEIGHT_DP)
@Composable
fun ImageNamed_Light() = ImageHost(HaTheme.Light) {
    RemoteHaImageNamed(
        name = "samples/weather-now",
        placeholder = sampleBitmap(),
        contentDescription = "named sample".rs,
        modifier = RemoteModifier.fillMaxWidth().height(IMAGE_BOX_HEIGHT_DP.rdp),
    )
}

@Preview(name = "image-named (dark)", showBackground = false, widthDp = 200, heightDp = IMAGE_BOX_HEIGHT_DP)
@Composable
fun ImageNamed_Dark() = ImageHost(HaTheme.Dark) {
    RemoteHaImageNamed(
        name = "samples/weather-now",
        placeholder = sampleBitmap(),
        contentDescription = "named sample".rs,
        modifier = RemoteModifier.fillMaxWidth().height(IMAGE_BOX_HEIGHT_DP.rdp),
    )
}

@Composable
private fun ImageHost(
    theme: HaTheme,
    content: @Composable @RemoteComposable () -> Unit,
) {
    Box(modifier = Modifier.uiFillMaxWidth().uiHeight(IMAGE_BOX_HEIGHT_DP.dp)) {
        RemotePreview(profile = androidXExperimental) {
            ProvideHaTheme(theme) { content() }
        }
    }
}
