package ee.schimke.terrazzo.shared

/**
 * The page's settings storage, read each time: [browserAppStorage] becomes Wasmo's once that loads.
 * In the Home Assistant panel it is the browser's, on Home Assistant's origin.
 */
internal actual fun appSettings(): AppStorage? =
  object : AppStorage {
    override val location: StorageLocation
      get() = browserAppStorage.location

    override fun get(key: String): String? = browserAppStorage.get(key)

    override fun set(key: String, value: String?) = browserAppStorage.set(key, value)
  }
