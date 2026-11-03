# Meta Quest headset acceptance test

Quest spatial room controls shipped in
[PR #1381](https://github.com/yschimke/homeassistant-remotecompose/pull/1381) (merged as
`7d35520a`). Unit tests, lint, debug assembly and API-36 instrumented tests cover the module;
this checklist is the remaining on-device check, which only a headset can do.

## What shipped

- Home Assistant OAuth sign-in, secure refresh-token storage, session restoration and sign-out
  (`QuestAuth.kt`), with protection against stale OAuth responses and restored sessions.
- A movable Spatial SDK room panel with passthrough (`QuestActivity.kt`, `QuestRoomUi.kt`).
- Space Setup / MRUK-backed control pinning (`QuestRoomAnchors.kt`, `QuestRoomModel.kt`).
- **Move here** for an existing pin, which moves it rather than adding a duplicate panel.
- Controls locked by default; an explicit unlock is persisted.
- Fallback messaging when Space Setup or Horizon OS room mapping is unavailable.

All sources are under `quest/src/main/kotlin/ee/schimke/terrazzo/quest/`.

## Build

```bash
./gradlew :quest:test :quest:assembleDebug
```

The debug APK lands in `quest/build/outputs/apk/debug/`.

## Checklist

Use a **safe Home Assistant test entity only** (a helper `input_boolean`, not a lock, alarm or
heater).

1. Enable Quest Developer Mode and connect over USB.
2. Install the debug APK: `adb install -r quest/build/outputs/apk/debug/quest-debug.apk`.
3. Launch Terrazzo from **Unknown Sources**.
4. Allow Space Setup access.
5. Sign into a reachable Home Assistant URL.
6. Live state is correct on the first frame after launch — no stale value that later corrects.
7. Controls start locked. Unlock, toggle the test entity, restart the app, and confirm the
   unlock persists.
8. Pin an entity to a mapped surface, then use **Move here** elsewhere: exactly one panel remains.
9. Lock controls again: pinned controls no longer toggle Home Assistant.
10. Unpin: the spatial panel disappears.
11. Clear app data, deny Space Setup: the main panel is still movable and pinning is clearly
    explained as unavailable.

Record the result (headset model, Horizon OS version, commit, pass/fail per step) on the PR or
issue that tracks the run.

## Useful commands

Clean test state:

```bash
adb shell pm clear ee.schimke.terrazzo.quest
```

Logs:

```bash
adb logcat -c
```

```bash
adb logcat -v time | grep -Ei 'terrazzo|quest|appauth|mruk|metaspatial'
```
