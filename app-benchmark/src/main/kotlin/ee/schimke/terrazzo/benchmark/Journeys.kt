package ee.schimke.terrazzo.benchmark

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** `:app`'s application id (shared with `:wear`). */
internal const val TARGET_PACKAGE = "ee.schimke.harc"

/** `MainActivity.EXTRA_TEST_DEMO_MODE`; honoured by the `TEST_HOOKS` variants only. */
private const val EXTRA_TEST_DEMO_MODE = "terrazzo.test.demo_mode"

/** Demo mode's default board — the first of `DemoHaSession.BOARDS`. */
private const val DEFAULT_BOARD = "Security"

private const val UI_TIMEOUT_MS = 15_000L

/**
 * Cold-starts into demo mode, which needs no Home Assistant and no login, and waits for the default
 * dashboard's first card. The demo hatch also clears the last-viewed dashboard, so every run lands
 * on the same board.
 */
internal fun MacrobenchmarkScope.startDemoDashboard() {
  startActivityAndWait { intent: Intent -> intent.putExtra(EXTRA_TEST_DEMO_MODE, true) }
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
 * The dashboard's card list: the tallest scrollable on screen. Only a board taller than the screen
 * is scrollable — the default Security board fits a phone, so scroll [LONG_BOARD] and the like.
 */
internal fun MacrobenchmarkScope.dashboardList(): UiObject2 {
  device.wait(Until.hasObject(By.scrollable(true)), UI_TIMEOUT_MS)
  return device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }
    ?: error("no scrollable dashboard on screen")
}

/** Flings the open dashboard to the bottom and back to the top. */
internal fun MacrobenchmarkScope.scrollDashboard() {
  val list = dashboardList()
  // Keep flings clear of the system gesture-navigation edges.
  list.setGestureMargin(device.displayWidth / 5)
  repeat(2) { list.fling(Direction.DOWN) }
  device.waitForIdle()
  repeat(2) { list.fling(Direction.UP) }
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
