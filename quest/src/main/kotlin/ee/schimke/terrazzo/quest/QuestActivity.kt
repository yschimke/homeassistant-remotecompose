package ee.schimke.terrazzo.quest

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    QuestRoomModel(this, getSharedPreferences("quest_room", Context.MODE_PRIVATE))
  }
  private lateinit var mruk: MRUKFeature

  // Compose state: the panels may compose before the scene is ready or a pin's entity is mapped,
  // and
  // must pick them up when they arrive.
  private var anchors by mutableStateOf<QuestRoomAnchors?>(null)

  /** Space Setup's rooms are loaded: a pin has a wall or room to be kept against. */
  private var sceneLoaded by mutableStateOf(false)

  /** The panel entity on each placed pin, by anchor id, and the pin behind each panel entity. */
  private val pinPanels = mutableMapOf<String, Entity>()
  private val panelPins = mutableStateMapOf<Long, RoomPin>()

  override fun registerFeatures(): List<SpatialFeature> {
    val base = listOf(VRFeature(this), ComposeFeature())
    // Room mapping and passthrough need the Android-14 Horizon OS path. Older supported Quest
    // firmware still gets a movable room-control panel, just without Space Setup anchors/pins.
    if (!supportsRoomMapping) return base
    mruk = MRUKFeature(this, systemManager)
    return base + mruk
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    model.restore()
    if (
      supportsRoomMapping &&
        checkSelfPermission(SCENE_PERMISSION) != PackageManager.PERMISSION_GRANTED
    ) {
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
      supportsRoomMapping &&
        requestCode == SCENE_REQUEST &&
        grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
    ) {
      loadScene()
    }
  }

  @Deprecated("AppAuth delivers the browser result to the initiating activity.")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == SIGN_IN_REQUEST) model.authorizationResult(data)
  }

  override fun onSceneReady() {
    super.onSceneReady()
    if (supportsRoomMapping) scene.enablePassthrough(true)
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
    if (supportsRoomMapping) {
      anchors = QuestRoomAnchors(scene, mruk)
      if (checkSelfPermission(SCENE_PERMISSION) == PackageManager.PERMISSION_GRANTED) loadScene()
    }
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
    model.close()
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
    when (val connection = model.connection) {
      QuestConnection.Connected ->
        QuestRoomPanel(
          content =
            roomContent(
              onPin = anchors?.takeIf { sceneLoaded }?.let { { entityId -> pin(entityId) } }
            ),
          lostPins = model.pins.filter { anchors != null && it.anchorId in anchors.lost },
          onRemoveLost = ::unpin,
          onSignOut = model::signOut,
          controlsLocked = model.controlsLocked,
          onControlsLocked = model::updateControlsLocked,
          pinningHint =
            when {
              !supportsRoomMapping ->
                "This room panel can still be moved. Pinning individual controls requires Horizon OS Android 14 or newer."
              !sceneLoaded ->
                "This room panel can still be moved. Allow Space Setup access to pin individual controls around your room."
              else -> null
            },
        )
      else ->
        QuestSignInPanel(
          connection = connection,
          onSignIn = { url ->
            model.authorizationIntent(url)?.let { startActivityForResult(it, SIGN_IN_REQUEST) }
          },
        )
    }
  }

  @Composable
  private fun PinnedPanel(pin: RoomPin?) {
    pin ?: return
    QuestPinnedPanel(
      pinnedEntity(pin, model.snapshot),
      model.snapshot,
      model::toggle,
      onUnpin = { unpin(pin) },
      controlsLocked = model.controlsLocked,
    )
  }

  private fun roomContent(onPin: ((String) -> Unit)?) =
    RoomContent(
      state = model.state,
      snapshot = model.snapshot,
      tracker = model.tracker,
      onPick = model::pick,
      onFollow = model::follow,
      entityCard = { entity, modifier ->
        QuestEntityCard(
          entity = entity,
          snapshot = model.snapshot,
          onToggle = model::toggle,
          onPin = onPin,
          pinned = model.pins.any { it.entityId == entity.entityId },
          controlsLocked = model.controlsLocked,
          modifier = modifier,
        )
      },
    )

  private fun loadScene() {
    if (!supportsRoomMapping) return
    mruk.loadSceneFromDevice(true, true, SceneModel.V2_FALLBACK_V1).thenAccept { result ->
      if (result != MRUKLoadDeviceResult.SUCCESS) return@thenAccept
      runOnUiThread {
        sceneLoaded = true
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
    /** Android 14 is the first Horizon OS version with this room-mapping/passthrough route. */
    val supportsRoomMapping: Boolean
      get() = supportsQuestRoomMapping()

    const val SCENE_PERMISSION = "com.oculus.permission.USE_SCENE"
    const val SCENE_REQUEST = 1
    const val SIGN_IN_REQUEST = 2
    const val NEAREST_PIN_INTERVAL_MS = 2_000L
    const val ROOM_PANEL_DISTANCE = 1.2f
    const val ROOM_PANEL_WIDTH = 1.0f
    const val ROOM_PANEL_HEIGHT = 0.7f
    const val PIN_PANEL_WIDTH = 0.36f
    const val PIN_PANEL_HEIGHT = 0.2f
    const val DP_PER_METRE = 1000f
  }
}
