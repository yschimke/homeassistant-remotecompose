package ee.schimke.terrazzo.wear.ui

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.MESSAGE_CARD_TYPE
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.PinnedCard
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSection
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The full-screen card: what its button runs and says, and the cards it pages through. */
class WearCardScreenTest {
  private val registry = defaultRegistry()

  private fun tile(entity: String, tap: String? = null) =
    CardConfig(
      "tile",
      buildJsonObject {
        put("entity", entity)
        tap?.let { put("tap_action", buildJsonObject { put("action", it) }) }
      },
    )

  private fun snapshot(vararg states: Pair<String, String>) =
    HaSnapshot(states = states.associate { (id, state) -> id to EntityState(id, state) })

  @Test
  fun aToggleCardsButtonRunsItsToggleAndSaysWhatItWillDo() {
    val on = snapshot("light.kitchen" to "on")
    val action = wearCardPrimaryAction(tile("light.kitchen"), on, registry)
    assertEquals(HaAction.Toggle("light.kitchen"), action)
    assertEquals("Turn off", wearCardActionLabel(action!!, on))
    assertEquals("Turn on", wearCardActionLabel(action, snapshot("light.kitchen" to "off")))
  }

  @Test
  fun aCardThatOnlyShowsSomethingHasNoButton() {
    // A sensor's tap is more-info: the card full screen is already that.
    assertNull(wearCardPrimaryAction(tile("sensor.temperature"), snapshot(), registry))
    assertNull(wearCardPrimaryAction(tile("light.kitchen", tap = "none"), snapshot(), registry))
  }

  @Test
  fun aServiceCallIsLabelledByItsService() {
    assertEquals(
      "Unlock",
      wearCardActionLabel(HaAction.CallService("lock", "unlock", "lock.front"), snapshot()),
    )
    assertEquals(
      "Set hvac mode",
      wearCardActionLabel(HaAction.CallService("climate", "set_hvac_mode"), snapshot()),
    )
  }

  @Test
  fun theDeckIsTheSectionsCardsOrThePinnedListsInOrderWithoutMessageCards() {
    fun summary(entity: String, type: String = "tile") =
      CardSummary(type = type, rawJson = """{"type":"$type","entity":"$entity"}""")
    val first = summary("light.a")
    val second = summary("light.b")
    val message = summary("x", MESSAGE_CARD_TYPE)
    val pinned =
      PinnedCardSet(
        cards =
          listOf(
            PinnedCard(card = second, cardKey = "b", orderIndex = 2),
            PinnedCard(card = message, cardKey = "m", orderIndex = 1),
            PinnedCard(card = first, cardKey = "a", orderIndex = 0),
          )
      )
    val sections =
      PinnedSectionSet(
        sections = listOf(PinnedSection(sectionKey = "kitchen", cards = listOf(second, first)))
      )
    assertEquals(listOf(first, second), fullScreenDeck(null, pinned, sections))
    assertEquals(listOf(second, first), fullScreenDeck("kitchen", pinned, sections))
    assertEquals(emptyList(), fullScreenDeck("gone", pinned, sections))
  }
}
