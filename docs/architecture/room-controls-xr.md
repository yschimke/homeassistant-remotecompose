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
2. **Pinned**: on a headset, the room of the pinned control (below) nearest the user, within
   4 m, once the headset has found its place.
3. **Tracker**: an entity the user follows (the location button in the top bar). Its state — or
   its `area` / `area_id` / `room` attribute — names an area by id, name or alias, ignoring case
   and separators. That covers Bermuda's `sensor.<phone>_area`, ESPresense and room-assistant
   sensors, and any template sensor a user builds. The menu offers the entities naming an area
   right now.
4. **Presence**: the area whose `presence`, then `occupancy`, then `motion` binary sensor most
   recently turned on.
5. **None**: the room the dashboards care most about, labelled as a guess.

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

## Pinned controls (XR)

Without a room signal, the user can place a control themselves. In full space every control panel
has **Pin here**: it creates an ARCore for XR anchor where the user is looking, persists it to the
headset's local storage (`AnchorPersistenceMode.LOCAL`) and records
`RoomPin(anchorId, baseUrl, entityId)` in `PreferencesStore.roomPins`. Stand by the heater, look at
the wall beside it, pin.

**Snapping to walls.** The session also runs plane tracking (`HORIZONTAL_AND_VERTICAL`). Pinning
casts the gaze against the tracked vertical planes (`room-ui` `PinPlacement.kt`, `wallPlacement`): the nearest
wall the gaze meets from the front, within 4 m and inside its extent (with 0.3 m of slack, since
tracked extents run short), takes the anchor 2 cm off its surface, facing into the room and
upright. With no wall in reach the pin goes in the air instead (`airPlacement`): 0.8 m ahead, level
with the eyes, facing the user. **Move here** on a pinned panel pins it again where the user now
looks (on a wall if there is one) and forgets the old anchor.

Why not `SubspaceModifier.anchorable()`: in Compose for XR 1.0.0-beta02 it is internal. Its public
counterpart, `movable(movePolicy = MovePolicy.anchor(...))` (`@ExperimentalMoveAnchorPolicy`),
snaps a panel to a plane while it is dragged, but reparents it under an anchor entity it doesn't
expose: the move event's pose is relative to that hidden parent, so there is nothing to persist
and restore next visit. Casting the gaze against `Plane.subscribe` gives a pose in the session's
own space, which `Anchor.create` and `persist` keep.

On later visits to full space the room screen loads each pin's anchor (`Anchor.load`) and puts a
panel on it with `Subspace(follow = FollowTarget.anchor(anchor))`, so the heater's controls are
where the user left them, live. The pin found nearest the user (polled every 2 s from the device
pose) is the **Pinned** room signal above, so pinning doubles as room calibration.

The first pin asks for `SCENE_UNDERSTANDING_COARSE` and `HEAD_TRACKING`. A pin whose anchor doesn't
load (the room changed, the headset forgot its spaces) is listed under "Pins not found here" on
the room panel to remove and pin again; **Unpin** on a pinned panel forgets the anchor on the
headset too. Pins live in `room-ui` (`RoomPins.kt`: storage, nearest-pin choice;
`PinPlacement.kt`: where a pin goes) and, for Android XR, `app/.../room/SpatialPins.kt` (anchors,
permissions, panels).

## Modules

The room controls are split so another XR platform can reuse everything but its spatial layer:

| Where | What |
|---|---|
| `ha-model` (`ee.schimke.ha.room`) | Areas, dashboard importance, room ranking, which room the user is in. Pure KMP. |
| `room-ui` (KMP: Android, JVM) | `RoomPin` storage and nearest pin; `pinPlacement` / `wallPlacement` / `airPlacement` on a platform-neutral `Vec3` and `WallPlane`; the `RoomAnchors` interface a platform implements for persisted anchors; `RoomState` and `sourceLabel`; the flat screen (`FlatRoomControls`, `RoomHeader`, `RoomTrackerMenu`, `LostPins`, `PinHereButton`), drawn from a `RoomContent` whose `entityCard` slot each host fills. |
| `app` (`ee.schimke.terrazzo.room`) | The glue (`RoomScreen`: preferences, dashboards, live states, the action dispatcher), the entity card in the user's card style (`RoomEntityCard`, the more-info controls), and the Android XR layer: `SpatialRoom.kt` (Compose for XR panels) and `SpatialPins.kt` (`PinAnchors`, the ARCore for XR `RoomAnchors`; permissions; panels on anchors). |

| `quest` (`ee.schimke.terrazzo.quest`) | The Meta Quest app on the Spatial SDK: `QuestActivity` (passthrough, the room panel, a panel per pin), `QuestRoomAnchors` (the MRUK `RoomAnchors`), the panels in the Spatial SDK UI set (`QuestRoomUi.kt`) and their previews. |

The `room-ui` row also has `ScenePanel`, `curvedRow` and `rotateY` for laying panels out around
the user, and `RoomScenePreview`, which draws walls, floor and panels from a camera behind the user
so a `@Preview` shows the spatial layout.

## Meta Quest

`:quest` reuses `ha-model` and `room-ui` and adds only the Spatial SDK layer. It doesn't depend on
`terrazzo-core`, which needs API 35 (Quest 3 runs API 34), so for now it shows a demo house
(`QuestDemoHouse`: a living room with a heater, TV, lamp and fan, and a kitchen) toggled in
memory. Signing in to a Home Assistant through `ha-client` comes next.

- **Room panel.** A grabbable 1.0 × 0.7 m panel 1.2 m ahead when the scene is ready (`airPlacement`),
  at 1000 dp a metre: the same `RoomContent` as the phone, drawn with `SpatialSwitch` and
  `BorderlessButton`. The shared Material parts (`RoomHeader`, `LostPins`) sit inside a dark
  Material scheme so they read on the dark panel.
- **Pins.** The Spatial SDK has no public API for an app to persist its own anchors, but Horizon OS
  keeps the room's Space Setup anchors (each wall, the room) with stable UUIDs. So a Quest pin is
  stored relative to one of those (`QuestPinPlace`, encoded into `RoomPin.anchorId`): on a wall, the
  wall's UUID and the offset across and up it from its centre; in the air, the room's UUID and the
  pose in the room anchor's frame. `pinPlacement` decides which, using MRUK's walls as `WallPlane`s
  (the hit wall comes back as `PinPlacement.wall`). Loading looks the anchor up again; a pin whose
  wall or room is gone (Space Setup was redone) is listed under "Pins not found here".
- **Which room.** As on Android XR: the pin the user stands nearest, checked every two seconds.
- **To check on a headset.** Panels are posed facing against their forward axis
  (`panelPose`); if they show their backs, that sign flips.

### Previews

The Spatial SDK needs its native runtime, so `QuestActivity` doesn't render off-device (its
synthetic `activity__QuestActivity` preview fails and is tolerated, like the phone's). Everything
inside the panels is plain Compose, so `QuestPreviews.kt` shows:

- **Room panel** and **pinned heater** at their real size, 1000 × 700 dp and 360 × 200 dp at 160 dpi
  (1 dp a pixel).
- **Room scene**: a 4 × 5 m living room from behind the user through `RoomScenePreview`, with the
  room panel ahead and the heater and TV pinned on the walls where `pinPlacement` puts them. Each
  panel is the real composable, scaled and turned in perspective.

Run `compose-preview show --filter Quest` to render them.

## Not yet

- Following the headset's own position: Android XR doesn't place the headset in a Home Assistant
  area. A tracker entity (Bermuda with the headset or phone as the beacon) is the bridge for now.
- Dragging a pinned panel along a wall: once Compose for XR exposes the anchor a
  `MovePolicy.anchor` drag lands on, that can replace **Move here**.
- Tables: horizontal planes are tracked but pins only snap to walls; a control lying flat on a
  table needs its own facing rule.
- Wear, TV and the shared desktop / Wasm hosts don't show room controls yet; the ranking lives in
  `ha-model` so they can.
