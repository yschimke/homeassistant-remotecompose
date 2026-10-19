# Everyday cards per uber theme

`scripts/card-usage.py` over the bundled dashboards: **tile 67%, heading 14%, markdown 5%,
history-graph 3%** of content cards, ~90% together. The `UberCommon` previews put those four under
every uber theme, and before this brief most themes changed only their palette and typeface on
them. The meters, dials and controls diverged; the cards people actually look at did not.

This brief gives every visual family ([`HaVisualFamily`](../../rc-components/src/main/kotlin/ee/schimke/ha/rc/components/HaUberTheme.kt))
its own design of each everyday card. A theme must be recognisable from a crop of its sensor
tile or its heading alone, with the colour removed.

## Rules

- **Classic and Home Assistant stay the HA reference.** They mirror `hui-tile-card`,
  `hui-heading-card` and `hui-markdown-card`, so the other themes have something to depart from.
- **Structure, not just paint.** Each family changes at least one of: layout (where icon, name and
  value sit), the identity glyph, the surface the text sits on, or the drawing of the trace.
- **Live data still flows.** A tile's value stays the `<id>.state` binding; a sensor tile's glyph
  is a `HaReading` (path 1: `<id>.state` + `<id>.numeric_state`, fill and band computed
  in-document). Nothing here adds a re-encode. See
  [`live-card-updates.md`](../architecture/live-card-updates.md).
- **Readable first.** The value stays at least body size and in primary ink; decoration never
  sits under text it would lower the contrast of.

## 1. Sensor tile: the family's meter replaces the icon

A tile for a sensor whose reading has a `HaMeasureKind` (temperature, humidity, battery, and the
other kinds `HaReading` maps from `device_class`) draws the family's compact meter glyph
(`RemoteHaMeasurementGlyph`) in the icon's slot: a segment column on LCD, a glowing IN-9 tube on
Nixie, an LED column on Trinitron, a tuning scale on Braun, petals on Mushroom, beads on Bubble,
pips on Minimalist… Classic and Home Assistant keep the HA icon.

## 5. Tile layout per family

| Family | Layout |
| --- | --- |
| Classic, Home Assistant | Icon, then name over state (HA). |
| Expressive | Glyph, name, and the value on a **tonal pill** at the end of the row. |
| Trinitron | **Plate**: a tracked caps name ruled with a hairline over the value; LED column glyph. |
| LCD | **Big number** in a recessed screen panel, name as a caption under it; segment glyph. |
| Braun | Name left, value right on one line, a small orange index dot before the name. |
| Nixie | **Big number** in glowing amber, name as a small caption; tube glyph. |
| Console | Engraved **tape label** for the name, value beside a pilot lamp. |
| IKEA | **Product tag**: bold caps name over a large value, glyph left. |
| Mushroom | Round tinted shape around the glyph, name over a bold value. |
| Bubble | Capsule: glyph, name, value at the end (existing). |
| Minimalist | Value over name, icon at the end (existing). |
| Cupertino | Value over name, large (existing). |
| Catppuccin | Value in a pastel **lozenge** under the name. |
| Obsidian | Editorial: serif name, value in small caps with a hairline rule. |
| Noctis | Twin rails down the left edge, name over value. |
| LCARS | **Command key**: a coloured end cap on the left, caps name, value right. |
| Arctic | Name over value, icon at the end (existing). |
| Aeon | **Framed key**: double-ruled frame, diamond index before the name. |

## 2. History graph per family

The trace is drawn 28 dp tall today, too small for a family's design to read. Every family draws
it taller (40 dp) and with its own frame:

| Family | Graph |
| --- | --- |
| Classic, Home Assistant | Line over a baseline (HA). |
| Expressive | Thick round line over a soft tonal area (existing), area reaching the floor. |
| Trinitron | Dotted graticule, hairline trace, peak dot; scale ticks at the left. |
| LCD | Segment bars with ghosts (existing), framed in the recessed screen. |
| Braun | Hairline trace over index ticks along the bottom; orange dot on the latest sample. |
| Nixie | Glowing filament inside a glass tube outline. |
| Console | Trace in a recessed screen with a graticule (existing). |
| IKEA | Thick line over a ruled grid, last sample dot (existing), with an end label. |
| Mushroom | Filled soft area with seed dots. |
| Bubble | Bead line: a dot per sample on a thick line (existing), with a soft fill. |
| Minimalist | Stepped hairline with only min and max marked. |
| Cupertino | Smooth line with a gradient area and the ringed last point (Health). |
| Catppuccin | Stepped pastel line with a dashed stitched baseline. |
| Obsidian | Hairline on ruled lines (existing). |
| Noctis | Twin rail: a wide dim rail with a hairline inside (existing). |
| LCARS | Blocky bars with rounded caps in band colours. |
| Arctic | Hairline with a "now" needle at the end (existing). |
| Aeon | Bars inside a framed marquee with diamond markers at min and max. |

## 3. Heading per family

| Family | Heading |
| --- | --- |
| Classic, Home Assistant | Plain title (HA). |
| Expressive | Bold headline with a short tonal capsule under it. |
| Trinitron | Tracked caps label, hairline rule to the edge, a red power-LED dot. |
| LCD | Text in a recessed screen strip with a block cursor. |
| Braun | Small caps beside an orange dot. |
| Nixie | Glowing amber caps (a glow pass under the text). |
| Console | Engraved tape label. |
| IKEA | Leaflet step: a numbered black circle, bold caps title. |
| Mushroom | Large bold title with a rounded accent underline. |
| Bubble | Title in a capsule. |
| Minimalist | Small uppercase caption, wide tracking, muted. |
| Cupertino | Large Title: big, heavy, tight. |
| Catppuccin | Title on a pastel lozenge chip. |
| Obsidian | Serif title with a muted `#` before it and a hairline under it. |
| Noctis | Twin rails before the title. |
| LCARS | Elbow band: a coloured bar, rounded at the left, title in caps at its right end. |
| Arctic | Thin, widely tracked title over an ice rule. |
| Aeon | Marquee: `◆ Title ◆` between rules. |

## 4. Markdown per family: the surface the note is made of

Today a markdown card is the theme's card chrome with the text in the theme's font and colours.
Here the card takes the family's **material**:

| Family | Markdown |
| --- | --- |
| Classic, Home Assistant | HA card (unchanged). |
| Expressive | Tonal container, large corner, no border. |
| Trinitron | Black panel; title as a tracked caps plate. |
| LCD | Text in the recessed screen panel, monospace ink. |
| Braun | Plain sheet with a grey index rule on the left. |
| Nixie | Amber text on black glass. |
| Console | Text in a recessed screen; title as a tape label. |
| IKEA | Instruction leaflet: bold title, a numbered circle per heading. |
| Mushroom | Soft card with a coloured leading bar. |
| Bubble | Speech bubble: a capsule with a tail. |
| Minimalist | No chrome; a hairline on the left. |
| Cupertino | Inset grouped panel. |
| Catppuccin | Dashed stitched border. |
| Obsidian | Editor: muted `#` / `-` markers kept visible, serif. |
| Noctis | Twin rails down the left. |
| LCARS | A coloured side bar with a rounded cap. |
| Arctic | Frosted, borderless, thin type. |
| Aeon | Double-ruled frame. |
