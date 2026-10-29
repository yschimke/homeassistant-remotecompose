package ee.schimke.terrazzo.quest

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.ha.room.DashboardImportance
import ee.schimke.terrazzo.room.RoomContent
import ee.schimke.terrazzo.room.RoomPin
import ee.schimke.terrazzo.room.RoomScenePreview
import ee.schimke.terrazzo.room.ScenePanel
import ee.schimke.terrazzo.room.Vec3
import ee.schimke.terrazzo.room.WallPlane
import ee.schimke.terrazzo.room.airPlacement
import ee.schimke.terrazzo.room.pinPlacement
import ee.schimke.terrazzo.room.pinnedEntity
import ee.schimke.terrazzo.room.roomState
import ee.schimke.terrazzo.room.rotateY

/*
 * What the Quest room looks like, without a headset: each panel at its real size (a metre is 1000 dp
 * on Quest, rendered at 160 dpi so 1 dp is 1 px), and the whole room as a scene from behind the
 * user ([RoomScenePreview]) — the room panel ahead, controls pinned on the walls where `pinPlacement`
 * puts them.
 */

@Preview(name = "Quest: room panel", device = "spec:width=1000dp,height=700dp,dpi=160")
@Composable
fun QuestRoomPanelPreview() {
  QuestTheme { QuestRoomPanel(previewContent()) }
}

@Preview(name = "Quest: pinned heater", device = "spec:width=360dp,height=200dp,dpi=160")
@Composable
fun QuestPinnedPanelPreview() {
  QuestTheme {
    QuestPinnedPanel(
      pinnedEntity(RoomPin("preview", PREVIEW_BASE_URL, HEATER), PreviewSnapshot),
      PreviewSnapshot,
      onToggle = {},
      onUnpin = {},
    )
  }
}

@Preview(name = "Quest: room scene", device = "spec:width=1600dp,height=900dp,dpi=160")
@Composable
fun QuestRoomScenePreview() {
  val eye = Vec3(0f, 1.6f, 0f)
  val ahead = Vec3(0f, 0f, -1f)
  val room = airPlacement(eye, ahead, distance = 1.2f)
  // Looking a little left at the side wall to pin the heater, ahead at the front wall for the TV.
  val heater = pinPlacement(eye, rotateY(ahead, -1.0f), PreviewWalls)
  val tv = pinPlacement(eye, rotateY(ahead, 0.35f).let { Vec3(it.x, 0.05f, it.z) }, PreviewWalls)
  val panels =
    listOf(
      ScenePanel("room", room.position, room.facing, 1.0f, 0.7f),
      ScenePanel(HEATER, heater.position, heater.facing, 0.36f, 0.2f),
      ScenePanel(TV, tv.position, tv.facing, 0.36f, 0.2f),
    )
  QuestTheme {
    RoomScenePreview(eye, ahead, PreviewWalls, panels, Modifier.fillMaxSize()) { panel ->
      when (panel.id) {
        "room" -> QuestRoomPanel(previewContent())
        else ->
          QuestPinnedPanel(
            pinnedEntity(RoomPin(panel.id, PREVIEW_BASE_URL, panel.id), PreviewSnapshot),
            PreviewSnapshot,
            onToggle = {},
            onUnpin = {},
          )
      }
    }
  }
}

private fun previewContent(): RoomContent =
  RoomContent(
    state =
      roomState(
        PreviewSnapshot,
        DashboardImportance.Empty,
        picked = null,
        tracker = null,
        nearestPin = HEATER,
      ),
    snapshot = PreviewSnapshot,
    tracker = null,
    onPick = {},
    onFollow = {},
    entityCard = { entity, modifier ->
      QuestEntityCard(entity, PreviewSnapshot, onToggle = {}, onPin = {}, modifier = modifier)
    },
  )

private val PreviewSnapshot = QuestDemoHouse.snapshot
private const val PREVIEW_BASE_URL = QuestDemoHouse.BASE_URL
private const val HEATER = QuestDemoHouse.HEATER
private const val TV = QuestDemoHouse.TV

/** A 4 m × 5 m living room around the user: a wall ahead and one to each side. */
private val PreviewWalls =
  listOf(
    WallPlane(
      Vec3(0f, 1.25f, -3f),
      Vec3(0f, 0f, 1f),
      Vec3(1f, 0f, 0f),
      Vec3(0f, 1f, 0f),
      4f,
      2.5f,
      "front",
    ),
    WallPlane(
      Vec3(-2f, 1.25f, -0.5f),
      Vec3(1f, 0f, 0f),
      Vec3(0f, 0f, -1f),
      Vec3(0f, 1f, 0f),
      5f,
      2.5f,
      "left",
    ),
    WallPlane(
      Vec3(2f, 1.25f, -0.5f),
      Vec3(-1f, 0f, 0f),
      Vec3(0f, 0f, 1f),
      Vec3(0f, 1f, 0f),
      5f,
      2.5f,
      "right",
    ),
  )
