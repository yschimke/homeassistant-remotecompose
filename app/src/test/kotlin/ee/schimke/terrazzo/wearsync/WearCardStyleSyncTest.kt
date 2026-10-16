package ee.schimke.terrazzo.wearsync

import ee.schimke.ha.client.design.CardDesignOverride
import ee.schimke.ha.client.design.CardDesignOverrides
import ee.schimke.ha.client.design.CardDesignScope
import ee.schimke.ha.model.CardConfig
import ee.schimke.terrazzo.core.pin.MobilePinnedCard
import ee.schimke.terrazzo.core.pin.PinnedCardData
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class WearCardStyleSyncTest {
  private val raw = """{"type":"sensor","entity":"sensor.temperature"}"""
  private val card = Json.decodeFromString(CardConfig.serializer(), raw)
  private val pin =
    MobilePinnedCard(
      key = "temperature",
      baseUrl = "demo",
      dashboardUrlPath = "home",
      card = PinnedCardData(type = "sensor", rawJson = raw),
    )

  private fun style(overrides: CardDesignOverrides) =
    listOf(pin).toWearPinnedCards(overrides, updatedAtMs = 1).cards.single().card.cardStyleOverride

  @Test
  fun explicitCardStyleWinsOverTypeAndClearingRestoresInheritance() {
    val type =
      CardDesignOverrides()
        .with(CardDesignScope.CardType, card, CardDesignOverride(cardStyle = "braun"))
    val own = type.with(CardDesignScope.Card, card, CardDesignOverride(cardStyle = "lcd"))
    assertEquals("lcd", style(own))
    assertEquals("braun", style(own.with(CardDesignScope.Card, card, null)))
    assertEquals("", style(CardDesignOverrides()))
  }

  @Test
  fun variantOnlyOverrideStillInheritsTheTypesStyle() {
    val overrides =
      CardDesignOverrides()
        .with(CardDesignScope.CardType, card, CardDesignOverride(cardStyle = "console"))
        .with(CardDesignScope.Card, card, CardDesignOverride(variant = "compact"))
    assertEquals("console", style(overrides))
    assertEquals(
      "",
      style(
        CardDesignOverrides()
          .with(CardDesignScope.Card, card, CardDesignOverride(variant = "compact"))
      ),
    )
  }

  @Test
  fun malformedPinnedConfigKeepsTheWatchDefault() {
    val broken = pin.copy(card = pin.card.copy(rawJson = "invalid"))
    assertEquals(
      "",
      listOf(broken).toWearPinnedCards(CardDesignOverrides()).cards.single().card.cardStyleOverride,
    )
  }

  @Test
  fun styleOverrideIsAnAdditiveWireField() {
    val summary = CardSummary(type = "sensor", rawJson = raw, cardStyleOverride = "lcd")
    assertEquals(summary, decodeProto<CardSummary>(encodeProto(summary)))
    assertEquals("sensor", decodeProto<OldCardSummary>(encodeProto(summary))?.type)
    assertEquals(
      "",
      decodeProto<CardSummary>(encodeProto(OldCardSummary(type = "sensor")))?.cardStyleOverride,
    )
  }

  @Serializable
  data class OldCardSummary(
    val type: String = "",
    val title: String = "",
    val primaryEntityId: String = "",
    val rawJson: String = "",
  )
}
