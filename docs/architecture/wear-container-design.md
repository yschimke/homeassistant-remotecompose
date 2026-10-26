# Wear container design

A Wear widget is a card drawn inside the watch's widget container. The container — its size,
padding and corner radius — belongs to the watch's widget host, and differs by device: a round
watch's Large container is 200×112 dp with 26 dp corners, a square watch's may have 4 dp corners,
a Small one is close to a pill. The card draws no chrome of its own there
(`ProvideCardChrome(false)`), so the container *is* the card's surface: it is the largest thing a
card style has to tell itself apart on the watch.

This document is the contract for what a card style draws in it. Code: `HaWearBackground.kt` (the
model) and `RemoteWearContainer.kt` (the one drawer) in `:rc-components`.

## Rules

1. **The container's shape is the host's, on every device.** Never change its corner radius,
   size or padding. A style changes what is drawn inside it, nothing else.
2. **Use all of it, never draw outside it.** Everything is drawn to *fit* the host's rounded rect
   at the host's own radius, not cut off by a clip:
   - fills, gradients and glows are drawn *as* that rounded rect;
   - bands, edges and rings are strokes that follow its outline inwards (`Outline`), so they hug
     a 26 dp corner, a 4 dp one and a pill alike;
   - a fill level is the rounded rect cut straight along the reading, so it keeps the
     container's corners wherever it reaches them.
3. **Text stays readable.** Anything under the card's text (a fill level) is capped to the
   strongest alpha that keeps the style's primary text at 4.5:1 and secondary text at 3:1, with a
   margin for a state tint (`readableAlpha`). A ring lies in the container's padding — 4 dp in from
   the outline, inside the 8 dp the card's content is inset by — so it never sits under text and
   is drawn at full strength.
4. **Values stay live.** A level binds `<entity>.numeric_state` and computes its fraction
   in-document (path 1 of [live-card-updates.md](live-card-updates.md)): the Wear app moves it on a
   pushed value with no new document, a widget bakes the current value, and a non-numeric state
   (unavailable, unknown) draws no reading. It animates on change only.
5. **Decided from the shape, never the value.** Neutral templates are cached per card shape, so
   whether a card is a level (`HaWearLevel.of`) reads only the entity's domain, `device_class` and
   unit — the properties its design shape already carries.

## Where the container is drawn

| Path | Drawn by | Corner radius |
| --- | --- | --- |
| Neutral template (most cards) | `WearWidgetTemplate.capture` → `RemoteWearContainer` | named `wear.container.corner_radius`, written from `WearWidgetParams` per widget |
| Wear app, card with a template | the same template via `WearWidgetTemplate.forApp` | the watch's remembered `WearWidgetFrame` |
| Wear app, card without one | `WearAppFrame` → `RemoteWearContainer` | `WearWidgetFrame` |
| Glance direct capture (fallback) | Glance Wear's own container, `wearWidgetBrush` | the host's |
| Phone "Preview on a watch" | `CardStyleWearPanes` → `RemoteWearContainer` | 26 dp, a round watch's |

Glance Wear's `WearWidgetBrush` can only paint colours, straight gradients and images, so the
direct-capture fallback keeps a style's base and straight gradients and leaves out its glows,
bands, edge and level.

## What the Wear widget profile can draw

`WearWidgetProfileCapabilitiesTest` captures one document per feature as a Wear template, checks it
against `RcTargetProfiles.WEAR_WIDGETS`, makes it a widget (`adaptTo`) and plays it:

| Feature | Plays |
| --- | --- |
| Linear gradients: vertical, horizontal, diagonal | yes |
| Radial and sweep gradients | yes |
| Gradient (shader) strokes | yes |
| Border modifier, stroked round rects, arcs, lines | yes |
| Clip to a *named* corner radius; `clipRect` | yes |
| Geometry from a named float, and its animation | yes |
| Translucent layers | yes |
| Dash path effect | validates, but draws **solid** — don't use |

So a container can carry glows, gradient frames, partial strokes driven by live values and
animated levels. A name used twice in one capture is one named variable, so a container's level
and a card's meter glyph share `<slot>.numeric_state`.

## The styles

Every style but Classic brings its dark card surface as the container's base (`wearBackground`),
plus layers and an edge that carry its identity — Home Assistant's blue from the top, Expressive's
tonal glows, Trinitron's hairline and rule, an LCD's backlit glass and bezel, a Nixie glow, a
painted-metal sheen with a bevel, a birch frame, Minimalist's index tab, Catppuccin's pastel
gradient frame, LCARS's elbow band, Aeon's gold marquee frame. Classic keeps the watch's own
surface and palette.

A reading's level (`wearLevelLook`) is a ring for the soft and instrument styles (with variants: a
thick activity ring, a pastel sweep, a trackless hairline, a dial pointer) and a fill for the
display styles (segmented with ghosts for LCD and Minimalist, LED columns for Trinitron, a glow for
Nixie, a ruled fill for the rest).

`WearContainerStylesTest` renders every style in a round, a square and a Small container with a
battery level, as the watch bakes it, to `wear/build/wear-container-styles/`.
