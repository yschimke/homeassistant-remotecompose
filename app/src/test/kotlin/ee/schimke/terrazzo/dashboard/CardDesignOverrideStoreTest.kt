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
  fun aBackgroundSkinInstallPreservesTheLatestStyleAndVariant() = runBlocking {
    val store = CardDesignOverrideStore(file)
    store.set(
      CardDesignScope.Card,
      card,
      CardDesignOverride(cardStyle = "lcd", variant = "compact"),
    )
    store.setDesign(cardFingerprint(card), "user:installed")
    assertEquals(
      CardDesignOverride(cardStyle = "lcd", variant = "compact", designId = "user:installed"),
      CardDesignOverrideStore(file).overrides.value.forCard(card),
    )
  }

  @Test
  fun aFileFromBeforeDesignIdsStillLoads() {
    file.writeText("""{"byCard":{"${cardFingerprint(card)}":{"cardStyle":"lcd"}}}""")
    assertEquals(
      CardDesignOverride(cardStyle = "lcd"),
      CardDesignOverrideStore(file).overrides.value.forCard(card),
    )
  }

  @Test
  fun sharedSelectionOverridesTheDesignWithoutPersistingAnotherUsersChoice() = runBlocking {
    val store = CardDesignOverrideStore(file)
    val owner = Any()
    store.set(
      CardDesignScope.Card,
      card,
      CardDesignOverride(cardStyle = "lcd", designId = "user:local"),
    )
    store.beginShared(owner)
    store.setShared(owner, mapOf(cardFingerprint(card) to "user:shared"))
    assertEquals(
      CardDesignOverride(cardStyle = "lcd", designId = "user:shared"),
      store.overrides.value.forCard(card),
    )
    store.setDesign(cardFingerprint(card), "user:new-local")
    assertEquals("user:shared", store.overrides.value.forCard(card)?.designId)
    store.setPublishedShared(owner, cardFingerprint(card), "user:published")
    assertEquals("user:published", store.overrides.value.forCard(card)?.designId)
    assertEquals(
      "user:new-local",
      CardDesignOverrideStore(file).overrides.value.forCard(card)?.designId,
    )
    store.endShared(owner)
    assertEquals("user:new-local", store.overrides.value.forCard(card)?.designId)
  }

  @Test
  fun anOldSessionCannotOverwriteOrClearTheNewUsersChoices() = runBlocking {
    val store = CardDesignOverrideStore(file)
    val old = Any()
    val current = Any()
    store.beginShared(old)
    store.beginShared(current)
    store.setShared(current, mapOf(cardFingerprint(card) to "user:new"))
    store.setShared(old, mapOf(cardFingerprint(card) to "user:old"))
    store.setPublishedShared(old, cardFingerprint(card), "user:old-published")
    store.endShared(old)
    assertEquals("user:new", store.overrides.value.forCard(card)?.designId)
  }
}
