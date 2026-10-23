package ee.schimke.terrazzo.wear.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Captures `:wear`'s baseline profile: cold start onto the seeded pinned cards, scroll them, open a
 * pinned section, then open and scroll Settings and visit the dashboard library.
 *
 * Run with `./gradlew :wear:generateReleaseBaselineProfile` against a Wear emulator (API 33+, or
 * rooted). The plugin writes the result to `wear/src/release/generated/baselineProfiles/`.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

  @get:Rule val rule = BaselineProfileRule()

  @Test
  fun generate() =
    rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
      pressHome()
      startTopLevel()
      scrollList()
      openSection()
      backToTopLevel()
      openSettings()
      scrollList()
      backToTopLevel()
      openDashboards()
      backToTopLevel()
    }
}
