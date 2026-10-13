# Card Studio in the mobile and desktop apps

The app can open a card in UI Builder or ask OpenRouter, Claude, OpenAI, or an
OpenAI-compatible agent endpoint to create or repair a draft. Android exposes it from
the card's long-press history screen; desktop exposes it below each dashboard card.

This version uses the hosted web editor in an Android WebView or desktop Chromium
view, with **Open in browser** available in both. Native CMP embedding is the preferred
next step. The shared provider client and draft gate are independent of the editor host.

## Preview data and drafts

`CardStudioClient` uses the app's selected card and current `HaSnapshot`. Desktop
continues to observe the live snapshot while the studio controls are open; Android
starts with the snapshot captured when opening the card, then fetches a new snapshot
for seeding, refreshing or opening a draft. Refresh also updates the mobile card preview.
The editor gets the values captured when the draft is seeded; it does not stream the
whole home or keep updating its saved example data.

`cardPreviewData` restricts the request to the card's entity slots, maps real entity ids
to positional ids, filters card configuration fields, and keeps only `friendly_name`,
`unit_of_measurement`, and `device_class` attributes. Home Assistant access tokens,
entity-picture URLs, unrelated entities, and their attributes are never inputs to this
client. Card display text and entity values remain personal data: the UI explains which
provider receives them before sending a request.

The design server adds three routes:

- `POST /api/studio/seed`: build a bound `ha-cards` document using the card schema and
  supplied values. Its random id identifies the original contract for later validation.
- `POST /api/studio/handoffs`: validate the draft against that original contract and
  return a capability link under `/studio/<token>`.
- `GET` / `POST /studio/<token>`: review the handoff and, after normal browser sign-in
  or edit-link authorization, create a fresh private UI Builder design. The builder
  performs its own catalog/schema validation. No existing design is overwritten.

Seed and handoff documents live in separate bounded, in-memory stores of 128 entries
each and expire after ten minutes. Responses containing drafts use `no-store`; the
handoff page escapes source text, sends no referrer, and contains no script. A handoff
is consumed only after a successful builder create/share operation. Opening a handoff
claims it atomically: a second concurrent request receives 409 instead of creating a
second design. Failures and cancellation release the claim so the person can retry.

The app accepts the existing design-library setting ending in `/designs/v1` as well
as the design server's base URL. The app offers **Refresh preview data** while keeping
layout edits. If a handoff reports an expired seed, the client fetches a new seed,
rebases the draft's id and initial values, validates that its binding types and catalog
are unchanged, and retries once. A changed contract requires resetting the draft;
repeated expiry never causes an unbounded retry loop. Missing routes, unavailable
builders and full preview stores produce actionable messages without echoing server
response bodies.

Both the design server and UI Builder must be deployed
with the new routes and the published `ha-cards` catalog before this app flow works.
The container includes `design-server/catalogs.json` and loads that catalog by default.
If you override `UI_BUILDER_ARGS`, retain `--catalogs-file /app/catalogs.json`
and `--ui-builder-catalogs remote-m3,ha-cards` alongside your other flags.
The authorized browser handoff resolves the catalog revision and native runtime from
UI Builder's v1 catalog listing before creating the design. The draft's initial
`candidate` revision is not a published revision. Missing catalogs or failed lookups
refuse creation and leave the handoff available for retry; layouts and bindings stay intact.
Anonymous seed and handoff JSON APIs permit cross-origin browser calls without cookies;
the browser's authorized editor routes remain same-origin.
The preview page's `form-action` policy permits its own origin and the configured
builder origin, so browsers can follow the authorized form's redirect to a separate host.

## Calling a provider

Provider keys are entered for this studio session, masked in the UI, and kept only in
memory. They are sent directly to the selected provider, never to Home Assistant or
the design server. Claude uses Messages with `x-api-key` and `anthropic-version`;
OpenAI and OpenRouter use their chat-completions APIs. A connected agent uses an
explicit HTTPS OpenAI-compatible chat-completions URL and its own bearer key.

The request includes the seeded document, its bindings and example values, and the
user's instruction. It asks for a complete JSON document. Requests have a 90-second
timeout and can be cancelled. Truncated replies and requests for tool execution are
rejected; malformed JSON diagnostics do not echo private preview text. This is a single-turn draft generator, not a resumable
coding-agent session or a general tool execution environment.

The local and server draft gates preserve the schema, catalog pin, id, state variables,
roots and existing live display bindings. They reject unknown bindings, unsupported
components, action payloads, missing children, cycles, repeated children and unreachable
nodes. The agent can rearrange the existing components and add text, rows and columns;
the full builder palette remains available to the person reviewing the draft.

Generated drafts do not modify the HA dashboard or install a new Remote Compose
template automatically. Export, runtime/profile validation and publishing into the
card library remain separate from creating a design. In particular, a provider must
not generate Kotlin that the mobile app compiles or executes.

## Native CMP and agent sessions

The CMP editor does **not** need to impersonate an agent harness. A design host owns the
document, its storage and its revision; an agent proposes edits. The host applies those
edits through the same reducer/protocol path a person uses.

The current `compose-ui-builder` checkout already has useful pieces:

- `ui-builder-host-jvm` exposes `OfflineUiBuilderSession.projectDocument` and
  `OfflineUiBuilderSessionView`, including local storage and an embedding API used by
  desktop and the IDE host.
- `JvmLocalAgentHost` and `LocalAgentRunner` discover local Codex, Claude Code and
  OpenCode executables, use the user's existing local login, remember provider session
  ids, and resume conversations. They currently send design snapshots as restricted
  chat context; they explicitly do not expose a hosted MCP editing session.
- The editor exposes `UiBuilderAgentHost`, `UiBuilderChatHost` and the protocol's
  revisioned submissions. A provider adapter can use those instead of emulating a CLI.

Native integration has two remaining prerequisites: the editor and its artwork/renderer
SDK currently target JVM and Wasm, with no Android target, and the JVM host's known
offline catalogs do not yet include `ha-cards`. Its platform services also use JVM
clipboard, files, process execution and rendering APIs. Adding Android is a real port,
not a dependency substitution.

A practical native sequence is:

1. Define an Android-capable host boundary in `compose-ui-builder`, with local document
   storage, clipboard/image/font platform implementations and an explicit `ha-cards`
   capability source. Keep JVM subprocess runners on desktop.
2. Embed the existing CMP editor on desktop and Android through that boundary, with the
   selected card's schema and values. Cache catalogs and drafts for local design work.
3. Adapt the direct provider clients to the editor's chat host. The application owns
   conversation history; provider requests need no fake coding-agent identity.
4. For multi-step editing, expose a small set of revisioned tools: inspect the current
   card, propose/apply a design operation, and render/check the preview. Implement a
   bounded tool loop in the host, or connect to a real remote agent that calls those
   tools. Reject stale revisions and keep a person's acceptance before publishing.
5. Optionally expose the same tools through a scoped local MCP bridge for a desktop
   CLI session. Mobile connects to a remote agent or calls providers directly; it does
   not try to spawn Claude Code or Codex processes.

This preserves one local design authority while allowing direct APIs, desktop CLI
sessions and hosted agents to use the same editing contract. Web remains an option
after native embedding is available.

## Desktop packaging

The web host uses Compose WebView 2.0.3 / KCEF. It initializes Chromium only when the
editor opens, keeps initialization alive across card navigation, and offers the system
browser if initialization fails. Chromium's installed files and browser profile live
in `~/.terrazzo/browser`. The first launch may download its native runtime; packaging
the Java application does not pre-install that runtime.

KCEF's original JogAmp 2.5.0 dependencies were not released to Maven Central. Both
JogAmp halves are pinned together at 2.6.0, with their official native-library
aggregates from Central, so no extra Maven repository is required. Desktop rendering
still needs a native smoke test on each supported platform before release.
