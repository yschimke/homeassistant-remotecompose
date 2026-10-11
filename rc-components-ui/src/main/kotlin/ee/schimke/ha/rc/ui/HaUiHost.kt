@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ProvideRemoteHaTheme
import ee.schimke.ha.rc.components.RemoteHaTheme
import ee.schimke.ha.rc.components.currentRemoteHaTheme

/**
 * Tier-2 embedding host: captures [content] (a Tier-1 `@RemoteComposable` call) into a `.rc`
 * document and plays it inside an ordinary Compose layout with the Compose Multiplatform player
 * (via [HaEmbeddedPlayer]). The host:
 *
 * 1. Picks the wrap-friendly experimental profile so the player's measured size flows from the
 *    surrounding Compose constraints (matching how `DashboardViewScreen` embeds cards in
 *    production).
 * 2. Re-provides the current Remote Material color scheme inside the capture scope so theme look-up
 *    works exactly as it does for top-level Tier-1 callers — Tier-2 callers can still wrap in
 *    [ProvideHaTheme] from outside.
 *
 * Action callbacks: the embedded RemoteCompose document delivers `HostAction`s at playback time.
 * This host doesn't expose the named-action stream, so apps that want HA service dispatch must drop
 * to Tier-1 (use `RemoteHa*` directly inside `CachedCardPreview`, which wires the dispatcher).
 * Tier-2 is for visual embedding.
 */
@Composable
internal fun HaUiHost(
  modifier: Modifier = Modifier,
  theme: RemoteHaTheme = currentRemoteHaTheme(),
  content: @Composable @RemoteComposable () -> Unit,
) {
  HaEmbeddedPlayer(modifier = modifier, profile = haUiEmbedProfile) {
    ProvideRemoteHaTheme(theme) { content() }
  }
}
