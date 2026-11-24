package ee.schimke.terrazzo.crash

import dev.zacsweers.metro.createGraphFactory
import ee.schimke.terrazzo.BuildConfig
import ee.schimke.terrazzo.di.RealAppGraph
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrashReporterTest {
  @Test
  fun buildBackendResolvesThroughAppGraph() {
    // Holds for whichever backend this build selected (none by default, firebase when a key is
    // present): Metro binds the compiled backend into AppScope at compile time.
    val graph =
      createGraphFactory<RealAppGraph.Factory>().create(RuntimeEnvironment.getApplication())
    val reporter = graph.crashReporter
    assertSame(reporter, graph.crashReporter)
    if (BuildConfig.CRASH_REPORTER == "none") {
      assertIs<NoopCrashReporter>(reporter)
    } else {
      assertFalse(reporter is NoopCrashReporter)
    }
  }
}
