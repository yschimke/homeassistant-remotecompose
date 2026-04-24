# `addon-server` — Home Assistant add-on serving RemoteCompose dashboards

Status: **plan**. Prototype scaffold lives alongside this document.

## Goal

Run a Home Assistant add-on that speaks to HA over its existing WebSocket
API and exposes, to native clients (Android phone, Glance widget, Wear, TV,
ESP32, e-paper, …), a RemoteCompose-native view of the user's Lovelace
dashboards:

1. A REST surface returns a `.rc` byte stream per card, pre-rendered by
   our converters on the JVM.
2. A WebSocket fan-out streams HA state changes to clients as named-state
   updates that the RemoteCompose player applies **without re-fetching** the
   document.
3. The server picks layout/variant per client profile (phone, wear, tv,
   glance, mono) so the same Lovelace card can adapt across surfaces.

This moves the "fetch config + render" loop off every device and puts a
single, cache-friendly encoder inside the HA host.

## Why an add-on, not a custom component

- Add-ons run as their own container with their own runtime (JVM today,
  GraalVM native-image later) — no Python interop, no need to ship our
  converter into `homeassistant/components/`.
- The HA supervisor gives us:
  - `SUPERVISOR_TOKEN` env var → auth against the HA Core API at
    `http://supervisor/core/api/` and `ws://supervisor/core/websocket`.
  - `ingress: true` → HA reverse-proxies us under the user's existing
    session, so we don't have to do auth for in-frontend usage.
  - One-click install, versioned updates, log surface in the Supervisor UI.
- Users who want to call us from a native app outside the HA frontend can
  still do so by forwarding a port; the same endpoints work with an HA
  long-lived access token.

## High-level architecture

```
            ┌──────────────────────────────────────────────────┐
            │                 HA supervisor host               │
            │                                                  │
  HA Core ──┼──▶ ws://supervisor/core/websocket ──┐            │
            │                                      │            │
            │   ┌──────────────────────────────────▼─────────┐ │
            │   │              addon-server (JVM)            │ │
            │   │                                            │ │
            │   │  HaSupervisorBridge  (1 persistent WS)     │ │
            │   │     ├─ subscribe state_changed             │ │
            │   │     ├─ subscribe lovelace_updated          │ │
            │   │     └─ StateCache  (entity_id → snapshot)  │ │
            │   │                                            │ │
            │   │  DashboardCache                            │ │
            │   │     ├─ lovelace/config → Dashboard         │ │
            │   │     └─ (cardId, profile) → CardDocument    │ │
            │   │                                            │ │
            │   │  RcRenderService (rc-converter-jvm)        │ │
            │   │                                            │ │
            │   │  Ktor HTTP + WS server                     │ │
            │   │     ├─ /v1/... REST                        │ │
            │   │     └─ /v1/stream  (per-client fan-out)    │ │
            │   └─────────────────────┬──────────────────────┘ │
            └─────────────────────────┼────────────────────────┘
                                      │
                                      ▼ (ingress or forwarded port)
                ┌──────────────────────────────────────────────┐
                │  Clients                                     │
                │    phone app, Glance widget, Wear tile, …    │
                │    fetch .rc once, subscribe stream forever  │
                └──────────────────────────────────────────────┘
```

The invariant we're optimising for: **the document bytes are stable across
a state change**. The existing `LiveBindings` in `rc-converter/` encode
every entity-dependent text/boolean as a named `RemoteString` /
`RemoteBoolean` in the `User` domain, keyed `"<entity_id>.<suffix>"`. The
add-on's job is to keep that stream alive: subscribe to HA state, forward
updates with matching names, and only re-encode the document when the card
config itself changes.

## Module layout

```
addon-server/
├── PLAN.md                  (this file)
├── build.gradle.kts         Kotlin/JVM, Ktor server, depends on ha-model
│                            + ha-client + (future) rc-converter-jvm
├── addon/                   HA add-on packaging
│   ├── config.yaml          add-on manifest (slug, image, ports, ingress)
│   ├── Dockerfile           multi-stage: gradle build → distroless JRE
│   └── run.sh               entrypoint (reads SUPERVISOR_TOKEN, starts JVM)
└── src/main/kotlin/ee/schimke/ha/addon/
    ├── Main.kt                       entry point; wires Ktor + services
    ├── Config.kt                     env/options parsing
    ├── bridge/
    │   ├── HaSupervisorBridge.kt     one persistent WS to HA
    │   ├── StateCache.kt             entity_id → EntityState (MutableStateFlow)
    │   └── LovelaceCache.kt          cached Dashboards + invalidation
    ├── render/
    │   ├── RcRenderService.kt        calls CardConverter, caches bytes
    │   └── ClientProfile.kt          phone / wear / tv / glance / mono
    ├── client/
    │   └── ClientSession.kt          per-WS subscription + entity filter
    ├── routes/
    │   ├── DashboardRoutes.kt        REST endpoints
    │   ├── StreamRoute.kt            /v1/stream WebSocket
    │   └── HealthRoutes.kt           /healthz, /readyz for supervisor
    └── auth/
        ├── IngressAuth.kt            trust X-Remote-User from ingress
        └── TokenAuth.kt              validate HA access token
```

Tests go under `src/test/kotlin/...`. An `integration` sub-target can wrap
the existing `integration/` module's ephemeral HA container to give us
end-to-end coverage.

## Wire protocol

### REST

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/dashboards` | List Lovelace dashboards (wraps `lovelace/dashboards/list`). |
| `GET` | `/v1/dashboards/{urlPath}` | The resolved dashboard config (views, cards). |
| `GET` | `/v1/dashboards/{urlPath}/views/{viewIdx}/cards/{cardId}.rc` | Encoded `.rc` bytes for one card, adapted to the querying client. |
| `GET` | `/v1/snapshot` | Current state cache as JSON (debug / fall-back clients). |
| `GET` | `/healthz`, `/readyz` | Liveness + readiness. |

Card fetch query params:

- `w`, `h`, `density` — natural pixel size; defaults come from the card
  converter's `naturalHeightDp` + profile.
- `profile` — client kind (`phone`, `wear`, `tv`, `glance`, `mono`). If
  absent, inferred from `X-RC-Client` header.
- `rc` — RemoteCompose wire version the client speaks. Server picks the
  highest encoder it has that `rc ≥ version` accepts.

Response headers include:

- `Content-Type: application/x-remote-compose`
- `ETag: "<sha256 of bytes>"` — lets clients skip re-downloads across
  reconnects when the document didn't change.
- `X-RC-Entity-IDs: light.kitchen,sensor.outside_temp` — comma list of
  entity IDs whose `.is_on` / `.state` named bindings this document reads,
  so a narrowing client can pre-subscribe.

### WebSocket `/v1/stream`

Newline-delimited JSON frames, symmetric with HA's own style.

Client → server:

```json
{ "id": 1, "type": "auth",       "token": "<long-lived-token>" }
{ "id": 2, "type": "subscribe",  "entities": ["light.kitchen", "sensor.x"] }
{ "id": 3, "type": "unsubscribe","entities": ["sensor.x"] }
{ "id": 4, "type": "call_service","domain":"light","service":"toggle",
  "target":{"entity_id":"light.kitchen"} }
```

Server → client:

```json
{ "type": "ready" }
{ "type": "state",
  "bindings": {
    "light.kitchen.is_on": true,
    "light.kitchen.state": "on",
    "sensor.outside_temp.state": "21.4 °C"
  }
}
{ "type": "lovelace_updated", "url_path": "lovelace" }
{ "type": "error", "id": 2, "message": "..." }
```

The `bindings` shape matches `LiveBindings` exactly, so a client can hand
the map straight to its `RemoteDocumentPlayer` without mapping.

### Adaptive behaviour

`ClientProfile` is the single knob.

- **phone** — full Material3 card, touch targets, colour, shadow.
- **wear** — compact layout, safe for AOD, no shadows; override
  `naturalHeightDp`; converters may swap to the Wear M3 RC primitives
  where available.
- **tv** — larger typography, focusable affordances, 10-ft safe margins.
- **glance** — single-card documents sized for launcher widgets; no
  horizontal scroll, no nested players.
- **mono** — dithered 1-bit colour substitution for e-paper / ESP32;
  collapses backgrounds, drops icons missing mono masks.

Per profile we can (a) choose a different converter per `type` (via a
profile-indexed `CardRegistry`), or (b) pass the profile as a render
parameter to a single converter. We'll start with (b) and promote (a) if
wear/tv diverge enough.

## Live-update lifecycle

1. Client opens `/v1/stream`, authenticates.
2. Client fetches one card's `.rc`. Response includes
   `X-RC-Entity-IDs: <list>`.
3. Client sends `{type:"subscribe", entities:[<list>]}`.
4. Server joins the union of all active subscriptions. If the requested
   entity isn't in the server's supervisor-wide `state_changed`
   subscription, the server adds it (or, simpler, keeps a wildcard
   subscription and just gates the per-client fan-out).
5. Each HA `state_changed` event → server computes the `bindings` diff for
   subscribed entities (using the same formatters as the converters, via
   `HaStateFormat`) → pushes `{type:"state", bindings:{...}}` to clients
   whose subscription intersects.
6. `lovelace_updated` event → server invalidates `DashboardCache` +
   matching `CardDocument` entries → pushes `lovelace_updated` so clients
   re-fetch.

Formatter invariant: whatever string the client sees for
`entity.state` at subscribe time (server's initial "hydrate" push) must be
byte-identical to what the converter baked into the initial document. Both
must call the same `HaStateFormat` code path.

## Auth

Two mutually-exclusive modes, selected at startup:

- **Ingress** (default). `ingress: true` in `config.yaml`. HA proxies us;
  we trust `X-Remote-User-Name` / `X-Remote-User-Id` headers, which HA
  signs before passing through. No token needed.
- **External**. Client presents `Authorization: Bearer <ha-token>`.
  `TokenAuth` validates by calling HA Core `GET /api/` with the token; a
  200 means valid. Cache the (token, userId) for 5 min to avoid a lookup
  per request.

Service calls (`call_service`) always go through the supervisor bridge, so
the HA-side audit log sees the supervisor as the actor. If that's not
acceptable, we require per-user tokens and open a short-lived HA WS per
call — defer.

## Rendering path on the JVM

The existing `rc-converter/` is Android-targeted. To run encoders on the
server we have two options.

**Option A — port the converters to `remote-creation-jvm`** (the JVM-only
sibling already in `libs.versions.toml` at line 36). This is the pure
`RemoteCreationCore` writer API — no Compose runtime, no Android
`Context`, no `VirtualDisplay`. Trade-off: the converters lose the
`@Composable` DSL and become imperative "emit-to-writer" code.

**Option B — run the current Compose-based converters on the JVM**. The
Compose compiler plugin and runtime work on JVM today. `captureSingle
RemoteDocumentV2` expects an Android `Context`, so we'd need a JVM
equivalent of that capture entry point.

Starting plan: **A, phased**. A new `rc-converter-jvm` module that starts
by porting `tile`, `entities`, `glance`, `button` (in that order — the
bulk of real dashboards per the README). Each port is gated by the same
pixel-parity harness (`integration/`) used for Android. The existing
`rc-converter` module stays intact for the Android client, sharing
`HaStateFormat`, `HaStateColor`, `HaIconMap`, `LiveBindings`, and
`HaActionParser` via a common source set we extract during the port.

This also keeps **native-image** on the table: no Compose runtime, no
reflection beyond kotlinx-serialization's (which has first-class
native-image metadata).

## Native-image (phase 2)

- GraalVM 21, `native-image --no-fallback --enable-url-protocols=http,https`.
- Netty is painful; use Ktor CIO server + OkHttp client (or Ktor CIO
  client). Both have working native-image configs.
- kotlinx.serialization: register `@Serializable` types via
  `@ReflectiveAccess` + `META-INF/native-image/.../reflect-config.json`
  generated by the `serialization-agent` plugin, or hand-written.
- `remote-creation-jvm` inspection: flag any AWT / ImageIO usage before
  committing to native; if present, either substitute or stay on HotSpot.
- Image budget target: <30 MB binary, <60 MB RSS, <200 ms cold start. Only
  if we hit those does native-image pay for itself; otherwise stay on a
  distroless JRE.

## Packaging as an HA add-on

`addon/config.yaml` (sketch):

```yaml
slug: remotecompose
name: RemoteCompose Dashboards
version: 0.1.0
arch: [amd64, aarch64]
startup: application
boot: auto
hassio_api: true
homeassistant_api: true
auth_api: true
ingress: true
ingress_port: 8099
panel_icon: mdi:remote
ports:
  8099/tcp: 8099          # optional: expose to LAN for native apps
options:
  log_level: info
schema:
  log_level: list(trace|debug|info|warning|error|fatal)
image: ghcr.io/yschimke/ha-remotecompose-{arch}
```

Dockerfile strategy:

1. `gradle/gradle:8-jdk21` builder → `./gradlew :addon-server:installDist`.
2. Final: `gcr.io/distroless/java21-debian12:nonroot`. Copy the install
   dir, `ENTRYPOINT ["/app/bin/addon-server"]`.
3. For native-image phase 2: builder is a GraalVM image, final is
   `gcr.io/distroless/static-debian12:nonroot` with just the binary.

## Milestones

- **M0 — scaffold (this PR).** Module exists, `./gradlew :addon-server:run`
  starts an empty Ktor server answering `/healthz`, Dockerfile + config.yaml
  checked in. No actual HA plumbing.
- **M1 — supervisor bridge.** `HaSupervisorBridge` connects, authenticates,
  subscribes to `state_changed`, holds a `StateCache`. `/v1/snapshot` dumps
  the cache as JSON. Verify end-to-end against the `integration/`
  compose file.
- **M2 — dashboards + passthrough.** `/v1/dashboards`, `/v1/dashboards/{p}`
  — wraps `HaClient` calls. No `.rc` bytes yet.
- **M3 — first card on JVM.** Extract a `rc-converter-common` (pure Kotlin)
  source set from `rc-converter`; add `rc-converter-jvm` with a JVM port of
  `tile`. Wire `RcRenderService` and expose `GET …/cards/{id}.rc`.
  Pixel-parity test replays the existing `references/` fixtures.
- **M4 — live stream.** `/v1/stream` WS, `state_changed` → `bindings`
  fan-out, per-client entity filter, formatter parity with converter.
- **M5 — adaptive profiles.** `ClientProfile` threaded through
  `RcRenderService`; split per-profile tests. Start with `phone` +
  `glance`; wear/tv gated behind their own converter ports.
- **M6 — native image.** `:addon-server:nativeCompile` producing a static
  binary; CI matrix builds arm64 + amd64; image size and cold-start budgets
  in `README.md`.

## Risks / unknowns

- **`remote-creation-jvm` surface.** We've never built against it. M3 may
  surface missing primitives (text measurement, icons). Mitigation: spike
  `tile` first; if blocked, fall back to Compose-on-JVM for M3 and revisit
  native in M6.
- **Strategy dashboards.** HA resolves strategies client-side today.
  Either we port the resolution logic (tractable — it's JS that reads
  registries) or we require `type:` dashboards. Defer to M2.
- **Service-call audit log identity.** See "Auth" above. Worst case:
  per-call HA WS with user token. Defer to M4.
- **WebSocket load.** A single supervisor WS fanning out to many clients
  is our bottleneck. HA doesn't have per-entity filtering on the server —
  we get every `state_changed`. At ~50 events/sec, filtering + JSON
  encoding per client is fine up to O(100) clients on a Pi; benchmark at M4.
- **Custom cards.** Out of scope for the initial set, same as `rc-converter`.
  The server should return a typed 404 (`X-RC-Unsupported: custom:foo`) so
  clients can render a placeholder.

## Out of scope (initial)

- Map, picture-elements, history-graph, energy cards (same list as the
  Android converter).
- Writing to HA from profiles other than `phone` (wear/tv/glance still only
  display — no `call_service` from tiles yet).
- Multi-tenant add-on hosting — one HA instance per add-on install.
