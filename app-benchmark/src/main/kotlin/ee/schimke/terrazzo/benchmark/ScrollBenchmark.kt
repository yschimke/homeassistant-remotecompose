package ee.schimke.terrazzo.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing (jank) while flinging the demo dashboard, whose cards are RemoteCompose documents
 * played back as they scroll into view.
 *
 * Run with `./gradlew :app-benchmark:connectedBenchmarkReleaseAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {

  @get:Rule val rule = MacrobenchmarkRule()

  @Test fun scrollCompilationNone() = scroll(CompilationMode.None())

  @Test
  fun scrollBaselineProfile() =
    scroll(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

  private fun scroll(compilationMode: CompilationMode) =
    rule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics = listOf(FrameTimingMetric()),
      compilationMode = compilationMode,
      startupMode = StartupMode.WARM,
      iterations = 5,
      setupBlock = {
        pressHome()
        startDemoDashboard()
      },
    ) {
      scrollDashboard()
    }
}
