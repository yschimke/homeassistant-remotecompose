package ee.schimke.terrazzo.benchmark

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/** `:app`'s application id (shared with `:wear`). */
internal const val TARGET_PACKAGE = "ee.schimke.harc"

/** `MainActivity.EXTRA_TEST_DEMO_MODE`; honoured by the `TEST_HOOKS` variants only. */
private const val EXTRA_TEST_DEMO_MODE = "terrazzo.test.demo_mode"

/** `MainActivity.EXTRA_TEST_EXPAND_SECTIONS`: every section open rather than only the first. */
private const val EXTRA_TEST_EXPAND_SECTIONS = "terrazzo.test.expand_sections"

/** Demo mode's default board — the first of `DemoHaSession.BOARDS`. */
private const val DEFAULT_BOARD = "Security"

private const val UI_TIMEOUT_MS = 15_000L

/**
 * Cold-starts into demo mode, which needs no Home Assistant and no login, and waits for the default
 * dashboard's first card. The demo hatch also clears the last-viewed dashboard, so every run lands
 * on the same board.
 *
 * Sections start collapsed (the app's default), so a phone shows one section's cards at a time;
 * [expandSections] opens them all, so scrolling brings card after card on screen.
 */
internal fun MacrobenchmarkScope.startDemoDashboard(expandSections: Boolean = false) {
  startActivityAndWait { intent: Intent ->
    intent.putExtra(EXTRA_TEST_DEMO_MODE, true)
    intent.putExtra(EXTRA_TEST_EXPAND_SECTIONS, expandSections)
  }
  awaitDashboard(DEFAULT_BOARD)
}

/** Waits until [board] is the open dashboard and its cards have rendered. */
internal fun MacrobenchmarkScope.awaitDashboard(board: String) {
  check(device.wait(Until.hasObject(By.text(board)), UI_TIMEOUT_MS)) {
    "dashboard '$board' did not open"
  }
  check(device.wait(Until.hasObject(By.descStartsWith("dashboard-card:")), UI_TIMEOUT_MS)) {
    "dashboard '$board' rendered no cards"
  }
}

/** Demo mode's longest board (about 200 cards), the one worth scrolling. */
internal const val LONG_BOARD = "3D Printing"

/**
 * Flings the open dashboard to the bottom and back to the top. Only a board taller than the screen
 * scrolls — the default Security board fits a phone, so scroll [LONG_BOARD] and the like.
 *
 * Raw swipes rather than `UiObject2.fling`: on the API 36 emulator uiautomator reports no
 * scrollable node for the dashboard's list, even on a board several screens long.
 */
internal fun MacrobenchmarkScope.scrollDashboard() {
  repeat(3) { swipe(up = true) }
  repeat(3) { swipe(up = false) }
}

/**
 * Drags the open dashboard down [drags] half screens and back up the same way, at a steady reading
 * pace. A fling can skip past cards in a few frames; a drag holds the finger down, so every card
 * comes on screen during a frame the user is watching: the frames scroll jank lands in.
 */
internal fun MacrobenchmarkScope.dragDashboard(drags: Int) {
  repeat(drags) { swipe(up = true, steps = DRAG_STEPS) }
  repeat(drags) { swipe(up = false, steps = DRAG_STEPS) }
}

/**
 * About 5 ms per step: half a screen in half a second, a deliberate drag with no fling at the end.
 */
private const val DRAG_STEPS = 100

/**
 * One vertical swipe through the middle of the screen, clear of the gesture-nav edges. Few steps
 * (about 5 ms each) make it a fling; many, a drag.
 */
private fun MacrobenchmarkScope.swipe(up: Boolean, steps: Int = 5) {
  val x = device.displayWidth / 2
  val low = device.displayHeight * 3 / 4
  val high = device.displayHeight / 4
  if (up) device.swipe(x, low, x, high, steps) else device.swipe(x, high, x, low, steps)
  device.waitForIdle()
}

/** Opens [board] from the top-bar dashboard switcher. */
internal fun MacrobenchmarkScope.switchDashboard(board: String) {
  device.findObject(By.desc("Switch dashboard"))?.click() ?: error("no dashboard switcher")
  val item =
    device.wait(Until.findObject(By.text(board)), UI_TIMEOUT_MS)
      ?: error("dashboard '$board' missing from the switcher")
  item.click()
  awaitDashboard(board)
}
