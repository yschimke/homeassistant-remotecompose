@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.shaders.RemoteBrush
import androidx.compose.remote.creation.compose.shaders.image
import androidx.compose.remote.creation.compose.shaders.linearGradient
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// The GShark face plate's perforated mesh, as one small tile repeated over a card. The tile is
// transparent but for its hole, so it presses into a card of any colour, and it is a single image
// fill: a card pays one draw for the whole plate, not one per hole.
//
// The tile is decoded from PNG bytes, as every host that bakes cards can (the design-library
// generator on the JVM included), so a published design draws the plate it was captured with. A
// host with no image decoder at all gets fine stripes of the same pitch instead.

/** The mesh's pitch, in document pixels. */
private const val MeshPitch = 7

/**
 * One hole of the mesh in a [MeshPitch] tile: a dark pit with a faint lit lower lip. A light plate
 * takes shallower holes, so the mesh stays behind the text on it. Shipped as PNG bytes and decoded
 * like the bundled wordmark, so the JVM library bake and Android's own capture draw the same tile;
 * null only where a host can't decode an image at all.
 */
@OptIn(ExperimentalEncodingApi::class)
private val DarkMeshTile: ImageBitmap? by lazy { decodeBundledImage(Base64.decode(DarkMeshPng)) }

@OptIn(ExperimentalEncodingApi::class)
private val LightMeshTile: ImageBitmap? by lazy { decodeBundledImage(Base64.decode(LightMeshPng)) }

private const val DarkMeshPng =
  "iVBORw0KGgoAAAANSUhEUgAAAAcAAAAHCAYAAADEUlfTAAAAj0lEQVR42m2OIQ7CABAE5+5yvbRIRB/QkKoKgsCRIAn9DYpvIngAJChMXRPSHoKAYtUkI2bh/wTAvgBsVPVoZjrP8wMQ+4qIOFdVtXL3fV3X92EYbgrg7uuIUFV9ZubL3bdFUaAAZVleAJumaQlY27ZXkU9NRISu63ZN05z6vj9k5gIQ+d0TISIAGMdRgHwDPWAfbp7lARYAAAAASUVORK5CYII="
private const val LightMeshPng =
  "iVBORw0KGgoAAAANSUhEUgAAAAcAAAAHCAYAAADEUlfTAAAAiklEQVR42m2OMQ7CMBAE946IAqdBQAmF5YKT3IQmjf2FPMMvy394AlT5gKWgiGKpKIjYbkZTLPB/shat9/6SUtqvi3YYhpuZHUhKCOFeSnkoAPR9f4wxnpxzbxGhqnqS2wYAuq6bVVVqrQ0AmtkLwEYAiIhwHMfrsiznEMKcc34CmH4ekdyRdF/+ABuuJuhqikX8AAAAAElFTkSuQmCC"

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
