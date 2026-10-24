package ee.schimke.ha.rc

/**
 * How often cards on screen rebuild their document while they stay on screen — the cost of live
 * data that didn't arrive as a named binding (docs/architecture/live-card-updates.md). Counted by
 * [CachedCardPreview] each time a card's document key moves under it, per card type, by why (the
 * caller's reason for its own cache key, or [REASON_PICTURE]) and by what it cost ([Cost]).
 *
 * A card scrolling into view isn't a rebuild: only a second document for the same slot is. Kept as
 * a few counters per card type until a host [drain]s them (analytics, a debug screen, a test).
 */
object CardRebuilds {
  /** What a rebuild cost the device. */
  enum class Cost {
    /** The converter captured a new document: a composition and a serialisation. */
    Encode,
    /** New bytes for the player to decode (a cached document, or a template with new pictures). */
    Decode,
    /** The same bytes again, with new bindings: a template whose key moved. */
    None,
  }

  /** One card type's rebuilds for one reason at one cost. */
  data class Count(val cardType: String, val reason: String, val cost: Cost, val count: Int)

  /** The reason [CachedCardPreview] gives when only a picture's URL moved. */
  const val REASON_PICTURE: String = "picture"

  /** The reason when the caller gave none for its key change. */
  const val REASON_KEY: String = "key"

  private data class Bucket(val cardType: String, val reason: String, val cost: Cost)

  private val lock = PlatformLock()
  private val counts = HashMap<Bucket, Int>()

  fun record(cardType: String, reason: String, cost: Cost) {
    val bucket = Bucket(cardType, reason, cost)
    lock.withLock { counts[bucket] = (counts[bucket] ?: 0) + 1 }
    harcTrace(HarcTraceSections.REBUILD) {}
  }

  /** The counts so far, most frequent first, without clearing them. */
  fun peek(): List<Count> = lock.withLock { counts.toCounts() }

  /** The counts since the last drain, most frequent first; clears them. */
  fun drain(): List<Count> = lock.withLock { counts.toCounts().also { counts.clear() } }

  private fun Map<Bucket, Int>.toCounts(): List<Count> = map { (b, n) ->
    Count(b.cardType, b.reason, b.cost, n)
  }
    .filter { it.count > 0 }
    .sortedByDescending { it.count }
}
