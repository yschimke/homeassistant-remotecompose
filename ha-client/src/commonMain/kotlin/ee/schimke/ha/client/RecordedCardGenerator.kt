package ee.schimke.ha.client

import ee.schimke.ha.model.CardBytes
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardKey
import ee.schimke.ha.model.CardSize
import ee.schimke.ha.model.ClientProfile
import ee.schimke.ha.model.HaSnapshot

/**
 * Temporary in-process generator backed by captured Remote Compose documents.
 *
 * This keeps Apple hosts on the same [CardGenerator] / [CardSource] architecture that the eventual
 * Kotlin Multiplatform converter will use. It is intentionally snapshot-insensitive and therefore
 * suitable only for demos, tests, and UI development; production documents must come from a real
 * converter so baked values and data signatures are correct.
 */
class RecordedCardGenerator(
  documentsByCardType: Map<String, ByteArray>,
  override val priority: Int = 100,
) : CardGenerator {
  private val documents = documentsByCardType.mapValues { (_, bytes) -> bytes.copyOf() }

  override val name: String = "recorded"

  override fun supports(card: CardConfig, profile: ClientProfile): Boolean =
    documents.containsKey(card.type)

  override suspend fun generate(
    key: CardKey,
    card: CardConfig,
    snapshot: HaSnapshot,
    size: CardSize,
    profile: ClientProfile,
  ): CardBytes? {
    val bytes = documents[card.type] ?: return null
    return CardBytes(bytes.copyOf(), widthPx = size.widthPx, heightPx = size.heightPx)
  }
}
