@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the current capture profile admits the experimental `FlowLayout` op (op 240) used by
 * `RemoteFlowRow`. Defaults to `true`: the in-app dashboard captures with
 * `androidXExperimentalWrap` and the launcher widget with `widgetsProfile`, both of which OR in
 * `PROFILE_EXPERIMENTAL`.
 *
 * Set to `false` on surfaces whose capture writer is fixed to a stable profile — notably the Glance
 * Wear `WearWidgetDocument` capture path, which is owned by AndroidX and rejects experimental ops.
 * HA card components that use `RemoteFlowRow` (`RemoteHaGlance`, `RemoteHaGrid`) read this local
 * and degrade to a non-wrapping `RemoteRow` when it is `false`, so cards capture instead of
 * throwing.
 */
val LocalSupportsFlowLayout = staticCompositionLocalOf { true }

/**
 * Wrap [content] so HA card components know whether they can emit `RemoteFlowRow`. Pass `false` on
 * Glance Wear slot surfaces.
 */
@Composable
fun ProvideFlowLayoutSupport(enabled: Boolean, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalSupportsFlowLayout provides enabled, content = content)
}
