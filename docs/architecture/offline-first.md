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

All persistent state lives under
`context.filesDir/terrazzo/<scope>/...`. JSON for everything except
the wear sync proto blobs, which already have a wire schema.

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
      meta.json                       # last-fetched timestamps
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
DataClient/MessageClient. We add a parallel `WearOfflineStore`
(JSON file under `filesDir/terrazzo/wear/last_state.json`) that
mirrors every successful proto blob handled and rehydrates from
disk on `start()` before the listeners attach. Schema is the
same proto types we already use (`WearSettings`, `PinnedCardSet`,
list of `DashboardData`, `LiveValues`).

This way:

- Cold-start, phone unreachable → wear renders the last known
  dashboard list, pinned cards, and values from disk.
- Phone reconnects → DataClient cold-read overwrites with newer
  proto; subsequent stream deltas merge as before.

### TV (`tv/`)

TV does not yet wire an HA session — it's kiosk + demo today. The
offline-first scaffold added here is `TvOfflineCache`: same JSON
layout as the mobile cache, scoped to the TV app's `filesDir`. A
future change that lands a `TvHaSession` will read/write from this
cache via the existing `CachedHaSession` wrapper, so wall-mounted
TVs that lose the LAN keep their last good dashboard on screen.

## Auto-resume

`MainActivity.onCreate` (mobile) does a synchronous
`runBlocking` read of `TokenVault.lastInstance()` (already does
this for `lastViewedDashboardNow()`). If an instance is present:

1. Construct a `CachedHaSession` immediately so the first
   composition has a session to render against — backed by the
   on-disk cache, so the UI can paint without a network call.
2. Asynchronously refresh the access token (`TokenVault.get` →
   AppAuth refresh exchange). On success, the session upgrades to
   live; on failure, the session remains cache-only and the UI
   shows a small "offline" badge.

This eliminates the prior "always go through discovery + login on
cold start" path. Sign-out clears both the vault entry and the
cache directory for that instance.

## Image cache (follow-up)

RemoteCompose documents fetch their own raster bytes when a card
contains an `entity_picture` URL or addon-rendered prebaked PNG.
There is no native image-loading library (no Coil/Glide) in the
project today. A future change should:

1. Add a Coil 3 OkHttp loader to `app/` and `wear/`, with a disk
   cache rooted at `filesDir/terrazzo/images/`.
2. Provide a `DiskCachedImageLoader` to RemoteCompose so card
   documents resolve image URLs through the disk-cached pipeline
   instead of bypassing it.
3. Pre-warm the cache when a dashboard is first cached, so first
   offline paint includes images.

Tracked here so it's not lost; not implemented in the current
offline-first PR.

## Error and refresh signals

The cache exposes a `lastFetchedAtMs(instanceId, scope)` helper.
The "showing cached data" pill in the UI uses this to render a
human-readable freshness ("3 min ago"). The pill appears only when
the most recent live fetch failed — successful fetches reset the
fail flag and hide the pill.

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
