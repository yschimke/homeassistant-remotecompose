package ee.schimke.terrazzo.wear.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start onto the top-level list, without any AOT compilation and with the baseline profile
 * applied. The gap between the two is what the checked-in profile buys.
 *
 * Run with `./gradlew :wear-benchmark:connectedBenchmarkReleaseAndroidTest`; prefer a physical
 * device for numbers worth comparing — emulator timings only show large regressions.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

  @get:Rule val rule = MacrobenchmarkRule()

  @Test fun startupCompilationNone() = startup(CompilationMode.None())

  @Test
  fun startupBaselineProfile() =
    startup(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

  private fun startup(compilationMode: CompilationMode) =
    rule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics = listOf(StartupTimingMetric()),
      compilationMode = compilationMode,
      startupMode = StartupMode.COLD,
      iterations = 10,
      setupBlock = { pressHome() },
    ) {
      startTopLevel()
    }
}
