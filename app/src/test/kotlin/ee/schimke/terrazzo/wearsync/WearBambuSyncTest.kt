package ee.schimke.terrazzo.wearsync

import ee.schimke.ha.client.design.CardDesignOverride
import ee.schimke.ha.client.design.CardDesignOverrides
import ee.schimke.ha.client.design.CardDesignScope
import ee.schimke.ha.design.DeviceInfo
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.BambuLabPrintControlCardConverter
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.terrazzo.core.pin.MobilePinnedCard
import ee.schimke.terrazzo.core.pin.MobilePinnedSection
import ee.schimke.terrazzo.core.pin.PinnedCardData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A Bambu Lab card names its printer by device id, and only the phone has the device registry: the
 * watch's values carry entity states. With two printers, the card the watch gets must already say
 * which one it means, or its control pad has nothing to press.
 */
class WearBambuSyncTest {
  private fun printer(prefix: String) =
    listOf(
      EntityState("sensor.${prefix}_print_status", "running"),
      EntityState("sensor.${prefix}_print_progress", "40"),
      EntityState("binary_sensor.${prefix}_online", "on"),
      EntityState("button.${prefix}_pause_printing", "unknown"),
      EntityState("button.${prefix}_resume_printing", "unknown"),
      EntityState("button.${prefix}_stop_printing", "unknown"),
    )

  private val states = (printer("a1mini_sn1") + printer("p2s_sn2")).associateBy { it.entityId }

  /** The phone's snapshot: every entity's device, from the registry. */
  private val phone =
    HaSnapshot(
      states = states,
      devices =
        states.keys.associateWith { id ->
          val device = if ("p2s_sn2" in id) "dev-p2s" else "dev-a1"
          DeviceInfo(integration = "bambu_lab", deviceId = device)
        },
    )

  /** The watch's snapshot: what `LiveValues` carries, states only. */
  private val watch = HaSnapshot(states = states.mapValues { (id, s) -> EntityState(id, s.state) })

  private val controlJson =
    """{"type":"custom:ha-bambulab-print_control-card","printer":"dev-p2s"}"""

  private val pinned =
    PinnedCardData(
      type = "custom:ha-bambulab-print_control-card",
      title = "P2S",
      rawJson = controlJson,
    )

  /** How the watch reads a synced card (`CardWidget`'s decode). */
  private fun onWatch(card: PinnedCardData): CardConfig =
    watchJson.decodeFromString(CardConfig.serializer(), card.rawJson)

  private val watchJson = Json { ignoreUnknownKeys = true }

  private val converter = BambuLabPrintControlCardConverter()

  /** The control design's Pause press, as the template names it. */
  private val templatePause =
    HaAction.CallService(
      domain = "button",
      service = "press",
      entityId = "button.rcslot_pause_printing",
      serviceData = JsonObject(emptyMap()),
    )

  @Test
  fun deviceResolutionPreservesOriginalCardScopedStyleOnEverySurface() {
    val original = onWatch(pinned)
    val overrides =
      CardDesignOverrides()
        .with(CardDesignScope.CardType, original, CardDesignOverride(cardStyle = "braun"))
        .with(CardDesignScope.Card, original, CardDesignOverride(cardStyle = "lcd"))
    val pin = MobilePinnedCard("k", "https://ha", "", pinned)
    val section =
      MobilePinnedSection("s", "https://ha", "", "printing", 0, "Printers", listOf(pinned))
    val summaries =
      listOf(
        listOf(pin).toWearPinnedCards(overrides, snapshot = phone).cards.single().card,
        listOf(section)
          .toWearPinnedSections(overrides, snapshot = phone)
          .sections
          .single()
          .cards
          .single(),
        original.toWearDashboardSummary(phone, overrides),
      )
    for (summary in summaries) {
      assertEquals("lcd", summary.cardStyleOverride)
      val resolved = watchJson.decodeFromString(CardConfig.serializer(), summary.rawJson)
      assertEquals(JsonPrimitive("p2s_sn2"), resolved.raw["entity_prefix"])
    }
  }

  @Test
  fun theWatchCannotTellTheConfiguredPrinterFromTheCardAsPinned() {
    val card = onWatch(pinned)
    assertNull(converter.designProperties(card, watch))
    assertNull(converter.designAction(card, watch, 0, templatePause))
  }

  @Test
  fun aPinnedControlCardActsOnItsPrinterOnTheWatch() {
    val synced = listOf(MobilePinnedCard("k", "https://ha", "", pinned)).resolvedFor(phone)
    val card = onWatch(synced.single().card)
    assertEquals(JsonPrimitive("p2s_sn2"), card.raw["entity_prefix"])
    assertNotNull(converter.designProperties(card, watch))
    val press = converter.designAction(card, watch, 0, templatePause) as HaAction.CallService
    assertEquals("button.p2s_sn2_pause_printing", press.entityId)
  }

  @Test
  fun aPinnedSectionsCardsAreResolvedToo() {
    val section =
      MobilePinnedSection(
        key = "s",
        baseUrl = "https://ha",
        dashboardUrlPath = "",
        viewPath = "printing",
        sectionIndex = 0,
        title = "Printers",
        cards = listOf(pinned),
      )
    val card = onWatch(listOf(section).resolvedFor(phone).single().cards.single())
    val press = converter.designAction(card, watch, 0, templatePause) as HaAction.CallService
    assertEquals("button.p2s_sn2_pause_printing", press.entityId)
  }

  @Test
  fun otherCardsAndASnapshotWithoutTheRegistryAreLeftAlone() {
    assertSame(pinned, pinned.resolvedFor(watch))
    val tile = PinnedCardData(type = "tile", rawJson = """{"type":"tile","entity":"light.x"}""")
    assertSame(tile, tile.resolvedFor(phone))
  }
}
