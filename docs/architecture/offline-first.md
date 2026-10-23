# Offline-first data flow

The Terrazzo apps (`app/`, `wear/`, `tv/`) follow an offline-first
contract: once a user has logged in and the app has fetched data
once, every subsequent launch must render the last-known dashboards
and entity values without a network. When the network is reachable
the cache refreshes in the background and the UI updates in place.

This document records the contract, the cache layout, and the
read/write paths each app uses.

## Contract

A surface is **offline-first** when:

1. Cold launch with no network shows the user's last known data
   instead of a spinner or error screen.
2. Live data, when it arrives, replaces cached data in place
   without flicker.
3. Going offline mid-session does not blank the UI — it freezes the
   last good values and (if visible at all) shows a small
   "showing cached data from <ts>" hint.
4. Mutations (toggling a switch, etc.) are out of scope here — they
   inherently require connectivity. UI affordances may stay
   interactive; the call simply fails with a normal error.

## Caches

Each app stores cached data in the format that matches its source — JSON
for the mobile cache (HA's `lovelace/config` and entity-state payloads
are JSON over the WebSocket), proto wire bytes for the wear cache (the
wear data layer already speaks proto). Each blob lives in its own file
so a corruption or partial write only loses one entry, never the whole
cache. Writes are atomic (tmp + rename).

### Mobile (`app/` + `terrazzo-core`)

`OfflineCache` (in `terrazzo-core`'s `androidMain`) owns one
`<instanceId>` directory per HA instance. `instanceId` is the
normalized `baseUrl` (no trailing slash) — the same key
[`TokenVault`](../../terrazzo-core/src/androidMain/kotlin/ee/schimke/terrazzo/core/auth/TokenVault.kt)
uses, so credentials and cached payloads share a lifecycle.

```
filesDir/
  terrazzo/
    instance.json                     # { baseUrl }
    cache/<sha-baseUrl>/
      dashboards.json                 # List<DashboardSummary>
      dashboard/<urlPath>.json        # Dashboard
      snapshot/<urlPath>.json         # HaSnapshot
```

`urlPath` may be `null` (HA's default dashboard); we encode that as
the file name `_default`.

`CachedHaSession` (in `terrazzo-core`) wraps a `LiveHaSession` and
mirrors every successful fetch into the cache. `listDashboards()`
and `loadDashboard()` first emit the cached value (if any) via the
`Flow<...>` companion APIs, then attempt a live fetch and emit the
fresh result. Failures never overwrite a successful cache entry.

`TokenVault.lastInstance()` returns the most-recently-stored
instance, used by `MainActivity` to auto-resume on cold start. If
the access-token mint fails (no network, refresh token expired) the
app still constructs a session using only the cache; UI surfaces
fall through unchanged because they always read from cache first.

### Wear (`wear/`)

`WearSyncRepository` already builds StateFlows from
DataClient/MessageClient. We add a parallel `WearOfflineStore` that
persists each blob the repository handles as proto wire bytes (same
encoding the wear data layer itself uses — no re-serialisation). One
file per blob type so each blob has an independent lifecycle:

```
filesDir/
  terrazzo/
    wear/
      settings.pb                     # WearSettings
      pinned.pb                       # PinnedCardSet
      values.pb                       # LiveValues (full or merged)
      dashboards/<urlPath>.pb         # DashboardData
```

The repository hydrates its StateFlows from these files in its primary
constructor, so the very first composition sees cached data — listeners
attach afterwards and overlay live updates. Stream deltas merged into
the in-memory map are written back to `values.pb` so a process restart
resumes from the latest delta-updated state, not the last full DataItem.

This way:

- Cold-start, phone unreachable → wear renders the last known
  dashboard list, pinned cards, and values from disk.
- Phone reconnects → DataClient cold-read overwrites with newer
  proto; subsequent stream deltas merge as before.

### TV (`tv/`)

TV does not yet wire an HA session — it's kiosk + demo today. The
offline-first scaffold added here is `TvOfflineCache`: a blob store
under the TV app's `filesDir/terrazzo/tv/<scope>/<name>`, with the
same per-instance / atomic-write semantics as the mobile and wear
caches. The cache stores raw payloads (callers own the format —
JSON for HA-shaped data, proto if the TV app ever pairs with the
phone) so the TV module can stay minimal until it has a real use
for it. A future change that lands a `TvHaSession` will plug into
the same `CachedHaSession` wrapper used by mobile.

## Auto-resume

`MainActivity.onCreate` (mobile) does a synchronous
`runBlocking` read of `TokenVault.lastInstance()` (already does
this for `lastViewedDashboardNow()`). If an instance is present:

1. Construct a cache-only `CachedHaSession` immediately so the first
   composition has a session to render against — backed by the
   on-disk cache, so the UI can paint without a network call.
2. As soon as the vault holds a refresh token for it, swap in a live
   `CachedHaSession(LiveHaSession)` **without waiting for a token**: the
   session mints its access token from the refresh token when it
   connects. Offline, that connect fails and the session reads Failed;
   `TerrazzoApp`'s reconnect loop retries with backoff (2 s doubling to
   5 min), and at once whenever `LanConnectionPolicy.networkChanges`
   reports the network coming back. A cold start offline therefore goes
   live the moment it can, instead of staying on the cache for the life
   of the process.
3. Only Home Assistant refusing the refresh token (an OAuth
   `invalid_grant` / `invalid_request` / `access_denied` body) flips the
   session's `needsReauth`, which signs out. A bare 401 / 403 / 400 —
   a reverse proxy, access gateway or captive portal — is transient, so
   it never signs the user out or deletes their cached dashboards.

## Live refresh

The open dashboard loads again every time its session connects
(`DashboardViewScreen`): the first load of an offline start only reached
the cache, and a reconnect may have missed both config edits and state
changes. Between connects, pushed `state_changed` events update it in
place, and are written back to its cached snapshot debounced (5 s), so
the next offline start shows the values last on screen.

A live read that comes back without the best-effort per-instance facts
(device registry, unit system) keeps the cached ones rather than
overwriting them with nothing.

## Images

Mobile: one Coil 3 `ImageLoader` with a disk cache in
`noBackupFilesDir/terrazzo/images` (10–100 MB), moved out of `cacheDir`
so the system doesn't clear the pictures an offline dashboard draws.
Its OkHttp client attaches the session bearer by origin, then rewrites a
LAN URL to the instance's `external_url` off the home network
(`RemoteUrlInterceptor`).

Coil 3's default cache strategy serves any disk hit without
revalidating and ignores `Cache-Control`. For most of what Home
Assistant serves that is right, and Home Assistant's own headers agree:

| Endpoint | HA's headers | URL changes when the picture does? |
| --- | --- | --- |
| `/api/image/serve/<id>/<size>` (person and uploaded pictures) | `public, max-age` 31 days | yes (new id) |
| `/api/media_player_proxy/<entity>?token=&cache=<hash>` | `max-age=3600` | yes (`cache` is the art's hash) |
| `/local/…` (the `www` folder) | `public, max-age` 31 days | no: edits show after eviction, as in a browser for a month |
| `/api/camera_proxy/<entity>?token=` | none | no, and the token rotates every 5 min (two valid at once) |
| `/api/image_proxy/<entity>?token=` | none | no, same rotating token |

The last two change in place, so `LiveImageCacheInterceptor` keys them
on the URL without `token` (one disk entry per entity rather than one
per rotation) and fetches them network first, falling back to the last
frame on disk when the fetch fails (offline, an expired token) — or
straight to disk when the device has no network.

Everything else that can change at one URL — `/local/` files, other
sites' pictures — keeps Coil's disk-first rule until its copy is a day
old, then goes network first the same way, the old copy standing in
when the fetch fails. Content-addressed pictures (`/api/image/serve/…`,
media artwork with `cache=`) are never refetched.

A dashboard camera gets a new frame only when Home Assistant rotates its
token, every five minutes. A card's long-press sheet has a **Live
camera** switch: for ten minutes, while the dashboard is in the
foreground, its cameras' URLs gain a `terrazzo_frame` counter every two
seconds (`LiveCameras`), each a network-first fetch; the disk key leaves
the counter out, so it still holds one frame per camera. A small pulsing
dot in the card's corner marks a live camera.

Sign-out clears the image caches, memory and disk, with the instance's
other cached data.

After each live dashboard load, `HaImageStack.prewarm` fetches the
`entity_picture`s of the dashboard's entities that aren't on disk yet,
without a memory-cache entry, so cards not yet scrolled into view still
have pictures on the next offline start.

Wear: no network image loader. The phone syncs each card entity's
picture over the Data Layer into `pictures/`, and both the widgets and
the in-app cards resolve an `entity_picture` URL to the entity's synced
copy on disk.

## History

The card-history screen's recorder read is cached per entity set and
window (`history/<sha1>.json`, the 20 most recent kept): offline it
draws the last chart read. Statistics and forecasts are not fetched by
any card yet, so there is nothing to cache.

## Wear widgets

A widget renders from `WearOfflineStore` before anything else: each
Data Layer read ahead of the render is capped at 2 s, and the direct
Home Assistant read (for values the phone hasn't sent lately) runs after
the render, re-rendering the widget only when it stored newer values.

## Error and refresh signals

`HaSession.snapshotFetchedAtMs(urlPath)` is null after a live fetch and,
after a fallback, says when the cached snapshot was fetched (the Wear
sync uses it to date what it sends). When the session has failed, the
phone's status strip reads "Offline · data from 5 min. ago", dated by
the open dashboard's cached snapshot (live pushes are saved to it, so it
is as old as the values on screen).

## Sign-out

Sign-out clears (in order):

1. The vault entry for the instance — so a relaunch does not
   auto-resume.
2. The cache directory for the instance — so subsequent users on
   the device cannot read the previous user's dashboard config.
3. `lastViewedDashboard` and `lastInstance` prefs.

Demo mode does not write to the cache (its session is
deterministic and re-derived from `DemoData`), so toggling
demo mode is a no-op against the cache layer.
