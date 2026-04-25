package ee.schimke.ha.client

import ee.schimke.ha.model.CardBytes
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardKey
import ee.schimke.ha.model.CardSize
import ee.schimke.ha.model.ClientProfile
import ee.schimke.ha.model.HaSnapshot

/**
 * One strategy for turning a [CardConfig] + [HaSnapshot] into encoded RemoteCompose bytes. The
 * runtime composes several of these into a priority chain — see [CardSource].
 *
 * Two impls today: an [AddonCardGenerator] talking to the HA add-on's `/v1/cards/...` endpoint, and
 * a `LocalCardGenerator` (in `rc-converter`) wrapping the existing in-process converters. New
 * generators (e.g. a debug one that draws a placeholder, or a profile-specific one) plug in by
 * implementing this interface.
 *
 * Failure semantics: [generate] **must return null** for any recoverable failure — bad network,
 * server-side 501/404, unsupported card type, timeout, etc. Throwing is reserved for programmer
 * errors (e.g. a precondition violation). `null` is what lets the chain fall through to the next
 * generator transparently; an exception bubbles up and is treated as a bug.
 */
interface CardGenerator {

  /**
   * Stable identifier, lower-case. Used in logs + the optional `cards.generator` audit column in
   * the offline store.
   */
  val name: String

  /**
   * Lower wins. Built-ins:
   * - [AddonCardGenerator] = 0 (try the offload first if available)
   * - LocalCardGenerator = 100 (always-available safety net)
   */
  val priority: Int

  /**
   * Quick "would I bother trying?" predicate. **No I/O.** Used by [CardSource] to skip generators
   * that obviously won't handle a card type, avoiding a wasted HTTP round-trip.
   *
   * The local generator returns true only for card types it has a registered converter for. The
   * add-on generator is optimistic — it returns true for everything and lets the server's response
   * decide.
   */
  fun supports(card: CardConfig, profile: ClientProfile): Boolean

  /**
   * Produce bytes for [card] at [size] for [profile].
   *
   * @return [CardBytes] on success, `null` on any recoverable failure (network, unsupported,
   *   timeout, …). See class doc.
   */
  suspend fun generate(
    key: CardKey,
    card: CardConfig,
    snapshot: HaSnapshot,
    size: CardSize,
    profile: ClientProfile,
  ): CardBytes?
}

/**
 * Result of a [CardSource.render] — bytes from some generator, or an "unsupported" placeholder when
 * no generator could produce them.
 *
 * The exact generator that won is exposed for telemetry / debug only. UI must not branch on it —
 * that would re-introduce the user-visible "local vs server" distinction we're explicitly trying to
 * hide.
 */
sealed interface CardRender {
  data class Bytes(val card: CardBytes, val generator: String) : CardRender

  data class Unsupported(val cardType: String) : CardRender
}

/**
 * Priority chain over [CardGenerator]s. The runtime constructs one of these per
 * [ee.schimke.ha.model.HaServer] at session-open time. The order isn't sticky after construction —
 * generators that fail at runtime just return null and the chain moves on, no rebuild needed.
 *
 * Local generator is always last so the chain never empties. If even the local generator can't
 * handle a card (custom card with no converter), [render] returns [CardRender.Unsupported].
 */
class CardSource(generators: List<CardGenerator>) {

  val generators: List<CardGenerator> = generators.sortedBy { it.priority }

  suspend fun render(
    key: CardKey,
    card: CardConfig,
    snapshot: HaSnapshot,
    size: CardSize,
    profile: ClientProfile,
  ): CardRender {
    for (gen in generators) {
      if (!gen.supports(card, profile)) continue
      val bytes = gen.generate(key, card, snapshot, size, profile) ?: continue
      return CardRender.Bytes(bytes, generator = gen.name)
    }
    return CardRender.Unsupported(card.type)
  }
}
