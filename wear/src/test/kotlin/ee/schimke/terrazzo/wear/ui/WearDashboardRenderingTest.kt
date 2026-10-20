package ee.schimke.terrazzo.wear.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w240dp-h240dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearDashboardRenderingTest {
  @get:Rule val compose = createComposeRule()

  private val grid =
    CardSummary(
      type = "grid",
      title = "grid", // Exactly what the phone sends for an untitled container.
      rawJson =
        """{"type":"grid","columns":1,"square":false,"cards":[{"type":"gauge","entity":"sensor.battery","name":"Battery","min":0,"max":100}]}""",
    )

  private fun values(state: String) =
    mapOf(
      "sensor.battery" to
        EntityValue(state = state, friendlyName = "Battery", unit = "%", deviceClass = "battery")
    )

  @Test
  fun dashboardRendersContainerContentInsteadOfItsType() {
    compose.setContent {
      MaterialTheme {
        Box(
          Modifier.size(240.dp).background(MaterialTheme.colorScheme.background).testTag("screen")
        ) {
          WearDashboardScreen(DashboardData(title = "Home", cards = listOf(grid)), values("65"), {})
        }
      }
    }
    save("wear-dashboard-grid", screenshot())
    compose.onNodeWithText("grid").assertDoesNotExist()
  }

  @Test
  fun pinnedSectionRendersContainerContent() {
    compose.setContent {
      MaterialTheme {
        WearSectionScreen(
          ee.schimke.terrazzo.wearsync.proto.PinnedSection(title = "Room", cards = listOf(grid)),
          values("65"),
          {},
        )
      }
    }
    compose.onNodeWithText("grid").assertDoesNotExist()
  }

  @Test
  fun pinnedCardRendersContainerContent() {
    compose.setContent {
      MaterialTheme {
        WearTopLevelScreen(
          ee.schimke.terrazzo.wearsync.proto.WearSettings(),
          ee.schimke.terrazzo.wearsync.proto.PinnedCardSet(
            cards = listOf(ee.schimke.terrazzo.wearsync.proto.PinnedCard(card = grid))
          ),
          ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet(),
          values("65"),
          {},
          {},
          {},
        )
      }
    }
    compose.onNodeWithText("grid").assertDoesNotExist()
  }

  @Test
  fun malformedContainerHasAnExplicitFallbackWithoutLeakingItsType() {
    compose.setContent {
      MaterialTheme { WearRemoteCard(grid.copy(rawJson = "invalid"), emptyMap()) }
    }
    compose.onNodeWithText("Card unavailable").assertExists()
    compose.onNodeWithText("grid").assertDoesNotExist()
  }

  @Test
  fun stylesValuesAndOfflineReplayChangeTheActualPixels() {
    // Exercise the persisted wire representation, not just an in-memory style resolver.
    var summary by mutableStateOf(decodeProto<CardSummary>(encodeProto(grid))!!)
    var look by mutableStateOf(WearCardLook(style = "classic"))
    var live by mutableStateOf(values("20"))
    var visible by mutableStateOf(true)
    compose.setContent {
      MaterialTheme {
        CompositionLocalProvider(LocalWearCardLook provides look) {
          Box(
            Modifier.size(240.dp).background(MaterialTheme.colorScheme.background).testTag("screen")
          ) {
            if (visible) WearRemoteCard(summary, live)
          }
        }
      }
    }
    val classic = screenshot()
    compose.runOnIdle { look = look.copy(style = "lcd") }
    val lcd = screenshot()
    assertFalse(classic.sameAs(lcd), "Changing watch style must change the card artwork")
    compose.runOnIdle {
      summary = decodeProto<CardSummary>(encodeProto(grid.copy(cardStyleOverride = "braun")))!!
    }
    val overridden = screenshot()
    assertFalse(lcd.sameAs(overridden), "Phone override must replace the watch style")
    compose.runOnIdle { live = values("80") }
    val updated = screenshot()
    assertFalse(overridden.sameAs(updated), "A nested gauge must show updated values")
    compose.runOnIdle { visible = false }
    compose.waitForIdle()
    compose.runOnIdle { visible = true }
    assertTrue(updated.sameAs(screenshot()), "Cached replay must seed the current value")
    compose.runOnIdle { summary = grid }
    assertFalse(updated.sameAs(screenshot()), "Clearing override must restore watch style")
    save("wear-grid-classic", classic)
    save("wear-grid-lcd", lcd)
    save("wear-grid-braun", overridden)
    save("wear-grid-braun-updated", updated)
  }

  private fun screenshot(): Bitmap {
    compose.waitForIdle()
    return compose.onNodeWithTag("screen").captureToImage().asAndroidBitmap()
  }

  private fun save(name: String, bitmap: Bitmap) {
    System.getProperty("wearRenderEvidence")?.let { path ->
      val dir = File(path).apply { mkdirs() }
      File(dir, "$name.png").outputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
      }
    }
  }
}
