@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the current capture profile admits the experimental `FlowLayout` op (op 240) used by
 * `RemoteFlowRow`. Defaults to `true`: the in-app dashboard captures with
 * `androidXExperimentalWrap`. Launcher captures override this from their restricted profile:
 * Android 16 cannot decode FlowLayout, even though AndroidX admits it as experimental.
 *
 * Set to `false` on surfaces whose capture writer is fixed to a stable profile — notably the Glance
 * Wear `WearWidgetDocument` capture path, which is owned by AndroidX and rejects experimental ops.
 * HA card components that use `RemoteFlowRow` (`RemoteHaGlance`, `RemoteHaGrid`) read this local
 * and use compatible rows when it is `false`, so cards capture instead of throwing.
 */
val LocalSupportsFlowLayout = staticCompositionLocalOf { true }

/** Wear's deliberately shortened entity strip, independent of the host's supported operations. */
val LocalCompactEntityLayout = staticCompositionLocalOf { false }

/**
 * Wrap [content] so HA card components know whether they can emit `RemoteFlowRow`. Pass `false` on
 * Glance Wear slot surfaces, with [compactEntities] to deliberately limit their entity strip.
 */
@Composable
fun ProvideFlowLayoutSupport(
  enabled: Boolean,
  compactEntities: Boolean = false,
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(
    LocalSupportsFlowLayout provides enabled,
    LocalCompactEntityLayout provides compactEntities,
    content = content,
  )
}
