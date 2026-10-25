# Terrazzo on Wasmo as a cloud service (plan)

> Status: **staged implementation.** Cloud usage settings are merged. This review stage adds
> encrypted per-install HA sessions and browser integration for owner-supplied **public HTTPS** HA
> origins. Public cards are explicitly deferred: `publish` stays false and no public HA data is
> written. Wasmo is already running; this session has not deployed or activated these changes.
> See [wasmo-app.md](wasmo-app.md).

## Implementation amendment: shared services

The server is also intended for other yschimke apps. Shared settings services live in
`apps/services/wasmo-app` in the Wasmo fork, enabled by an explicit operator allowlist such as
`WASMO_SERVICE_APPS=terrazzo,notes,photos,tasks`. Every listed install uses the same factory and
its own per-install SQL database. The API is `/api/wasmo/v1/`, with `X-Wasmo-Api: 1`; the client
keeps its Terrazzo-specific storage keys. An install name is not proof of a GitHub repository.
The Terrazzo-specific module names and API paths below describe the original handover and are
superseded by this amendment. The server never shares HA credentials between apps or owners.

The settings stage adds `POST kv/import` (advertised as `kvImport: true`) to atomically import
initial values only into an empty install, avoiding partial uploads and first-device races.

## Cross-repository dependencies and review order

1. [Web detection/local storage PR #1225](https://github.com/yschimke/homeassistant-remotecompose/pull/1225)
   is the merged baseline. It retains static behaviour, including on hosted wasmo.com.
2. Review [Wasmo shared services PR #1](https://github.com/yschimke/wasmo/pull/1) against `deploy`.
   Its 11 module tests and full wiring compile pass. The earlier npm resolution failure
   ([SCH-50](https://linear.app/yschimke/issue/SCH-50/wasmo-wiring-build-fails-resolving-peculiarasn1-x509-2111))
   no longer reproduces; no dependency or registry change was needed. A live OS owner-cookie
   flow remains unverified.
3. Review the web cloud-settings stage after stage 1. It needs stage 2's capabilities, `kv` and
   `kvImport` contracts. Each load reads one snapshot before composition. Usage ledger and sharing
   values stay strings in the generic JSON KV service. Startup read/import failure shows a retry
   screen instead of silently switching to empty browser settings. Failed writes stay in memory,
   are labelled unsaved, and retry on the next storage write.
4. Only after **separate deployment authorization**, release an opt-in server build and install a
   matching web archive. Enable the operator's supported install names with `WASMO_SERVICE_APPS`.
   The archive stays the same one used for static sites; an absent backend keeps browser storage.
5. Review the optional session custody stage and matching browser integration. Enable it only after
   separately authorized key provisioning and consistent backup/restore verification. Public
   snapshots, publishing and background refresh are deferred; expose no public card data.

### HA session amendment (current implementation)

The authoritative private session protocol and operator key lifecycle are in
[Wasmo HA-SESSIONS.md](https://github.com/yschimke/wasmo/blob/agent/ha-session-custody/apps/services/HA-SESSIONS.md).
They supersede the original HA endpoint shapes and secret-key proposal below. Services remain
shared across supported apps; the initial operator list is `terrazzo`. `haSession` is enabled only
when Wasmo has a configured key ring and a bounded public HTTPS service.

Cloud startup loads metadata and validates the server session before composing the signed-in UI.
Browser OAuth retains its pending nonce/callback guard, then transfers the refresh token through
the same-origin owner API. The server verifies it with HA and stores encrypted credentials outside
KV. It returns metadata and short-lived access tokens only. A second owner device needs its Wasmo
session, but no separate HA sign-in. The browser still connects directly to HA for live WebSocket
state; it needs network access to the public HA origin.

Legacy browser tokens are imported once, only into a never-initialized session slot. After server
acknowledgement, matching legacy storage is removed; an import conflict reads the authoritative
server session instead. An invalid grant is discarded after a successful metadata read. Network
errors preserve legacy data and show a startup retry screen, never a local-auth fallback after
`haSession` was detected. An unsupported legacy origin can require reconnecting with a public HTTPS
origin; the retry screen offers an explicit way to skip this tab’s legacy import without deleting
a credential that was not saved. Concurrent browser writes are preserved until the next restore. Static deployments keep the
existing OAuth/localStorage path; the HA panel and demo do not use these endpoints.

Sign-out waits for server revocation and removal before disconnecting the UI. Failure keeps the
session visible with an error dialog and lets the owner retry. Generation checks reject stale device
refresh/sign-out requests, and a server tombstone prevents another device's legacy tokens from
restoring a signed-out session. Already-issued access tokens are revoked by HA on successful
sign-out. Replacing a sign-in forgets the previous grant; older grants can be revoked in HA's profile.

The public HTTPS client rejects private/reserved addresses at connection-time DNS resolution,
checks literals too, disables redirects/proxies, bounds response size and enforces call deadlines.
No private/LAN HA destinations, public entity states, snapshots or publishing are enabled here.

### Remaining settings semantics

A page seeds settings once at startup; it does not subscribe to changes made by another open page.
KV writes use last-writer-wins semantics. Atomic initial import protects migration, but it does not
merge concurrently edited usage ledgers or coordinate exactly-once usage reports across devices.
Those need a domain merge/report-idempotency design before claiming a combined usage record from
several active devices. The browser tests prove persistence and startup seeding on another device,
not concurrent telemetry aggregation. If discovery cannot find the backend, the specified static
fallback uses that browser's retained preferences; it is not an offline cache of cloud preferences.

Migration uses `POST kv/import` only when the remote snapshot is empty. After an acknowledged
import, only matching known usage/sharing keys are removed from browser storage. On conflict,
remote values win and conflicting browser values are retained. OAuth and unrelated browser keys
are never imported. The Settings label applies to usage settings; the design-server field retains
its existing session-only behaviour.

Local browser fixture: `python scripts/wasmo-sim.py app-ui/build/dist/wasmJs/productionExecutable`.
It binds to loopback, has test-only owner cookies and fake HA token values, and must never be
published as a real backend. `--static` makes the API unavailable for the browser-storage path.

Browser verification uses the actual Wasm UI (including a second independent browser context):

```sh
npm ci --prefix scripts/browser-tests --ignore-scripts
CHROMIUM_PATH=/path/to/chrome node scripts/test-wasmo-browser.cjs app-ui/build/dist/wasmJs/productionExecutable
```

The existing Wasm website CI supplies Chrome from its Playwright installation. The script starts
and stops its own fixture processes, checks anonymous rejection, migration, write-through,
second-device preferences, failed-write recovery, fail-closed startup and static persistence, and
writes screenshots under `_wasm-test/`. `WASMO_BEFORE_DIST` optionally points to a baseline build
for the before screenshot. These fixtures do not exercise the real Wasmo owner-cookie service or
prove a real Wasmo/HA sign-in. `--cloud-session` adds fake private custody for the browser suite:
it verifies legacy auth migration, a second device without browser tokens, failed/successful
sign-out, startup retry UI and anonymous denial. The server tests independently verify encryption,
refresh/revocation and SQL locking; a live owner-cookie/HA flow remains a deployment gate.

### Public renderer security boundary

A public card on the app origin shares an origin with the owner API. If its scripts run in the
parent document while the owner views it, owner-only routing does not isolate those scripts from
private endpoints. Treat remotely updated renderer code and card-provided markup as untrusted.
The publication stage needs an isolated renderer (for example, a sandboxed iframe without
`allow-same-origin`) or an equivalently reviewed trusted renderer. Use `srcdoc` or a separate
renderer origin; a bare same-origin renderer URL would still be unsafe when opened outside the
frame. A sandboxed renderer should
receive only the approved public snapshot through a constrained bridge; it must never receive
HA credentials or private API access. Validate this boundary before enabling publication.
See the browser contracts for [same-origin access](https://developer.mozilla.org/en-US/docs/Web/Security/Defenses/Same-origin_policy)
and [iframe sandboxing](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/iframe#sandbox).

## Goal

Today the Terrazzo Wasmo app is static files: everything it remembers lives in the browser's
`localStorage`, so every device signs in to Home Assistant separately and nothing works without a
browser open. Wasmo's model (see its journal app, milestone 2) is that each installed app gets
**its own database, its own object store, background jobs and public routes**. This plan makes
Terrazzo use those, so that:

1. **Sign in once.** The Home Assistant session lives on the owner's Wasmo, and any device the
   owner opens Terrazzo on is already signed in.
2. **Settings follow the owner.** The card-usage record, the sharing choice and future app settings
   are stored per install, not per browser.
3. **Publish a card.** The owner can publish one card as a public, read-only page on their app's
   origin (like the journal's "publish an entry"), kept current by a server-side job.
4. **Same app everywhere.** The one Wasm web build still runs on GitHub Pages, in the Home
   Assistant panel and on hosted wasmo.com with no server code. It **detects** the backend at
   startup and takes one of two paths.

## Where Wasmo is today

Checked against upstream `e688b5e` (25 Sep 2026) and this fork's `deploy` branch
([yschimke/wasmo](https://github.com/yschimke/wasmo), which carries `WASMO_BASE_URL`, the `.wasm`
content type, custom-domain routing and `WASMO_ADMIN_PASSWORD`; see its `DEPLOY.md`).

| Platform feature | Upstream status | What this plan does |
|---|---|---|
| Per-install SQL (`SqlService.getOrCreate`) | Implemented (PostgreSQL per app) | Use it |
| Object store (put/get/delete/list) | Implemented | Use it |
| Serving objects from `www-public/` (everyone) and `www/` (owner) | Implemented, ahead of archive files | Use it for published cards |
| Outbound HTTP (`Platform.httpService`, OkHttp) | Implemented, no allowlist | Use it to refresh HA tokens and read states |
| `Downloader` (HTTP straight into the object store) | Implemented | Not needed yet |
| Job queue (run now or at a time; re-enqueue replaces) | Implemented | Use it for publish refresh |
| Inbound HTTP to app code | Owner only (`Caller` not passed; `TODO`) | Fine: the API is owner-only by design |
| `afterInstall(old, new)` | Called once, `(0, version)` | Use it, plus a self-check (below) |
| **Running third-party app code** | **Built-in apps only** (`JvmAppLoader`, `mapOf(AppSlug("journal") …)`); an installed `app.wasm` is never run. SDK and WASI runtime planned for 2027 | **Simulate in the fork** |
| App updates | Plan only | **Simulate**: idempotent schema check |
| Secrets vault | Plan only | **Simulate**: encrypted table behind the planned interface |
| Inbound WebSockets | Plan only | Not needed: the browser keeps its direct HA connection |
| Resource limits | Plan only | **Simulate**: size caps in the app |
| `/tmp` files, media service, in-app purchases | Plan only | Not needed |

## Architecture

```
Browser (Terrazzo Wasm app, origin terrazzo-<computer>.<host>)
  │
  ├── detect: GET /api/terrazzo/v1/capabilities
  │      200 + {"api":1,…}  → Wasmo path          anything else → local path (today's behaviour)
  │
  ├── Wasmo path ── /api/terrazzo/v1/* (owner-only, same origin, Wasmo session cookie)
  │                    ├── kv/*            settings and the usage record      → SQL
  │                    ├── ha/session      HA refresh token (never returned)  → secrets (simulated)
  │                    ├── ha/token        fresh access token, refreshed by the server via outbound HTTP
  │                    └── publish/*       published cards                    → SQL + object store + jobs
  │
  └── both paths ── wss://<hass>/api/websocket directly from the browser (unchanged; Wasmo has no
                    WebSockets and live cards must stay live)

Public visitors ── /p/<slug>/  served from the object store's www-public/ by Wasmo itself
                   (index.html + state.json written by the publish job; no app code runs)
```

The server never renders cards. Card looks stay design-template documents from the design server
(as for the [embed](card-embedding.md)); the server only stores settings, holds the HA session and
writes small JSON state snapshots for published cards.

## Part 1: Wasmo fork (server)

All work goes on a new fork branch `agent/terrazzo-app`, cut from `deploy` and merged back into it,
so the published `ghcr.io/yschimke/wasmo-homelab` image carries it.

### 1.1 Simulated third-party loading (the one hardcoded bit in the OS)

- Add `apps/terrazzo/wasmo-app` (Kotlin multiplatform, `libraryJvm()`, like
  `apps/journal/wasmo-app`), and `include(":apps:terrazzo:wasmo-app")` in `settings.gradle.kts`.
  **Unlike the journal, it packages no `.wasmo`**: the static files keep coming from the
  `terrazzo.wasmo` this repository releases. Only the server code is compiled into the OS.
- Register it in `os/server/wiring/.../ServiceBindings.kt`
  `provideWasmoAppFactories()`, **gated by an environment variable** so `deploy` stays generic
  (its `DEPLOY.md` rule):

  ```kotlin
  fun provideWasmoAppFactories(): Map<AppSlug, WasmoApp.Factory> = buildMap {
    put(AppSlug("journal"), JournalWasmoApp.Factory())
    // Simulates the third-party SDK: built-in server code for an app installed from a URL.
    if ("terrazzo" in simulatedApps()) put(AppSlug("terrazzo"), TerrazzoWasmoApp.Factory())
  }
  ```

  `simulatedApps()` reads `WASMO_SIMULATED_APPS` (comma-separated, default empty), the same way
  the fork reads `WASMO_BASE_URL`. Pass the secrets key the same way (1.4).
- `JvmAppLoader` keys factories by **install name**, so the app must be installed as
  `terrazzo` (Wasmo's Install App dialog asks for the name). Document this; don't change the loader.
- Add a "Simulated third-party apps" entry to `DEPLOY.md`'s patch list, and
  `--env WASMO_SIMULATED_APPS=terrazzo --env TERRAZZO_SECRETS_KEY=<base64 32 bytes>` to its
  "Running it" example.

### 1.2 App shape

Copy the journal's structure, minus its admin web app and API module:

```
apps/terrazzo/wasmo-app/src/jvmMain/kotlin/ee/schimke/terrazzo/wasmo/
  TerrazzoWasmoApp.kt        # WasmoApp: httpService, jobHandlerFactory, afterInstall; Factory(platform)
  TerrazzoHttpService.kt     # path routing, JSON in/out, CSRF header check, size caps
  TerrazzoDb.kt              # schema + queries (raw SqlConnection or SQLDelight like journal/db)
  KvAction.kt
  HaSessionAction.kt         # session store/clear, token refresh via platform.httpService
  PublishAction.kt
  PublishJobHandler.kt       # queue "publish": fetch states, write www-public/p/<slug>/state.json
  planned/                   # simulations of plan-only platform features (1.4); one file each,
    SecretsVault.kt          #   shaped like the planned API so they can be deleted when it ships
    SchemaVersion.kt
    Limits.kt
```

Keep dependencies to `projects.platform.api`, `kotlinx-serialization-json`, `okio` (and
`wasmox-sqldelight` if using SQLDelight). That keeps the module movable: once Wasmo publishes the
SDK jar (milestone 3), it moves into this repository as a `wasmo-server/` module and builds into
the released `terrazzo.wasmo` as `app.wasm`.

### 1.3 HTTP API (v1)

Base path `/api/terrazzo/v1/`. Only the owner's requests reach app code, so authentication is
Wasmo's. All bodies are JSON. Every non-GET request must carry `X-Terrazzo-Api: 1`, or the
service answers 403: a cross-site form post can't set it, so this blocks CSRF against the owner's
session cookie without depending on its SameSite setting.

| Method and path | Request | Response | Storage |
|---|---|---|---|
| `GET capabilities` | | `{"api":1,"backend":"wasmo-simulated","kv":true,"haSession":<secrets configured>,"publish":<secrets configured>}` | |
| `GET kv` | | `{"<key>": <json>, …}` (all keys, one round trip for startup seeding) | SQL `kv` |
| `PUT kv/{key}` | any JSON, ≤ 256 KiB | 204 | SQL `kv` |
| `DELETE kv/{key}` | | 204 | SQL `kv` |
| `GET ha/session` | | `{"hassUrl","clientId"}` or 404. **Never the refresh token.** | SQL + vault |
| `PUT ha/session` | `{"hassUrl","clientId","refreshToken"}` | 204 | vault |
| `POST ha/token` | | `{"accessToken","expiresIn"}`; 401 when HA refuses the refresh (session deleted); 502 `{"error":"unreachable"}` when HA can't be reached from the server | outbound HTTP |
| `DELETE ha/session` | | 204, after a best-effort `POST <hass>/auth/revoke` | vault |
| `GET publish` | | `[{"slug","title","design","card","entities","refreshMinutes","updatedAt"}]` | SQL |
| `PUT publish/{slug}` | `{"title","design","card","entities","refreshMinutes"}` | the stored record; writes `www-public/p/<slug>/index.html`, enqueues a refresh now. 409 `{"error":"no-session"}` when the server holds no HA session | SQL + objects + job |
| `DELETE publish/{slug}` | | 204; deletes `www-public/p/<slug>/*` and cancels the job | |

Rules:

- `kv` keys match `[a-z0-9][a-z0-9._-]{0,63}`; slugs match `[a-z0-9][a-z0-9-]{0,39}`. Anything
  else is 400.
- `ha/token` refreshes against `<hassUrl>/auth/token` with
  `grant_type=refresh_token&client_id=<clientId>`. The `client_id` is the app's origin, which is
  the same for every device, so one refresh token serves all of them. Cache the access token in
  memory until 60 s before expiry so concurrent devices don't each refresh.
- `hassUrl` must be `https://` (or `http://` only when the fork's wiring passes
  `TERRAZZO_ALLOW_HTTP_HASS=true`, for a homelab on the same network as HA); the server never
  follows redirects to another host.
- Publishing is opt-in per card and exposes only the listed entities' states. `entities` must be
  non-empty and at most 20; `refreshMinutes` is clamped to 5–1440.

### 1.4 Simulated plan-only features

Each lives in `planned/` with a header comment naming the Wasmo plan doc it stands in for, and
the API shape that doc describes, so replacing it is a one-file change.

- **Secrets vault** (`docs/platform/plans/secrets_vault.md`: key-value store with
  `getOrCreate`, authenticated encryption).
  `interface SecretsVault { suspend fun get(key): ByteString?; suspend fun put(key, value);
  suspend fun delete(key); suspend fun getOrCreate(key, create: () -> ByteString) }`.
  Simulation: SQL table `secret(key, nonce, ciphertext)`, AES-256-GCM, with the key passed to
  `TerrazzoWasmoApp.Factory(secretsKey)` from `TERRAZZO_SECRETS_KEY` in the fork's wiring (a
  JVM-only shortcut; real apps won't see OS environment). With no key configured,
  `capabilities.haSession` and `capabilities.publish` are both `false`: the browser keeps the HA
  session local and settings still sync, but publishing is off, because the publish job can only
  read states with the server-held session.
- **App updates** (`app_updates.md`: `afterInstall(old, new)` on each update). Today it's called
  only on first install, and reinstalling an app from a URL keeps the install. Simulation: a
  `schema_version` single-row table and `ensureSchema()`, run from `afterInstall` **and** lazily
  before the first request, applying idempotent migrations from 0 up. Never downgrade: if the
  stored version is newer, fail requests with 503.
- **Resource limits** (`resource_limits.md`). Simulation: the size caps above, at most 50
  published cards, and at most 1 MiB of `kv` in total; over a limit answers 413.

Not simulated: inbound WebSockets (the browser keeps the HA connection), `/tmp` files, media
service, in-app purchases.

### 1.5 Published cards

`PUT publish/{slug}` writes a static `www-public/p/<slug>/index.html` (from a template in the
module's resources) that loads the design server's embed scripts and points `<ha-card>` at a
**static state source** instead of a live Home Assistant:

```html
<script type="module" src="https://ha.coo.ee/embed/v1/ha-card.js"></script>
<ha-card design="<sha256>" card='<card json>' states="state.json"></ha-card>
```

`PublishJobHandler` (queue `publish`, one job per slug, the job body is the slug) gets an access
token as `ha/token` does, reads each entity with `GET <hass>/api/states/<entity_id>`, writes
`state.json` (`{"updatedAt", "states": {entity_id: state object}}`) to the object store, then
re-enqueues itself at `now + refreshMinutes`. On 401 it stops and marks the record
`"error":"signed-out"`; on network errors it retries with backoff up to the next refresh. Wasmo
serves both files to the public itself; no app code runs for anonymous visitors.

The `states` attribute is new embed work (2.5).

### 1.6 Tests

Mirror `apps/journal/wasmo-app/src/jvmTest`: a `TerrazzoAppTester` (Burst
`CoroutineTestInterceptor`) builds the app on `FakePlatform(FakeSqlService("terrazzo_test"))`,
calls `afterInstall(0, 1)`, and uses `FakeHttpService` handlers as a fake Home Assistant
(`/auth/token`, `/auth/revoke`, `/api/states/*`) and `FakeClock`/`FakeJobQueueFactory` for jobs.
`FakeSqlService` needs a local PostgreSQL (`postgres`/`password`, `POSTGRESQL_HOSTNAME`), as the
journal's tests do; see the fork's `docs/local_development/postgresql.md`.

Cover: capabilities with and without a secrets key; kv round trip, key validation, size caps;
missing CSRF header → 403; session store never echoes the token; token refresh, cache, 401
clears the session, 502 when unreachable; publish writes both objects, the job refreshes and
re-enqueues, delete removes objects and cancels; schema check is idempotent and refuses a newer
schema; vault round trip and tamper detection.

## Part 2: Terrazzo web app (this repository)

### 2.1 Backend detection (`app-ui/src/wasmJsMain/resources/terrazzo-backend.js`, new)

Loaded by `index.html` before `ha-oauth.js`. It exposes
`window.terrazzoBackend = { ready: Promise<{kind: 'wasmo'|'local', caps}> , … }`:

- Never probe inside the HA panel (`window.frameElement?.terrazzoHost`) or in demo mode; those
  are `local` (the panel has its own auth).
- Otherwise `fetch('/api/terrazzo/v1/capabilities', {cache: 'no-store'})` with a 3 s timeout.
  `wasmo` only if the response is 200, JSON, and `api === 1`; anything else (GitHub Pages' 404,
  hosted wasmo.com's 404, a network error, a timeout) is `local`. Cache the answer for the page
  only; don't remember it in storage.
- The Wasmo path's fetch wrapper adds `X-Terrazzo-Api: 1` and `credentials: 'same-origin'`.

### 2.2 One storage interface, two implementations

Kotlin side (`app-ui`): an `AppStorage` in `commonMain`:

```kotlin
interface AppStorage {
  val location: StorageLocation          // BROWSER or WASMO, shown in Settings
  fun get(key: String): String?          // synchronous, from a seeded in-memory map
  fun set(key: String, value: String?)   // updates memory now, persists in the background
}
```

- `LocalAppStorage` wraps `localStorage` (today's `storageGet/Set/Remove` in
  `CardUsagePlatform.kt`).
- `WasmoAppStorage` seeds from one `GET kv` **before the first frame** (in `main()`, alongside
  `restoreSignIn()`), then writes through with `PUT`/`DELETE kv/{key}`, coalescing rapid writes
  per key. A failed write keeps the value in memory and retries on the next write; nothing blocks
  the UI.
- Route `CardUsagePlatform.kt`'s ledger and sharing keys (`terrazzo.usage.ledger`,
  `terrazzo.usage.sharing`) through `AppStorage`, so `UsageStore` and `readSharing`/`writeSharing`
  keep their current signatures. New settings use `AppStorage` from the start.
- First run on the Wasmo path: if `kv` is empty and `localStorage` has those keys, upload them,
  then remove them locally.

### 2.3 Home Assistant sign-in on the two paths (`ha-oauth.js`)

Keep the public `window.terrazzoOAuth` API (`restore`, `signIn`, `signOut`, `accessToken`) so
`Main.kt` doesn't change. Inside, pick a token holder from `terrazzoBackend.ready`:

- **local** (unchanged): tokens in `localStorage`, refreshed from the browser.
- **wasmo** (when `caps.haSession`):
  - `restore()`: finish an OAuth redirect if there is one, then `PUT ha/session` with the
    refresh token and keep only the access token in memory. With no redirect, `GET ha/session`;
    if there is one, the device is signed in (this is the "sign in once" win).
  - `accessToken()`: return the in-memory token if it's fresh, else `POST ha/token`. On 401,
    sign out. On 502 (the server can't reach Home Assistant, e.g. hosted Wasmo and a LAN-only
    HA), fall back to a **device-local** session: ask the user to sign in on this device and keep
    those tokens local, labelled as such in Settings.
  - `signOut()`: `DELETE ha/session` and clear memory.
  - Migration: if local tokens exist and the server has no session, upload them and clear them
    locally.

### 2.4 UI

- Settings shows where data lives: "Stored on your Wasmo" or "Stored in this browser", and for
  the HA session "Signed in on all your devices" or "Signed in on this device".
- On the Wasmo path, when `caps.publish` is true **and** this device is signed in through the
  server-held session (not the device-local fallback), a card's menu gets **Publish…**: title, refresh interval, a list of the
  exact entities that will become public, and after publishing the public URL with copy and
  unpublish. A Settings page lists published cards. Text follows the
  [component system](card-component-system.md) and needs the usual previews.
- A published card's design key is the one the converter already computes for the design server
  (`POST /designs/v1/generate`); cards with no design template can't be published (the menu item
  is disabled with a reason).

### 2.5 Embed: static state source (`embed/ha-card.js`)

Add a `states="<url>"` attribute: instead of signing in and subscribing, fetch the JSON, feed
its `states` map to the existing `slotBindings(card, states)` (which already takes entity id →
`{state, attributes}`), re-fetch every 60 s (or on `visibilitychange`), and
render taps as no-ops. Per [live-card-updates.md](live-card-updates.md), seed values before the
first frame: fetch `state.json` before mounting the player, and show nothing rather than a
placeholder value. Show "Updated <time>" from `updatedAt`.

### 2.6 Packaging and docs

- `app-ui/src/wasmJsMain/resources/index.html`: add `<script src="terrazzo-backend.js" defer>`
  before `ha-oauth.js`, for every web build (the detection is harmless on Pages and does nothing
  in the panel). The Wasmo packaging in `app-ui/build.gradle.kts` needs no change.
- `wasmo-app.md`: replace "No WebAssembly runs on Wasmo yet…" bullets with a pointer here, and
  document installing **as `terrazzo`** on a fork-based Wasmo with `WASMO_SIMULATED_APPS`.
- Landing page (`app-ui/wasmo/www-public/index.html`): no change needed; published cards live
  under `/p/`, which the landing page doesn't shadow.

### 2.7 Tests

- Unit (`app-ui` `jvmTest`/`commonTest`): `AppStorage` contract test run against both
  implementations (the Wasmo one against a fake transport): seeding, write-through, coalescing,
  retry, migration from local.
- Browser: extend `scripts/test-wasm-browser.py` with a small stand-in server
  (`scripts/wasmo-sim.py`) that follows Wasmo's routing (`www-public/` first, owner-only `www/`
  and API) and implements the v1 API in memory with a fake Home Assistant. Run the same flows
  twice:
  - **local path**: API 404s → sign-in stays in `localStorage`;
  - **Wasmo path**: second browser context (a "new device") is signed in without OAuth; settings
    written in one context appear in the other; `ha/token` 502 falls back to device-local;
    publish writes `/p/<slug>/` and an anonymous context sees the card with the snapshot values
    on its first frame.

## Delivery order

Each step is a separate PR and leaves both builds working.

1. **Web: detection + `AppStorage` with only the local implementation** (2.1, 2.2 local). No
   behaviour change; proves the seam.
2. **Fork: module, gated registration, schema check, `capabilities` and `kv`** (1.1–1.3 kv,
   1.4 schema and limits, 1.6). Deploy the image with `WASMO_SIMULATED_APPS=terrazzo`.
3. **Web: Wasmo storage path** (2.2 Wasmo, settings label, browser test both paths).
4. **Fork: simulated vault and `ha/*`** (1.3 session/token, 1.4 vault).
5. **Web: sign-in on the Wasmo path** (2.3, Settings labels, browser test "new device").
6. **Embed `states=` source** (2.5), then **fork publish + job** (1.5) and **web Publish UI**
   (2.4).
7. Docs (2.6) alongside each step.

## Risks and open questions

- **Hosted wasmo.com can't run any of this** until the real SDK ships; it keeps the local path
  automatically. Only a fork-based homelab (or a self-built image) gets the Wasmo path.
- **Install name coupling.** The simulation binds code to the install named `terrazzo`. Installing
  under another name silently gives the local path; Settings should say which path is active.
- **Server reachability of Home Assistant.** `ha/token` and publishing need the Wasmo server to
  reach HA over HTTPS. A homelab next to HA is fine; elsewhere the browser falls back to
  device-local sign-in and publishing reports `unreachable`.
- **Token custody.** Moving the refresh token server-side trades "anyone with this browser" for
  "anyone with the Wasmo owner session". The vault simulation encrypts at rest, but the key sits
  in the container's environment. Revisit when Wasmo's real vault lands.
- **Publishing is public.** Snapshot values are world-readable by design; the UI must list the
  exact entities and the refresh interval before the owner confirms.
- **Upstream drift.** The fork's daily sync merges upstream `main` into `deploy`; keep the OS
  change to the one gated `ServiceBindings` line so conflicts stay trivial. When upstream ships
  the SDK, delete the gate and the `planned/` simulations, move the module here, and build it
  into `terrazzo.wasmo`.
