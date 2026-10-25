package ee.schimke.terrazzo.dashboard

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import ee.schimke.ha.client.DashboardSummary
import ee.schimke.terrazzo.previews.Screen_DashboardPicker
import ee.schimke.terrazzo.previews.Screen_DashboardView
import java.io.File
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalMaterial3Api::class)
class DashboardStartUiTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun theMenuCanRecoverWhenOnlyOneOtherDashboardIsAvailable() {
    var opened: String? by mutableStateOf(null)
    compose.setContent {
      MaterialTheme {
        Column {
          DashboardSwitcher(
            dashboards = listOf(DashboardSummary("climate", "Climate")),
            currentUrlPath = opened,
            onSwitch = { opened = it },
          )
        }
      }
    }
    compose.onNodeWithContentDescription("Switch dashboard").performClick()
    compose.onNodeWithText("Climate").performClick()
    assertEquals("climate", opened)
    compose.onNodeWithContentDescription("Switch dashboard").assertDoesNotExist()
    compose.onNodeWithText("Climate").assertExists()
  }

  @Test
  fun dashboardViewAndTitleMenuReplaceThePicker() {
    val dashboards = listOf(DashboardSummary(null, "Home"), DashboardSummary("climate", "Climate"))
    var opened by mutableStateOf(preferredDashboardPath(dashboards, null))
    compose.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        MaterialTheme {
          Scaffold(
            topBar = {
              TopAppBar(
                title = { DashboardSwitcher(dashboards, opened, onSwitch = { opened = it }) }
              )
            }
          ) { padding ->
            Column(Modifier.padding(padding)) { Screen_DashboardView() }
          }
        }
      }
    }
    compose.onNodeWithText("Choose a Lovelace dashboard.", substring = true).assertDoesNotExist()
    compose.onNodeWithContentDescription("Switch dashboard").assertExists()
    screenshot("after-dashboard")
    compose.onNodeWithContentDescription("Switch dashboard").performClick()
    compose.onNodeWithText("Climate").assertExists()
    screenshot("switch-menu")
  }

  @Test
  fun capturePreviousPickerForComparison() {
    compose.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        MaterialTheme {
          Scaffold(topBar = { TopAppBar(title = { Text("Pick a dashboard") }) }) { padding ->
            Column(Modifier.padding(padding)) { Screen_DashboardPicker() }
          }
        }
      }
    }
    screenshot("before-picker")
  }

  private fun screenshot(name: String) {
    val directory = File("build/reports/tests/dashboard-start").apply { mkdirs() }
    val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
    require(bitmap.width <= 1800 && bitmap.height <= 1800)
    File(directory, "$name.png").outputStream().use {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
}
