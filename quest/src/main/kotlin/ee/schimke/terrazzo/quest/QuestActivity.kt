package ee.schimke.terrazzo.quest

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.mruk.MRUKLoadDeviceResult
import com.meta.spatial.mruk.SceneModel
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.vr.VRFeature
import ee.schimke.terrazzo.room.RoomContent
import ee.schimke.terrazzo.room.RoomPin
import ee.schimke.terrazzo.room.airPlacement
import ee.schimke.terrazzo.room.nearestPinnedEntity
import ee.schimke.terrazzo.room.pinnedEntity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The room controls on Meta Quest. A Spatial SDK activity in passthrough: the room panel in front
 * of the user (grab it to move it), and a panel on each pinned control where the user pinned it, on
 * a wall or in the air. Walls and the room come from Space Setup through MRUK, once the user grants
 * the scene permission.
 */
class QuestActivity : AppSystemActivity() {
  private val scope = MainScope()
  private val model by lazy {
    QuestRoomModel(getSharedPreferences("quest_room", Context.MODE_PRIVATE))
  }
  private lateinit var mruk: MRUKFeature
  private var anchors: QuestRoomAnchors? = null

  /** The panel entity on each placed pin, by anchor id, and the pin behind each panel entity. */
  private val pinPanels = mutableMapOf<String, Entity>()
  private val panelPins = mutableMapOf<Long, RoomPin>()

  override fun registerFeatures(): List<SpatialFeature> {
    mruk = MRUKFeature(this, systemManager)
    return listOf(VRFeature(this), ComposeFeature(), mruk)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (checkSelfPermission(SCENE_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(arrayOf(SCENE_PERMISSION), SCENE_REQUEST)
    }
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray,
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (
      requestCode == SCENE_REQUEST &&
        grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
    ) {
      loadScene()
    }
  }

  override fun onSceneReady() {
    super.onSceneReady()
    scene.enablePassthrough(true)
    val head = scene.getViewerPose()
    // The room panel a little over arm's length ahead, facing the user.
    val place =
      airPlacement(head.t.toVec3(), head.forward().toVec3(), distance = ROOM_PANEL_DISTANCE)
    Entity.create(
      listOf(
        Panel(R.id.room_panel),
        Transform(panelPose(place.position, place.facing)),
        Grabbable(),
      )
    )
    anchors = QuestRoomAnchors(scene, mruk)
    if (checkSelfPermission(SCENE_PERMISSION) == PackageManager.PERMISSION_GRANTED) loadScene()
    // Which pin the user stands nearest: the room they are in. Every couple of seconds is enough.
    scope.launch {
      while (true) {
        val distances = anchors?.distances().orEmpty()
        model.nearestPin =
          nearestPinnedEntity(
            model.pins.mapNotNull { pin -> distances[pin.anchorId]?.let { pin.entityId to it } }
          )
        delay(NEAREST_PIN_INTERVAL_MS)
      }
    }
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  override fun registerPanels(): List<PanelRegistration> =
    listOf(
      ComposeViewPanelRegistration(
        R.id.room_panel,
        { _, context -> composeView(context) { RoomPanel() } },
        {
          UIPanelSettings(
            QuadShapeOptions(ROOM_PANEL_WIDTH, ROOM_PANEL_HEIGHT),
            DpPerMeterDisplayOptions(DP_PER_METRE),
          )
        },
      ),
      ComposeViewPanelRegistration(
        R.id.pinned_panel,
        { entity, context -> composeView(context) { PinnedPanel(panelPins[entity.id]) } },
        {
          UIPanelSettings(
            QuadShapeOptions(PIN_PANEL_WIDTH, PIN_PANEL_HEIGHT),
            DpPerMeterDisplayOptions(DP_PER_METRE),
          )
        },
      ),
    )

  private fun composeView(context: Context, content: @Composable () -> Unit) =
    ComposeView(context).apply { setContent { QuestTheme(content) } }

  @Composable
  private fun RoomPanel() {
    val anchors = anchors
    QuestRoomPanel(
      content = roomContent(onPin = anchors?.let { { entityId -> pin(entityId) } }),
      lostPins = model.pins.filter { anchors != null && it.anchorId in anchors.lost },
      onRemoveLost = ::unpin,
    )
  }

  @Composable
  private fun PinnedPanel(pin: RoomPin?) {
    pin ?: return
    QuestPinnedPanel(pinnedEntity(pin, model.snapshot), model.snapshot, model::toggle)
  }

  private fun roomContent(onPin: ((String) -> Unit)?) =
    RoomContent(
      state = model.state,
      snapshot = model.snapshot,
      tracker = model.tracker,
      onPick = model::pick,
      onFollow = model::follow,
      entityCard = { entity, modifier ->
        QuestEntityCard(entity, model.snapshot, model::toggle, onPin, modifier)
      },
    )

  private fun loadScene() {
    mruk.loadSceneFromDevice(true, true, SceneModel.V2_FALLBACK_V1).thenAccept { result ->
      if (result != MRUKLoadDeviceResult.SUCCESS) return@thenAccept
      runOnUiThread {
        anchors?.load(model.pins)
        syncPinPanels()
      }
    }
  }

  private fun pin(entityId: String) {
    val anchors = anchors ?: return
    scope.launch {
      val place = anchors.pinWhereLooking() ?: return@launch
      model.pinned(place.anchorId, entityId)
      syncPinPanels()
    }
  }

  private fun unpin(pin: RoomPin) {
    anchors?.unpin(pin.anchorId)
    model.unpinned(pin)
    syncPinPanels()
  }

  /** A panel on every placed pin, and none on a pin that is gone. */
  private fun syncPinPanels() {
    val placed = anchors?.poses.orEmpty()
    for ((anchorId, entity) in pinPanels.toMap()) {
      if (anchorId !in placed || model.pins.none { it.anchorId == anchorId }) {
        panelPins.remove(entity.id)
        entity.destroy()
        pinPanels.remove(anchorId)
      }
    }
    for (pin in model.pins) {
      val pose = placed[pin.anchorId] ?: continue
      if (pin.anchorId in pinPanels) continue
      val entity = Entity.create(listOf(Panel(R.id.pinned_panel), Transform(pose)))
      panelPins[entity.id] = pin
      pinPanels[pin.anchorId] = entity
    }
  }

  private companion object {
    const val SCENE_PERMISSION = "com.oculus.permission.USE_SCENE"
    const val SCENE_REQUEST = 1
    const val NEAREST_PIN_INTERVAL_MS = 2_000L
    const val ROOM_PANEL_DISTANCE = 1.2f
    const val ROOM_PANEL_WIDTH = 1.0f
    const val ROOM_PANEL_HEIGHT = 0.7f
    const val PIN_PANEL_WIDTH = 0.36f
    const val PIN_PANEL_HEIGHT = 0.2f
    const val DP_PER_METRE = 1000f
  }
}
