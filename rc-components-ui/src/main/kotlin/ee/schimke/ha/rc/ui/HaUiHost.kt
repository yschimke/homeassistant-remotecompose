@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.tooling.preview.RemotePreview
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.LocalHaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme

/**
 * Tier-2 embedding host: wraps [content] (a Tier-1 `@RemoteComposable`
 * call) in a [RemotePreview] so the resulting `.rc` document is played
 * inside an ordinary Compose layout. The host:
 *
 * 1. Picks the wrap-friendly experimental profile so the player's
 *    measured size flows from the surrounding Compose constraints
 *    (matching how `DashboardViewScreen` / `WidgetInstallSheet` embed
 *    cards in production).
 * 2. Re-provides [LocalHaTheme] inside the capture scope so theme look-up
 *    works exactly as it does for top-level Tier-1 callers — Tier-2
 *    callers can still wrap in [ProvideHaTheme] from outside; the inner
 *    provide is a passthrough when no override is set.
 *
 * Action callbacks: the embedded RemoteCompose document delivers
 * `HostAction`s at playback time. `RemotePreview` doesn't expose the
 * named-action stream, so apps that want HA service dispatch must drop
 * to Tier-1 (use `RemoteHa*` directly inside their own
 * `RemoteDocumentPlayer(onNamedAction = …)`). Tier-2 is for
 * visual embedding.
 */
@Composable
internal fun HaUiHost(
    modifier: Modifier = Modifier,
    theme: HaTheme = LocalHaTheme.current,
    content: @Composable @RemoteComposable () -> Unit,
) {
    Box(modifier) {
        RemotePreview(profile = haUiEmbedProfile) {
            ProvideHaTheme(theme) { content() }
        }
    }
}
