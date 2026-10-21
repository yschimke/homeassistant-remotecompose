package ee.schimke.ha.rc

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.ThemeStyle
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A card scrolling back into view plays the same bytes it played before; the Compose player must
 * not decode them again on the main thread (a section's cards all compose in one frame).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DecodedDocumentCacheTest {

  @Test
  fun theSameBytesDecodeOnce() {
    val context = org.robolectric.RuntimeEnvironment.getApplication()
    val card =
      CardConfig(
        "tile",
        buildJsonObject {
          put("type", "tile")
          put("entity", "light.kitchen")
        },
      )
    val snapshot = HaSnapshot(states = mapOf("light.kitchen" to EntityState("light.kitchen", "on")))
    val key =
      assertNotNull(
        CardDesignTemplates.keyFor(card, snapshot, ThemeStyle.TerrazzoHome, dark = false)
      )
    val bytes = CardDesignLibrary.template(context, key)

    val first = decodedForComposePlayer(bytes)
    assertNotNull(first.document)
    assertSame(first, decodedForComposePlayer(bytes))
    // Another array is another document to the player (a picture copy), decoded afresh.
    assertNotSame(first, decodedForComposePlayer(bytes.copyOf()))
  }
}
