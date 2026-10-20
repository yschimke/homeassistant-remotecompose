package ee.schimke.terrazzo.wear.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

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
  // The list opens centred on its second item, so either of the first two may be the one on screen.
  open("Settings", "Report a bug", "Theme")
}

/** Opens the dashboard library from the top-level list. */
internal fun MacrobenchmarkScope.openDashboards() {
  open("Browse dashboards", "Dashboards", "Back")
}

/** Returns to the top-level list with the system back gesture. */
internal fun MacrobenchmarkScope.backToTopLevel() {
  device.pressBack()
  awaitText("Pinned", "Browse dashboards", "Settings")
}

/**
 * Flings the screen's list to the end and back. Raw swipes, like `:app-benchmark`'s: they do not
 * depend on uiautomator finding a scrollable node in the Compose tree.
 */
internal fun MacrobenchmarkScope.scrollList() {
  repeat(4) { swipe(up = true) }
  repeat(4) { swipe(up = false) }
}

/**
 * Taps the [entry] row and waits for any of [landmarks] on the screen it opens. A tap that lands
 * while the list is still settling from a fling can miss its row, so one miss is retried with a
 * fresh lookup.
 */
private fun MacrobenchmarkScope.open(entry: String, vararg landmarks: String) {
  findScrollingTo(entry).click()
  if (waitForAny(landmarks, UI_TIMEOUT_MS / 3)) return
  device.findObject(By.text(entry))?.click()
  awaitText(*landmarks)
}

/**
 * Finds [text] in the current list, dragging it up a little at a time. Slow drags rather than
 * flings: a fling can carry a short round-screen list past its last rows, which then sit scaled at
 * the edge where uiautomator does not report them.
 */
private fun MacrobenchmarkScope.findScrollingTo(text: String): UiObject2 {
  repeat(8) {
    device.wait(Until.findObject(By.text(text)), 1_000)?.let {
      return it
    }
    drag(up = true)
  }
  return device.findObject(By.text(text)) ?: error("'$text' not found; on screen: ${onScreen()}")
}

/** A slow drag of a third of the screen, which moves the list without flinging it. */
private fun MacrobenchmarkScope.drag(up: Boolean) {
  val x = device.displayWidth / 2
  val low = device.displayHeight * 2 / 3
  val high = device.displayHeight / 3
  if (up) device.swipe(x, low, x, high, 40) else device.swipe(x, high, x, low, 40)
  device.waitForIdle()
}

private fun MacrobenchmarkScope.onScreen(): List<String> =
  device.findObjects(By.text(Pattern.compile(".+"))).map { it.text }

/** One fast vertical swipe through the middle of the round screen. */
private fun MacrobenchmarkScope.swipe(up: Boolean) {
  val x = device.displayWidth / 2
  val low = device.displayHeight * 3 / 4
  val high = device.displayHeight / 4
  if (up) device.swipe(x, low, x, high, 5) else device.swipe(x, high, x, low, 5)
  device.waitForIdle()
}

/** Waits for any of [texts]: a lazy list only lays out what is on screen. */
private fun MacrobenchmarkScope.awaitText(vararg texts: String) {
  check(waitForAny(texts, UI_TIMEOUT_MS)) {
    "none of ${texts.toList()} appeared; on screen: ${onScreen()}"
  }
}

private fun MacrobenchmarkScope.waitForAny(texts: Array<out String>, timeoutMs: Long): Boolean {
  val pattern = Pattern.compile(texts.joinToString("|") { Pattern.quote(it) })
  return device.wait(Until.hasObject(By.text(pattern)), timeoutMs)
}
