package ee.schimke.terrazzo.glasses

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.terrazzo.room.DemoHouse
import ee.schimke.terrazzo.room.FlatRoomControls
import ee.schimke.terrazzo.room.RoomContent
import ee.schimke.terrazzo.room.RoomSceneColors
import ee.schimke.terrazzo.room.RoomScenePreview
import ee.schimke.terrazzo.room.Vec3
import ee.schimke.terrazzo.room.WallPlane
import ee.schimke.terrazzo.room.roomState

/*
 * What the glasses show, without glasses: the screen as drawn (black is see-through on their
 * additive display), the same screen through the lens over a room, and the phone screen that starts
 * it. All on the shared demo house, so they match what the apps show.
 *
 * The glasses' display size here is an assumption (a 480 dp square at 160 dpi); the layout only
 * relies on Glimmer's own list and spacing, so a different size reflows rather than breaks.
 */

private val Snapshot = DemoHouse.snapshot

private fun state(picked: String?) =
  roomState(Snapshot, DemoHouse.importance, picked = picked, tracker = null)

@Preview(name = "Glasses: living room", device = "spec:width=480dp,height=480dp,dpi=160")
@Composable
fun GlassesRoomPreview() {
  GlassesRoomScreen(state(DemoHouse.livingRoom.id), Snapshot, onToggle = {}, onPick = {})
}

@Preview(name = "Glasses: kitchen", device = "spec:width=480dp,height=480dp,dpi=160")
@Composable
fun GlassesKitchenPreview() {
  GlassesRoomScreen(state(DemoHouse.kitchen.id), Snapshot, onToggle = {}, onPick = {})
}

/**
 * Through the lens: the living room behind, the glasses' screen added on top of it the way an
 * additive display adds light ([BlendMode.Screen]: black adds nothing, bright rows show over the
 * room).
 */
@Preview(name = "Glasses: through the lens", device = "spec:width=960dp,height=600dp,dpi=160")
@Composable
fun GlassesThroughTheLensPreview() {
  Box(Modifier.fillMaxSize()) {
    RoomScenePreview(
      eye = Vec3(0f, 1.6f, 0f),
      ahead = Vec3(0f, 0f, -1f),
      walls = LensWalls,
      panels = emptyList(),
      modifier = Modifier.fillMaxSize(),
      // Seen from the wearer's eyes: no marker for where they stand.
      colors = RoomSceneColors(user = Color.Transparent),
    ) {}
    Box(
      Modifier.align(Alignment.Center).size(480.dp).graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
        blendMode = BlendMode.Screen
      }
    ) {
      GlassesRoomScreen(state(DemoHouse.livingRoom.id), Snapshot, onToggle = {}, onPick = {})
    }
  }
}

@Preview(name = "Glasses: phone", device = "spec:width=411dp,height=891dp,dpi=320")
@Composable
fun GlassesPhonePreview() {
  MaterialTheme(darkColorScheme()) {
    FlatRoomControls(
      content =
        RoomContent(
          state = state(null),
          snapshot = Snapshot,
          tracker = null,
          onPick = {},
          onFollow = {},
          entityCard = { entity, modifier ->
            PhoneControlCard(entity, Snapshot, onToggle = {}, modifier = modifier)
          },
        ),
      onBack = {},
      actions = {},
    )
  }
}

/** A living room wall ahead and to each side, as in the Quest scene preview. */
private val LensWalls =
  listOf(
    WallPlane(Vec3(0f, 1.25f, -3f), Vec3(0f, 0f, 1f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), 4f, 2.5f),
    WallPlane(
      Vec3(-2f, 1.25f, -0.5f),
      Vec3(1f, 0f, 0f),
      Vec3(0f, 0f, -1f),
      Vec3(0f, 1f, 0f),
      5f,
      2.5f,
    ),
    WallPlane(
      Vec3(2f, 1.25f, -0.5f),
      Vec3(-1f, 0f, 0f),
      Vec3(0f, 0f, 1f),
      Vec3(0f, 1f, 0f),
      5f,
      2.5f,
    ),
  )
