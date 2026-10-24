package ee.schimke.terrazzo.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What live data costs a dashboard nobody is touching: demo mode's Security board, every section
 * open, left alone for [IDLE_MS] while its values move (each minute) and its cameras roll (every
 * ten seconds). Nothing scrolls, so every document captured or decoded in the window is a card on
 * screen rebuilding on live data rather than taking it as a named binding
 * (docs/architecture/live-card-updates.md).
 *
 * Reports `TraceSectionMetric` sums and counts for `harc:rebuild` (one per rebuild, `CardRebuilds`
 * in `rc-converter`), `harc:captureDocument` (a converter re-encode) and `harc:decodeDocument` (new
 * bytes for the player). The aim is zero of each.
 *
 * Run with `./gradlew :app-benchmark:connectedBenchmarkReleaseAndroidTest`
 * (`-Pandroid.testInstrumentationRunnerArguments.class=` this class to run it alone).
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class LiveUpdateBenchmark {

  @get:Rule val rule = MacrobenchmarkRule()

  @Test
  fun idleSecurityBoard() =
    rule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics = TRACE_SECTIONS.map { TraceSectionMetric(it, TraceSectionMetric.Mode.Sum) },
      compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
      // No StartupMode: the setup launches a fresh process itself (see
      // DashboardScrollJankBenchmark).
      startupMode = null,
      iterations = 3,
      setupBlock = {
        pressHome()
        killProcess()
        startDemoDashboard(expandSections = true)
      },
    ) {
      Thread.sleep(IDLE_MS)
    }

  private companion object {
    /** A minute: one move of the demo values, six camera frames. */
    const val IDLE_MS = 60_000L

    /** `HarcTraceSections`' names; `:app-benchmark` doesn't depend on `rc-converter`. */
    val TRACE_SECTIONS = listOf("harc:rebuild", "harc:captureDocument", "harc:decodeDocument")
  }
}
