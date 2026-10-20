package ee.schimke.terrazzo.wear

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import ee.schimke.terrazzo.wear.sync.WearOfflineStore
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.PinnedSection
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import org.junit.Rule
import org.junit.Test

/** Real activity navigation with offline demo fixtures: no home or paired phone required. */
class WearNavigationTest {
  @get:Rule val compose = createEmptyComposeRule()

  @Test
  fun systemBackReturnsThroughDashboardHierarchyAndSettings() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val store = WearOfflineStore(context)
    store.writeSettings(WearSettings(demoMode = true))
    store.writeDashboard(
      DashboardData(
        urlPath = "qa",
        title = "QA Home",
        cards = listOf(CardSummary(title = "QA Lamp")),
      )
    )
    ActivityScenario.launch(WearMainActivity::class.java).use { activity ->
      click("Browse dashboards")
      click("QA Home")
      compose.onNodeWithText("QA Lamp").assertExists()
      activity.onActivity { it.onBackPressedDispatcher.onBackPressed() }
      compose.onNodeWithText("Dashboards").assertExists()
      activity.onActivity { it.onBackPressedDispatcher.onBackPressed() }
      compose.onNodeWithText("Pinned · Demo").assertExists()
      click("Settings")
      compose.onNodeWithText("Theme").assertExists()
      click("Report a bug")
      compose.onNodeWithText("PUBLIC issue").assertExists()
      activity.onActivity { it.onBackPressedDispatcher.onBackPressed() }
      compose.onNodeWithText("Theme").assertExists()
      activity.onActivity { it.onBackPressedDispatcher.onBackPressed() }
      compose.onNodeWithText("Pinned · Demo").assertExists()
    }
  }

  @Test
  fun systemBackReturnsFromAPinnedSection() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    WearOfflineStore(context).apply {
      writeSettings(WearSettings(demoMode = true))
      writeSections(
        PinnedSectionSet(
          sections =
            listOf(
              PinnedSection(
                sectionKey = "qa-room",
                title = "QA Room",
                cards = listOf(CardSummary(title = "QA Temperature")),
              )
            )
        )
      )
    }
    ActivityScenario.launch(WearMainActivity::class.java).use { activity ->
      click("QA Room")
      compose.onNodeWithText("QA Temperature").assertExists()
      activity.onActivity { it.onBackPressedDispatcher.onBackPressed() }
      compose.onNodeWithText("Pinned · Demo").assertExists()
    }
  }

  private fun click(text: String) {
    // The activity waits for persisted palette/style before composing its first card.
    // DataStore IO is outside Compose's idling resources, especially on a cold emulator.
    compose.waitUntil(timeoutMillis = 10_000) {
      compose
        .onAllNodes(hasScrollAction())
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .isNotEmpty()
    }
    compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    compose.onNodeWithText(text).performClick()
  }
}
