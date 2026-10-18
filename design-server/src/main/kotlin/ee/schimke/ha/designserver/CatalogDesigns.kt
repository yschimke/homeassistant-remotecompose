package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.design.DesignCatalog
import ee.schimke.ha.design.DesignCatalogEntry
import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.design.DesignExample
import ee.schimke.ha.design.DesignKey
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Website-owned publications. The UID is stored with every immutable revision, never synthesized.
 */
@Serializable
data class CatalogDesignRevision(
  val id: String,
  val revision: Long,
  val source: JsonObject,
  val entry: DesignEntry,
  val example: DesignExample?,
  val publishedBy: String,
  val publishedAtEpochMs: Long,
  /** Existing contract used to validate this publication; does not transfer ownership to code. */
  val contractDesign: String,
  /** Exact compiler release supplements catalog protocols whose nativeRuntimeId is "candidate". */
  val compilerVersion: String = "",
)

@Serializable
data class CatalogDesignIndex(
  val schema: Int = 1,
  val serial: Long = 0,
  val revisions: List<CatalogDesignRevision> = emptyList(),
)

/**
 * Single-writer durable store on a mounted volume. Blob first, atomic index last: failed writes
 * cannot replace the last good publication. History is retained, including replaced RC blobs. CAS
 * is checked again after the remote compiler returns, so concurrent publishers never win by
 * accident. Repository backups have no write path into this store.
 */
class CatalogDesignStore(private val dir: File) {
  private val json = Json { prettyPrint = true }
  private val file = File(dir, "publications.json")
  @Volatile
  private var index =
    if (file.isFile) json.decodeFromString(CatalogDesignIndex.serializer(), file.readText())
    else CatalogDesignIndex()

  init {
    index.revisions.forEach { revision ->
      val bytes = requireNotNull(blob(revision.entry.sha256)) { "Missing catalog blob" }
      check(bytes.size == revision.entry.bytes && Sha256.hex(bytes) == revision.entry.sha256) {
        "Corrupt catalog blob"
      }
    }
  }

  fun snapshot(): CatalogDesignIndex = index

  fun latest(): List<CatalogDesignRevision> =
    index.revisions.groupBy { it.id }.values.map { it.maxBy { r -> r.revision } }

  fun source(id: String): JsonObject? = latest().find { it.id == id }?.source

  fun blob(sha: String): ByteArray? =
    if (Regex("[0-9a-f]{64}").matches(sha))
      File(dir, "blobs/$sha.rc").takeIf { it.isFile }?.readBytes()
    else null

  @Synchronized
  fun publish(
    id: String,
    expectedRevision: Long,
    source: JsonObject,
    key: DesignKey,
    bytes: ByteArray,
    slots: Int,
    example: DesignExample?,
    actor: String,
    contractDesign: String,
    now: Long = System.currentTimeMillis(),
    compilerVersion: String = "",
  ): CatalogDesignRevision {
    require(Regex("[a-z0-9][a-z0-9-]{0,79}").matches(id)) { "Invalid design id" }
    val current = latest().find { it.id == id }
    check((current?.revision ?: 0) == expectedRevision) {
      "Publication changed; reload before publishing"
    }
    check(current == null || current.entry.key == key) {
      "Use a new publication ID for a different runtime key"
    }
    // One owner for a runtime key: another design must not silently replace it.
    check(latest().none { it.id != id && it.entry.key == key }) { "Runtime key already published" }
    val sha = Sha256.hex(bytes)
    val publication =
      CatalogDesignRevision(
        id,
        expectedRevision + 1,
        source,
        DesignEntry(key, sha, bytes.size, slots),
        example,
        actor,
        now,
        contractDesign,
        compilerVersion,
      )
    atomicWrite(File(dir, "blobs/$sha.rc"), bytes)
    val next = index.copy(serial = index.serial + 1, revisions = index.revisions + publication)
    atomicWrite(
      file,
      json.encodeToString(CatalogDesignIndex.serializer(), next).encodeToByteArray(),
    )
    index = next
    return publication
  }

  /** Overlay only published keys; all unconverted families stay available from the image. */
  fun overlay(base: DesignLibrary): DesignLibrary {
    val snapshot = index
    if (snapshot.revisions.isEmpty()) return base
    val publications =
      snapshot.revisions.groupBy { it.id }.values.map { it.maxBy { r -> r.revision } }
    val keys = publications.map { it.entry.key }.toSet()
    val serial = base.manifest.serial + snapshot.serial
    val manifest =
      base.manifest.copy(
        serial = serial,
        generatedAtEpochMs =
          maxOf(base.manifest.generatedAtEpochMs, publications.maxOf { it.publishedAtEpochMs }),
        expiresAtEpochMs =
          maxOf(
            base.manifest.expiresAtEpochMs,
            publications.maxOf { it.publishedAtEpochMs } + 14L * 24 * 60 * 60 * 1000,
          ),
        designs =
          base.manifest.designs.filterNot { it.key in keys } + publications.map { it.entry },
        signature = null,
      )
    val catalog =
      DesignCatalog(
        serial,
        manifest.generatedAtEpochMs,
        base.catalog?.entries.orEmpty().filterNot { it.key in keys } +
          publications.map { DesignCatalogEntry(it.id, it.entry.sha256, it.entry.key, it.example) },
      )
    return DesignLibrary.overlay(
      base,
      manifest,
      catalog,
      publications.associate { it.entry.sha256 to requireNotNull(blob(it.entry.sha256)) },
    )
  }

  private fun atomicWrite(target: File, bytes: ByteArray) {
    val parent = requireNotNull(target.parentFile).apply { mkdirs() }
    val temporary = File.createTempFile("catalog-", ".tmp", parent)
    try {
      FileOutputStream(temporary).use { stream ->
        stream.write(bytes)
        stream.fd.sync()
      }
      Files.move(
        temporary.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    } finally {
      temporary.delete()
    }
  }
}

/** Fail closed on unpinned sources: compilers must use the document's exact catalog/runtime. */
internal fun catalogSourceProblem(source: JsonObject): String? {
  val pin = source["catalogPin"] as? JsonObject ?: return "A catalog pin is required"
  for (name in listOf("systemId", "catalogRevision", "nativeRuntimeId", "capabilityDigest")) {
    val value = (pin[name] as? JsonPrimitive)?.content
    if (value.isNullOrBlank() || (name == "catalogRevision" && value == "candidate"))
      return "Resolve $name before publishing"
  }
  // Asset references are not a durable source unless bundled. Until the export API provides an
  // asset bundle, refuse them rather than publish a document a backup cannot reconstruct.
  if (source["assets"]?.let { it.toString() !in setOf("{}", "[]", "null") } == true)
    return "External assets must be bundled before publication"
  val nodes = source["nodes"] as? JsonObject
  if (nodes.isNullOrEmpty()) return "A design needs nodes"
  if (
    nodes.values.any { node ->
      val value = node as? JsonObject ?: return@any true
      val assets = value["assetBindings"]?.toString()
      val documentUrl = (value["properties"] as? JsonObject)?.get("documentUrl")
      (assets != null && assets !in setOf("{}", "null")) || documentUrl != null
    }
  )
    return "Asset bindings and external document references need bundled source assets"
  return null
}
