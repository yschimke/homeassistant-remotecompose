# Editable message cards

The public [input controls v1 contract](https://github.com/yschimke/rc-players/blob/v2.16.1/docs/design/RC_INPUT_COMPONENTS.md)
is independent of Home Assistant. The player SDK also supports boolean toggles, numeric entry and
bounded ranges; published `rc-player-protocol` factories/fragments and `rc-creation-bridge` Compose
helpers let other authors create them. The HA **message schema** below deliberately binds text and
choice strings; it does not silently coerce new control types into HA service data.

This message implementation supports plain single-line text, multiline text, and single-choice
values. Selects use their declared options; `input_select`/`select` entity rows use HA's options,
and `input_text` rows use text fields. Numeric/date/password/rich-text/multi-select inputs are not
implemented. Unsupported/missing choices are reported instead of silently turning into free text.

The document carries versioned `rc:input/text:v1` / `rc:input/choice:v1` custom components, existing
typed properties and `TEXT_RETURN` bindings. Each edit updates both the remote text table and host
state. The host owns drafts by card/input key, so normal snapshot updates and document re-encodes
preserve entered text. Drafts live only for the card host and are not stored on disk; hosts discard them when replacing a card/account. Explicit Send dispatches the configured action through the existing authenticated service
route. Single-line Enter submits; multiline Enter inserts a newline. Quotes/newlines are JSON data,
not evaluated templates. The only substitution syntax is an exact `{{input.key}}` data value.

## Example

Configure a normal authenticated HA Remote Compose card:

```yaml
type: custom:remote-compose-card
card:
  type: custom:terrazzo-message
  title: Send to a room
  inputs:
    - key: recipient_type
      label: Recipient type
      type: choice
      value: room
      options: [room, contact]
    - key: channel
      label: Channel
      type: choice
      entity: input_select.message_channel
    - key: room
      label: Contact / Room
      type: choice
      value: Kitchen
      options: [Kitchen, Living room]
    - key: message
      label: Message
      type: multiline
  tap_action:
    action: perform-action
    perform_action: script.send_room_message
    data:
      recipient_type: '{{input.recipient_type}}'
      channel: '{{input.channel}}'
      room: '{{input.room}}'
      message: '{{input.message}}'
```

`script.send_room_message` is the user's configured HA script, not a bundled service. Its fields
can route to that installation's notification integration. The configured channel helper must exist
and expose options. Static options can be used instead. This example does not create helpers,
service endpoints or credentials, and no real message is sent by tests.

Android entity cards also replace available `input_text`, `input_select` and `select` rows with
these custom controls. The row's Send explicitly applies `set_value` / `select_option` to that
configured entity. Missing/unavailable entities keep their status row. The message card instead
holds all drafts locally until its configured action is sent; it does not mutate helper entities
on each keystroke.

## Capability and host policy

The CMP registry supports Android, desktop, Wasm and Apple; Swift/native UIKit and AppKit and the
TS browser player implement the same property/return contract in rc-players. Native iOS consumption
uses the additive input source files in its local source package. Old players/unknown configs cannot
be treated as editable just because they decode opcode 93. Install/register the renderer and
explicitly enable editing only in authenticated interactive hosts. Read-only and demo hosts do not
gain write access.

Wear foreground app message cards use the CMP player and a paired-phone request/ack bridge. The
phone matches the card against its published configuration, validates choices, rejects demo/no
sessions and runs the existing authenticated `HaSession.callService`. No token goes to the watch;
the phone’s Read/Write mode also gates this explicit capability (old phones default to read-only); there is no offline send queue or automatic retry. If an acknowledgement is lost, check the phone
before retrying. Physical Wear keyboard/radio behavior needs device validation.

Wear widgets and launcher widgets never register editable controls. Message-card widget captures
show “Open app to send a message” linked to the dashboard; helper rows remain state/open-info rows.
Capture also checks the writer’s supported operation set: profiles without `LAYOUT_CUSTOM` emit
the same alternative rather than attempting an unsupported experimental opcode.
This excludes widgets, not the foreground Wear app.

## Compatibility/release ordering

The consumer pins the Kotlin player artifacts to published **2.16.0** and the separately released
JS bundle to **2.16.1**. These versions share the input contract. The corrected browser distribution
includes the input API; 2.16.0's JS archive contained an older bundle and cannot render the form. CI and release builds resolve the same Maven Central artifacts,
without a player source checkout. Release/package the player before updating the consumer. The
native source additions ship with the consumer. Do not hand-edit release SPM URLs/checksums.

Rendered evidence and its test commands are in `docs/evidence/editable-message/README.md`.

Consumer surfaces in this change: Android CMP and gateway-rendered native iOS support message forms
and helper entity rows; common desktop/Wasm and the HA TS adapter support the explicit message form;
Wear app supports the message form with phone acknowledgement. The existing Wear summary rows and
common desktop/Wasm entity summaries remain summaries. The underlying CMP/Swift/TS input renderers
share the same contract wherever an interactive document host registers them.

Per-field `chrome: none` forwards the shared input contract's CHROME property (13) with NONE (1);
omitting it uses `default`. It removes generic caption, padding, editor background and border so
custom authors can wrap the component in their own themed container and visible label. The accessible
`label` remains required. The built-in message card has no per-field custom wrapper, so keep its
default chrome unless you provide that visible labeling/layout yourself. Styling cannot enable a
widget, demo or read-only host. Older players ignore the optional property; consume Kotlin 2.16.0
and JS 2.16.1 before relying on this appearance.

CI validates published Kotlin 2.16.0 dependencies and the 2.16.1 browser bundle. Local Kotlin development
can still use Gradle's `--include-build <rc-players-checkout>`; the embedded browser player always
comes from the declared JS distribution, so tests exercise what the consumer will ship.
