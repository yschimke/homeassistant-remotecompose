# Design principles

> _The rules every harc design follows — library cards, uber designs, and designs an agent or a
> person authors in the UI Builder. Each one names how to check it in the builder's preview
> strip, so a review is a look, not an argument._

A harc design is one design shown in many hosts: a dashboard column on a phone or tablet, a
launcher widget the user resizes, Samsung's round and Pixel Watch's squircle Wear widget hosts.
The ha-cards catalog previews every design in all of them (`frame.previews` in
[`ui-builder.policy.json`](../../ha-cards-catalog/ui-builder.policy.json)). These principles are
what those previews are checked against. The ladder mechanics behind them — tiers, breakpoints,
per-card worked examples — are in
[adaptive-card-layouts.md](../architecture/adaptive-card-layouts.md).

## 1. Content follows area; arrangement follows aspect

**Halve the height and double the width, and you see the same content, in a row.**

How much a design shows is decided by the room it has. How that content is laid out is decided
by the room's shape. Two frames of about the same area show the same things: a tall one stacks
them, a wide one lays them side by side. A design that loses its value or its name when it is
turned sideways has answered the wrong question. It treated a short frame as a small one.

- *Check:* the launcher widget at 2×2 (130×220dp) and at 4×1 (276×102dp) are about the same area.
  Drag the **Resizable** pane between them. Every label, value and control in one is in the
  other, reflowed from a column into a row.
- *Limit:* the rule holds while each dimension still fits its smallest element. A 4×1 row must
  still fit the icon's height. When it can't, that is no longer a change of shape but a smaller
  frame, and principle 3 applies.

## 2. Identity before density

The first thing a design says is *what kind of thing it is*: the gauge's dial, the tile's icon in
its state colour, the lock's pictogram. When room runs out, drop density (range labels, captions,
the name) before the shape that identifies the card. Text-only is the last fallback, never the
first compact form.

- *Check:* the **Samsung · Small** Wear pane still reads as the same kind of card as the phone pane.

## 3. Reflow before remove

When a frame shrinks, the first move is to rearrange: switch axes, tighten padding, move the
secondary line beside the primary one. Removal starts only when no arrangement fits. What goes
first is the least important element, by the card's own priority order, never whatever happens
to sit at the end of a column.

## 4. Earn the canvas

A bigger frame shows **more**: more of the data the compact forms dropped, a larger key element,
history behind the current value. It never shows the same content floating in empty space.
Content grows with area (principle 1), in both directions.

- *Check:* the **Tablet** pane and the large launcher sizes have no empty half.

## 5. Compact is designed, not stripped

Each smaller form is a layout someone would pick on purpose, like the icon-only chip or the
mini-arc gauge. It is not the large layout with parts cut off or clipped. Looking at the smallest
pane, a reviewer should think "that is how this card looks small", not "that is the broken
version".

## 6. One design, every host

A design adapts through its layout (collapsible rows and columns, fit boxes, `for-each`), not by
being copied once per size. A design does not branch on width into separate designs. Runtime
state such as on, off or unavailable belongs in bindings and state predicates, never in another
design. Wear widgets, launcher widgets and dashboard cards are sizes and roots a design takes,
not separate starting points.

## 7. Real values only

A design shows the entity's own state. When a value is unavailable, it shows *unavailable*,
never the example data it was authored against. Example values exist for authoring and for the
library's thumbnails, nothing else.

## 8. The host's theme unless the style is the point

Colours come from theme roles, so a design follows the app's theme, the system's dynamic
colours, light and dark. A style whose identity is its palette (LCD, Nixie) may force its own
colours, and may be dark-only. Launcher widgets default to the system theme.

- *Check:* every design renders in light and dark. A themed design changes with the theme; a
  forced palette stays the same on purpose.

## 9. The dashboard's sections and order; the cards' own sizes

**Keep where the user put things; size them by harc's designs, not HA's.**

A dashboard brings its structure from Home Assistant: its views, its sections, and the order of
the cards inside each section. harc keeps all of that. What it doesn't keep is HA's size for each
card. A section's `grid_options` (`columns`, `rows`, and each card type's defaults) describe HA's
design of that card, and harc's design for the same card is a different size: a tile with its
features, a button, a gauge. So inside a section the cards flow by their own designs. Each card
has the narrowest width it still reads as itself in, rows fill in authored order while every card
keeps that width, a row is shared in proportion to those widths, and the section reflows when its
width changes. Headings keep a row to themselves, because they divide a section into groups.

- *Check:* the same section on a phone and on a tablet keeps its cards in the same order. On the
  tablet, compact cards that stacked on the phone share rows, and no card is narrower than its
  design's minimum.
- *Limit:* order is never changed to fill a gap. A wide card that doesn't fit after a compact one
  starts the next row, even if a later compact card would have fitted beside it.
