package ee.schimke.ha.designs

import ee.schimke.ha.rc.androidXExperimentalWrap
import kotlin.test.Test
import kotlin.test.assertEquals

/** A capture without Android writes what the app's own capture profile allows: no more, no less. */
class LibraryCaptureProfileTest {
  @Test
  fun operationsAreTheAppProfiles() {
    assertEquals(
      androidXExperimentalWrap.supportedOperations.sorted(),
      LibraryCaptureProfile.OPERATIONS.sorted(),
    )
  }
}
