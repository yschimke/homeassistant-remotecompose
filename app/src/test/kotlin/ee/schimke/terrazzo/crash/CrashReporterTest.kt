package ee.schimke.terrazzo.crash

import ee.schimke.terrazzo.BuildConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CrashReporterTest {
  @Test
  fun noneIsNoop() {
    assertSame(NoopCrashReporter, CrashReporter.create("none"))
  }

  @Test
  fun unknownBackendIsNoop() {
    assertSame(NoopCrashReporter, CrashReporter.create("bogus"))
  }

  @Test
  fun keylessBuildSelectsNone() {
    // The test build has no google-services.json and no harc.crashReporter override, so `auto`
    // must resolve to none — the default build carries no crash SDK.
    assertEquals("none", BuildConfig.CRASH_REPORTER)
    assertSame(NoopCrashReporter, CrashReporter.create())
  }
}
