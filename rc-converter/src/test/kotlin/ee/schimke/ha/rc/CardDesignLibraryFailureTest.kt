package ee.schimke.ha.rc

import androidx.test.core.app.ApplicationProvider
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.ThemeStyle
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A template capture that fails at runtime (Crashlytics: `CardDesignTemplates.capture`,
 * "captureSingleRemoteDocument did not reach a quiescent Idle state") leaves its card on the
 * converter path and is reported once, instead of crashing the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardDesignLibraryFailureTest {

  @AfterTest
  fun tearDown() {
    CardDesignLibrary.onCaptureFailure = null
  }

  @Test
  fun aFailedCaptureIsNullAndReportedOnce() {
    val reports = mutableListOf<Pair<DesignKey, Throwable>>()
    CardDesignLibrary.onCaptureFailure = { key, error -> reports += key to error }
    // A key of its own (never captured by another test), and a registry without a single
    // converter, not even the unsupported-card fallback: its capture throws.
    val key = assertNotNull(tileKey()).copy(cardType = "custom:never-registered-card")
    val empty = CardRegistry()

    assertNull(
      CardDesignLibrary.templateOrNull(ApplicationProvider.getApplicationContext(), key, empty)
    )
    assertNull(
      CardDesignLibrary.templateOrNull(ApplicationProvider.getApplicationContext(), key, empty)
    )

    assertEquals(1, reports.size, "a failed key is reported, and captured, once")
    assertEquals(key, reports.single().first)
    assertTrue(reports.single().second.message.orEmpty().contains("template capture failed"))
  }

  @Test
  fun aWorkingCaptureStillReturnsItsTemplate() {
    val key = assertNotNull(tileKey())

    val bytes = CardDesignLibrary.templateOrNull(ApplicationProvider.getApplicationContext(), key)

    assertTrue(assertNotNull(bytes).isNotEmpty())
  }

  private fun tileKey(): DesignKey? =
    CardDesignTemplates.keyFor(
      CardConfig(
        type = "tile",
        raw =
          buildJsonObject {
            put("type", "tile")
            put("entity", "light.kitchen")
          },
      ),
      HaSnapshot(states = mapOf("light.kitchen" to EntityState("light.kitchen", "on"))),
      ThemeStyle.TerrazzoHome,
      dark = false,
    )
}
