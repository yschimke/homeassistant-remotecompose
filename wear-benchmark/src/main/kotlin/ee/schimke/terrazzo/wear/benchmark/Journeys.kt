package ee.schimke.terrazzo.wear.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** `:wear`'s application id (shared with `:app`). */
internal const val TARGET_PACKAGE = "ee.schimke.harc"

private const val UI_TIMEOUT_MS = 15_000L

/**
 * Cold-starts the watch app onto its top-level list. With no paired phone the list holds only the
 * empty pinned state and the "Browse dashboards" / "Settings" entries, which is what an emulator
 * shows; a seeded data layer would widen the profile to the card screens.
 */
internal fun MacrobenchmarkScope.startTopLevel() {
  startActivityAndWait()
  awaitText("Browse dashboards")
}

/** Opens Settings from the top-level list; the longest list the unpaired app has. */
internal fun MacrobenchmarkScope.openSettings() {
  findScrollingTo("Settings").click()
  awaitText("Theme")
}

/** Opens the dashboard library from the top-level list. */
internal fun MacrobenchmarkScope.openDashboards() {
  findScrollingTo("Browse dashboards").click()
  awaitText("Dashboards")
}

/** Returns to the top-level list with the system back gesture. */
internal fun MacrobenchmarkScope.backToTopLevel() {
  device.pressBack()
  awaitText("Browse dashboards")
}

/** Flings the screen's list to the end and back. */
internal fun MacrobenchmarkScope.scrollList() {
  val list = list()
  list.setGestureMargin(device.displayWidth / 5)
  repeat(3) { list.fling(Direction.DOWN) }
  device.waitForIdle()
  repeat(3) { list.fling(Direction.UP) }
  device.waitForIdle()
}

private fun MacrobenchmarkScope.list(): UiObject2 =
  device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }
    ?: error("no scrollable list on screen")

private fun MacrobenchmarkScope.findScrollingTo(text: String): UiObject2 =
  device.findObject(By.text(text))
    ?: list()
      .also { it.setGestureMargin(device.displayWidth / 5) }
      .scrollUntil(
        Direction.DOWN,
        Until.findObject(By.text(text)),
      )
    ?: error("'$text' not found")

private fun MacrobenchmarkScope.awaitText(text: String) {
  check(device.wait(Until.hasObject(By.text(text)), UI_TIMEOUT_MS)) { "'$text' did not appear" }
}
