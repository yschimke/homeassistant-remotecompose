# RemoteCompose alpha08: `.clip(RemoteCircleShape)` on a square Box renders as a rounded rectangle

**For filing at** https://issuetracker.google.com (androidx.compose.remote component).

## Summary

Clipping a square `RemoteBox` with `RemoteCircleShape` (or
`RemoteRoundedCornerShape(percent = 50)`) produces a rounded
rectangle at playback, not a full circle.

## Repro

```kotlin
RemoteBox(
    modifier = RemoteModifier
        .size(32.rdp)
        .clip(RemoteCircleShape)              // or RemoteRoundedCornerShape(percent = 50)
        .background(Color.Red.rc),
    contentAlignment = RemoteAlignment.Center,
) { }
```

Rendered via `RemotePreview(profile = androidXExperimental)` at density
2.625 (preview canvas 861 × 147 px in my repro). The 32 dp → 84 px box
shows with corners rounded to only ~8 px instead of the expected 42 px
(50 % of 84). Visibly a rounded rectangle.

Same result with the explicit `.clip(RemoteRoundedCornerShape(16.rdp))`
(half of 32 dp).

## Expected

Full circle — corners = 50 % of box dimension.

## Environment

- `androidx.compose.remote:remote-creation-compose:1.0.0-alpha08`
- `androidx.compose.remote:remote-player-compose:1.0.0-alpha08`
- `androidx.wear.compose.remote:remote-material3:1.0.0-alpha02`
- Gradle 9.3.1, AGP 9.1.0, Kotlin 2.1.20
- Rendered via `ee.schimke.composeai.preview` plugin 0.7.4 (Robolectric)

## Workarounds

None found. The same `.clip(RemoteRoundedCornerShape(N.rdp))` call on
the outer 12 dp rounded-corner card surface works visually at small
corner-size, so the issue seems specific to high corner-percent or
small square dimensions inside a row.

## Live code

- `RemoteHaTile`: [rc-components/src/main/kotlin/ee/schimke/ha/rc/components/RemoteHaTile.kt](../../rc-components/src/main/kotlin/ee/schimke/ha/rc/components/RemoteHaTile.kt)
- Rendered output showing the issue: the `Front door` tile's padlock
  badge in
  https://gist.githubusercontent.com/yschimke/09c6fbeb83f71b4de3442a410010ee3f/raw/rc_tile_lock_locked.png —
  the green chip behind the padlock should be circular.

## What I tried

1. `.clip(RemoteRoundedCornerShape(16.rdp))` — same rounded-rectangle look.
2. `.clip(RemoteRoundedCornerShape(percent = 50))` — same.
3. `.clip(RemoteCircleShape)` (the library's built-in circle) — same.
4. Moved `.size(32.rdp)` before and after `.clip` — no difference.
