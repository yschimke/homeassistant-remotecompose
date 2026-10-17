package ee.schimke.ha.rc

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchyAndroid
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A launcher-widget preview plays its card on the AndroidX View player, the way the home screen
 * does. The accessibility checker sees that View, not the card's semantics inside it, so the player
 * itself carries the card's name: otherwise every widget cell reads as an unlabelled element.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherWidgetPlayerA11yTest {

  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val snapshot =
    HaSnapshot(
      states =
        mapOf(
          "sensor.outside" to
            EntityState(
              "sensor.outside",
              "8.2",
              JsonObject(
                mapOf(
                  "friendly_name" to JsonPrimitive("Outside"),
                  "unit_of_measurement" to JsonPrimitive("°C"),
                )
              ),
            )
        )
    )

  private val card =
    CardConfig(
      "sensor",
      buildJsonObject {
        put("type", "sensor")
        put("entity", "sensor.outside")
      },
    )

  private fun players(view: View): List<RemoteComposePlayer> =
    when (view) {
      is RemoteComposePlayer -> listOf(view)
      is ViewGroup -> (0 until view.childCount).flatMap { players(view.getChildAt(it)) }
      else -> emptyList()
    }

  @Test
  fun launcherWidgetPlayerIsLabelled() {
    val registry = defaultRegistry()
    val theme = haThemeFor(ThemeStyle.TerrazzoHome, false)
    compose.setContent {
      Box(Modifier.size(180.dp, 110.dp)) {
        CachedCardPreview(
          cacheKey = "launcher-a11y",
          profile = androidXExperimentalWrap,
          modifier = Modifier.fillMaxSize(),
          card = card,
          snapshot = snapshot,
          playback = CardPlayback.LauncherWidget,
        ) {
          ProvideCardRegistry(registry) {
            ProvideHaTheme(theme) { RenderChild(card, snapshot, RemoteModifier.fillMaxWidth()) }
          }
        }
      }
    }
    compose.waitForIdle()

    val root = compose.activity.window.decorView
    val player = assertNotNull(players(root).singleOrNull(), "one launcher-widget player")

    val hierarchy = AccessibilityHierarchyAndroid.newBuilder(root).build()
    val errors =
      AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset(
          AccessibilityCheckPreset.LATEST
        )
        .flatMap { it.runCheckOnHierarchy(hierarchy) }
        .filter { it.type == AccessibilityCheckResultType.ERROR }
        .map { "${it.sourceCheckClass.simpleName}: ${it.element?.className}" }
    assertEquals(emptyList(), errors, "accessibility errors")
    assertEquals("Outside", player.contentDescription?.toString())
  }
}
