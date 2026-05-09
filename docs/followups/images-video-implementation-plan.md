# Images + video support plan (RemoteCompose)

## Goals

- Make image-backed cards (`picture`, `picture-entity`, `picture-glance`, `picture-elements`) robust across app runtime, widgets, and previews.
- Support "video" cards initially as **still-frame streams** (periodic image refresh), then decide if/when to add full video playback overlay UX.
- Validate all behavior in deterministic previews/tests using fake loaders (no live network dependency).

## Current behavior snapshot (code anchors)

- `RemoteHaImageUrl` serializes URL-only image references and relies on player-side `BitmapLoader.loadBitmap(url)` at playback.
- `HaEmbeddedPlayer` is the host wrapper that can inject a custom `BitmapLoader`; `RemotePreview` cannot.
- `CoilBitmapLoader` does synchronous memory-cache lookup and, on miss, enqueues async warm-up then returns empty bytes for that render pass.
- Preview infrastructure already includes `previewCoilBitmapLoader(...)` backed by `FakeImageLoaderEngine` and explicit memory-cache priming.

## Key open question: when does RemoteCompose re-fetch image URLs?

### Existing code touchpoints

- `rc-components/src/main/kotlin/ee/schimke/ha/rc/components/RemoteHaImage.kt`
  - `RemoteHaImageInline`, `RemoteHaImageNamed`, `RemoteHaImageUrl`
- `rc-components-ui/src/main/kotlin/ee/schimke/ha/rc/ui/HaEmbeddedPlayer.kt`
  - player host that accepts a `BitmapLoader`
- `rc-image-coil/src/main/kotlin/ee/schimke/ha/rc/image/CoilBitmapLoader.kt`
  - sync memory lookup + async warm-up on miss
- `previews/src/main/kotlin/ee/schimke/ha/previews/PreviewCoil.kt`
  - fake-coil helper for deterministic preview loading
- `previews/src/main/kotlin/ee/schimke/ha/previews/ImagePreviews.kt`
  - sample preview entrypoints for inline/named/url images


### What we know

- We control the loader implementation (`CoilBitmapLoader`), but the player controls `loadBitmap` call timing.
- On first miss, the loader warms cache asynchronously and returns empty stream; image appears only if the player retries decode later.

### Verification task (must-do first)

1. Add a small instrumentation probe loader (`RecordingBitmapLoader`) that records every `loadBitmap(name)` call and timestamp.
2. Wire the probe into `HaEmbeddedPlayer` in a preview-only experiment screen.
3. Exercise scenarios:
   - Initial render with cold cache.
   - Cache warm-up completion.
   - Parent recomposition without document change.
   - Document regeneration with same URL.
   - Document regeneration with URL cache-buster (`?t=...`).
4. Capture observed retry policy:
   - "No automatic retry" vs "retries on next frame" vs "retries on invalidation only".
5. Document result in `docs/followups/...` and convert into implementation rules.

## Implementation track A — still-frame "video" (recommended first)

Treat video cards/camera feeds as image URLs refreshed on a fixed cadence.

### A1. Data model + converter

- Extend card model(s) with optional frame-refresh settings:
  - `frameUrl` (or derived from existing entity/camera endpoint)
  - `frameIntervalMs` (e.g. 1000–5000 ms)
  - optional `cacheBustStrategy` (`none`, `queryTimestamp`, `etagVersion`)
- Add converter support for HA card types that expose camera/video stills.

### A2. Refresh orchestration

- In app host layer (outside RemoteCompose document generation), create a refresh scheduler per visible card:
  - ticks every `frameIntervalMs`
  - computes next URL (with cache-bust if needed)
  - triggers document regeneration or targeted card regeneration
- Prefer batching refreshes by dashboard/page to avoid N timers.

### A3. Re-fetch strategy based on verification result

- If player retries automatically after cache warm-up: keep stable URL, rely on cache updates.
- If player does **not** retry automatically:
  - force invalidation by changing URL token per frame (`?frame=<tick>`), or
  - force document/card refresh to trigger new decode pass.

### A4. Performance guardrails

- Backoff refresh when app is backgrounded or dashboard tab not visible.
- Cap minimum interval (e.g. >= 500 ms) and default conservatively (e.g. 2 s).
- Track decoded bitmap size and memory/cache hit rate.

## Implementation track B — click-to-open video overlay

Add richer playback UX without requiring inline RC video rendering.

### B1. Interaction contract

- In RemoteCompose card: show still frame + play affordance.
- Tap action emits existing navigation/event action with payload: media URL/entity id.

### B2. Host overlay player

- In app Compose UI (outside RC), show modal/bottom-sheet/fullscreen video player overlay.
- Reuse dashboard context underneath; dismiss returns user to same scroll position.

### B3. Decision criteria

Use overlay when:
- stream is high frame-rate or interactive (seek/audio controls), or
- still-frame polling cost is too high.

Keep still-frame-only mode for:
- glanceable cameras where low fps is acceptable.

## Preview/testing plan

## 1) Fake loader preview tests (requested)

- Build preview scenarios using `previewCoilBitmapLoader`:
  - URL resolves from preseeded cache (instant success).
  - URL initially missing, then added to fake cache after delay (simulate late frame availability).
  - Rotating URLs for per-frame updates.
- Add snapshot previews demonstrating frame 0/1/2 progression.

## 2) Unit tests

- `CoilBitmapLoader` behavior:
  - cache hit returns non-empty stream
  - miss enqueues warm-up and returns empty
  - keying respects cache-buster URL differences
- Scheduler tests:
  - emits ticks at configured cadence
  - suspends when hidden/backgrounded
  - resumes without burst storm

## 3) Integration checks

- Dashboard screen with fake clock + fake loader to confirm visible frame updates.
- Verify no crashes when offline and URLs unreachable.

## Rollout plan

1. **Phase 1:** probe + verify RemoteCompose re-fetch timing semantics.
2. **Phase 2:** implement still-frame refresh for one card type (camera/picture proof-of-concept).
3. **Phase 3:** expand to remaining image/video-like cards.
4. **Phase 4:** optional click-to-open overlay video player behind feature flag.

## Risks / mitigations

- **Risk:** player never re-decodes same URL after initial miss.
  - **Mitigation:** URL cache-busting + explicit doc/card invalidation.
- **Risk:** excessive network + battery from polling.
  - **Mitigation:** visibility-aware scheduler + interval caps.
- **Risk:** cache churn/memory pressure.
  - **Mitigation:** bounded frame sizes, disk-cache leverage, and per-dashboard refresh budgets.

## Definition of done

- Image cards render via wired loader in runtime + previews with deterministic fake-loader tests.
- Still-frame video mode updates at fixed interval with documented retry/invalidation behavior.
- User can tap a video-capable card and either:
  - continue with still-frame mode, or
  - open overlay player (if Phase 4 enabled).
- Performance and offline behavior are documented and regression-tested.


## Delivery checklist (ordered)

1. Build `RecordingBitmapLoader` experiment and publish findings in this doc.
2. Implement still-frame refresh POC for one card type behind a feature flag.
3. Add fake-loader preview coverage for cache-hit, cache-miss, and URL-rotation cases.
4. Add scheduler unit tests (timing + visibility lifecycle).
5. Validate dashboard integration (foreground/background/offline).
6. Decide whether overlay video player is required for v1 based on usability + perf data.

## Decision log template

When the probe results are in, record them in this format:

- Date:
- Build/branch:
- Scenario tested:
- Observed `loadBitmap` call pattern:
- Outcome category:
  - [ ] auto-retry
  - [ ] retry-on-invalidation
  - [ ] no-retry
- Chosen strategy:
- Follow-up implementation tasks:
