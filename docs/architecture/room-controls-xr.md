# Room controls and the XR experience

"This room" (dashboard overflow menu → **This room**) shows the controls for the room the user is
in: turn off the room's heater, turn down its TV, without finding the card on a dashboard. On an
Android XR headset it can surround the user: a spatial panel per control on a curve at arm's
length.

It answers three questions, each from data Home Assistant already has.

## 1. Which controls matter? — the dashboards

A dashboard is the user's own answer to "which of my hundreds of entities do I reach for?".
`DashboardImportance` (`ha-model`, `ee.schimke.ha.room`) scores every entity the selected
dashboards show:

| shown as | weight |
|---|---|
| a control card (`tile`, `button`, `light`, `thermostat`, `humidifier`, `media-control`, `alarm-panel`, `picture-entity`) | 3 |
| a row of an `entities` / `glance` card, or an unknown card | 2 |
| a display card (`gauge`, `sensor`, `history-graph`, `statistic(s-graph)`, `weather-forecast`) | 1 |
| … with tile `features` (volume slider, target temperature) | +1 |
| … on a dashboard's first view | ×1.5 |

Scores add across cards and dashboards, nested stacks / grids / conditionals included. An `area`
card scores its area, which orders the room picker.

## 2. Where is each entity? — areas

`HaClient.fetchDevices` reads each entity's area from the entity registry's display list (`ai`),
falling back to its device's `area_id`, as Home Assistant's frontend does; it also keeps the
entity category (`ec`) and hidden flag (`hb`), so configuration and diagnostic entities stay out
of the room as they stay off HA's own area pages. `HaClient.fetchAreas` reads
`config/area_registry/list` (names, floors, aliases). Both land on `HaSnapshot` (`devices`,
`areas`) and are read once per session.

`roomControls(area, snapshot, importance)` keeps the area's controllable domains (lights,
switches, climate, media players, covers, fans, …), then ranks them: dashboard score first, then
on before off (a running heater is the likelier one to want changing), then domain, then name. The
top six with a dashboard score are the room's **primary** controls; the rest (lower-ranked
dashboard entities, then those no dashboard shows) are **more**, still reachable. Names drop the
room's prefix, as HA's area page does ("Living Room TV" → "TV" in the Living Room).

## 3. Which room is the user in? — `currentRoom`

1. **Picked**: a room chip the user tapped. It sticks until they tap **Automatic**.
2. **Tracker**: an entity the user follows (the location button in the top bar). Its state — or
   its `area` / `area_id` / `room` attribute — names an area by id, name or alias, ignoring case
   and separators. That covers Bermuda's `sensor.<phone>_area`, ESPresense and room-assistant
   sensors, and any template sensor a user builds. The menu offers the entities naming an area
   right now.
3. **Presence**: the area whose `presence`, then `occupancy`, then `motion` binary sensor most
   recently turned on.
4. **None**: the room the dashboards care most about, labelled as a guess.

The picked room and the tracker are stored in `PreferencesStore` (`roomPicked`, `roomTracker`).

## Live data

The screen reads `HaSession.liveStates` (which carries the registries) and re-ranks on every
state change, so a heater turning off moves down and its controls follow the state. Controls are
the more-info sheet's own (`entityControls` + `EntityControlsPanel`) sent through the dashboard's
action dispatcher, so read-only mode, demo mode, haptics and the action log apply as on a card.
Nothing here encodes a Remote Compose document: there is no bake to go stale.

## XR

`app` depends on Jetpack Compose for XR (`androidx.xr.compose:compose`) and declares
`android.software.xr.api.spatial` as optional, so the same APK serves phones, tablets and
headsets.

- **Phones / tablets**: no XR feature, so `SpatialRoomHost` always picks the flat grid.
- **Headset, home space**: the flat grid in the app's window, with a "Surround me" button in the
  top bar that calls `requestFullSpace()`.
- **Headset, full space** (`LocalSpatialCapabilities.isSpatialUiEnabled`): `SpatialRoomControls`
  — a `Subspace` with a `SpatialCurvedRow`: the room panel (where you are, room chips, the
  **more** list; movable and resizable) and one movable `SpatialPanel` per primary control. An
  orbiter under the room panel goes back (leaving full space first, since the dashboards are flat)
  or collapses to home space.

## Not yet

- Following the headset's own position: Android XR doesn't place the headset in a Home Assistant
  area. A tracker entity (Bermuda with the headset or phone as the beacon) is the bridge for now.
- Spatial anchors per room (pinning the heater's panel to the heater). Compose for XR's
  `anchorable` and ARCore for XR persistent anchors would allow it; it needs a per-room store of
  anchor ids.
- Wear, TV and the shared desktop / Wasm hosts don't show room controls yet; the ranking lives in
  `ha-model` so they can.
