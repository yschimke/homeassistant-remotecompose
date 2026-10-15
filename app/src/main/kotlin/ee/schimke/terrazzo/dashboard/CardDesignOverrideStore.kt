package ee.schimke.terrazzo.dashboard

import android.content.Context
import ee.schimke.ha.client.design.CardDesignOverride
import ee.schimke.ha.client.design.CardDesignOverrides
import ee.schimke.ha.client.design.CardDesignScope
import ee.schimke.ha.model.CardConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The user's card design overrides, on this device only: `files/terrazzo/designs/overrides.json`.
 * Designs hold no Home Assistant data, but a card fingerprint is a digest of a card's config, so
 * sign-out leaves the file alone as it does the design cache. Writes replace the file atomically.
 */
class CardDesignOverrideStore internal constructor(private val file: File) {
  private val _overrides = MutableStateFlow(read())

  /** One update and its write at a time, so the file always ends up with the newest overrides. */
  private val writes = Mutex()

  /** The current overrides; the dashboard and the design sync both follow it. */
  val overrides: StateFlow<CardDesignOverrides> = _overrides.asStateFlow()

  /** Sets [override] at [scope] for [card], or clears it when null or empty. */
  suspend fun set(scope: CardDesignScope, card: CardConfig, override: CardDesignOverride?) {
    writes.withLock {
      _overrides.update { it.with(scope, card, override) }
      val text = _overrides.value.encode()
      withContext(Dispatchers.IO) { write(text) }
    }
  }

  private fun read(): CardDesignOverrides =
    CardDesignOverrides.decode(runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull())

  private fun write(text: String) {
    val dir = requireNotNull(file.parentFile).apply { mkdirs() }
    val tmp = File.createTempFile("${file.name}.", ".tmp", dir)
    try {
      tmp.writeText(text)
      java.nio.file.Files.move(
        tmp.toPath(),
        file.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
      )
    } finally {
      tmp.delete()
    }
  }

  companion object {
    @Volatile private var instance: CardDesignOverrideStore? = null

    /** The app's one store. */
    fun of(context: Context): CardDesignOverrideStore =
      instance
        ?: synchronized(this) {
          instance
            ?: CardDesignOverrideStore(
                File(context.applicationContext.filesDir, "terrazzo/designs/overrides.json")
              )
              .also { instance = it }
        }
  }
}
