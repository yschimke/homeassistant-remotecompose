package ee.schimke.terrazzo.shared

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the host is in developer mode (on Android, unlocked by tapping the version in Settings
 * seven times). Shared screens show their power-user parts only while it is on: card settings'
 * Lovelace JSON. Off unless a host provides it.
 */
val LocalDeveloperMode = staticCompositionLocalOf { false }
