package ee.schimke.terrazzo.shared

import ee.schimke.ha.client.TerrazzoInfo
import ee.schimke.ha.client.TerrazzoIntegration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Settings tells "install or update the integration" apart from "update the app". */
class TerrazzoIntegrationStatusTest {
  @Test
  fun installedNamesItsVersion() {
    val status =
      terrazzoIntegrationStatus(TerrazzoIntegration.Installed(TerrazzoInfo(1, version = "0.1.99")))
    assertEquals("Installed, version 0.1.99.", status.text)
    assertFalse(status.offersInstall)
  }

  @Test
  fun missingOrOlderOffersTheIntegration() {
    assertTrue(terrazzoIntegrationStatus(TerrazzoIntegration.Absent).offersInstall)
    val older = terrazzoIntegrationStatus(TerrazzoIntegration.TooOld("0.1.90"))
    assertTrue(older.offersInstall)
    assertTrue(older.text.startsWith("Version 0.1.90 is older"), older.text)
  }

  @Test
  fun tooNewAsksForTheAppToBeUpdated() {
    val status = terrazzoIntegrationStatus(TerrazzoIntegration.Unsupported(minClient = 2))
    assertFalse(status.offersInstall)
    assertTrue("Update the app" in status.text, status.text)
  }
}
