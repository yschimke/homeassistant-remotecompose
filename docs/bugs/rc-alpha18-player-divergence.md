# RemoteCompose alpha18: the View player samples an in-flight animation; the embedded player drops a text node

**For filing at** https://issuetracker.google.com (androidx.compose.remote component).

Two independent defects in `androidx.compose.remote:*:1.0.0-alpha18`, found while
triaging unstable wear previews. They are filed together because the second is only
visible once you switch players to avoid the first.

## 1. The View player captures mid-animation, nondeterministically

`RemoteDocumentPreview` → `RemoteDocumentPlayer` → `RemoteComposePlayer` (an
`AndroidView`) drives document animation off the view's own clock
(`RemoteComposeView.mClock` / `mLastAnimationTime`), independent of the Compose frame
clock. A still capture therefore samples whatever point the animation happens to be at.

`RemoteHaToggleButton` tweens its accent over 0.20s:

```kotlin
val accentProgress = animateRemoteFloat(isOnBinding.select(1f.rf, 0f.rf), durationSeconds = 0.20f)
tween(data.accent.inactiveAccent, data.accent.activeAccent, accentProgress)
```

Three forced renders of the `:wear` module at one commit (74 previews each):

| player | unstable previews |
| --- | --- |
| View (`RemoteDocumentPreview`) | **3** of 74 |
| embedded (`ExperimentalRemoteDocumentPlayer`) | **0** of 74 |

The three are `WearButtonLarge`, `WearGridLarge`, `WearHorizontalStackLarge` — every
preview containing a full-tier toggleable button. Each produced **three distinct
sha256s across three runs**; no two agreed. The small tier is stable because
`RemoteHaButtonIcon` takes a static accent and never tweens.

The output is not merely unstable, it is **wrong**: the sampled frames composite into a
smeared, doubled bulb with an offset halo. Every published baseline for those previews
was a mid-tween artifact.

There is no seam to pin it from a caller's position. `RemoteComposeView.setClock` is
private; the only injection point is the constructor
`RemoteComposeView(Context, AttributeSet, int, java.time.Clock)`, and
`RemoteDocumentPlayer` constructs the player itself. `RemoteDocument(InputStream,
RemoteClock)` exists but `RemoteDocumentPreview` does not expose it.

**Workaround adopted here:** render previews through the embedded player instead —
see `WearWidgetPreviewSnapshot`. It requires
`RemoteComposePlayerFlags.isEmbeddedPlayerEnabled = true`; without it every render
fails with `IllegalStateException: Embedded player is disabled`.

## 2. The embedded player drops a text node

Switching players fixes the above but loses content. In `WearEntitiesLarge` the third
entity's state label ("On", under the power icon) renders under the View player and is
**absent** under the embedded player. Same document bytes, same size, same fixture —
only the player differs.

The two preceding labels ("21.5 °C" and the second "On") render under both, so this is
not a wholesale text failure; it is the last child of the row being dropped or clipped.

Not yet minimised to a standalone repro, and `RemoteComposePlayerFlags` also carries
`shouldPlayerWrapContentSize`, which affects the player's sizing contract and has not
been ruled out as a contributing factor.

**Accepted, not worked around.** The determinism win is worth more than one label in
one preview, and hiding it behind a local hack would make the upstream bug harder to
see. This document is the record.

## Affected versions

`androidx.compose.remote:remote-player-view`, `remote-player-compose`,
`remote-tooling-preview` — all `1.0.0-alpha18`.
