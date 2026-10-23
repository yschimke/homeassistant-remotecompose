package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.client.design.DesignedBlob
import ee.schimke.ha.client.design.DesignsById
import ee.schimke.ha.client.design.ResolvedDesigns
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignQuery
import ee.schimke.ha.design.DeviceMatch
import ee.schimke.terrazzo.BuildConfig
import java.io.File
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * What the design cache resolved to for one set of preferences, kept beside the cache so a cold
 * start reads one file instead of decoding the whole manifest (thousands of entries, megabytes of
 * JSON) and opening and hashing every blob it picks. That decode held the first frame for about 200
 * ms.
 *
 * Named for the preferences it was resolved with and the app version (protobuf encodes enums by
 * ordinal, so another version's file may not mean the same), and valid only while the manifest it
 * was resolved from is the one on disk: a sync or a revocation rewrites the manifest, which a stat
 * of its file notices. The blobs need no checking again; they were verified against the manifest
 * when stored.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object CardDesignSnapshot {
  private const val MANIFEST = "manifest.json"
  private const val PREFIX = "startup-"
  private const val SUFFIX = ".pb"

  @Serializable
  private class Snapshot(
    val manifestLength: Long,
    val manifestModified: Long,
    val generic: List<Generic> = emptyList(),
    val device: List<Device> = emptyList(),
    val designed: List<Designed> = emptyList(),
  )

  @Serializable private class Generic(val key: DesignKey, val bytes: ByteArray)

  @Serializable
  private class Device(val key: DesignKey, val match: DeviceMatch, val bytes: ByteArray)

  @Serializable
  private class Designed(
    val designId: String,
    val key: DesignKey,
    val match: DeviceMatch? = null,
    val bytes: ByteArray,
  )

  /** The manifest's identity now: take it before resolving, so a sync mid-resolve invalidates. */
  fun manifestStamp(dir: File): Pair<Long, Long> =
    File(dir, MANIFEST).let { it.length() to it.lastModified() }

  /**
   * The snapshot for [prefer] and [designIds], or null when there is none or the manifest has
   * changed since.
   */
  fun read(dir: File, prefer: List<DesignQuery>, designIds: Set<String>): ResolvedDesigns? {
    val file = file(dir, prefer, designIds)
    if (!file.isFile) return null
    val snapshot =
      runCatching { ProtoBuf.decodeFromByteArray(Snapshot.serializer(), file.readBytes()) }
        .getOrNull() ?: return null
    if (snapshot.manifestLength to snapshot.manifestModified != manifestStamp(dir)) return null
    return ResolvedDesigns(
      byKey = snapshot.generic.associate { it.key to it.bytes },
      deviceByKey = snapshot.device.groupBy({ it.key }) { it.match to it.bytes },
      byDesign =
        DesignsById(
          snapshot.designed.groupBy({ it.designId }) { DesignedBlob(it.key, it.match, it.bytes) }
        ),
    )
  }

  /**
   * Stores [designs], resolved for [prefer] and [designIds] from the manifest [stamp] identified;
   * drops others.
   */
  fun write(
    dir: File,
    prefer: List<DesignQuery>,
    designIds: Set<String>,
    stamp: Pair<Long, Long>,
    designs: ResolvedDesigns,
  ) {
    val snapshot =
      Snapshot(
        manifestLength = stamp.first,
        manifestModified = stamp.second,
        generic = designs.byKey.map { (key, bytes) -> Generic(key, bytes) },
        device =
          designs.deviceByKey.flatMap { (key, list) ->
            list.map { (match, bytes) -> Device(key, match, bytes) }
          },
        designed =
          designs.byDesign.entries.flatMap { (id, list) ->
            list.map { Designed(id, it.key, it.device, it.bytes) }
          },
      )
    val target = file(dir, prefer, designIds)
    dir.mkdirs()
    // A unique temp file and a rename, as FileDesignStore writes, so a reader never sees half.
    val tmp = File.createTempFile("${target.name}.", ".tmp", dir)
    try {
      tmp.writeBytes(ProtoBuf.encodeToByteArray(Snapshot.serializer(), snapshot))
      java.nio.file.Files.move(
        tmp.toPath(),
        target.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
      )
    } finally {
      tmp.delete()
    }
    // Only the current preferences' snapshot is worth its space.
    dir
      .listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) && f != target }
      ?.forEach(File::delete)
  }

  private fun file(dir: File, prefer: List<DesignQuery>, designIds: Set<String>): File {
    val queries = Json.encodeToString(ListSerializer(DesignQuery.serializer()), prefer)
    // Each id is a fork or device design name; sorted, so the set's order never splits a file.
    val ids = designIds.sorted().joinToString("\n")
    val id = Sha256.hex("${BuildConfig.VERSION_CODE}:$queries:$ids".encodeToByteArray())
    return File(dir, "$PREFIX$id$SUFFIX")
  }
}
