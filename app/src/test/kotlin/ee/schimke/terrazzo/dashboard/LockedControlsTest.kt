package ee.schimke.terrazzo.dashboard

import androidx.compose.material3.SnackbarHostState
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.HaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class LockedControlsTest {
  private val snapshot =
    HaSnapshot(
      states =
        mapOf(
          "light.kitchen" to EntityState("light.kitchen", "off"),
          "lock.front_door" to EntityState("lock.front_door", "locked"),
        ),
    )

  @Test
  fun lowRiskTapReplays() {
    var ran = false
    val locked = lockedAction(HaAction.Toggle("light.kitchen"), snapshot) { ran = true }
    assertNotNull(locked.replay).invoke()
    assertTrue(ran)
  }

  @Test
  fun highRiskTapIsNeverReplayed() {
    assertNull(lockedAction(HaAction.Toggle("lock.front_door"), snapshot) {}.replay)
  }

  @Test
  fun withoutASnapshotNothingReplays() {
    assertNull(lockedAction(HaAction.Toggle("light.kitchen"), null) {}.replay)
  }

  @Test
  fun unlockUnlocksThenRunsTheTap() = runTest {
    val host = SnackbarHostState()
    val order = mutableListOf<String>()
    launch { host.offerUnlock(LockedAction { order += "replay" }) { order += "unlock" } }
    runCurrent()
    val shown = assertNotNull(host.currentSnackbarData)
    assertEquals(CONTROLS_LOCKED, shown.visuals.message)
    assertEquals(UNLOCK, shown.visuals.actionLabel)
    shown.performAction()
    runCurrent()
    assertEquals(listOf("unlock", "replay"), order)
  }

  @Test
  fun dismissedPromptChangesNothing() = runTest {
    val host = SnackbarHostState()
    var unlocked = false
    launch { host.offerUnlock(LockedAction(null)) { unlocked = true } }
    runCurrent()
    val shown = assertNotNull(host.currentSnackbarData)
    assertEquals(CONTROLS_LOCKED_TAP_AGAIN, shown.visuals.message)
    shown.dismiss()
    runCurrent()
    assertFalse(unlocked)
  }

  @Test
  fun aNewerLockedTapReplacesThePrompt() = runTest {
    val host = SnackbarHostState()
    val replays = mutableListOf<Int>()
    launch { host.offerUnlock(LockedAction { replays += 1 }) {} }
    runCurrent()
    launch { host.offerUnlock(LockedAction { replays += 2 }) {} }
    runCurrent()
    assertNotNull(host.currentSnackbarData).performAction()
    runCurrent()
    // Only the tap the user was looking at runs.
    assertEquals(listOf(2), replays)
  }

  @Test
  fun aNewerTapDropsAPromptQueuedBehindAnotherSnackbar() = runTest {
    val host = SnackbarHostState()
    val prompts = UnlockPrompts(host, this)
    val replays = mutableListOf<Int>()
    // A Home Assistant notification is showing; both prompts would queue behind it.
    launch { host.showSnackbar("New notification") }
    runCurrent()
    prompts.offer(LockedAction { replays += 1 }) {}
    runCurrent()
    prompts.offer(LockedAction { replays += 2 }) {}
    runCurrent()
    assertNotNull(host.currentSnackbarData).dismiss()
    runCurrent()
    assertEquals(CONTROLS_LOCKED, assertNotNull(host.currentSnackbarData).visuals.message)
    assertNotNull(host.currentSnackbarData).performAction()
    runCurrent()
    // Only the latest tap runs, and no stale prompt follows it.
    assertEquals(listOf(2), replays)
    assertNull(host.currentSnackbarData)
  }

  @Test
  fun unlockingElsewhereDropsAQueuedPrompt() = runTest {
    val host = SnackbarHostState()
    val prompts = UnlockPrompts(host, this)
    var replayed = false
    launch { host.showSnackbar("New notification") }
    runCurrent()
    prompts.offer(LockedAction { replayed = true }) {}
    runCurrent()
    // The top bar's lock is opened while the prompt waits behind the notification.
    prompts.cancel()
    assertNotNull(host.currentSnackbarData).dismiss()
    runCurrent()
    assertNull(host.currentSnackbarData)
    assertFalse(replayed)
  }

  @Test
  fun leavingTheDashboardDropsAQueuedPrompt() = runTest {
    val host = SnackbarHostState()
    // The dashboard screen's scope: switching dashboards or opening the editor cancels it.
    val screen = CoroutineScope(coroutineContext + Job())
    val prompts = UnlockPrompts(host, screen)
    var unlocked = false
    launch { host.showSnackbar("New notification") }
    runCurrent()
    prompts.offer(LockedAction {}) { unlocked = true }
    runCurrent()
    screen.cancel()
    assertNotNull(host.currentSnackbarData).dismiss()
    runCurrent()
    assertNull(host.currentSnackbarData)
    assertFalse(unlocked)
  }
}
