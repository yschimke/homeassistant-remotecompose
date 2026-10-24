package ee.schimke.terrazzo.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Memory while reading the dashboard, and what is left once the system trims the app.
 *
 * Both start from a fresh process on [LONG_BOARD] with every section open, the board that fills the
 * card caches fastest (each card captured, decoded and cached on its way into view):
 * - [browseExpandedBoard] drags down and back up, reporting the peak (`Max`) and where it settles
 *   (`Last`): the Java heap and the resident anonymous and file-backed pages.
 * - [trimInBackground] does the same browsing in its setup, then goes home and asks for
 *   `TRIM_MEMORY_BACKGROUND`, as the system does once the app is cached. `CardMemory` drops every
 *   rebuildable card cache at that level and ART collects once the process is in the background, so
 *   its `Last` should sit well below the browse's.
 *
 * Run with `./gradlew :app-benchmark:connectedBenchmarkReleaseAndroidTest`
 * (`-Pandroid.testInstrumentationRunnerArguments.class=` this class to run it alone).
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class MemoryBenchmark {

  @get:Rule val rule = MacrobenchmarkRule()

  @Test
  fun browseExpandedBoard() =
    rule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics =
        listOf(
          MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
          MemoryUsageMetric(MemoryUsageMetric.Mode.Last),
        ),
      compilationMode = COMPILATION,
      // No StartupMode: the setup launches a fresh process itself (see
      // DashboardScrollJankBenchmark).
      startupMode = null,
      iterations = ITERATIONS,
      setupBlock = { openExpandedBoard() },
    ) {
      dragDashboard(drags = DRAGS)
    }

  @Test
  fun trimInBackground() =
    rule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics = listOf(MemoryUsageMetric(MemoryUsageMetric.Mode.Last)),
      compilationMode = COMPILATION,
      startupMode = null,
      iterations = ITERATIONS,
      setupBlock = {
        openExpandedBoard()
        dragDashboard(drags = DRAGS)
      },
    ) {
      trimInBackground(TARGET_PACKAGE)
    }

  private fun MacrobenchmarkScope.openExpandedBoard() {
    pressHome()
    killProcess()
    startDemoDashboard(expandSections = true)
    switchDashboard(LONG_BOARD)
  }

  private companion object {
    val COMPILATION = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require)
    const val ITERATIONS = 5

    /** As [DashboardScrollJankBenchmark]: several screens of cards, inside a CI time budget. */
    const val DRAGS = 12
  }
}
