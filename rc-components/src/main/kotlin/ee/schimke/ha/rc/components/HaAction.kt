package ee.schimke.ha.rc.components

import androidx.annotation.RestrictTo
import androidx.compose.remote.creation.compose.action.Action
import androidx.compose.remote.creation.compose.action.HostAction
import androidx.compose.remote.creation.compose.state.rs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * User-intent abstractions mirroring Home Assistant's Lovelace tap/hold/
 * double-tap action model (see `home-assistant/frontend`
 * `src/data/lovelace/config/action.ts`).
 *
 * At render time these get serialized into a single RemoteCompose
 * [HostAction] carrying a JSON payload; the widget-side host intercepts
 * the action via `RemoteDocumentPlayer(onNamedAction = …)` and calls the
 * corresponding HA service / navigates / fires a URL.
 *
 * The action name on the wire is always [ACTION_NAME]; the payload is the
 * discriminated-union JSON of the sealed subtype.
 */
@Serializable
sealed interface HaAction {
    @Serializable
    data class CallService(
        val domain: String,
        val service: String,
        val entityId: String? = null,
        val serviceData: JsonObject = JsonObject(emptyMap()),
    ) : HaAction

    /** Convenience: HA service `<domain>.toggle` on a single entity. */
    @Serializable
    data class Toggle(val entityId: String) : HaAction

    @Serializable
    data class MoreInfo(val entityId: String) : HaAction

    @Serializable
    data class Navigate(val path: String) : HaAction

    @Serializable
    data class Url(val url: String) : HaAction

    @Serializable
    data object None : HaAction
}

/** Host-side action name; receive via `RemoteDocumentPlayer(onNamedAction = …)`. */
public const val HA_ACTION_NAME: String = "ha"

private val json = Json { ignoreUnknownKeys = true }

/**
 * Wrap an [HaAction] as a RemoteCompose [Action] ready to be plugged into
 * `Modifier.clickable(action)`. Returns null for [HaAction.None] so callers
 * can treat "no action" as "no click handler".
 */
@OptIn(ExperimentalStdlibApi::class)
@Suppress("RestrictedApi")
@RestrictTo(RestrictTo.Scope.LIBRARY)
fun HaAction.toRemoteAction(): Action? {
    if (this is HaAction.None) return null
    val payload = json.encodeToString(HaAction.serializer(), this)
    return HostAction(name = HA_ACTION_NAME.rs, value = payload.rs)
}
