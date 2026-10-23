package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.client.design.CardDesignOverride
import ee.schimke.ha.client.design.CardDesignScope
import ee.schimke.ha.client.design.cardFingerprint
import ee.schimke.ha.model.CardConfig
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class CardDesignOverrideStoreTest {
  private val dir: File = createTempDirectory("overrides").toFile()
  private val file = File(dir, "overrides.json")
  private val card =
    CardConfig(
      type = "tile",
      raw =
        buildJsonObject {
          put("type", JsonPrimitive("tile"))
          put("entity", JsonPrimitive("sensor.flow_temperature"))
        },
    )

  @AfterTest fun cleanUp() = dir.deleteRecursively().let {}

  @Test
  fun aChosenDesignIsKeptAcrossRestarts() = runBlocking {
    val chosen = CardDesignOverride(cardStyle = "lcd", designId = "user:boiler-dial")
    CardDesignOverrideStore(file).set(CardDesignScope.Card, card, chosen)
    assertEquals(chosen, CardDesignOverrideStore(file).overrides.value.forCard(card))
  }

  @Test
  fun aFileFromBeforeDesignIdsStillLoads() {
    file.writeText("""{"byCard":{"${cardFingerprint(card)}":{"cardStyle":"lcd"}}}""")
    assertEquals(
      CardDesignOverride(cardStyle = "lcd"),
      CardDesignOverrideStore(file).overrides.value.forCard(card),
    )
  }
}
