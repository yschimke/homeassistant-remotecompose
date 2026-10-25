package ee.schimke.terrazzo

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.design.CardDesignOverrides
import ee.schimke.ha.client.design.DesignOption
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.terrazzo.core.prefs.CardStylePref
import ee.schimke.terrazzo.dashboard.CardStylePicker
import ee.schimke.terrazzo.dashboard.SkinState
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
class DesignPickerUiTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun settingsStartsWithInstalledAndSelectedDesignsAndCanBrowseMore() {
    val installed = mutableStateOf(setOf(CardStylePref.Braun))
    compose.setContent {
      MaterialTheme {
        Surface {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            CardStyleSection(
              selected =
                CardStylePref.Lcd, // Preserve a legacy selection even without its install record.
              onSelect = {},
              state = {
                when (it) {
                  CardStylePref.Classic,
                  CardStylePref.HomeAssistant -> CardStyleState.BuiltIn
                  in installed.value -> CardStyleState.Installed
                  else -> CardStyleState.NotInstalled
                }
              },
            )
          }
        }
      }
    }
    compose.onNodeWithContentDescription("Classic.", substring = true).assertExists()
    compose.onNodeWithContentDescription("Home Assistant.", substring = true).assertExists()
    compose.onNodeWithContentDescription("Braun.", substring = true).assertExists()
    compose.onNodeWithContentDescription("LCD.", substring = true).assertExists()
    compose.onNodeWithContentDescription("Install Nixie").assertDoesNotExist()
    screenshot("installed-default")
    compose.onNodeWithText("Browse all designs").performScrollTo().performClick()
    compose.onNodeWithContentDescription("Install Nixie").assertExists()
    compose.onNodeWithContentDescription("Install Nixie").performScrollTo()
    screenshot("catalog-browse")
    compose.onNodeWithText("Show installed designs").performScrollTo().performClick()
    compose.runOnIdle { installed.value = emptySet() }
    compose.onNodeWithContentDescription("Braun.", substring = true).assertDoesNotExist()
    compose.onNodeWithText("Browse all designs").assertExists()
  }

  @Test
  fun settingsDefaultEvidence() {
    compose.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        MaterialTheme {
          Surface {
            Column(Modifier.verticalScroll(rememberScrollState())) {
              CardStyleSection(
                selected = CardStylePref.Braun,
                onSelect = {},
                state = {
                  when (it) {
                    CardStylePref.Classic,
                    CardStylePref.HomeAssistant -> CardStyleState.BuiltIn
                    CardStylePref.Braun -> CardStyleState.InUse
                    else -> CardStyleState.NotInstalled
                  }
                },
              )
            }
          }
        }
      }
    }
    screenshot("settings-default")
  }

  @Test
  fun emptyInstalledSkinsExplainHowToInstallAndUpdateLive() {
    val installed = mutableStateOf(false)
    var requested: String? = null
    compose.setContent {
      MaterialTheme {
        Surface {
          CardStylePicker(
            card = CardConfig("tile"),
            overrides = CardDesignOverrides(),
            dashboardStyle = HaUberTheme.Classic,
            onPick = { _, _ -> },
            onDone = {},
            modifier = Modifier.height(640.dp),
            designs = listOf(DesignOption("user:test", "Test skin", "Someone", null)),
            skinState = { if (installed.value) SkinState.Installed else SkinState.NotInstalled },
            styleInstalled = { it == "classic" },
            onInstall = { requested = it },
            preview = { Text("Card preview") },
          )
        }
      }
    }
    compose
      .onNodeWithText("No skins installed for this card. Browse all skins to install one.")
      .assertExists()
    compose.onNodeWithText("Test skin").assertDoesNotExist()
    screenshot("empty-installed-skins")
    compose.onNodeWithText("Browse all skins").performScrollTo().performClick()
    compose.onNodeWithText("Install").performScrollTo().performClick()
    assertEquals("user:test", requested)
    compose.runOnIdle { installed.value = true }
    compose.onNodeWithText("Show installed skins").performScrollTo().performClick()
    compose.onNodeWithText("Test skin").assertExists()
    compose.onNodeWithText("Install").assertDoesNotExist()
    screenshot("installed-skin")
  }

  private fun screenshot(name: String) {
    val directory = File("build/reports/tests/installed-design-picker").apply { mkdirs() }
    compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
      require(bitmap.width <= 1800 && bitmap.height <= 1800)
      File(directory, "$name.png").outputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
      }
    }
  }
}
