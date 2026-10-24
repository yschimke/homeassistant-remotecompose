package ee.schimke.terrazzo.wear.benchmark

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
 * Memory on the watch, where there is least of it: browsing the seeded pins, and what is left once
 * the system trims the app.
 *
 * Both start from a fresh process on the top-level list, seeded as a demo-mode phone would pin it:
 * - [browsePins] flings the list, opens the "Living room" section and flings it, then comes back,
 *   reporting the peak (`Max`) and where it settles (`Last`): the Java heap and the resident
 *   anonymous and file-backed pages.
 * - [trimInBackground] does the same browsing in its setup, then goes to the watch face and asks
 *   for `TRIM_MEMORY_BACKGROUND`. `CardMemory` drops every rebuildable card cache and the widget
 *   templates at that level, so its `Last` should sit well below the browse's.
 *
 * Run with `./gradlew :wear-benchmark:connectedBenchmarkReleaseAndroidTest`
 * (`-Pandroid.testInstrumentationRunnerArguments.class=` this class to run it alone).
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class MemoryBenchmark {

  @get:Rule val rule = MacrobenchmarkRule()

  @Test
  fun browsePins() =
    rule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics =
        listOf(
          MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
          MemoryUsageMetric(MemoryUsageMetric.Mode.Last),
        ),
      compilationMode = COMPILATION,
      // No StartupMode: the setup launches a fresh process itself.
      startupMode = null,
      iterations = ITERATIONS,
      setupBlock = { openTopLevel() },
    ) {
      browse()
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
        openTopLevel()
        browse()
      },
    ) {
      trimInBackground(TARGET_PACKAGE)
    }

  private fun MacrobenchmarkScope.openTopLevel() {
    pressHome()
    killProcess()
    startTopLevel()
  }

  private fun MacrobenchmarkScope.browse() {
    scrollList()
    openSection()
    scrollList()
    backToTopLevel()
  }

  private companion object {
    val COMPILATION = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require)
    const val ITERATIONS = 5
  }
}
