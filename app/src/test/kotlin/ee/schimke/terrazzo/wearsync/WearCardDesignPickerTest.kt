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
import ee.schimke.terrazzo.core.pin.MobilePinnedCard
import ee.schimke.terrazzo.core.pin.MobilePinnedSection
import ee.schimke.terrazzo.core.pin.PinnedCardData
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

/** Which Wear widget design each pinned card is sent: the card's own design's, per container. */
class WearCardDesignPickerTest {
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
      wearCardDesigns(
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
      wearCardDesigns(
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
    assertTrue(wearCardDesigns(listOf("pin1" to card), snapshot, CardDesigns()).isEmpty())
    val huge = ByteArray(WEAR_DESIGN_MAX_BYTES + 1)
    assertTrue(
      wearCardDesigns(
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

  @Test
  fun everyPinnedCardIsSentItsWearDesignsWithNoWidgetSlots() {
    // Two pinned cards and a pinned section: no slot assigns any of them, and the watch may place
    // any as a widget.
    val tileJson = """{"type":"tile","entity":"light.kitchen"}"""
    val buttonJson = """{"type":"button","entity":"light.kitchen"}"""
    val sectionJson = """{"type":"entity","entity":"light.kitchen"}"""
    val widgetCards =
      wearWidgetCards(
        listOf(
          MobilePinnedCard(
            "pin1",
            "https://ha",
            "",
            PinnedCardData(type = "tile", rawJson = tileJson),
          ),
          MobilePinnedCard(
            "pin2",
            "https://ha",
            "",
            PinnedCardData(type = "button", rawJson = buttonJson),
          ),
          // Not a card: left out.
          MobilePinnedCard("pin3", "https://ha", "", PinnedCardData(rawJson = "nope")),
        ),
        listOf(
          MobilePinnedSection(
            key = "sec1",
            baseUrl = "https://ha",
            dashboardUrlPath = "",
            viewPath = "0",
            sectionIndex = 0,
            title = "Kitchen",
            cards = listOf(PinnedCardData(type = "entity", rawJson = sectionJson)),
          )
        ),
      )
    assertEquals(listOf("pin1", "pin2"), widgetCards.pinned.map { it.first })
    // Pictures follow every card the watch can place, the section's too.
    assertEquals(listOf("tile", "button", "entity"), widgetCards.all.map { it.type })

    val buttonKey =
      requireNotNull(
        CardDesignTemplates.keyFor(
          widgetCards.pinned[1].second,
          snapshot,
          choice.style,
          dark = true,
          cardStyle = choice.cardStyle,
        )
      )
    val tileSmall = byteArrayOf(1)
    val buttonLarge = byteArrayOf(2)
    val picked =
      wearCardDesigns(
        widgetCards.pinned,
        snapshot,
        designs(
          mapOf(
            key.forWearWidget(DesignKey.WEAR_SMALL) to tileSmall,
            buttonKey.forWearWidget(DesignKey.WEAR_LARGE) to buttonLarge,
          )
        ),
      )
    assertEquals(
      listOf(
        WearSlotDesign("pin1", DesignKey.WEAR_SMALL, Sha256.hex(tileSmall)),
        WearSlotDesign("pin2", DesignKey.WEAR_LARGE, Sha256.hex(buttonLarge)),
      ),
      picked.map { it.first },
    )
  }
}
