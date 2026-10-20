package ee.schimke.terrazzo.crash

import ee.schimke.terrazzo.BuildConfig
import kotlin.test.Test
import kotlin.test.assertNotSame
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
  fun buildBackendResolves() {
    // Holds for whichever backend this build selected (none by default, firebase when a key is
    // present): a selected backend's class is compiled in, so it never silently falls back.
    val reporter = CrashReporter.create()
    if (BuildConfig.CRASH_REPORTER == "none") {
      assertSame(NoopCrashReporter, reporter)
    } else {
      assertNotSame(NoopCrashReporter, reporter)
    }
  }
}
