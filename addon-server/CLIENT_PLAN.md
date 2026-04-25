# Client architecture — multi-server, offline-first, mixed-source rendering

Companion to [`PLAN.md`](PLAN.md). PLAN covers the **server** (the HA
add-on); this doc covers how **clients** (the Android phone app, Wear,
TV, Glance widgets, …) talk to one or more HA servers, transparently
mix locally-rendered and server-rendered cards, and remain usable
offline.

> Status: design. Implementation lands in slices (a) → (b) → (c) below.

---

## Goals

1. Display dashboards from **multiple HA servers simultaneously**, in
   one screen, with no global "current server" mode-switch.
2. **Offline-first.** Every screen the user has opened recently must
   render from local storage with no network. Reconnecting catches the
   cache up; it does not gate display.
3. The add-on, when present, is **a transparent rendering offload**.
   The user neither sees nor configures "which server rendered this
   card" — it's an implementation detail.
4. **Graceful degradation.** Add-on missing → local rendering. Local
   converter missing for a card type → "unsupported" placeholder.
   Network missing → cached bytes + last-known state.

## Non-goals

- A user-facing setting to choose "render this card locally vs on the
  server". The mix is decided automatically by a priority chain; the
  user only ever sees a card. (If we discover per-card-type quality
  gaps later, the override lives in a config file or A/B flag, not
  in the UI.)
- Per-device cards (e.g. "show this on Wear only"). Profile-aware
  rendering of the *same* dashboard on different surfaces, yes; but
  there's one dashboard list per server, shared across devices.
- Editing dashboards. Read-only mirror of the user's HA Lovelace.

---

## The three layers per HA server

For every dashboard rendered, three operations happen — and each has a
different sourcing rule. **This is the load-bearing distinction in the
whole design**; the rest of the doc follows from it.

| Layer | What | Source | Why |
|---|---|---|---|
| **a) Structure** | dashboard config — views, sections, cards (Lovelace's `lovelace/config` payload) | **always HA Core directly**, over the standard WebSocket | HA owns the truth; the add-on adds nothing. Going via the add-on would just put a cache in front of an authoritative source. |
| **b) Card generation** | the `.rc` byte stream the RemoteCompose player renders | **priority chain** of generators, decided per-card. Add-on first if available; local `rc-converter` always last as the safety net. | This is the expensive layer — Compose-style authoring, encoding to RC bytes — and the layer where the add-on actually pays for itself. |
| **c) Data** | live entity states (`hass.states`, `state_changed` events) | **always HA Core directly**, over the standard WebSocket — same connection as (a) | One client ↔ one HA WS for state. Routing state through the add-on adds latency and a single point of failure for live updates. |

The add-on, in this model, is a **pure card-rendering offload**. It
keeps its own `/v1/stream` for diagnostics (and for non-Android clients
that want to skip running an HA WS themselves), but the Android client
treats it as a stateless byte producer.

That isolation means a broken/missing/slow add-on never blocks
structure or data — at worst the user gets local rendering for the
card types `rc-converter` covers, plus an "unsupported card"
placeholder for the ones it doesn't (same behaviour as today).

### Worked example

User has two HA servers configured: `home` (with add-on) and `cabin`
(no add-on). They open the dashboards screen.

```
1. Structure: client opens HA WS to `home` and `cabin` in parallel.
   Calls `lovelace/config` on each. → 2 Dashboard structures.
2. Cards (per dashboard, per card):
     - server=home,  card=tile         → AddonCardGenerator hits
                                          home's add-on /v1/cards/...
                                          → bytes
     - server=home,  card=custom:foo   → AddonCardGenerator returns null
                                          (server doesn't support it)
                                          → falls through to
                                          LocalCardGenerator → bytes (or
                                          unsupported placeholder)
     - server=cabin, card=tile         → no add-on configured, chain
                                          starts at LocalCardGenerator
                                          → bytes
3. Data: each session keeps its own HA WS. State changes from `home`
   feed bindings on home's cards; same for `cabin`. Independent.
```

UI sees: one screen, four-ish cards, all "just work". No knob
exposed for which generator did what.

---

## Backend abstractions

All in `ha-client/commonMain` so KMP clients can reuse them. Android
adds the `LocalCardGenerator` impl (depends on Compose-Android
`rc-converter`); other Kotlin targets get add-on-only generation.

```kotlin
/** Layer (a) — dashboard structure. */
interface DashboardStructureSource {
    suspend fun listDashboards(): List<DashboardSummary>
    suspend fun fetchDashboard(urlPath: String?): Dashboard
}

/** Layer (b) — one strategy for turning a CardConfig + snapshot into RC bytes. */
interface CardGenerator {
    /** Lower wins. AddonCardGenerator = 0, LocalCardGenerator = 100. */
    val priority: Int

    /** Cheap "would you even try?" predicate. No I/O. Used to skip
     *  generators that obviously won't handle a card type so we don't
     *  burn an HTTP round-trip just to learn they don't. */
    fun supports(card: CardConfig, profile: ClientProfile): Boolean

    /** Returns null on graceful failure (501 from the server, network
     *  flap, unknown card type, etc.) so the next generator in the
     *  chain gets a turn. Throws only on programmer errors. */
    suspend fun generate(
        card: CardConfig,
        snapshot: HaSnapshot,
        size: CardSize,
        profile: ClientProfile,
    ): CardDocument?
}

/** Layer (c) — live entity state. */
interface StateStream {
    val states: StateFlow<Map<String, EntityState>>
    suspend fun connect()
    suspend fun close()
}
```

A `HaSession` ties them together for one server:

```kotlin
data class HaServer(
    val id: String,                    // stable UUID, key for Room
    val label: String,                 // user-visible name
    val haBaseUrl: String,
    val accessToken: String,
    val addonBaseUrl: String? = null,  // optional add-on URL
)

class HaSession(
    val server: HaServer,
    val structure: DashboardStructureSource,
    val state: StateStream,
    val cards: CardSource,    // priority chain, see below
)
```

`CardSource` is the chain that hides the priority logic from callers:

```kotlin
class CardSource(private val generators: List<CardGenerator>) {
    suspend fun render(card: CardConfig, snapshot: HaSnapshot,
                       size: CardSize, profile: ClientProfile): CardRender {
        for (g in generators.sortedBy { it.priority }) {
            if (!g.supports(card, profile)) continue
            val doc = g.generate(card, snapshot, size, profile) ?: continue
            return CardRender.Bytes(doc, generator = g::class)
        }
        return CardRender.Unsupported(card.type)
    }
}
```

### Session construction

`HaSessionFactory.create(server)`:

1. Open one HA WS via `HaClient` → wrap as the session's
   `DashboardStructureSource` and `StateStream`. **Required** — if
   this fails the session is unusable, surface an error.
2. If `server.addonBaseUrl != null`, probe `<addon>/healthz` with a
   1.5 s timeout. **Optional** — failure here is normal; we just
   skip the add-on.
3. Build the generator chain:
   - if probe succeeded → `[AddonCardGenerator(server), LocalCardGenerator()]`
   - else → `[LocalCardGenerator()]`
4. Wrap in `CardSource`.

The probe runs once at session open. Long-running add-on outages are
detected by `AddonCardGenerator.generate` returning null (network
error → log + null → fall through). The chain doesn't need to be
rebuilt; the local generator is always there as the fallback.

---

## Multiple HA servers

`HaServerStore` (Room — see slice (b)) holds a list of `HaServer`s.
The app keeps a `Map<HaServer.id, HaSession>` and surfaces dashboards
from all sessions in one screen, namespaced by server label. Each
session owns one HA WS — N servers configured = N HA WS connections,
no more.

Per-server isolation is hard-wired by construction:

- a broken token on server B never affects A's render path,
- a wedged WS on A doesn't block dashboard listing for B,
- offline reads (Room) work whether 0, 1, or N servers are reachable,
- an add-on outage on A's host doesn't affect B's add-on.

```
   ┌──────────┐   HA WS    ┌────────────┐
   │  HaSession A ◀────────▶ HA Core (A) │
   │              │ /v1/cards ▲          │
   │              │     ▶ add-on (A) ────┘
   └──────────┘
   ┌──────────┐   HA WS    ┌────────────┐
   │  HaSession B ◀────────▶ HA Core (B) │   (no add-on)
   └──────────┘
```

---

## Offline-first via Room

UI never reads from the network. It reads Room, and a sync layer keeps
Room current. This is the load-bearing rule for offline.

### Tables (slice (b) finalises)

| table | key | value |
|---|---|---|
| `ha_servers` | `id` | label, urls, encrypted token |
| `dashboards` | `(server_id, url_path)` | resolved `Dashboard` JSON, `etag`, `fetched_at` |
| `cards` | `(server_id, dashboard_path, view_idx, card_idx, profile)` | `.rc` bytes, w/h/density, referenced `entity_ids`, generator name (debug only), `generated_at` |
| `entity_states` | `(server_id, entity_id)` | latest state JSON, `last_updated` |

### Sync workers

Per-server, scoped to session lifetime. All write through the same
Room database; UI observes via Flow queries.

- **`StructureSync`** — fetches dashboard configs on session open and
  on `lovelace_updated` events. Writes `dashboards`. Triggers card
  re-render for changed entries.
- **`StateSync`** — owns the HA WS state subscription. Writes
  `entity_states` on each `state_changed` event. Cheap.
- **`CardSync`** — for each `dashboards` row × profile we care about,
  asks `CardSource` for bytes and writes them to `cards`. Re-runs on
  (a) dashboard change, (b) coarse state changes that affect
  non-binding values (icon-by-state etc. — most state changes go
  through `LiveBindings` and don't invalidate bytes).

### Read path

```
UI Composable
  ↓ collectAsState()
Flow<DashboardWithCards>           ← Room query, observable
  ├─ dashboards  (joined)
  └─ cards       (rows for the active profile)
       ↓ render
   RemoteDocumentPlayer(bytes)
       ↑ live bindings
   Flow<EntityState>               ← Room query on `entity_states`
       ← StateSync writes
```

When the device is offline:

- structure: served from `dashboards` (last-fetched config);
- card bytes: served from `cards` (last-rendered);
- live values: bindings stop animating but the player keeps the
  last-pushed value on screen — the card just looks frozen, not
  broken;
- when the device comes back, `StateSync` resumes the WS, pushes a
  hydrate, and bindings come back to life with no UI re-render.

---

## Slicing

### Slice (a) — abstractions + add-on client + fallback (next PR)

**Scope.** Deliver the runtime so a client can transparently mix
add-on and local rendering for a single configured server. No Room
yet — sessions are still in-memory.

**Adds:**
- `ha-client` (KMP): `AddonClient` — REST wrapper for `/v1/cards/*`,
  `/v1/dashboards`, `/healthz`.
- `ha-client` (KMP): `CardGenerator` interface + `CardSource` chain +
  `AddonCardGenerator`.
- `rc-converter` (Android): `LocalCardGenerator` wrapping the
  existing `CardRegistry` + `captureCardDocument`.
- `terrazzo-core` (Android): `HaSession` becomes a 3-component
  composition; `HaSessionFactory.create(server)` runs the probe and
  builds the chain.
- Tests with Ktor `MockEngine`: probe success/failure, generator
  fallback when the higher-priority returns null, "no add-on
  configured" path.

**Doesn't change:**
- Storage (still in-memory).
- UI (existing call sites get the new behaviour for free; no screen
  rewrites).
- The add-on server (already shipped in M0–M2).

### Slice (b) — Room store

**Scope.** Make every read offline-first. Single-server users
benefit; multi-server isn't UI-exposed yet.

**Adds:**
- New `ha-store` module (Android) with the four tables above.
- `StructureSync` and `StateSync` workers.
- `CardSync` — runs the chain from slice (a), persists bytes.
- `HaSession` migrates to write through the store; reads become
  observable Room queries.
- One-time migration: existing single-server users get a default
  `HaServer` row on first launch, populated from `PreferencesStore`.

### Slice (c) — multi-server + UI

**Scope.** Expose the multi-server capability to users.

**Adds:**
- `HaServerStore` CRUD UI (add/edit/delete servers, paste tokens,
  optional add-on URL).
- Aggregated dashboards list across servers (label-prefixed).
- Per-server status indicator: HA online?, add-on online?, last sync
  timestamp.
- `CardSync` runs concurrently per server.

---

## Open questions (defer until the relevant slice)

- **State drift between client + add-on.** The add-on holds its own
  state cache for rendering decisions. The client's snapshot may
  diverge by milliseconds. For `LiveBindings` cards this is invisible
  (player applies live bindings on top of cached bytes). For
  non-binding values it can race — e.g. an icon picked from
  `state == "on"` rendered server-side using a stale state. *Slice
  (b)* mitigation idea: client includes its own `state_etag`
  (hash of relevant entity states) in the request; server returns
  `409` if its cache is older, client falls through to local
  generator and queues a re-fetch.
- **WS reconnect strategy across servers.** Slice (a) reuses the
  per-server backoff `HaClient` already does. Slice (b) needs a
  global "are we online?" signal so background workers don't burn
  battery retrying on a known-dead network. Defer to (b).
- **Token rotation.** HA long-lived tokens don't expire by default
  but can be revoked. Currently a 401 just kills the session;
  surface this in (c)'s status UI.

## What we explicitly are not building

- A user-facing setting to pick add-on vs local rendering per card.
  The chain is the contract; the user sees cards.
- A "primary server" concept — every configured server is equal.
- Live editing of HA dashboards from the client.
- A second WebSocket from client to add-on for state. (`/v1/stream`
  stays in the server for diagnostics + non-Android clients.)
