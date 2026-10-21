package ee.schimke.terrazzo.wearsync

import ee.schimke.ha.client.design.CardDesignOverride
import ee.schimke.ha.client.design.CardDesignOverrides
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.client.design.cardFingerprint
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.dashboard.CardDesignChoice
import ee.schimke.terrazzo.dashboard.CardDesigns
import ee.schimke.terrazzo.wearsync.proto.WearSlotDesign
import ee.schimke.terrazzo.wearsync.proto.WearSlotDesigns
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Which Wear widget design each slot card is sent: the card's own design's, per container. */
class WearSlotDesignPickerTest {
  private val card =
    (Json.parseToJsonElement("""{"type":"tile","entity":"light.kitchen"}""") as JsonObject).let {
      CardConfig(type = "tile", raw = it)
    }
  private val snapshot =
    HaSnapshot(
      states =
        mapOf(
          "light.kitchen" to
            EntityState(
              "light.kitchen",
              "on",
              JsonObject(mapOf("friendly_name" to JsonPrimitive("Kitchen"))),
            )
        )
    )
  private val choice =
    CardDesignChoice(
      serverUrl = "https://designs.example",
      style = ThemeStyle.TerrazzoHome,
      cardStyle = HaUberTheme.Classic,
      variant = "neon",
    )

  /** The card's dark design key on the phone: what its Wear designs are made from. */
  private val key =
    requireNotNull(
      CardDesignTemplates.keyFor(
        card,
        snapshot,
        choice.style,
        dark = true,
        cardStyle = choice.cardStyle,
      )
    )

  private fun designs(byKey: Map<DesignKey, ByteArray>, choice: CardDesignChoice = this.choice) =
    CardDesigns(byKey = byKey, variant = choice.variant, loadedFor = choice)

  @Test
  fun eachContainerGetsTheWearDesignOfTheCardsVariantThenDefault() {
    val small = byteArrayOf(1, 2)
    val large = byteArrayOf(3, 4)
    val picked =
      wearSlotDesigns(
        listOf("pin1" to card),
        snapshot,
        designs(
          mapOf(
            key.copy(variant = "neon").forWearWidget(DesignKey.WEAR_SMALL) to small,
            // No neon Large: the default variant's is next.
            key.forWearWidget(DesignKey.WEAR_LARGE) to large,
            // The card's phone design is not a Wear one.
            key.copy(variant = "neon") to byteArrayOf(9),
          )
        ),
      )
    assertEquals(
      listOf(
        WearSlotDesign("pin1", DesignKey.WEAR_SMALL, Sha256.hex(small)),
        WearSlotDesign("pin1", DesignKey.WEAR_LARGE, Sha256.hex(large)),
      ),
      picked.map { it.first },
    )
    assertContentEquals(small, picked[0].second)
  }

  @Test
  fun aCardsOwnVariantOverrideWins() {
    val garage = byteArrayOf(5)
    val overrides =
      CardDesignOverrides(
        byCard = mapOf(cardFingerprint(card) to CardDesignOverride(variant = "garage"))
      )
    val picked =
      wearSlotDesigns(
        listOf("pin1" to card),
        snapshot,
        designs(
          mapOf(
            key.copy(variant = "garage").forWearWidget(DesignKey.WEAR_SMALL) to garage,
            key.copy(variant = "neon").forWearWidget(DesignKey.WEAR_SMALL) to byteArrayOf(6),
          ),
          choice.copy(overrides = overrides),
        ),
      )
    assertEquals(listOf(Sha256.hex(garage)), picked.map { it.first.sha256 })
  }

  @Test
  fun nothingIsSentWithoutServerDesignsOrForTooBigAOne() {
    assertTrue(wearSlotDesigns(listOf("pin1" to card), snapshot, CardDesigns()).isEmpty())
    val huge = ByteArray(WEAR_DESIGN_MAX_BYTES + 1)
    assertTrue(
      wearSlotDesigns(
          listOf("pin1" to card),
          snapshot,
          designs(mapOf(key.forWearWidget(DesignKey.WEAR_SMALL) to huge)),
        )
        .isEmpty()
    )
  }

  @Test
  fun theIndexRoundTripsTheWire() {
    val index =
      WearSlotDesigns(listOf(WearSlotDesign("pin1", DesignKey.WEAR_LARGE, "ab")), updatedAtMs = 7)
    assertEquals(index, decodeProto<WearSlotDesigns>(encodeProto(index)))
  }
}
