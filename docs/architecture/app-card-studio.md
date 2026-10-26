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
UI Builder 3.138.0 or newer passes the served catalog to its packaged renderer; older
bundles can export an "Unsupported component" image for `ha-cards/dashboard-card`.
Republish `ha-cards` when deploying the updated image: dashboard and launcher roots
use the Scaffold canvas adapter, whose `content` slot matches their declared slot.
Box reads `children` and would leave those roots empty.
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

For a matching staged or published catalog, set `STUDIO_RENDER_SMOKE_REQUIRED=1`
alongside `STUDIO_SERVER_URL` and `STUDIO_OPERATOR_TOKEN` when running
`node --test design-server/test/studio-browser.test.mjs`. This exports the same card
with two different light states and compares decoded pixels. A blank canvas or a
successful PNG containing an unsupported-component message fails the check.
The default CI handoff test uses the shared published catalog, which can still carry
the previous main policy while a catalog change is under review.

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
template automatically. In particular, a provider must not generate Kotlin that the
mobile app compiles or executes.

## Using your design on the card

Once a design opened from the studio is saved in UI Builder, **Publish privately and
use it** (Android) puts it on the card, for that person alone:

1. The app reads the design server's device token from Settings (minted on the
   server's `/my` page). Without one the action explains where to get it.
2. `GET /api/my/studio` lists the UI Builder designs the server opened for that
   person, newest first, each with the card type it was seeded for. The server
   records a design when the signed-in browser opens a studio handoff (or seeds a
   schema or fork); UI Builder itself records the server as the owner.
3. `POST /api/my/designs` publishes this card's design: the newest one opened from a
   handoff this card's studio opened (the server records each design with its handoff
   token), else the only one of its card type, never a guess between several. It
   publishes under a variant of its own (`mine-` and a hash of its UI Builder id), so
   designs for two cards with the same key both stay, keyed by the card's design key
   as the dashboard draws it (keyed as the skin picker keys it). The server publishes it for that card in **each theme of its
   card style** (light and dark; Classic in every app palette), under one design id
   (`user:<UI Builder id>`), so the card keeps it when the dashboard changes mode or
   palette. Picking another card style needs another publish. Republishing replaces it.
4. The app hands that id to `SkinInstallWorker`, which downloads the private
   manifest's assets with the device token and switches this card to the skin, as
   the card's style sheet does. It finishes even if the studio closes.

Publishing is refused unless the person may edit the design: the server made it for
them, or UI Builder lists them as its owner or an editor (`getDesignAccess`). The
server reads designs with UI Builder's operator token, which opens every design, so
without this check one person could publish another's private design, photos and
all, into their own manifest.

### Building the template

UI Builder's `export.rc` is a build feature of compose-preview-server, and the hosted
builder doesn't serve it. When the export isn't available the design server builds
the template itself (`UiBuilderDesignCompiler`, `:card-designs`), capturing it on the
JVM exactly as the library is captured. It draws:

- `layout/box`, `layout/column`, `layout/row`, and the card containers
  (`ha-cards/dashboard-card`, `ha-cards/remote-card`, `ha-cards/dashboard-section`);
- `asset/image`, with the design's uploaded pictures baked in (as PNG) under names
  of the design's own (`asset_<key>`), within a byte budget for the target
  (`imageBudget`): 512 KB for a phone card, 128 KB for the Wear app, about 50 KB for a
  launcher or Wear widget. The first fit wins, best first: full size in full colour,
  then 5 and 4 bits a channel (a photo's PNG roughly halves each step, with little to
  see), then smaller sizes, all pictures scaled alike. A photo card of two pictures
  is about 400 KB on a phone, down from 770 KB, and under 50 KB for a widget;
  launcher and Wear widgets take designs built here once they publish for those
  surfaces (today launcher widgets capture on the phone, and Wear widgets need
  UI Builder's export);
- `ha-cards/remote-text`;
- the `size`, `width`, `height`, `offset`, `padding`, `alpha`, `scale`, `rotate`,
  `background` and `fillMax*` modifiers, and the `remoteCall` of RemoteModifier's
  `graphicsLayer` (`rotationX`, `rotationY`, `rotationZ`, `scaleX`, `scaleY`, `alpha`,
  `transformOriginX`, `transformOriginY`, `cameraDistance`) for what the catalog's
  modifiers can't say: a turn in perspective above all. UI Builder checks a
  `remoteCall` against RemoteModifier's real functions, so it is the one extension
  point a design can carry.

Any modifier value (and a text's `text`) may be a literal, a state variable
(`{"type": "state"}`), a clock value (`{"type": "system"}`: `time.minuteOfDay`,
`time.dayOfYear`, `time.utcOffset`, `time.hour`, …) or an expression in UI Builder's
own ops, with its arities: arithmetic, `min`/`max`/`clamp`, trigonometry,
`radians`/`degrees`, `smoothstep`, `lerp`, comparisons, and `select` between two
numbers by a boolean. They become Remote Compose's own operations, so the player
evaluates them live: the card follows state and the clock with no re-encode.

Day and night, for one: a scrim's alpha can be the sun's elevation at the design's
location, faded through civil twilight. The declination comes from `time.dayOfYear`,
the hour angle from `time.minuteOfDay`, `time.utcOffset` and the longitude, and the
elevation is `degrees(asin(sin φ·sin δ + cos φ·cos δ·cos H))`; night is
`1 - smoothstep(-6, 6, elevation)`. It needs no host: a widget dims at dusk with
nothing pushed to it. State
variables become named values in the `USER` domain, so one named for a card binding
(`cover.s0.is_on`) is the value the app pushes: the card follows Home Assistant
without a re-encode.

Move things with transforms, not sizes. A state-driven `offset`, `scale` or
`rotate` is drawn as one `graphicsLayer` per element (a player takes only one), so it
tweens on change (never on the first frame). A `size`, `width` or `height` that reads
state snaps instead: the player measures when a value changes, so a tweened size
would be measured at its old value and land one change late. A door that folds up is
one picture tilted about its top edge in perspective: a `graphicsLayer` call with
`rotationX` = `select(is_on, 86, 0)`, `transformOriginY` 0 and `cameraDistance` 32,
so its bottom swings out and up, widening as it nears, and is almost edge-on under
the lintel when open. A black box with the same layer and an `alpha` that rises as it
opens shades it as its face turns down. (Without perspective, a picture scaled from
1 to almost 0 with an offset of half the height it loses shrinks towards its top
edge the same way, flat.)

Colours adapt to the theme. A colour is a literal (`#RRGGBB`) or a `colorToken`
naming a card theme role (`cardBackground`, `primaryText`, `secondaryText`,
`divider`, …), `accent` (the palette's hero colour, `placeholderAccent`) or
`night` (black in a dark theme, clear in a light one). A design is captured once per
theme it publishes for, so each token resolves in that theme: light and dark, and
every palette of the card style. A photo card can tint a picture with a translucent
`accent` box drawn over it (with the same transforms, so it moves with it) and dim
itself at night with a `night` box at partial alpha over everything. Anything
else (events, predicates, other components, and any property other than a text's
`text` that reads state) is refused naming the node, rather than drawn approximately. A template built here may use only operations the library
design it varies already uses; a UI Builder export is held to the app's profile.

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
