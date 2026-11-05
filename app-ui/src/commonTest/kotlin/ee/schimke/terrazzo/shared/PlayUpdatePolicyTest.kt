package ee.schimke.terrazzo.shared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayUpdatePolicyTest {
  @Test
  fun testersArePromptedAsSoonAsAnUpdateAppears() {
    assertTrue(shouldStartPlayUpdate(available = true, inProgress = false, tester = true, null))
    assertTrue(shouldStartPlayUpdate(available = true, inProgress = false, tester = true, 0))
  }

  @Test
  fun everyoneElseWaitsUntilTheUpdateIsAWeekOld() {
    assertFalse(shouldStartPlayUpdate(available = true, inProgress = false, tester = false, null))
    assertFalse(shouldStartPlayUpdate(available = true, inProgress = false, tester = false, 6))
    assertTrue(shouldStartPlayUpdate(available = true, inProgress = false, tester = false, 7))
    assertTrue(shouldStartPlayUpdate(available = true, inProgress = false, tester = false, 30))
  }

  @Test
  fun noUpdateMeansNoPrompt() {
    assertFalse(shouldStartPlayUpdate(available = false, inProgress = false, tester = true, 30))
  }

  @Test
  fun anUpdateAlreadyInProgressAlwaysResumes() {
    assertTrue(shouldStartPlayUpdate(available = false, inProgress = true, tester = false, null))
  }
}
