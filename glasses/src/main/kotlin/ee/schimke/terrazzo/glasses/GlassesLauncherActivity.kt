package ee.schimke.terrazzo.glasses

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.xr.projected.ProjectedContext
import androidx.xr.projected.experimental.ExperimentalProjectedApi
import ee.schimke.terrazzo.room.FlatRoomControls
import ee.schimke.terrazzo.room.RoomContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf

/**
 * The phone side: the shared flat room screen on [GlassesRoom], with "Show on glasses" to start
 * [GlassesRoomActivity] on the glasses' display. Picking a room or toggling a control here shows on
 * the glasses straight away.
 */
class GlassesLauncherActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme(if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        FlatRoomControls(
          content = phoneRoomContent(),
          onBack = ::finish,
          actions = { ShowOnGlassesButton() },
        )
      }
    }
  }
}

/** [GlassesRoom] for the shared room screen, each control a [PhoneControlCard]. */
fun phoneRoomContent(): RoomContent =
  RoomContent(
    state = GlassesRoom.state,
    snapshot = GlassesRoom.snapshot,
    tracker = GlassesRoom.tracker,
    onPick = GlassesRoom::pick,
    onFollow = GlassesRoom::follow,
    entityCard = { entity, modifier ->
      PhoneControlCard(entity, GlassesRoom.snapshot, GlassesRoom::toggle, modifier)
    },
  )

/** "Show on glasses", enabled while glasses are connected (where the phone can tell). */
@Composable
private fun ShowOnGlassesButton() {
  val context = LocalContext.current
  val connected by
    remember(context) { glassesConnected(context) }.collectAsState(initial = canAskGlasses())
  TextButton(onClick = { showOnGlasses(context) }, enabled = connected) { Text("Show on glasses") }
}

@OptIn(ExperimentalProjectedApi::class)
private fun glassesConnected(context: Context) =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
    ProjectedContext.isProjectedDeviceConnected(context, Dispatchers.Main)
  } else {
    // No way to ask before Android 16: offer it, and say so if there's nothing to show it on.
    flowOf(canAskGlasses())
  }

/** Projected activities need Android 15. */
private fun canAskGlasses() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

@OptIn(ExperimentalProjectedApi::class)
private fun showOnGlasses(context: Context) {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
  val options =
    try {
      ProjectedContext.createProjectedActivityOptions(context)
    } catch (e: IllegalStateException) {
      Toast.makeText(context, "No glasses connected", Toast.LENGTH_SHORT).show()
      return
    }
  context.startActivity(Intent(context, GlassesRoomActivity::class.java), options.toBundle())
}
