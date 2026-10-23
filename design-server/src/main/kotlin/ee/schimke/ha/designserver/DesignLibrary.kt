package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.edit.sizeReport
import ee.schimke.composeai.rcplayer.edit.validateFor
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.design.DesignCatalog
import ee.schimke.ha.design.DesignCatalogEntry
import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignManifest
import ee.schimke.ha.design.DesignProperties
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A card design library on disk, as `:card-designs:generateCardDesigns` writes it: `manifest.json`,
 * `blobs/<sha256>.rc`, and optionally `catalog.json`, `previews/` and `sources/<cardType>.uid` (the
 * checked-in UI Builder designs the migrated card types are drawn from).
 *
 * Loading checks every blob against the manifest (size and SHA-256), so the server never hands a
 * client a design it would reject. A library without a catalog still serves; its browser just has
 * no examples or previews.
 */
class DesignLibrary
private constructor(
  val dir: File,
  val manifest: DesignManifest,
  /** `manifest.json` exactly as published: its bytes are what clients hash and cache. */
  val manifestBytes: ByteArray,
  val catalog: DesignCatalog?,
  /**
   * Every blob's bytes, read and verified when the library loads. Templates are small (a few MB for
   * the whole library), and holding them means a loaded library never reads a blob a regenerated
   * one has since replaced on disk: it serves its own manifest's blobs until it is swapped out.
   */
  private val blobBytes: Map<String, ByteArray>,
  /**
   * The UI Builder card designs the library's migrated card types are drawn from, by card type
   * (server-owned-card-designs.md): what the library was built from, and what editing a library
   * design starts from.
   */
  val sources: Map<String, JsonObject>,
) {
  /** Every design, in manifest order, with its catalog entry when there is one. */
  val designs: List<LibraryDesign>

  private val byId: Map<String, LibraryDesign>
  private val blobs: Map<String, DesignEntry> = manifest.designs.associateBy { it.sha256 }
  private val decoded = ConcurrentHashMap<String, DecodedTemplate>()

  /** The manifest's ETag: its own SHA-256, so it changes exactly when the bytes do. */
  val manifestEtag: String = "\"${Sha256.hex(manifestBytes)}\""

  init {
    val catalogByKey = catalog?.entries.orEmpty().associateBy { it.key }
    designs =
      manifest.designs.map { entry ->
        val catalogEntry = catalogByKey[entry.key]
        LibraryDesign(
          id = catalogEntry?.id ?: idOf(entry.key),
          entry = entry,
          catalog = catalogEntry,
        )
      }
    byId = designs.associateBy { it.id }
  }

  fun design(id: String): LibraryDesign? = byId[id]

  /** The manifest entry whose blob is [sha256], or null when it isn't one of this library's. */
  fun entry(sha256: String): DesignEntry? = blobs[sha256]

  /** A blob's bytes, or null when [sha256] isn't one of this library's designs. */
  fun blob(sha256: String): ByteArray? = blobBytes[sha256]

  /** A catalog preview's bytes, or null when [path] isn't one the catalog names. */
  fun preview(path: String): ByteArray? {
    if (path !in previewPaths) return null
    return File(dir, path).takeIf { it.isFile }?.readBytes()
  }

  private val previewPaths: Set<String> =
    catalog
      ?.entries
      .orEmpty()
      .flatMap { listOfNotNull(it.previews.example, it.previews.template) }
      .toSet()

  /** What a template binds and how big it is: decoded once per blob. */
  fun decode(design: LibraryDesign): DecodedTemplate =
    decoded.getOrPut(design.entry.sha256) {
      val bytes = blob(design.entry.sha256) ?: return@getOrPut DecodedTemplate.Missing
      runCatching {
        val document = RcDocumentCodec.decode(bytes, RcWireLimits())
        DecodedTemplate(
          bindings =
            document.namedVariables
              .map { Binding(it.name.removePrefix(USER_PREFIX), typeName(it.type)) }
              .distinct()
              .sortedBy { it.name },
          operations = document.operations.size,
          sizeByOperation =
            document
              .sizeReport()
              .byOperation
              .entries
              .sortedByDescending { it.value }
              .associate { it.key to it.value },
          violations =
            document.validateFor(RcTargetProfiles.ANDROIDX_EXPERIMENTAL).map { it.message },
          error = null,
        )
      }
        .getOrElse { DecodedTemplate.Missing.copy(error = it.message ?: it.javaClass.simpleName) }
    }

  /** Designs with the same card type and shape as [design], in its other themes. */
  fun siblings(design: LibraryDesign): List<LibraryDesign> = designs.filter {
    it !== design &&
      it.key.cardType == design.key.cardType &&
      it.key.shape == design.key.shape &&
      it.key.surface == design.key.surface
  }

  val totalBlobBytes: Long = blobs.values.sumOf { it.bytes.toLong() }

  companion object {
    internal const val USER_PREFIX = "USER:"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Loads and verifies the library in [dir]; throws [IllegalStateException] naming the first
     * problem (a missing or mismatched blob, an unreadable manifest).
     */
    fun load(dir: File): DesignLibrary {
      val manifestFile = File(dir, MANIFEST)
      check(manifestFile.isFile) { "no $MANIFEST in $dir" }
      val manifestBytes = manifestFile.readBytes()
      val manifest =
        json.decodeFromString(DesignManifest.serializer(), manifestBytes.decodeToString())
      val blobBytes =
        manifest.designs
          .distinctBy { it.sha256 }
          .associate { entry ->
            val blob = File(dir, DesignManifest.blobPath(entry.sha256))
            check(blob.isFile) { "missing blob ${blob.name}" }
            val bytes = blob.readBytes()
            check(bytes.size == entry.bytes && Sha256.hex(bytes) == entry.sha256) {
              "blob ${blob.name} doesn't match the manifest"
            }
            entry.sha256 to bytes
          }
      val catalog =
        File(dir, DesignCatalog.PATH)
          .takeIf { it.isFile }
          ?.let { json.decodeFromString(DesignCatalog.serializer(), it.readText()) }
      val sources =
        File(dir, SOURCES)
          .listFiles { file -> file.extension == "uid" }
          .orEmpty()
          .sortedBy { it.name }
          .associate {
            it.nameWithoutExtension to json.parseToJsonElement(it.readText()).jsonObject
          }
      return DesignLibrary(dir, manifest, manifestBytes, catalog, blobBytes, sources)
    }

    const val MANIFEST = "manifest.json"

    /** Where the library keeps the checked-in card designs it was built from. */
    const val SOURCES = "sources"

    /** Id of a design with no catalog entry: the same short key hash the generator uses. */
    fun idOf(key: DesignKey): String =
      Sha256.hex(Json.encodeToString(DesignKey.serializer(), key).encodeToByteArray()).take(16)

    private fun typeName(type: Int): String =
      when (type) {
        RcNamedVariable.STRING_TYPE -> "text"
        RcNamedVariable.FLOAT_TYPE -> "float"
        RcNamedVariable.COLOR_TYPE -> "color"
        RcNamedVariable.IMAGE_TYPE -> "image"
        RcNamedVariable.INT_TYPE -> "int"
        RcNamedVariable.LONG_TYPE -> "long"
        RcNamedVariable.FLOAT_ARRAY_TYPE -> "float[]"
        else -> "type $type"
      }
  }
}

/** One design in a library. */
class LibraryDesign(val id: String, val entry: DesignEntry, val catalog: DesignCatalogEntry?) {
  val key: DesignKey
    get() = entry.key

  /** The generation properties [DesignKey.shape] encodes, by name. */
  val properties: Map<String, String> by lazy {
    runCatching { DesignProperties.parse(entry.key.shape).values }.getOrDefault(emptyMap())
  }
}

/** A named value the template reads: what a client must push for it to show a card's values. */
data class Binding(val name: String, val type: String)

data class DecodedTemplate(
  val bindings: List<Binding>,
  val operations: Int,
  val sizeByOperation: Map<String, Int>,
  /** Ops outside the mobile app's capture profile; empty for a valid template. */
  val violations: List<String>,
  val error: String?,
) {
  companion object {
    val Missing = DecodedTemplate(emptyList(), 0, emptyMap(), emptyList(), "blob missing")
  }
}
