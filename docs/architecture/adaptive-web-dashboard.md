# Adaptive web dashboard and conversations

The shared web/desktop host treats Lovelace sections and headings as grouping hints.
It does not change or export a rewritten HA dashboard.

A heading starts a cluster whose cards stay beside their heading. A wide HA section
with several headings becomes several related clusters; a section without headings
remains a cluster. Legacy cards without headings remain independent. Actionable
headings remain cards. The view header renders above the clusters. Original card
addresses remain stable for design editing.

Tracks target at least 360 dp, use all available width and respect the view's
`max_columns` limit (default four). A staggered layout lets the next short cluster
start beside a taller neighbor. Mobile uses a single track in source order.
Nested grids and horizontal stacks reflow into cells at least 160 dp wide;
vertical stacks retain their order in a column. HA's fixed column/row spans are
not literal pixel-layout constraints in this adaptive projection.

Authenticated cards can use the same anonymous template resolver as the public demo.
Only type and anonymous domain slots leave the host; names, entity IDs, readings,
message bodies and authentication stay on the HA connection. A template must satisfy
the complete host-binding contract, or the native card remains. Input helpers keep
native controls. Style is selectable directly on the dashboard. Per-card studio
links appear only after selecting Customize.

Native markdown uses the existing `JinjaTemplate` subset and formats headings and
bold text. Unsupported templates show a concise explanation, never raw Jinja.
Unknown entity IDs are distinguished from a real entity whose state is unavailable.
This renderer cannot make missing HA entities available.

## MeshCore conversation card

A MeshCore message tile/entity becomes a conversation automatically when its entity
matches `binary_sensor.meshcore_*_messages`. A one-entity logbook card and explicit
`custom:terrazzo-chat` configuration work too:

```yaml
type: custom:terrazzo-chat
entity: binary_sensor.meshcore_radio_ch_0_messages
title: Public conversation
# Set this when more than one companion is installed:
entry_id: your_meshcore_config_entry_id
```

This is a Terrazzo native card configuration, not an installed HA JavaScript custom card.
The activity sensor's `Active` state is never presented as message content. The host
reads the selected entity's HA logbook for the last 24 hours, refreshing every 15 seconds
while the card is mounted and connected. It keeps the latest 100 distinct messages,
sorted chronologically, and ignores state-change entries and other conversations.
History stays in memory for this card, is destroyed on leaving/signing out, and is
not sent to the design server. Empty, unavailable and offline history have distinct states.

Replies require an explicit entry ID or exactly one recognized companion, plus a
channel index or a 12-character contact prefix in the selected entity's attributes.
No recipient is guessed from a friendly name or from an activity state. A message
is limited conservatively to 120 UTF-8 bytes; HA remains responsible for radio
limits and permissions. The send button issues one `meshcore.send_channel_message`
or `meshcore.send_message` call. A failed call retains the draft. An accepted call
is labelled delivery-unconfirmed: it does not prove an ACK or a successful radio send.
Outgoing messages enter history only when HA records them; there are no optimistic
fabricated chat bubbles. Sending is unavailable while disconnected or resolving an
ambiguous companion.

Sources: [MeshCore messaging](https://meshcore-dev.github.io/meshcore-ha/docs/ha/messaging/),
[service fields and delivery semantics](https://meshcore-dev.github.io/meshcore-ha/docs/ha/services/),
[HA logbook WebSocket API](https://github.com/home-assistant/core/blob/dev/homeassistant/components/logbook/websocket_api.py).

The production WASM workflow runs `scripts/test-adaptive-dashboard-browser.py` alongside
the existing authenticated and public demo suites. Its network and conversation data
are synthetic test fixtures. These checks do not establish compatibility with every
MeshCore version, a physical radio, or the user's private HA instance.
