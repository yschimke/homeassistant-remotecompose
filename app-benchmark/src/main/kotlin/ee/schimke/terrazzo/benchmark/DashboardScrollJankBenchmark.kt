package ee.schimke.terrazzo.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Jank while cards come on screen: [LONG_BOARD] with every section open, dragged down at a steady
 * pace and back up, so card after card enters view during frames the user is watching.
 * [ScrollBenchmark] flings the same board with its sections collapsed, which brings few cards on
 * screen; this one is the dashboard as a user with collapsed mode off reads it.
 *
 * Each iteration starts a fresh process (the setup kills it before launching), so the way down
 * meets every card for the first time (no captured, decoded or template document cached yet) and
 * the way back meets cards the process has played before: the two costs a regression in either path
 * shows up in. No [androidx.benchmark.macro.StartupMode]: a cold one expects the measured block
 * itself to launch the app, and refuses to run when the setup already has.
 *
 * Besides frame timing it sums the app's own trace sections (`HarcTraceSections` in `rc-converter`)
 * over the scroll: the main-thread time spent resolving card templates, capturing documents and
 * decoding them, and how often each ran. A card scrolled back into view should add nothing to the
 * decode and capture counts.
 *
 * Run with `./gradlew :app-benchmark:connectedBenchmarkReleaseAndroidTest`
 * (`-Pandroid.testInstrumentationRunnerArguments.class=` this class to run it alone). Emulator
 * frame times only show large regressions; compare numbers from a physical device.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class DashboardScrollJankBenchmark {

  @get:Rule val rule = MacrobenchmarkRule()

  @Test
  fun dragExpandedBoard() =
    rule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics =
        listOf(FrameTimingMetric()) +
          TRACE_SECTIONS.flatMap { name ->
            listOf(
              TraceSectionMetric(name, TraceSectionMetric.Mode.Sum),
              TraceSectionMetric(name, TraceSectionMetric.Mode.Count),
            )
          },
      compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
      startupMode = null,
      iterations = 5,
      setupBlock = {
        pressHome()
        // A fresh process every iteration, so the way down meets every card for the first time.
        killProcess()
        startDemoDashboard(expandSections = true)
        switchDashboard(LONG_BOARD)
      },
    ) {
      dragDashboard(drags = DRAGS)
    }

  private companion object {
    /** Half screens each way: several screens of cards on a phone, inside a CI time budget. */
    const val DRAGS = 12

    /** `HarcTraceSections`' names; `:app-benchmark` doesn't depend on `rc-converter`. */
    val TRACE_SECTIONS = listOf("harc:cardTemplate", "harc:captureDocument", "harc:decodeDocument")
  }
}
