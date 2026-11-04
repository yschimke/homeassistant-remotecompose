@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.remote.player.view.RemoteComposePlayer
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A launcher capture plays in the AndroidX player without the player disabling it (its red warning
 * triangle). A document captured without a size leaves its nested size gates to the player, whose
 * partial layout pass throws a ConcurrentModificationException on the second measure; the
 * launcher's capture decides them at capture time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherWidgetPlaybackTest {
  private val snapshot =
    HaSnapshot(
      states =
        mapOf(
          "alarm_control_panel.house" to
            EntityState(
              "alarm_control_panel.house",
              "disarmed",
              JsonObject(mapOf("friendly_name" to JsonPrimitive("House"))),
            ),
          "light.kitchen" to
            EntityState(
              "light.kitchen",
              "on",
              JsonObject(mapOf("friendly_name" to JsonPrimitive("Kitchen"))),
            ),
        )
    )

  @Test
  fun alarmPanelStaysEnabled() {
    val card =
      CardConfig(
        "alarm-panel",
        buildJsonObject {
          put("type", "alarm-panel")
          put("entity", "alarm_control_panel.house")
        },
      )
    for ((cw, ch) in listOf(3 to 4, 4 to 5, 5 to 6)) assertPlays(card, cw, ch)
  }

  @Test
  fun entitiesStaysEnabled() {
    val card =
      CardConfig(
        "entities",
        Json.parseToJsonElement(
            """{"type":"entities","entities":["light.kitchen","alarm_control_panel.house"]}"""
          )
          .jsonObject,
      )
    for ((cw, ch) in listOf(4 to 3, 5 to 4)) assertPlays(card, cw, ch)
  }

  private fun assertPlays(card: CardConfig, cells: Int, rows: Int) {
    val context = RuntimeEnvironment.getApplication()
    val density = 480
    val w = cells * 72 * density / 160
    val h = rows * 84 * density / 160
    val bytes =
      captureLauncherWidgetDocument(
          context = context,
          card = card,
          snapshot = snapshot,
          registry = defaultRegistry(),
          widthPx = w,
          heightPx = h,
          densityDpi = density,
          captureProfile = androidXExperimentalWrap,
          dark = true,
          fetchImage = { null },
        )
        .bytes
    val player = RemoteComposePlayer(context)
    player.setDocument(RemoteDocument(bytes))
    val view =
      (0 until player.childCount)
        .map { player.getChildAt(it) }
        .first { it.javaClass.simpleName == "RemoteComposeView" }
    val disabled = view.javaClass.getDeclaredField("mDisable").apply { isAccessible = true }
    // The second measure is the partial layout pass that used to throw.
    repeat(2) {
      player.measure(
        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
      )
      player.layout(0, 0, w, h)
      player.draw(Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)))
      assertFalse(disabled.getBoolean(view), "${card.type} ${cells}x$rows: player disabled")
    }
  }
}
