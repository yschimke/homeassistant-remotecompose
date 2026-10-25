package ee.schimke.terrazzo.shared

/** The card style every dashboard draws in, one of [CARD_STYLES]' ids. */
internal const val CARD_STYLE_KEY = "terrazzo.card_style"

/** Where this host keeps the app's settings across launches; null keeps them for the session. */
internal expect fun appSettings(): AppStorage?

/** The stored card style, or `classic` when none is stored or it is no longer offered. */
internal fun storedCardStyle(settings: AppStorage?): String =
  settings?.get(CARD_STYLE_KEY)?.takeIf { stored -> CARD_STYLES.any { it.first == stored } }
    ?: "classic"
