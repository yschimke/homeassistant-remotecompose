package ee.schimke.ha.rc

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import ee.schimke.ha.rc.components.HaAction
import kotlinx.serialization.json.Json

/**
 * Receives [HaAction]s the player decoded from a RemoteCompose
 * `HostAction` named [HA_ACTION_NAME][ee.schimke.ha.rc.components.HA_ACTION_NAME].
 * Implementations decide what each variant means in the host app
 * (Intent.ACTION_VIEW for [HaAction.Url], an HA service call for
 * [HaAction.CallService] / [HaAction.Toggle], in-app navigation for
 * [HaAction.Navigate]).
 */
fun interface HaActionDispatcher {
    fun dispatch(action: HaAction)
}

/**
 * Dispatcher that drops every action on the floor. Used by previews,
 * tests, and any surface that hasn't wired a real handler — keeps the
 * playback path side-effect-free until somebody opts in.
 */
val NoOpHaActionDispatcher: HaActionDispatcher = HaActionDispatcher { /* no-op */ }

/**
 * Composition-scoped dispatcher. The dashboard / widget host wraps its
 * content in a `CompositionLocalProvider(LocalHaActionDispatcher provides …)`
 * so [CachedCardPreview] and [CardPlayer] can forward decoded actions
 * without each card having to wire a callback.
 */
val LocalHaActionDispatcher = staticCompositionLocalOf { NoOpHaActionDispatcher }

/**
 * Monotonic stamp that callers fold into per-card cache keys when they
 * need to bust the document cache out-of-band — e.g. demo mode bumps
 * this on every router action so cards baking state into bytes
 * (garage shutter, dial readouts) re-encode against the new snapshot
 * on the next composition.
 *
 * Default is 0L: production cards rely on RemoteCompose named bindings
 * to reflect new state without re-encoding, so most surfaces never
 * change this.
 */
val LocalCardCaptureEpoch = compositionLocalOf { 0L }

private val haActionJson = Json { ignoreUnknownKeys = true }

/**
 * Decode a `HostAction(name = "ha", value = …)` payload back into the
 * typed [HaAction] sealed hierarchy. Returns null if [value] isn't a
 * JSON string carrying our discriminator (host actions named `"ha"`
 * always are; this guards against bytes built by something else).
 *
 * Public so test surfaces in downstream modules can assert the
 * encode → decode contract; production callers should usually go
 * through [LocalHaActionDispatcher] instead.
 */
fun decodeHaAction(value: Any?): HaAction? {
    val text = value as? String ?: return null
    return runCatching {
        haActionJson.decodeFromString(HaAction.serializer(), text)
    }.getOrNull()
}
