package ee.schimke.ha.rc

import android.content.res.AssetManager
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignManifest
import java.io.InputStream
import kotlinx.serialization.json.Json

/**
 * The card designs an app ships in its assets ([CardDesignLibrary.shipped]): the library's
 * default-style designs, captured from the checked-in card designs when the app was built
 * (`:card-designs`' `ShippedCardDesignsTest` writes and checks them). A card the server has no
 * design for plays one of these instead of being captured on the device, so a card type whose
 * design was edited on the design server shows that edit offline too.
 *
 * The library has a design server's layout: `manifest.json` and `blobs/<sha256>.rc`. The manifest
 * is read once, on first use; a missing or unreadable one ships nothing. A blob that doesn't match
 * its manifest entry is never returned. Read-only: [write] does nothing.
 */
class ShippedDesignLibrary(
  /** Opens a file of the library by its path within it (`manifest.json`, `blobs/…`). */
  private val open: (path: String) -> InputStream
) : LocalDesignStore {
  /** The library in [assets] under [directory]. */
  constructor(
    assets: AssetManager,
    directory: String = DIRECTORY,
  ) : this({ path -> assets.open("$directory/$path") })

  private val json = Json { ignoreUnknownKeys = true }

  private val shas: Map<DesignKey, String> by lazy {
    runCatching {
        val manifest =
          open("manifest.json").use {
            json.decodeFromString(DesignManifest.serializer(), it.readBytes().decodeToString())
          }
        manifest.designs
          .filter { !it.revoked && it.device == null }
          .associate { it.key to it.sha256 }
      }
      .getOrDefault(emptyMap())
  }

  /** Every key this app ships a design for. */
  val keys: Set<DesignKey>
    get() = shas.keys

  override fun read(key: DesignKey): ByteArray? {
    val sha = shas[key] ?: return null
    return runCatching {
      open(DesignManifest.blobPath(sha)).use { it.readBytes() }
    }
      .getOrNull()
      ?.takeIf { Sha256.hex(it) == sha }
  }

  override fun write(key: DesignKey, bytes: ByteArray) {}

  companion object {
    /** Where an app's assets keep the shipped designs. */
    const val DIRECTORY = "card-designs"
  }
}
