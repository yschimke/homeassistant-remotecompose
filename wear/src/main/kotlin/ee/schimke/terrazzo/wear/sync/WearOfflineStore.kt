package ee.schimke.terrazzo.wear.sync

import android.content.Context
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.LiveValues
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import java.io.File

/**
 * Watch-side offline store. The wear data layer is push-only (phone
 * writes DataItems and ephemeral MessageClient deltas), so on cold
 * launch with the phone unreachable the [WearSyncRepository] would have
 * nothing to render. This store persists every proto blob the
 * repository handles so the watch boots from disk and overlays live
 * data when the phone reconnects.
 *
 * Layout under the wear app's `filesDir/terrazzo/wear/`:
 *
 * ```
 *   settings.pb        — WearSettings
 *   pinned.pb          — PinnedCardSet
 *   values.pb          — LiveValues  (last full snapshot or merged stream)
 *   dashboards/<file>  — DashboardData per `urlPath`
 * ```
 *
 * Files are proto wire bytes (the same encoding the wear data layer
 * itself uses), written via tmp+rename so a kill mid-write doesn't
 * leave half-written cache files.
 */
class WearOfflineStore(context: Context) {

  private val root: File = File(context.filesDir, "terrazzo/wear").apply { mkdirs() }
  private val dashboardsDir: File = File(root, "dashboards").apply { mkdirs() }

  fun readSettings(): WearSettings? =
    File(root, "settings.pb").readBytesOrNull()?.let { decodeProto<WearSettings>(it) }

  fun writeSettings(value: WearSettings) {
    File(root, "settings.pb").atomicWriteBytes(encodeProto(value))
  }

  fun readPinned(): PinnedCardSet? =
    File(root, "pinned.pb").readBytesOrNull()?.let { decodeProto<PinnedCardSet>(it) }

  fun writePinned(value: PinnedCardSet) {
    File(root, "pinned.pb").atomicWriteBytes(encodeProto(value))
  }

  fun readValues(): LiveValues? =
    File(root, "values.pb").readBytesOrNull()?.let { decodeProto<LiveValues>(it) }

  fun writeValues(value: LiveValues) {
    File(root, "values.pb").atomicWriteBytes(encodeProto(value))
  }

  /** Read every persisted dashboard. Order is filesystem order; callers re-sort. */
  fun readAllDashboards(): List<DashboardData> =
    dashboardsDir
      .listFiles { f -> f.isFile && f.name.endsWith(".pb") }
      .orEmpty()
      .mapNotNull { f -> f.readBytesOrNull()?.let { decodeProto<DashboardData>(it) } }

  fun writeDashboard(value: DashboardData) {
    val name = (value.urlPath.takeIf { it.isNotEmpty() }?.replace('/', '_') ?: "_default") + ".pb"
    File(dashboardsDir, name).atomicWriteBytes(encodeProto(value))
  }
}

private fun File.readBytesOrNull(): ByteArray? =
  runCatching { if (exists()) readBytes() else null }.getOrNull()

private fun File.atomicWriteBytes(bytes: ByteArray) {
  runCatching {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(this)) {
      writeBytes(bytes)
      tmp.delete()
    }
  }
}
