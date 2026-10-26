package ee.schimke.terrazzo.wearsync

import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.PinnedCard
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSection
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import ee.schimke.terrazzo.wearsync.proto.WearPlacedWidget
import ee.schimke.terrazzo.wearsync.proto.WearPlacedWidgets
import ee.schimke.terrazzo.wearsync.proto.WearWidgetCardChoice
import ee.schimke.terrazzo.wearsync.proto.wearSectionCardKey
import ee.schimke.terrazzo.wearsync.proto.wearWidgetCardOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone chooses each watch widget's card, from the cards it sent the watch. */
class WatchWidgetCardsTest {
  private fun summary(type: String, entity: String, title: String) =
    CardSummary(
      type = type,
      title = title,
      primaryEntityId = entity,
      rawJson = """{"e":"$entity"}""",
    )

  private val kitchen = summary("tile", "light.kitchen", "Kitchen")
  private val forecast = summary("weather-forecast", "weather.home", "Forecast")
  private val boiler = summary("thermostat", "climate.boiler", "Boiler")
  private val heating = PinnedSection(sectionKey = "heat", cards = listOf(boiler), orderIndex = 2)

  private val options =
    wearWidgetCardOptions(
      PinnedCardSet(
        listOf(
          PinnedCard(card = forecast, cardKey = "pin-forecast", orderIndex = 0),
          PinnedCard(card = kitchen, cardKey = "pin-kitchen", orderIndex = 1),
        )
      ),
      PinnedSectionSet(listOf(heating)),
    )

  private fun placed(vararg widgets: WearPlacedWidget) =
    mapOf("watch" to WearPlacedWidgets(widgets.toList(), nodeName = "Pixel Watch"))

  @Test
  fun aNewWidgetGetsTheFirstCardItCanShow() {
    val choices =
      watchWidgetCardChoices(
        placed(WearPlacedWidget(1, large = false), WearPlacedWidget(2, large = true)),
        chosen = emptyMap(),
        options,
      )
    assertEquals(
      listOf(
        WearWidgetCardChoice("watch", 1, "pin-forecast", forecast),
        // A forecast only fills a small widget.
        WearWidgetCardChoice("watch", 2, "pin-kitchen", kitchen),
      ),
      choices,
    )
  }

  @Test
  fun thePhonesChoiceWinsThenTheWatchsOwn() {
    val boilerKey = wearSectionCardKey(heating, boiler)
    val choices =
      watchWidgetCardChoices(
        placed(
          WearPlacedWidget(1, large = false, cardKey = "pin-kitchen"),
          WearPlacedWidget(2, large = false, cardKey = "pin-kitchen"),
          WearPlacedWidget(3, large = true, cardKey = "pin-forecast"),
        ),
        chosen = mapOf(watchWidgetKey("watch", 1) to boilerKey),
        options,
      )
    assertEquals(
      listOf(
        WearWidgetCardChoice("watch", 1, boilerKey, boiler),
        // Carried over from the watch.
        WearWidgetCardChoice("watch", 2, "pin-kitchen", kitchen),
        // The watch's card doesn't fit a large widget: the first that does.
        WearWidgetCardChoice("watch", 3, "pin-kitchen", kitchen),
      ),
      choices,
    )
  }

  @Test
  fun anUnpinnedChoiceFallsBackAndNoCardsLeavesTheWatchAlone() {
    val gone = mapOf(watchWidgetKey("watch", 1) to "pin-unpinned")
    assertEquals(
      "pin-forecast",
      watchWidgetCardChoices(placed(WearPlacedWidget(1)), gone, options).single().cardKey,
    )
    assertTrue(watchWidgetCardChoices(placed(WearPlacedWidget(1)), gone, emptyList()).isEmpty())
  }

  @Test
  fun thePhoneListsEachWidgetWithTheCardsItCanShow() {
    val widgets =
      watchWidgets(placed(WearPlacedWidget(5, large = true)), chosen = emptyMap(), options)
    val widget = widgets.single()
    assertEquals("watch", widget.nodeId)
    assertEquals("Pixel Watch", widget.nodeName)
    assertEquals("pin-kitchen", widget.cardKey)
    assertEquals(listOf("Kitchen", "Boiler"), widget.options.map { it.title })
    assertNull(watchWidgets(placed(WearPlacedWidget(5)), emptyMap(), emptyList()).single().cardKey)
  }
}
