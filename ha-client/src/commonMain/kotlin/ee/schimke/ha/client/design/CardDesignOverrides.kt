package ee.schimke.ha.client.design

import ee.schimke.ha.model.CardConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * How one card, or every card of one type, departs from the user's design choice: a different card
 * style (uber theme id) and/or a different design variant. A null field keeps the choice's value.
 *
 * [designId] switches a card to one design ([ee.schimke.ha.design.DesignEntry.stableDesignId]): a
 * device design or a fork, which then draws the card whenever it has an entry for the card's key.
 * The style sheet sets it on one card ([CardDesignOverrides.byCard]) only.
 */
@Serializable
data class CardDesignOverride(
  val cardStyle: String? = null,
  val variant: String? = null,
  val designId: String? = null,
) {
  val isEmpty: Boolean
    get() = cardStyle == null && variant == null && designId == null
}

/** Where an override applies: the one card it was made on, or every card of that card's type. */
enum class CardDesignScope {
  Card,
  CardType,
}

/**
 * The user's per-card and per-card-type design overrides, kept on the device. A card's own override
 * wins field by field over its type's, and either wins over the user's design choice, so a Braun
 * dashboard can carry one LCD thermostat.
 *
 * A card is keyed by [cardFingerprint], a digest of its config: it survives the card moving around
 * the dashboard, and a card whose config is edited starts afresh, as an edited card is a new card.
 */
@Serializable
data class CardDesignOverrides(
  val byCard: Map<String, CardDesignOverride> = emptyMap(),
  val byType: Map<String, CardDesignOverride> = emptyMap(),
) {
  /** The override [card] renders with, merged from its own and its type's, or null for none. */
  fun forCard(card: CardConfig): CardDesignOverride? {
    val own = byCard[cardFingerprint(card)]
    val type = byType[card.type]
    val merged =
      CardDesignOverride(
        cardStyle = own?.cardStyle ?: type?.cardStyle,
        variant = own?.variant ?: type?.variant,
        designId = own?.designId ?: type?.designId,
      )
    return merged.takeUnless { it.isEmpty }
  }

  /** The override set at [scope] for [card] alone, ignoring the other scope. */
  fun at(scope: CardDesignScope, card: CardConfig): CardDesignOverride? =
    when (scope) {
      CardDesignScope.Card -> byCard[cardFingerprint(card)]
      CardDesignScope.CardType -> byType[card.type]
    }

  /** These overrides with [override] set at [scope] for [card]; null or empty clears it. */
  fun with(
    scope: CardDesignScope,
    card: CardConfig,
    override: CardDesignOverride?,
  ): CardDesignOverrides {
    fun Map<String, CardDesignOverride>.put(key: String) =
      if (override == null || override.isEmpty) this - key else this + (key to override)
    return when (scope) {
      CardDesignScope.Card -> copy(byCard = byCard.put(cardFingerprint(card)))
      CardDesignScope.CardType -> copy(byType = byType.put(card.type))
    }
  }

  /** Every card style an override names: the sync downloads designs in these too. */
  val cardStyles: Set<String>
    get() = (byCard.values + byType.values).mapNotNull { it.cardStyle }.toSet()

  /** Every variant an override names: the sync downloads these too. */
  val variants: Set<String>
    get() = (byCard.values + byType.values).mapNotNull { it.variant }.toSet()

  /** Every design an override names: the sync downloads its entries too. */
  val designIds: Set<String>
    get() = (byCard.values + byType.values).mapNotNull { it.designId }.toSet()

  fun encode(): String = json.encodeToString(serializer(), this)

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    /** Overrides read from [text], or none when it is missing or unreadable. */
    fun decode(text: String?): CardDesignOverrides =
      text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
        ?: CardDesignOverrides()
  }
}

/**
 * A stable digest of [card]'s config. Object keys are sorted first, so the same card read back from
 * Home Assistant with its fields in another order keeps its fingerprint.
 */
fun cardFingerprint(card: CardConfig): String =
  Sha256.hex(canonical(card.raw).toString().encodeToByteArray())

private fun canonical(element: JsonElement): JsonElement =
  when (element) {
    // Rebuilt in key order (a JsonObject keeps its map's order); common code has no sorted map.
    is JsonObject ->
      JsonObject(element.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
    is JsonArray -> JsonArray(element.map(::canonical))
    else -> element
  }
