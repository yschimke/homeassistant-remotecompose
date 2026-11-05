@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.shaders.RemoteBrush
import androidx.compose.remote.creation.compose.shaders.linearGradient
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode

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
    tileMode = TileMode.Repeated,
  )
}

/**
 * The face plate's texture over a card ([HaSurfaceMaterial.MeshPlate]): fine stripes at the mesh's
 * pitch, one gradient fill. Not a tiled image: the Compose player can't yet play an image shader
 * (it asks for a shader matrix the document never declares), and a design baked on the JVM must
 * draw what Android captures, which only a plain gradient does on every host.
 */
internal fun meshPlateBrush(dark: Boolean): RemoteBrush = meshStripes(dark)
