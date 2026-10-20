package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DeviceMatch
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A design a person published from UI Builder: a manifest [entry] by `github:<login>`, private to
 * them until they [shared] it. [sourceDesignId] and [revision] say which UI Builder design and
 * revision it was exported from. A Wear widget design made from a card design names that design's
 * blob in [wearOf], and is shared, unshared and replaced with it.
 */
@Serializable
data class UserDesign(
  val entry: DesignEntry,
  val shared: Boolean = false,
  val sourceDesignId: String? = null,
  val revision: Long? = null,
  val publishedAtEpochMs: Long = 0,
  val wearOf: String? = null,
)

@Serializable
private data class UserDesignIndex(
  /** Bumped on every change, so a manifest that folds these in can raise its serial. */
  val revision: Long = 0,
  val designs: List<UserDesign> = emptyList(),
  /**
   * Bumped on every change to the shared set, so the manifest everyone sees can raise its serial
   * without counting private publishes. Null in an index written before it existed: read as
   * [revision], which every earlier anonymous serial was built from, so it never steps back.
   */
  val sharedRevision: Long? = null,
)

/**
 * The designs people published, on disk under [dir]: `index.json` and content-addressed
 * `blobs/<sha256>.rc`. The one writable store the server keeps; the library stays read-only. Writes
 * are serialized and replace the index atomically.
 */
class UserDesignStore(
  private val dir: File,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
  }
  private val indexFile = File(dir, "index.json")

  @Volatile
  private var index: UserDesignIndex =
    indexFile
      .takeIf { it.isFile }
      ?.let { json.decodeFromString(UserDesignIndex.serializer(), it.readText()) }
      ?: UserDesignIndex()

  /** Changes so far: folded into the served manifest's serial. */
  val revision: Long
    get() = index.revision

  /** Changes to the shared designs so far: folded into the anonymous manifest's serial. */
  val sharedRevision: Long
    get() = index.sharedRevision ?: index.revision

  /** [login]'s designs, newest first. */
  fun designs(login: String): List<UserDesign> =
    index.designs
      .filter { it.entry.author == authorOf(login) }
      .sortedByDescending { it.publishedAtEpochMs }

  /** Every design its author shared. */
  fun shared(): List<UserDesign> = index.designs.filter { it.shared }

  /** The entry of a published design whose blob is [sha256], or null. */
  fun entry(sha256: String): DesignEntry? =
    index.designs.firstOrNull { it.entry.sha256 == sha256 }?.entry

  /** A published blob's bytes, or null. */
  fun blob(sha256: String): ByteArray? {
    if (!SHA.matches(sha256)) return null
    return File(dir, "blobs/$sha256.rc").takeIf { it.isFile }?.readBytes()
  }

  /**
   * Publish [bytes] as [login]'s design for [key], replacing their earlier design for the same key.
   * Stays private, or keeps the replaced design's sharing.
   */
  @Synchronized
  fun publish(
    login: String,
    key: DesignKey,
    bytes: ByteArray,
    slots: Int,
    sourceDesignId: String?,
    revision: Long?,
    device: DeviceMatch? = null,
  ): UserDesign {
    val sha = Sha256.hex(bytes)
    writeAtomically(File(dir, "blobs/$sha.rc"), bytes)
    val author = authorOf(login)
    val replaced =
      index.designs.firstOrNull {
        it.entry.author == author && it.entry.key == key && it.entry.device == device
      }
    val design =
      UserDesign(
        entry =
          DesignEntry(
            key = key,
            sha256 = sha,
            bytes = bytes.size,
            slots = slots,
            author = author,
            device = device,
          ),
        shared = replaced?.shared ?: false,
        sourceDesignId = sourceDesignId,
        revision = revision,
        publishedAtEpochMs = clock(),
      )
    save(index.designs.filterNot { it === replaced } + design, sharedChanged = design.shared)
    return design
  }

  /**
   * Replace [login]'s Wear widget designs made from [card] (a design they just published) with
   * [wear], by container: the same key on the Wear widget surface, shared as [card] is. A container
   * missing from [wear] drops the earlier design for it, so a watch never draws a stale one.
   */
  @Synchronized
  fun publishWear(login: String, card: UserDesign, wear: Map<String, ByteArray>) {
    val author = authorOf(login)
    val keys = DesignKey.WEAR_CONTAINERS.map { card.entry.key.forWearWidget(it) }.toSet()
    val replaced =
      index.designs.filter {
        it.entry.author == author && it.entry.key in keys && it.entry.device == card.entry.device
      }
    if (replaced.isEmpty() && wear.isEmpty()) return
    val added = wear.map { (container, bytes) ->
      val sha = Sha256.hex(bytes)
      writeAtomically(File(dir, "blobs/$sha.rc"), bytes)
      card.copy(
        entry =
          card.entry.copy(
            key = card.entry.key.forWearWidget(container),
            sha256 = sha,
            bytes = bytes.size,
          ),
        wearOf = card.entry.sha256,
      )
    }
    save(
      index.designs.filterNot { it in replaced } + added,
      sharedChanged = card.shared || replaced.any { it.shared },
    )
  }

  /**
   * Share or unshare [login]'s design [sha256] together with its card design and the Wear widget
   * designs made from that (one group, whichever of them is picked); null when they have none by
   * that hash.
   */
  @Synchronized
  fun share(login: String, sha256: String, shared: Boolean): UserDesign? {
    val author = authorOf(login)
    val target =
      index.designs.firstOrNull { it.entry.author == author && it.entry.sha256 == sha256 }
        ?: return null
    val card = target.wearOf ?: target.entry.sha256
    fun inGroup(design: UserDesign) =
      design.entry.author == author && (design.entry.sha256 == card || design.wearOf == card)
    save(
      index.designs.map { if (inGroup(it)) it.copy(shared = shared) else it },
      sharedChanged = true,
    )
    return target.copy(shared = shared)
  }

  private fun save(designs: List<UserDesign>, sharedChanged: Boolean) {
    val next =
      UserDesignIndex(
        revision = index.revision + 1,
        designs = designs,
        sharedRevision = sharedRevision + if (sharedChanged) 1 else 0,
      )
    writeAtomically(
      indexFile,
      json.encodeToString(UserDesignIndex.serializer(), next).encodeToByteArray(),
    )
    index = next
  }

  private fun writeAtomically(target: File, bytes: ByteArray) {
    val parent = requireNotNull(target.parentFile).apply { mkdirs() }
    val tmp = File.createTempFile("${target.name}.", ".tmp", parent)
    try {
      tmp.writeBytes(bytes)
      Files.move(
        tmp.toPath(),
        target.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } finally {
      tmp.delete()
    }
  }

  companion object {
    private val SHA = Regex("[0-9a-f]{64}")

    fun authorOf(login: String): String = "github:$login"
  }
}
