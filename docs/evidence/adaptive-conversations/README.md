# Adaptive sections and MeshCore conversations

| View | Before | After |
| --- | --- | --- |
| Desktop 1440 × 900 | [Flat grid](before-wall.png) | [Section clusters and conversation](after-wall.png) |
| Phone 390 × 844 | [Flat cards](before-phone.png) | [Compact nested metrics](after-phone.png), [conversation](chat-phone.png) |
| Console | Basic fallback only | [Published RC gauge in authenticated dashboard](console-wall.png) |
| Message helpers | Plain state labels | [Recipient/text controls and configured service button](helpers-wall.png) |

The dashboard is deliberately shaped like the reported MeshCore dashboard: one wide
HA section, repeated heading cards, nested four-column grids, telemetry and a message
activity entity. All titles, entity values, people and messages are synthetic test
fixtures. These images contain no data from the user's private HA instance.

Before captures use the previous production WASM build retained in this session;
after captures use the development build with the section-cluster renderer.
The same test dashboard and HA-shaped state fixtures are used on each side.
The Console gauge plays the published, SHA-pinned binary in `scripts/fixtures/demo`;
the resolver deliberately returns 404 for other card types in this fixture.

`scripts/test-adaptive-dashboard-browser.py` verifies that section headings and
content cluster in three readable desktop tracks; short groups pack beside the
conversation; phone metrics wrap in two columns; raw Jinja and per-card studio links
are absent from normal viewing; and Console can play the real gauge binary.
It reads entity-scoped HA logbook messages, refreshes a later message, sends exactly
one reply with the right channel, retains a refused draft, edits a select/text helper,
and invokes the dashboard's explicitly configured service action. A live HA update during
helper editing reproduces draft loss on the prior build and preserves the draft on the fixed build. The HA WebSocket
transport is mocked. No radio message was sent to a real network.

Native source values continue to arrive through the existing live state subscription.
Template bindings, explicit no-op/more-info actions, stable original card addresses,
conversation filtering and UTF-8 reply limits have JVM regression coverage.
The full authenticated and public demo browser suites remain enabled in production CI.

Limitations: no private HA instance or physical MeshCore radio was available here;
logbook availability, permissions and older integration versions need real-instance
verification. Replies acknowledged by HA are labelled delivery-unconfirmed.
Conversation history refreshes every 15 seconds while mounted. Known player inactive-
layout accessibility duplicates remain tracked in SCH-13; the test selects the active
layout and preserves the value/control assertions. A Kotlin incremental test compiler
failure was cleared with a clean compile task and tracked as SCH-16.

See [architecture and configuration](../../architecture/adaptive-web-dashboard.md).

The development full-demo run initially suffered a Chromium renderer crash when multiple
suites ran with a large idle Gradle heap and no usable temporary shared-memory space.
The unchanged serial retry passed after stopping Gradle; obsolete browser caches were
then removed to recover disk space. The cause is not proven; environment evidence is
tracked in SCH-18. No browser case or assertion was disabled.
