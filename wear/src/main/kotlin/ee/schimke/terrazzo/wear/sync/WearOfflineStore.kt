package ee.schimke.terrazzo.wear.sync

import android.content.Context
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.LiveValues
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import ee.schimke.terrazzo.wearsync.proto.WearPicture
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import ee.schimke.terrazzo.wearsync.proto.WearWidgetSlots
import ee.schimke.terrazzo.wearsync.proto.decodeProto
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import java.io.File

/**
 * Watch-side offline store. The wear data layer is push-only (phone writes DataItems and ephemeral
 * MessageClient deltas), so on cold launch with the phone unreachable the [WearSyncRepository]
 * would have nothing to render. This store persists every proto blob the repository handles so the
 * watch boots from disk and overlays live data when the phone reconnects.
 *
 * Layout under the wear app's `filesDir/terrazzo/wear/`:
 * ```
 *   settings.pb        — WearSettings
 *   pinned.pb          — PinnedCardSet
 *   sections.pb        — PinnedSectionSet
 *   slots.pb           — WearWidgetSlots
 *   values.pb          — LiveValues  (last full snapshot or merged stream)
 *   dashboards/<file>  — DashboardData per `urlPath`
 *   pictures/<file>    — WearPicture per entity (Wear widget pictures)
 * ```
 *
 * Files are proto wire bytes (the same encoding the wear data layer itself uses), written via
 * tmp+rename so a kill mid-write doesn't leave half-written cache files.
 */
class WearOfflineStore(context: Context) {

  private val root: File = File(context.filesDir, "terrazzo/wear").apply { mkdirs() }
  private val dashboardsDir: File = File(root, "dashboards").apply { mkdirs() }
  private val picturesDir: File = File(root, "pictures").apply { mkdirs() }

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

  fun readSections(): PinnedSectionSet? =
    File(root, "sections.pb").readBytesOrNull()?.let { decodeProto<PinnedSectionSet>(it) }

  fun writeSections(value: PinnedSectionSet) {
    File(root, "sections.pb").atomicWriteBytes(encodeProto(value))
  }

  fun readSlots(): WearWidgetSlots? =
    File(root, "slots.pb").readBytesOrNull()?.let { decodeProto<WearWidgetSlots>(it) }

  fun writeSlots(value: WearWidgetSlots) {
    File(root, "slots.pb").atomicWriteBytes(encodeProto(value))
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

  /** [entityId]'s last synced picture, or null. */
  fun readPicture(entityId: String): WearPicture? =
    pictureFile(entityId).readBytesOrNull()?.let { decodeProto<WearPicture>(it) }

  fun writePicture(value: WearPicture) {
    if (value.entityId.isEmpty()) return
    pictureFile(value.entityId).atomicWriteBytes(encodeProto(value))
  }

  private fun pictureFile(entityId: String): File =
    File(picturesDir, entityId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".pb")

  fun writeDashboard(value: DashboardData) {
    val name = (value.urlPath.takeIf { it.isNotEmpty() }?.replace('/', '_') ?: "_default") + ".pb"
    File(dashboardsDir, name).atomicWriteBytes(encodeProto(value))
  }

  /**
   * Forgets what the phone sent at Data Layer [path], now that it deleted the item there (on
   * sign-out, or a dashboard Home Assistant no longer has). Returns what was forgotten, or null for
   * a path this store doesn't mirror.
   */
  fun forget(path: String): ForgottenItem? {
    val item = forgottenItemFor(path) ?: return null
    when (item) {
      ForgottenItem.Settings -> File(root, "settings.pb").delete()
      ForgottenItem.Pinned -> File(root, "pinned.pb").delete()
      ForgottenItem.Sections -> File(root, "sections.pb").delete()
      ForgottenItem.Slots -> File(root, "slots.pb").delete()
      ForgottenItem.Values -> File(root, "values.pb").delete()
      is ForgottenItem.Picture -> pictureFile(item.entityId).delete()
      // Named like the item's path: `writeDashboard` and `dashboardPath` encode alike.
      is ForgottenItem.Dashboard -> File(dashboardsDir, item.fileStem + ".pb").delete()
    }
    return item
  }

  /** Forgets every picture: the phone deleted them all. */
  fun forgetAllPictures() {
    picturesDir.listFiles().orEmpty().forEach { it.delete() }
  }
}

/** What a deleted Data Layer item was, by its path: see [WearOfflineStore.forget]. */
sealed interface ForgottenItem {
  data object Settings : ForgottenItem

  data object Pinned : ForgottenItem

  data object Sections : ForgottenItem

  data object Slots : ForgottenItem

  data object Values : ForgottenItem

  data class Picture(val entityId: String) : ForgottenItem

  /** [fileStem]: the path after the prefix, `_default` for the default dashboard. */
  data class Dashboard(val fileStem: String) : ForgottenItem {
    /** Whether [dashboard] is this one: its `urlPath` encodes to [fileStem]. */
    fun matches(dashboard: DashboardData): Boolean =
      WearSyncPaths.dashboardPath(dashboard.urlPath) == WearSyncPaths.DASHBOARD_PREFIX + fileStem
  }
}

/** The item at Data Layer [path], or null for a path the watch doesn't mirror. */
internal fun forgottenItemFor(path: String): ForgottenItem? =
  when {
    path == WearSyncPaths.SETTINGS -> ForgottenItem.Settings
    path == WearSyncPaths.PINNED -> ForgottenItem.Pinned
    path == WearSyncPaths.SECTIONS -> ForgottenItem.Sections
    path == WearSyncPaths.SLOTS -> ForgottenItem.Slots
    path == WearSyncPaths.VALUES -> ForgottenItem.Values
    path.startsWith(WearSyncPaths.PICTURE_PREFIX) ->
      path
        .removePrefix(WearSyncPaths.PICTURE_PREFIX)
        .takeIf { it.isNotEmpty() }
        ?.let { ForgottenItem.Picture(it) }
    path.startsWith(WearSyncPaths.DASHBOARD_PREFIX) ->
      path
        .removePrefix(WearSyncPaths.DASHBOARD_PREFIX)
        .takeIf { it.isNotEmpty() }
        ?.let { ForgottenItem.Dashboard(it) }
    else -> null
  }

private fun File.readBytesOrNull(): ByteArray? = runCatching {
  if (exists()) readBytes() else null
}
  .getOrNull()

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
