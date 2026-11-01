package ee.schimke.terrazzo.wear

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import ee.schimke.terrazzo.wear.sync.WearOfflineStore
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.PinnedSection
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real activity navigation with offline demo fixtures: no home or paired phone required. */
class WearNavigationTest {
  @get:Rule val compose = createEmptyComposeRule()

  /** The app asks for notifications on launch; granted up front, no dialog covers the screens. */
  @Before
  fun grantNotifications() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.grantRuntimePermission(
      instrumentation.targetContext.packageName,
      android.Manifest.permission.POST_NOTIFICATIONS,
    )
  }

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
      compose.onNodeWithText("Demo data from your phone").assertExists()
      click("Settings")
      compose
        .onNodeWithText(
          "What your watch shows, its theme and card designs are all chosen on your phone."
        )
        .assertExists()
      compose.onNodeWithText("Theme").assertDoesNotExist()
      click("Report a bug")
      compose.onNodeWithText("PUBLIC issue").assertExists()
      activity.onActivity { it.onBackPressedDispatcher.onBackPressed() }
      compose
        .onNodeWithText(
          "What your watch shows, its theme and card designs are all chosen on your phone."
        )
        .assertExists()
      compose.onNodeWithText("Theme").assertDoesNotExist()
      activity.onActivity { it.onBackPressedDispatcher.onBackPressed() }
      compose.onNodeWithText("Demo data from your phone").assertExists()
    }
  }

  @Test
  fun pinnedSectionExpandsOnHomeScreen() {
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
    ActivityScenario.launch(WearMainActivity::class.java).use {
      click("QA Room")
      compose.onNodeWithText("QA Temperature").assertExists()
    }
  }

  private fun click(text: String) {
    // Wait for the list root after a cold activity launch before scrolling to its target.
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
