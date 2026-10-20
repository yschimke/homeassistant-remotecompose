package ee.schimke.terrazzo.shared

/** The Android app counts its cards in its own `CardUsage`, under the phone's setting. */
internal actual fun platformCardUsage(): PlatformCardUsage? = null
