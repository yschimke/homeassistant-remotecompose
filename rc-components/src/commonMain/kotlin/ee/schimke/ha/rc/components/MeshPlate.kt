@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.shaders.RemoteBrush
import androidx.compose.remote.creation.compose.shaders.image
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode

// The GShark face plate's perforated mesh, as one small tile repeated over a card. The tile is
// transparent but for its hole, so it presses into a card of any colour, light or dark, and it is
// a single image fill: a card pays one draw for the whole plate, not one per hole.

/** The mesh's pitch, in document pixels. */
private const val MeshPitch = 7

/**
 * One hole of the mesh: a dark pit with a faint lit lower lip, in a [MeshPitch] tile. A light plate
 * takes shallower holes, so the mesh stays behind the text on it.
 */
private fun meshTile(dark: Boolean): ImageBitmap {
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
  return tile
}

/** The mesh tile, drawn once per process; documents share it by name. */
private val DarkMeshTile: ImageBitmap by lazy { meshTile(dark = true) }
private val LightMeshTile: ImageBitmap by lazy { meshTile(dark = false) }

/**
 * The face plate's mesh, tiled over a card ([HaSurfaceMaterial.MeshPlate]). Not remembered: the
 * card chrome is read-only, and the named bitmap is cached in the document by its name.
 */
internal fun meshPlateBrush(dark: Boolean): RemoteBrush =
  RemoteBrush.image(
    namedRemoteBitmap(
      if (dark) "ha.mesh_plate_dark" else "ha.mesh_plate_light",
      RemoteState.Domain.User,
      if (dark) DarkMeshTile else LightMeshTile,
    ),
    TileMode.Repeated,
    TileMode.Repeated,
  )
