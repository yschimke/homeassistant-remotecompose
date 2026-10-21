package ee.schimke.terrazzo.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Captures `:app`'s baseline profile: cold start into the demo dashboard, then open and scroll the
 * longer boards so the card converters and RemoteCompose player paths they hit are covered too, and
 * drag [LONG_BOARD] with every section open, the journey [DashboardScrollJankBenchmark] times.
 *
 * Run with `./gradlew :app:generateBaselineProfile` against an emulator (API 33+, or rooted). The
 * plugin writes the result to `app/src/main/generated/baselineProfiles/`.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

  @get:Rule val rule = BaselineProfileRule()

  @Test
  fun generate() =
    rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
      pressHome()
      startDemoDashboard()
      for (board in listOf(LONG_BOARD, "Meshcore", "GitHub")) {
        switchDashboard(board)
        scrollDashboard()
      }
      startDemoDashboard(expandSections = true)
      switchDashboard(LONG_BOARD)
      dragDashboard(drags = 4)
    }
}
