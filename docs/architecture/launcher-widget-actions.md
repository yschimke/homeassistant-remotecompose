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
- logging `remotecompose_metadata`, the fallback payload, and whether they match in
  `WidgetActionReceiver`.

`metadataForwarded=true` in logcat proves that the launcher supplied the dynamic metadata extra.
The receiver prefers that value and only falls back to the mirrored payload when the platform omits
it. It intentionally does not make Home Assistant writes yet: that needs a background-capable
session and a product decision about whether widget actions obey the dashboard's current read-only
default.

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
add the RemoteCompose metadata to the fill-in Intent. The separately logged fallback keeps the
prototype functional in that case.

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

Normal in-app playback and launcher widgets without a known length continue to emit individual
`AlarmKey`s. `AlarmKeypadCoordinator` supplies the idle-timeout heuristic for that path. AndroidX
Remote Compose has no delayed-action primitive with reliable background timing, so an
unknown-length PIN cannot remain entirely in the document and also submit after an idle timeout.

Other interactive controls dispatch immediately: tiles and toggles, thermostat/light/humidifier
steppers, shutter controls, media controls, to-do rows, links, and picture elements. For these, the
serialized action contains the requested operation/value while `documentValue` captures the
formatted entity state the document held when its metadata was last evaluated.

## Can an action run without a user tap?

Not through `Modifier.clickable`: those actions are evaluated only for a touch interaction.

RemoteCompose also has `RunAction`, which executes child actions during paint. In principle, a
time/value expression can cause repaints and a `RunAction` can then reach the same numeric callback.
That is not a reliable or safe background scheduler:

- paint cadence belongs to the launcher and can pause when the widget is off-screen;
- a dirty/time-driven document can repaint repeatedly, so an unguarded action can fire more than
  once;
- RemoteCompose state has no durable exactly-once delivery contract across host recreation; and
- activity/service starts still remain subject to Android background-execution policy.

Use Android scheduling for autonomous work instead. App-widget `updatePeriodMillis` has a 30-minute
minimum; use WorkManager/JobScheduler for other deferrable work, or an HA push/WebSocket owner with
an appropriate foreground lifecycle for near-real-time events. Update the widget after the work;
do not use rendering as the clock or event bus.

User taps are different: a widget interaction is recognized as direct user intent, so a broadcast
PendingIntent is an appropriate endpoint. Keep `BroadcastReceiver.onReceive` short and enqueue a
job for network work. Opening an activity from an autonomous/repaint-triggered callback is subject
to background-activity-launch restrictions and should generally become a notification instead.

## Platform references

- [RemoteViews draw-instruction action bridge](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/widget/RemoteViews.java#5813)
- [RemoteViews PendingIntent APIs](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/widget/RemoteViews.java#6752)
- [RemoteCompose wire format: numeric host action + metadata](https://android.googlesource.com/platform/frameworks/support/+/0ecddc8152eda57b806c09d55477d0c715d132fe/compose/remote/Documentation/RemoteComposeWireFormat.md.html)
- [Android widget update guidance](https://developer.android.com/develop/ui/views/appwidgets/advanced)
- [Broadcast receiver background-work guidance](https://developer.android.com/develop/background-work/background-tasks/broadcasts)
- [Background activity launch restrictions](https://developer.android.com/guide/components/activities/secure-bal)
