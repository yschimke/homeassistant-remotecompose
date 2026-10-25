package ee.schimke.terrazzo.dashboard

import android.content.Context
import android.util.Log
import ee.schimke.ha.client.design.DesignRepository
import ee.schimke.ha.design.DesignKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Fetches designs the server's manifest doesn't list, one key at a time, as cards need them: the
 * server answers with its library's design for the key or captures one ([DesignRepository.generate]
 * `POST <server>/generate`). Only the key leaves the device: a card's generation properties, never
 * its config or states. A stored design bumps [CardDesignSync.revision], so the dashboard reads the
 * cache again and the card switches from its converter's capture to the server's design.
 *
 * Each key is asked for once per cached manifest and server choice: fetched designs are kept beside
 * the manifest they were fetched under, so a new one ([manifestStored]) makes every key eligible
 * again, as does a restart or a change of choice. One the server has no design for (or that fails,
 * offline) is asked for again then too.
 */
object CardDesignGeneration {
  private const val TAG = "CardDesignGeneration"

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var choice: CardDesignChoice? = null
  private val requested = HashSet<DesignKey>()

  /** One repository (and HTTP client) for the choice's server, however many keys it fetches. */
  private var repository: DesignRepository? = null

  /**
   * A sync stored a new manifest: designs fetched beside the old one no longer resolve, and a key
   * asked for before any manifest was cached was never fetched.
   */
  fun manifestStored() = synchronized(this) { requested.clear() }

  /** Asks [choice]'s server for [key]'s design, unless it was already asked for. */
  fun request(context: Context, choice: CardDesignChoice, key: DesignKey) {
    if (!choice.enabled) return
    val app = context.applicationContext
    val repository =
      synchronized(this) {
        if (choice != this.choice) {
          this.choice = choice
          requested.clear()
          repository = null
        }
        if (!requested.add(key)) return
        repository ?: CardDesignSync.networkRepository(app, choice).also { repository = it }
      }
    scope.launch {
      val stored =
        runCatching {
          // Under the sync's lock, so a fetch by key never races a refresh's manifest write.
          CardDesignSync.refreshLock.withLock { repository.generate(key) }
        }
          .onFailure { Log.w(TAG, "fetching the design for $key failed", it) }
          .getOrNull() == true
      if (stored) CardDesignSync.stored()
    }
  }
}
