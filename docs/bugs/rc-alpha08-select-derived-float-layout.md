# RemoteCompose alpha08: a `RemoteFloat` derived from `RemoteBoolean.select` (or `animateRemoteFloat`) breaks `.clip()` and inner content of the surrounding layout

**Filed as** [b/504893436](https://issuetracker.google.com/issues/504893436) (not publicly visible).

## Summary

Whenever a `RemoteFloat` value is derived from a non-constant source —
specifically `RemoteBoolean.select(1f.rf, 0f.rf)` or
`animateRemoteFloat(...)` — and is then used either:

1. as the `t` argument to `lerp(...)` inside a
   `Modifier.background(<RemoteColor>)`, or
2. as the argument to `RemoteRowScope.weight(<RemoteFloat>)`,

…the surrounding `RemoteBox` / `RemoteRow` loses its `.clip(...)` shape
and its child content disappears. The background color still paints, so
visually the result is a flat colored rectangle instead of the intended
clipped shape with knob / inner content.

If the same `RemoteFloat` is replaced with a literal (`0f.rf`,
`0.5f.rf`, `1f.rf`), the layout renders correctly.

## Repro

A pill-shaped toggle switch — outer `RemoteBox` with rounded clip and
animated track color, inner `RemoteRow` with a knob between two
weight-driven spacers:

```kotlin
@Composable
@RemoteComposable
fun RemoteHaToggleSwitchByProgress(
    progress: RemoteFloat,
    activeAccent: RemoteColor,
    inactiveAccent: RemoteColor,
) {
    val trackColor = lerpRemoteColor(inactiveAccent, activeAccent, progress)
    val rightWeight: RemoteFloat = 1f.rf - progress

    RemoteBox(
        modifier = RemoteModifier
            .size(width = 36.rdp, height = 22.rdp)
            .clip(RemoteRoundedCornerShape(11.rdp))
            .background(trackColor),
    ) {
        RemoteRow(modifier = RemoteModifier.fillMaxWidth().fillMaxHeight()) {
            RemoteBox(modifier = RemoteModifier.weight(progress).fillMaxHeight())
            RemoteBox(modifier = RemoteModifier
                .size(16.rdp)
                .clip(RemoteCircleShape)
                .background(Color.White.rc))
            RemoteBox(modifier = RemoteModifier.weight(rightWeight).fillMaxHeight())
        }
    }
}

private fun lerpRemoteColor(from: RemoteColor, to: RemoteColor, t: RemoteFloat): RemoteColor =
    RemoteColor(
        lerp(from.alpha, to.alpha, t),
        lerp(from.red,   to.red,   t),
        lerp(from.green, to.green, t),
        lerp(from.blue,  to.blue,  t),
    )
```

### Three call sites

| Call site | `progress` value | Result |
| --- | --- | --- |
| ✅ Works | `0f.rf` (literal) | Rounded pill, white knob at left |
| ✅ Works | `0.5f.rf` (literal) | Rounded pill, white knob centered |
| ✅ Works | `1f.rf` (literal) | Rounded pill, white knob at right |
| ❌ Broken | `rememberMutableRemoteBoolean(true).select(1f.rf, 0f.rf)` | Flat colored rectangle, no clip, no knob |
| ❌ Broken | `animateRemoteFloat(rf = above, duration = 0.18f)` | Same: flat colored rectangle |

## Expected

Whatever the literal-`RemoteFloat` path renders, the
`select`-derived-`RemoteFloat` path should render too. The whole point
of the in-document state model is to let the layout react to a
`RemoteBoolean` without re-encoding.

## Actual

The outer `Modifier.clip(RemoteRoundedCornerShape(11.rdp))` is dropped.
The inner `RemoteRow` content does not render. Only the
`Modifier.background(<lerp-derived RemoteColor>)` paints — and it paints
in the unclipped 36×22 rectangle. The track color itself reflects the
`select` value correctly (gray at false, blue at true), so the
`RemoteFloat` is reaching the color computation; it just trashes
everything else in the surrounding `RemoteBox`.

## Environment

- `androidx.compose.remote:remote-creation-compose:1.0.0-alpha08`
- `androidx.compose.remote:remote-player-compose:1.0.0-alpha08`
- `androidx.wear.compose.remote:remote-material3:1.0.0-alpha02`
- Gradle 9.3.1, AGP 9.1.0, Kotlin 2.1.20, Compose compiler 1.5.15
- Rendered via `ee.schimke.composeai.preview` plugin 0.7.5 (Robolectric)
- `RemotePreview(profile = androidXExperimental)` — `PROFILE_ANDROIDX | PROFILE_EXPERIMENTAL` (FlowLayout op 240 enabled)

## Live code

- Component: [rc-components/src/main/kotlin/ee/schimke/ha/rc/components/RemoteHaEntityRow.kt](../../rc-components/src/main/kotlin/ee/schimke/ha/rc/components/RemoteHaEntityRow.kt) — see the `RemoteHaToggleSwitch` doc-comment.
- Previews: [previews/src/main/kotlin/ee/schimke/ha/previews/ToggleSwitchPreviews.kt](../../previews/src/main/kotlin/ee/schimke/ha/previews/ToggleSwitchPreviews.kt) — `ToggleByProgress_*` (works) vs `ToggleInitial_*` (would have shown the bug; now uses Kotlin-side branch + literal RemoteFloat).
- Reference: androidx-main `compose/remote/integration-tests/demos/.../ClickableDemo.kt` and `SwitchDemo.kt` use the same `rememberMutableRemoteBoolean` + `ValueChange` + `select` pattern; that pattern is what we hit the bug trying to apply.

## What I tried

1. Reordering the modifier chain (`.clickable` before vs after `.clip / .background`) — no difference once a `select`-derived `RemoteFloat` is in scope.
2. Wrapping the `RemoteRow` in an outer `RemoteBox` with a hard `.size(...)` — fixes a separate `weight()` over-claiming issue, but does not bring the clip or child content back.
3. Removing `animateRemoteFloat`, using just `RemoteBoolean.select(1f.rf, 0f.rf)` directly — same broken render. So the bug is not in `animateRemoteFloat`; it's in any non-constant `RemoteFloat` source.
4. Using `RemoteBoolean.select(<RemoteColor>, <RemoteColor>)` for the icon tint elsewhere in the same row — that works fine. So the issue is specific to `select`-derived **`RemoteFloat`** flowing into `lerp` / `weight`, not to `select` in general.

## Current workaround

Drop the in-document boolean entirely. Pick the `RemoteFloat` on the
host side from the Kotlin `Boolean` and pass `0f.rf` or `1f.rf`:

```kotlin
val progress: RemoteFloat = if (initiallyOn) 1f.rf else 0f.rf
```

Cost: the toggle no longer flips in-document on click. The host has to
re-encode the document after the HA service call returns. We lose the
optimistic in-document UX.

## Restore plan when fixed

In [RemoteHaToggleSwitch][rc] revert to:

```kotlin
val localIsOn = rememberMutableRemoteBoolean(initiallyOn)
val toggle = ValueChange(localIsOn, localIsOn.not())
val target  = localIsOn.select(1f.rf, 0f.rf)
val progress = animateRemoteFloat(rf = target, duration = 0.18f)
```

…and add `toggle` to the `.clickable(...)` action list alongside the
host action.

[rc]: ../../rc-components/src/main/kotlin/ee/schimke/ha/rc/components/RemoteHaEntityRow.kt
