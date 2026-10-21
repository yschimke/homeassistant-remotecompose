package ee.schimke.terrazzo.dashboard

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import ee.schimke.terrazzo.MainActivity
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * uiautomator-driven regression test for the long-press → card-history flow (and the install link
 * reachable from it).
 *
 * The dashboard renders each card inside a `RemotePreview` whose underlying RC player consumes
 * pointer events on the **Main** pass to dispatch in-document click regions. A standard outer
 * `Modifier.combinedClickable` long-press never fires because its `awaitFirstDown(requireUnconsumed
 * = true)` is always rejected by those consumed events.
 *
 * `Modifier.longPressBeforeChild` (in `CombinedClickLongPress.kt`) fixes this by listening on the
 * **Initial** pass; this test exercises the fix end-to-end through the real Android input pipeline
 * rather than Compose's test driver, since it's the input pipeline's Initial → Main pass
 * propagation that's actually under test.
 *
 * Demo mode is forced on via [MainActivity.EXTRA_TEST_DEMO_MODE] so we land directly on a dashboard
 * with deterministic fake data, avoiding the discovery / IndieAuth flow that can't reach anything
 * from CI.
 */
@RunWith(AndroidJUnit4::class)
class LongPressHistoryTest {

  private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

  @Before
  fun launchAppInDemoMode() {
    device.pressHome()
    val context = ApplicationProvider.getApplicationContext<Context>()
    val launch =
      context.packageManager
        .getLaunchIntentForPackage(context.packageName)!!
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        .putExtra(MainActivity.EXTRA_TEST_DEMO_MODE, true)
    context.startActivity(launch)
    // Wait for the dashboard view. Demo-mode clears the
    // last-viewed-dashboard pref, so the app lands on HA's
    // default dashboard (the first board in DemoData.BOARDS,
    // "Security") rather than auto-resuming from a previous
    // run. "Security" appears as the title in the top-bar
    // dashboard switcher.
    assertTrue(
      "Security dashboard did not surface within 15s",
      device.wait(Until.hasObject(By.text("Security")), 15_000),
    )
  }

  @After
  fun tearDown() {
    device.pressHome()
  }

  @Test
  fun forkUsesTheDashboardPreviewInsteadOfTheLauncherGrid() {
    waitForFirstCard().longClick()
    val fork = device.wait(Until.findObject(By.text("Redesign a copy in Card Studio")), 10_000)
    assertNotNull(fork)
    fork!!.click()
    assertTrue(device.wait(Until.hasObject(By.text("Design or fix this card")), 10_000))
    assertTrue(device.wait(Until.hasObject(By.descContains("Current card preview")), 15_000))
    assertTrue(
      "Forking must not show launcher size controls",
      !device.hasObject(By.textStartsWith("Preview at ")),
    )
    assertTrue(device.wait(Until.hasObject(By.text("Design in UI Builder")), 10_000))
  }

  @Test
  fun longPressOnDashboardCard_opensHistoryThenInstallSheet() {
    // Demo mode lands directly on the "Security" dashboard (the
    // first board in `DemoData.BOARDS`, exposed as HA's default
    // dashboard); the test exercises the long-press → history
    // screen flow on its first card.
    val card = waitForFirstCard()
    // uiautomator's longClick uses the platform long-press timeout,
    // so this matches what a real user's finger does — and that's
    // exactly the path Modifier.longPressBeforeChild's
    // `viewConfiguration.longPressTimeoutMillis` measures against.
    card.longClick()

    // Long-press offers the card's actions first: home screen, watch, style, history.
    val historyAction = device.wait(Until.findObject(By.text("History")), 10_000)
    assertNotNull(
      "card actions did not surface after long-press — long-press is being swallowed by the " +
        "RC player again, see Modifier.longPressBeforeChild",
      historyAction,
    )
    assertTrue(device.hasObject(By.text("Add to home screen")))
    assertTrue(device.hasObject(By.text("Show on watch")))
    historyAction!!.click()

    val historyVisible = device.wait(Until.hasObject(By.desc("Back")), 10_000)
    assertTrue(
      "card-history screen did not surface after long-press — long-press " +
        "is being swallowed by the RC player again, see " +
        "Modifier.longPressBeforeChild",
      historyVisible,
    )
    // History shows the card as the dashboard draws it; launcher sizing is the install sheet's.
    assertTrue(device.wait(Until.hasObject(By.descContains("Current card preview")), 15_000))
    assertTrue(
      "card history must not show launcher size controls",
      !device.hasObject(By.textStartsWith("Preview at ")),
    )

    // The top bar's actions button reopens the same sheet, which leads to the install sheet.
    device.findObject(By.desc("Card actions"))!!.click()
    val install = device.wait(Until.findObject(By.text("Add to home screen")), 10_000)
    assertNotNull("card actions did not reopen from the history screen", install)
    install!!.click()

    // WidgetInstallSheet.kt opens with this title.
    val sheetVisible = device.wait(Until.hasObject(By.text("Home screen widget")), 10_000)
    assertTrue("install sheet did not surface from the history screen", sheetVisible)
  }

  /**
   * The first dashboard card, once the dashboard has settled. A card's semantics node appears while
   * the rest of the board is still composing — each card's first document capture runs on the main
   * thread — so a long-press sent straight away can arrive while the main thread is busy and be
   * read as a tap. Waiting for an idle main thread and a card that has stopped moving keeps this
   * test about the long-press path rather than about load timing.
   */
  private fun waitForFirstCard(): UiObject2 {
    val selector = By.descStartsWith("dashboard-card:")
    assertNotNull(
      "no card with semantics descriptor 'dashboard-card:*' rendered within 10s",
      device.wait(Until.findObject(selector), 10_000),
    )
    var lastBounds: Rect? = null
    var stableSince = SystemClock.uptimeMillis()
    val deadline = stableSince + 10_000
    while (SystemClock.uptimeMillis() < deadline) {
      device.waitForIdle()
      val bounds = device.findObject(selector)?.visibleBounds
      val now = SystemClock.uptimeMillis()
      if (bounds == null || bounds != lastBounds) {
        lastBounds = bounds
        stableSince = now
      } else if (now - stableSince >= CARD_SETTLE_MS) {
        break
      }
      SystemClock.sleep(100)
    }
    return device.findObject(selector)
      ?: throw AssertionError("first dashboard card disappeared while the board settled")
  }

  private companion object {
    const val CARD_SETTLE_MS = 750L
  }
}
