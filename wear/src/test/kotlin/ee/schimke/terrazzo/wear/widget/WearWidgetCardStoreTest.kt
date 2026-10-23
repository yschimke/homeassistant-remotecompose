package ee.schimke.terrazzo.wear.widget

import ee.schimke.terrazzo.wear.sync.warmupCards
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.PinnedCard
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSection
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Which card each placed widget shows, chosen on the watch, and the cards the picker offers: the
 * ones the phone shows on the watch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearWidgetCardStoreTest {
  private val context = RuntimeEnvironment.getApplication()

  private fun summary(type: String, entity: String, title: String = "") =
    CardSummary(
      type = type,
      title = title,
      primaryEntityId = entity,
      rawJson = """{"type":"$type","entity":"$entity"}""",
    )

  private val kitchen = summary("tile", "light.kitchen", "Kitchen")
  private val office = summary("tile", "light.office", "Office")
  private val boiler = summary("thermostat", "climate.boiler", "Boiler")
  private val door = summary("tile", "lock.front_door", "Front door")

  @Before
  @After
  fun clearStore() {
    File(context.filesDir, "terrazzo/wear").deleteRecursively()
  }

  @Test
  fun aChoiceIsSavedReadAndRemovedPerWidget() {
    val store = WearWidgetCardStore(context)
    assertNull(store.read(7))
    store.write(7, WearWidgetCard("pin-kitchen", kitchen, chosenAtMs = 1))
    store.write(9, WearWidgetCard("pin-office", office, chosenAtMs = 2))
    // Another store over the same files reads them back: a widget render, or the app.
    val reread = WearWidgetCardStore(context)
    assertEquals(WearWidgetCard("pin-kitchen", kitchen, chosenAtMs = 1), reread.read(7))
    assertEquals(setOf(7, 9), reread.readAll().keys)
    // Choosing again replaces the widget's card.
    reread.write(7, WearWidgetCard("pin-office", office, chosenAtMs = 3))
    assertEquals("pin-office", store.read(7)?.cardKey)

    // The widget was removed from the watch: its card goes, the other widget's stays.
    store.remove(7)
    assertNull(store.read(7))
    assertEquals("pin-office", store.read(9)?.cardKey)

    // Signed out: none of the account's cards stay.
    store.clear()
    assertTrue(store.readAll().isEmpty())
  }

  private val pinned =
    PinnedCardSet(
      cards =
        listOf(
          PinnedCard(card = office, cardKey = "pin-office", orderIndex = 3),
          PinnedCard(card = kitchen, cardKey = "pin-kitchen", orderIndex = 0),
        )
    )
  private val sections =
    PinnedSectionSet(
      sections =
        listOf(
          PinnedSection(
            sectionKey = "heating",
            title = "Heating",
            // The kitchen is pinned on its own too: listed once, under its pin.
            cards = listOf(boiler, kitchen),
            orderIndex = 1,
          ),
          PinnedSection(
            sectionKey = "doors",
            title = "Doors",
            cards = listOf(door),
            orderIndex = 2,
          ),
        )
    )

  @Test
  fun thePickerListsThePinnedCardsInOrderThenTheSectionsCardsOnce() {
    val options = widgetCardOptions(pinned, sections)
    assertEquals(listOf(kitchen, office, boiler, door), options.map { it.card })
    assertEquals("pin-kitchen", options[0].cardKey)
    assertEquals("pin-office", options[1].cardKey)
    // Section cards are keyed by their section and config, stably across syncs.
    assertTrue(options[2].cardKey.startsWith("section:heating:"))
    assertEquals(options, widgetCardOptions(pinned.copy(), sections.copy()))
    assertEquals(options.map { it.cardKey }.distinct(), options.map { it.cardKey })
    // The same cards, in the same order, as the app warms.
    assertEquals(warmupCards(pinned, sections), options.map { it.card })

    assertTrue(widgetCardOptions(null, null).isEmpty())
    assertTrue(widgetCardOptions(PinnedCardSet(), PinnedSectionSet()).isEmpty())
  }

  @Test
  fun aWidgetDrawsThePhonesNewerCopyOrItsOwnOnceUnpinned() {
    val chosen = WearWidgetCard("pin-kitchen", kitchen)
    val renamed = kitchen.copy(title = "Kitchen lights")
    val repinned =
      PinnedCardSet(cards = listOf(PinnedCard(card = renamed, cardKey = "pin-kitchen")))
    assertEquals(renamed, currentWidgetCard(chosen, repinned, null))
    // Unpinned on the phone: the copy kept when it was chosen.
    assertEquals(kitchen, currentWidgetCard(chosen, PinnedCardSet(), PinnedSectionSet()))
    // A section card, found again by its key.
    val boilerOption = widgetCardOptions(pinned, sections).first { it.card == boiler }
    assertEquals(
      boiler,
      currentWidgetCard(WearWidgetCard(boilerOption.cardKey, CardSummary()), null, sections),
    )
  }
}
