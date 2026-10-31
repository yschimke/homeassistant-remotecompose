package ee.schimke.terrazzo.glasses

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * The room on the glasses' display. [GlassesLauncherActivity] starts it there; it reads and changes
 * the same [GlassesRoom] as the phone.
 */
class GlassesRoomActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      GlassesRoomScreen(
        state = GlassesRoom.state,
        snapshot = GlassesRoom.snapshot,
        onToggle = GlassesRoom::toggle,
        onPick = GlassesRoom::pick,
      )
    }
  }
}
