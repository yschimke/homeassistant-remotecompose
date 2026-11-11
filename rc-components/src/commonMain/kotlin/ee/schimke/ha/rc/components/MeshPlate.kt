@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import ee.schimke.composeai.rccreation.compose.state.RemoteTileMode
import ee.schimke.composeai.rccreation.compose.layout.RemoteOffset
import ee.schimke.composeai.rccreation.compose.shaders.RemoteBrush
import ee.schimke.composeai.rccreation.compose.shaders.linearGradient
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rf
import androidx.compose.ui.graphics.Color

// The GShark face plate's fine mesh, as a fine stripe texture pressed into a card.

/** The mesh's pitch, in document pixels. */
private const val MeshPitch = 7

/** Fine stripes at the mesh's pitch. */
private fun meshStripes(dark: Boolean): RemoteBrush {
  val clear = Color.Transparent.rc
  val shade = Color.Black.copy(alpha = if (dark) 0.26f else 0.07f).rc
  // Evenly spaced colours (the one form both the AndroidX and the desktop creation APIs take with a
  // tile mode): clear for the first third, a soft ramp, then the band.
  return RemoteBrush.linearGradient(
    listOf(clear, clear, shade, shade),
    start = RemoteOffset(0f.rf, 0f.rf),
    end = RemoteOffset(0f.rf, MeshPitch.toFloat().rf),
    tileMode = RemoteTileMode.Repeated,
  )
}

/**
 * The face plate's texture over a card ([HaSurfaceMaterial.MeshPlate]): fine stripes at the mesh's
 * pitch, one gradient fill. Not a tiled image: the Compose player can't yet play an image shader
 * (it asks for a shader matrix the document never declares, #1466). The JVM generator can now draw
 * the tile's `ImageBitmap` (`JvmComposeGraphics`, #1464), so that player bug is the only reason.
 */
internal fun meshPlateBrush(dark: Boolean): RemoteBrush = meshStripes(dark)
