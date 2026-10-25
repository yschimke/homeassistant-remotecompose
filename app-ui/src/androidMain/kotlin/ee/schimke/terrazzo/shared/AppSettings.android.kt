package ee.schimke.terrazzo.shared

/** The Android app keeps its own preferences; this shared UI only runs there in previews. */
internal actual fun appSettings(): AppStorage? = null
