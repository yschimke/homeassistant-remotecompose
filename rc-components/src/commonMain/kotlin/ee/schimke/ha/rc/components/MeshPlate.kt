@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.shaders.RemoteBrush
import androidx.compose.remote.creation.compose.shaders.image
import androidx.compose.remote.creation.compose.shaders.linearGradient
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode

// The GShark face plate's perforated mesh, as one small tile repeated over a card. The tile is
// transparent but for its hole, so it presses into a card of any colour, and it is a single image
// fill: a card pays one draw for the whole plate, not one per hole.
//
// Drawing the tile needs a Compose graphics backend. A host that bakes cards without one (the
// design-library generator on a plain JVM) gets fine stripes of the same pitch instead: one
// gradient fill, no bitmap.

/** The mesh's pitch, in document pixels. */
private const val MeshPitch = 7

/**
 * One hole of the mesh: a dark pit with a faint lit lower lip, in a [MeshPitch] tile. A light plate
 * takes shallower holes, so the mesh stays behind the text on it. Null without a graphics backend.
 */
private fun meshTile(dark: Boolean): ImageBitmap? = runCatching {
  val tile = ImageBitmap(MeshPitch, MeshPitch)
  val canvas = Canvas(tile)
  val centre = MeshPitch / 2f
  canvas.drawCircle(
    Offset(centre + 0.5f, centre + 0.6f),
    2.2f,
    Paint().apply { color = Color.White.copy(alpha = if (dark) 0.10f else 0.18f) },
  )
  canvas.drawCircle(
    Offset(centre, centre),
    2.1f,
    Paint().apply { color = Color.Black.copy(alpha = if (dark) 0.6f else 0.16f) },
  )
  tile
}
  .getOrNull()

/** The mesh tiles, drawn once per process; documents share them by name. */
private val DarkMeshTile: ImageBitmap? by lazy { meshTile(dark = true) }
private val LightMeshTile: ImageBitmap? by lazy { meshTile(dark = false) }

/** Fine stripes at the mesh's pitch, for a host that can't draw the tile. */
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
 * The face plate's mesh, tiled over a card ([HaSurfaceMaterial.MeshPlate]). Not remembered: the
 * card chrome is read-only, and the named bitmap is cached in the document by its name.
 */
internal fun meshPlateBrush(dark: Boolean): RemoteBrush {
  val tile = (if (dark) DarkMeshTile else LightMeshTile) ?: return meshStripes(dark)
  return RemoteBrush.image(
    namedRemoteBitmap(
      if (dark) "ha.mesh_plate_dark" else "ha.mesh_plate_light",
      RemoteState.Domain.User,
      tile,
    ),
    TileMode.Repeated,
    TileMode.Repeated,
  )
}
