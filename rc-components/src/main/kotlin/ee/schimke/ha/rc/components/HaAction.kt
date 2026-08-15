@file:Suppress("RestrictedApi", "INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package ee.schimke.ha.rc.components

import androidx.annotation.RestrictTo
import androidx.compose.remote.core.RemoteContext
import androidx.compose.remote.creation.compose.action.Action
import androidx.compose.remote.creation.compose.action.HostAction as RemoteHostAction
import androidx.compose.remote.creation.compose.action.hostAction
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import java.text.DecimalFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * User-intent abstractions mirroring Home Assistant's Lovelace tap/hold/ double-tap action model
 * (see `home-assistant/frontend` `src/data/lovelace/config/action.ts`).
 *
 * At render time these get serialized into a single RemoteCompose [HostAction] carrying a JSON
 * payload; the host intercepts the action via `RemoteDocumentPlayer(onNamedAction = …)` and calls
 * the corresponding HA service / navigates / fires a URL. In `:rc-converter` the playback path
 * (`CachedCardPreview`, `CardPlayer`) wires that callback to a composition-local
 * `HaActionDispatcher`, so cards themselves only need to emit actions and the host app supplies one
 * dispatcher for the whole dashboard.
 *
 * The action name on the wire is always [HA_ACTION_NAME]; the payload is the discriminated-union
 * JSON of the sealed subtype.
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
  @Serializable data class Toggle(val entityId: String) : HaAction

  @Serializable data class MoreInfo(val entityId: String) : HaAction

  @Serializable data class Navigate(val path: String) : HaAction

  @Serializable data class Url(val url: String) : HaAction

  /**
   * One press on an alarm-panel keypad. Each digit / `backspace` / `clear` tap fires its own
   * [AlarmKey] action; the host (typically via `AlarmKeypadCoordinator`) buffers the keys per
   * [entityId] and combines them with the most-recent [AlarmIntent] to call
   * `alarm_control_panel.alarm_*` with `code:` once it decides the user has finished entering an
   * attempt.
   *
   * [key] is one of "0".."9", `"backspace"`, or `"clear"`. The .rc document carries a separate host
   * action per key — there is no accumulated buffer in the document itself.
   */
  @Serializable data class AlarmKey(val entityId: String, val key: String) : HaAction

  /** A complete PIN accumulated by a launcher widget's Remote Compose document. */
  @Serializable data class AlarmPin(val entityId: String, val pin: String) : HaAction

  /**
   * User intent to arm or disarm an alarm panel. Carries the bare service suffix ("arm_away",
   * "arm_home", "disarm", …); the dispatcher decides when to actually call
   * `alarm_control_panel.alarm_<service>`, attaching the buffered keypad code from any in-flight
   * [AlarmKey]s.
   *
   * [codeLength] = 0 signals the entity does not require a code (e.g. `code_arm_required: false`)
   * so the dispatcher should fire immediately. A positive value lets the dispatcher auto-flush as
   * soon as the buffer fills. `null` means "length unknown — flush by idle-timeout heuristic".
   */
  @Serializable
  data class AlarmIntent(val entityId: String, val service: String, val codeLength: Int? = null) :
    HaAction

  @Serializable data object None : HaAction
}

/**
 * Host-side action name. The playback path in `:rc-converter` listens for `HostAction`s under this
 * name and decodes the JSON payload into an [HaAction] before forwarding to
 * `LocalHaActionDispatcher`.
 */
public const val HA_ACTION_NAME: String = "ha"

private val json = Json { ignoreUnknownKeys = true }

/**
 * Actions discovered while capturing one launcher-widget document, keyed by the numeric host-action
 * id that Android's `RemoteViews.DrawInstructions` player understands.
 *
 * RemoteCompose carries the payload as dynamic metadata too. Supporting platform builds forward it
 * as the `remotecompose_metadata` fill-in Intent extra. The widget provider also mirrors the
 * capture-time payload into the matching [android.app.PendingIntent], both as a compatibility
 * fallback and to make forwarded-vs-static metadata observable in the receiver.
 */
class WidgetActionRegistry(private val initialEntityStates: Map<String, String> = emptyMap()) {
  private val actions = linkedMapOf<Int, String>()

  val entries: Map<Int, String>
    get() = actions.toMap()

  internal fun register(payload: String): Int {
    // Host action 0 means "named action" in the AndroidX authoring API, so reserve it.
    var id = (payload.hashCode() and Int.MAX_VALUE).takeUnless { it == 0 } ?: 1
    while (true) {
      val previous = actions[id]
      if (previous == null || previous == payload) {
        actions[id] = payload
        return id
      }
      id = if (id == Int.MAX_VALUE) 1 else id + 1
    }
  }

  /**
   * Build metadata that is resolved by the launcher-side RemoteCompose player at interaction time.
   * The action JSON remains the first field so older receivers can still identify the action; the
   * current entity state and HH:mm:ss timestamp are dynamic RemoteCompose strings.
   */
  internal fun metadata(action: HaAction, payload: String): RemoteString {
    return metadata(action, payload.rs)
  }

  internal fun metadata(action: HaAction, payload: RemoteString): RemoteString {
    val entityId = action.entityIdForWidgetMetadata()
    val currentValue =
      if (entityId == null) {
        "".rs
      } else {
        LiveValues.state(entityId, initialEntityStates[entityId] ?: "Unavailable")
      }
    return payload +
      WIDGET_METADATA_MARKER +
      (entityId ?: "") +
      WIDGET_VALUE_MARKER +
      currentValue +
      WIDGET_TIME_MARKER +
      currentDocumentTime()
  }
}

/** Parsed view of the dynamic string delivered in `remotecompose_metadata`. */
data class WidgetActionMetadata(
  val actionPayload: String,
  val entityId: String? = null,
  val currentValue: String? = null,
  val documentTime: String? = null,
)

/** Decode both the enriched metadata envelope and the original raw-action payload. */
fun decodeWidgetActionMetadata(metadata: String?): WidgetActionMetadata? {
  if (metadata == null) return null
  if (!metadata.contains(WIDGET_METADATA_MARKER)) return WidgetActionMetadata(metadata)

  val actionPayload = metadata.substringBefore(WIDGET_METADATA_MARKER)
  val context = metadata.substringAfter(WIDGET_METADATA_MARKER)
  if (!context.contains(WIDGET_VALUE_MARKER) || !context.contains(WIDGET_TIME_MARKER)) {
    return WidgetActionMetadata(actionPayload)
  }

  val entityId = context.substringBefore(WIDGET_VALUE_MARKER).ifEmpty { null }
  val valueAndTime = context.substringAfter(WIDGET_VALUE_MARKER)
  return WidgetActionMetadata(
    actionPayload = actionPayload,
    entityId = entityId,
    currentValue = valueAndTime.substringBeforeLast(WIDGET_TIME_MARKER),
    documentTime = valueAndTime.substringAfterLast(WIDGET_TIME_MARKER),
  )
}

private const val WIDGET_METADATA_MARKER = "\n--RC-METADATA-V1--\n"
private const val WIDGET_VALUE_MARKER = "\n--RC-VALUE--\n"
private const val WIDGET_TIME_MARKER = "\n--RC-TIME--\n"

private fun HaAction.entityIdForWidgetMetadata(): String? =
  when (this) {
    is HaAction.CallService -> entityId
    is HaAction.Toggle -> entityId
    is HaAction.MoreInfo -> entityId
    is HaAction.AlarmKey -> entityId
    is HaAction.AlarmPin -> entityId
    is HaAction.AlarmIntent -> entityId
    is HaAction.Navigate,
    is HaAction.Url,
    HaAction.None -> null
  }

/** Local player time rounded down to a whole second. */
private fun currentDocumentTime(): RemoteString {
  val twoDigits = DecimalFormat("00")
  val hour = RemoteFloat(RemoteContext.FLOAT_TIME_IN_HR)
  val minute = RemoteFloat(RemoteContext.FLOAT_TIME_IN_MIN).rem(60f)
  val second = RemoteFloat(RemoteContext.FLOAT_TIME_IN_SEC).rem(60f)
  return hour.toRemoteString(twoDigits) +
    ":" +
    minute.toRemoteString(twoDigits) +
    ":" +
    second.toRemoteString(twoDigits)
}

private val LocalWidgetActionRegistry = staticCompositionLocalOf<WidgetActionRegistry?> { null }

@Composable
internal fun isWidgetActionCapture(): Boolean = LocalWidgetActionRegistry.current != null

/** Switch action encoding to numeric host actions for a launcher-widget capture. */
@Composable
fun ProvideWidgetActionRegistry(registry: WidgetActionRegistry, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalWidgetActionRegistry provides registry, content = content)
}

/**
 * Wrap an [HaAction] as a RemoteCompose [Action] ready to be plugged into
 * `Modifier.clickable(action)`. Returns null for [HaAction.None] so callers can treat "no action"
 * as "no interaction handler".
 */
@OptIn(ExperimentalStdlibApi::class)
@Suppress("RestrictedApi")
@RestrictTo(RestrictTo.Scope.LIBRARY)
fun HaAction.toNamedRemoteAction(): Action? {
  if (this is HaAction.None) return null
  val payload = json.encodeToString(HaAction.serializer(), this)
  return hostAction(HA_ACTION_NAME.rs, payload.rs)
}

/**
 * Encode an action for the current playback host.
 *
 * Normal in-app players keep using the named `ha` action. A launcher-widget capture uses a numeric
 * host action plus string metadata because the platform `RemoteViews` player only exposes numeric
 * actions to its PendingIntent bridge.
 */
@Composable
@OptIn(ExperimentalStdlibApi::class)
@Suppress("RestrictedApi")
@RestrictTo(RestrictTo.Scope.LIBRARY)
fun HaAction.toRemoteAction(): Action? {
  if (this is HaAction.None) return null
  val registry = LocalWidgetActionRegistry.current ?: return toNamedRemoteAction()
  val payload = json.encodeToString(HaAction.serializer(), this)
  val actionId = registry.register(payload)
  return RemoteHostAction(actionId, HA_ACTION_NAME.rs, registry.metadata(this, payload))
}

/**
 * Return a numeric host action only while capturing a launcher widget. [pin] is resolved by the
 * Remote Compose player when the final keypad digit is tapped, so the PendingIntent receives the
 * PIN accumulated inside the document rather than a capture-time value.
 */
@Composable
@Suppress("RestrictedApi")
internal fun widgetAlarmPinAction(entityId: String, pin: RemoteString): Action? {
  val registry = LocalWidgetActionRegistry.current ?: return null
  val placeholder = "__REMOTE_PIN__"
  // The actual PIN exists only in dynamic metadata. The empty static payload is a recognizable
  // placeholder that WidgetActionReceiver rejects rather than treating as a usable alarm code.
  val fallbackAction = HaAction.AlarmPin(entityId, "")
  val template =
    json.encodeToString(HaAction.serializer(), HaAction.AlarmPin(entityId, placeholder))
  val before = template.substringBefore(placeholder)
  val after = template.substringAfter(placeholder)
  val fallbackPayload = json.encodeToString(HaAction.serializer(), fallbackAction)
  val actionId = registry.register(fallbackPayload)
  val dynamicPayload = before.rs + pin + after
  return RemoteHostAction(
    actionId,
    HA_ACTION_NAME.rs,
    registry.metadata(fallbackAction, dynamicPayload),
  )
}
