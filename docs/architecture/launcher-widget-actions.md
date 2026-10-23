# RemoteCompose launcher-widget actions

## What Android 37 supports

`RemoteViews(DrawInstructions)` installs a platform `RemoteComposePlayer`. Numeric RemoteCompose
host actions are bridged to normal `RemoteViews` click responses:

1. The document emits `HostActionMetadata(actionId, metadataTextId)` from a clickable component.
2. `RemoteComposePlayer.addIdActionListener` reports `(actionId, metadata)`.
3. `RemoteViews.SetDrawInstructionAction` finds the `SetOnClickResponse` whose view id equals
   `actionId` and sends its `PendingIntent`.

This is why `RemoteViews.setOnClickPendingIntent(actionId, pendingIntent)` works even though there is
no Android `View` resource with that id. The id addresses a RemoteCompose host action.

The Android release implementation copies `metadata` into the PendingIntent fill-in Intent under
the hard-coded key `remotecompose_metadata`. Because immutable PendingIntents ignore fill-in data,
the action token must be mutable. It remains safe enough for this experiment because its base
Intent explicitly targets the app's non-exported `WidgetActionReceiver`.

The current prototype makes metadata forwarding directly observable by:

- encoding every `HaAction` JSON payload as RemoteCompose dynamic metadata;
- assigning that payload a deterministic, non-zero numeric action id;
- collecting `actionId -> payload` during widget capture; and
- attaching one explicit mutable broadcast PendingIntent per id;
- mirroring the capture-time payload in a separate static extra as a compatibility fallback; and
- logging whether metadata was forwarded and whether it matches the static fallback.

`metadataForwarded=true` in logcat proves that the launcher supplied the dynamic metadata extra.
The receiver prefers that value and only falls back to the mirrored payload when the platform omits
it.

## Which taps run

`WidgetActionRunner` makes the write from the background, on the app's own session while it is
connected or a short-lived one minted from the stored refresh token. A pinned widget is a
deliberate control, so the dashboard's read-only default doesn't apply. What a tap does:

| Action | On a launcher widget |
| --- | --- |
| `Toggle`, `CallService` | Calls Home Assistant. An on/off result shows at once and is put back if the call fails; a call that names no entity (a script, a scene) shows once a live session reports it. |
| `AlarmIntent`, `AlarmPin`, `AlarmKey` | Paired per widget and entity by `WidgetAlarmPairing` into one `alarm_control_panel.alarm_<service>` call with the entered `code` (below). |
| `MoreInfo`, `Navigate` | Open the app (an activity `PendingIntent`: a receiver can't start one from the background). |
| `Url` | Opens the URL. |

Every write must be the action the app itself put on the widget's `PendingIntent`
(`trustedWidgetAction`). The one exception is a widget PIN: its digits are typed in the player
after capture, so the app's own payload is the same entity's `AlarmPin` with no digits, and only the
entity is held to it. Home Assistant checks the code; a wrong one fails the call, which is logged
without the code.

To observe a widget tap:

```shell
adb logcat -c
adb logcat -s WidgetActionReceiver:I
```

A launcher that forwards the metadata should produce a line resembling:

```text
Received widgetId=12 actionId=123 metadataForwarded=true actionMatchesFallback=true documentEntityId=light.kitchen documentValue=On documentTime=21:44:02 receivedAt=2026-08-15T20:44:03Z
```

`metadataForwarded=false` means the action arrived successfully, but that platform build did not
add the RemoteCompose metadata to the fill-in Intent. The static fallback keeps fixed actions
functional. A dynamically accumulated `AlarmPin` has an intentionally empty fallback which the
receiver rejects, because the completed PIN exists only in interaction-time metadata.

The metadata envelope carries four fields:

- the original serialized `HaAction`;
- the action's entity id, when it has one;
- the current formatted `<entityId>.state` value resolved from the RemoteCompose document; and
- the document's `HH:mm:ss` time value.

`documentTime` is the player's last evaluation time, not a guaranteed interaction timestamp. A
time expression used only by action metadata does not make the launcher repaint every second, so
it can be older than the tap. `receivedAt` is captured independently by the receiver to the nearest
second and is the authoritative delivery timestamp. This avoids imposing a permanent one-second
repaint cadence on every installed widget.

## Staged input audit

The alarm-panel keypad is the one two-stage interaction in the current card set. ARM/DISARM emits
an `AlarmIntent`. When `code_length` is configured from 1 through 9, launcher-widget capture uses
two document-local `MutableRemoteInt`s for the PIN and digit count. A
`RemoteStateLayout(digitCount, 0..codeLength - 1)` makes the final stage identifiable. Intermediate
digits and backspace use AndroidX `ValueChangeAction`s only; the final digit uses a
`CombinedAction` to emit one dynamic `AlarmPin(entityId, pin)` host action and reset the buffer.
Formatting the integer to `code_length` digits preserves leading zeroes in the metadata payload.
The receiver decodes that payload without writing the PIN to logcat or the persistent Logs screen.

Normal in-app playback and launcher widgets without a known length continue to emit individual
`AlarmKey`s. In the app, `AlarmKeypadCoordinator` supplies the idle-timeout heuristic for that path.

A widget's taps each arrive as their own broadcast, so `WidgetAlarmPairing` keeps the pair for 30 s
per widget and entity: an action that needs no code calls at once; a code entered first calls on
the button's tap; a button tapped first calls once a code of known length completes; an unknown
length calls on the button's tap after the keys (type the code, then press ARM). A code left longer
than the window is forgotten rather than sent with a later tap.

Other interactive controls dispatch immediately: tiles and toggles, thermostat/light/humidifier
steppers, shutter controls, media controls, to-do rows, links, and picture elements. For these, the
serialized action contains the requested operation/value while `documentValue` captures the
formatted entity state the document held when its metadata was last evaluated.

## Platform references

- [RemoteViews draw-instruction action bridge](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/widget/RemoteViews.java#5813)
- [RemoteViews PendingIntent APIs](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/widget/RemoteViews.java#6752)
- [RemoteCompose wire format: numeric host action + metadata](https://android.googlesource.com/platform/frameworks/support/+/0ecddc8152eda57b806c09d55477d0c715d132fe/compose/remote/Documentation/RemoteComposeWireFormat.md.html)
- [Broadcast receiver background-work guidance](https://developer.android.com/develop/background-work/background-tasks/broadcasts)
