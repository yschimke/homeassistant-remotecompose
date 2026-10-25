package ee.schimke.terrazzo.shared

/** Settings storage. Values are immediately readable; implementations own persistence. */
internal interface AppStorage {
  val location: StorageLocation

  fun get(key: String): String?

  fun set(key: String, value: String?)
}

internal enum class StorageLocation {
  BROWSER,
  WASMO,
  /** A file on this computer: the desktop app. */
  DEVICE,
}

/** Browser operations are injected so the contract is testable without a browser. */
internal class LocalAppStorage(
  private val read: (String) -> String?,
  private val write: (String, String) -> Unit,
  private val remove: (String) -> Unit,
) : AppStorage {
  override val location = StorageLocation.BROWSER

  override fun get(key: String): String? = runCatching { read(key) }.getOrNull()

  override fun set(key: String, value: String?) {
    // Sandboxed frames and privacy settings can deny storage. Preserve the existing best effort
    // behaviour: denied storage must never prevent the app from opening or signing out.
    runCatching { if (value == null) remove(key) else write(key, value) }
  }
}
