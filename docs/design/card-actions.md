# Card actions: home screen, watch and style

A UX review of what the Android app offers once you leave the dashboard itself: the long-press
menu on a card, the home screen widget flow, pinning to the watch, the per-card style override
and the card details screen. Each finding is followed by what replaced it.

## What was there

| Surface | What it offered |
| --- | --- |
| Long-press sheet | Fork in card designer · Add to home screen · History and details |
| Install sheet ("Make it yours") | name field · launcher size preview · dashboard note · **Pin to Wear** · **Monitor for 15 min** · Install widget |
| Details screen | launcher size preview (again) · Prepare a home screen widget · Design or fix this card · **Card style** (two rows of chips) · History · Other actions → **Add to Home Screen** (again) · Open in Home Assistant |
| Overflow menu | Settings · Manage widgets · Manage pinned · Wear widgets · Install on watch · Logs · Sign out |
| Manage widgets | always "No widgets yet", even with widgets installed |

## Findings

1. **No single home for a card's actions.** The long-press sheet offered three, the details screen
   offered five more, and the install sheet two that had nothing to do with installing. "Add to
   home screen" appeared three times across two screens; the watch pin only inside the home screen
   sheet, which the empty "Manage pinned" screen nonetheless described as "the long-press sheet".
2. **The install sheet did several jobs.** Under the heading "Make it yours" it held the widget
   name, the launcher preview, a Wear pin toggle that closed the sheet as a side effect, and a
   demo-only monitor. The one action the sheet is for, installing, sat at the bottom behind all of
   them. The name field came before the preview, so you named a widget before you saw it.
3. **The details screen led with the wrong preview.** It opened on a draggable launcher grid with
   Compact / Medium / Roomy chips, a cell count and a paragraph about 1 × 1 and 4 × 4. It is
   sizing for a widget you have not decided to add yet, on a screen you opened to read history.
4. **The card style picker asked two questions at once.** Two horizontally scrolling rows of eight
   chips, "This card" and "Every tile card", each with a "Your style" or "Same as every tile
   (Braun)" chip. It was hard to tell which row was in effect, and there was no picture: you
   picked a name, then went back to the dashboard to see what it meant.
5. **Nothing said where a card already lived.** Whether it was on the home screen, on the watch, or
   in a style of its own showed nowhere in the card's menu, so "Pin to Wear" and "Add to home
   screen" were asked blind.
6. **Jargon led.** "Fork in card designer" was the first action on every long-press: it is a power
   tool, and "fork" is a developer's word.
7. **The menu named mechanisms, not places.** "Manage widgets", "Manage pinned" and "Wear widgets"
   read as three versions of the same thing. "Manage widgets" was a placeholder that never listed
   anything.

## What replaced it

**One sheet per card, in context.** Long-press opens the card's actions and nothing else does. Its
header names the card and its current state ("Kitchen lights · On"), then shows chips for where it
already lives: *On home screen*, *On watch*. Below, four actions in order of use:

- **Add to home screen**, with the widgets left ("3 of 5 left"); "Add another" once it is there;
  disabled with the reason when all five are used.
- **Show on watch**, a switch that flips in place. It says what it is and its state, rather than
  a button that may pin or unpin and closes the sheet.
- **Card style**, naming the style in use.
- **History**, only when the card has an entity with history.

Then, one line each and visually quieter: *Open in Home Assistant* (live instances), *Watch live
for 15 minutes* (where monitoring is available) and *Redesign a copy in Card Studio*.

**The install sheet installs.** "Home screen widget": the live preview and its size controls
first, then an optional label ("Only this widget. Your dashboard stays as it is."), then the
button, which reads *Add widget* with the widgets left above it (not "Add to home screen", which is
the launcher's own confirmation that follows).

**History is history.** The details screen shows the card as the dashboard draws it, the history
charts, and *Open in Home Assistant*. Its top bar's actions button reopens the card's sheet, so
nothing is offered twice and nothing is lost.

**Style is one decision with a picture.** A sheet with the card itself at the top, redrawn as you
pick; a segmented choice between *This card* and *All tile cards*, opened on whichever one already
decides the card; and one radio list led by *Same as your dashboard* (or *Same as other tile
cards*), naming the style that means.

**The menu names places.** *Home screen widgets* (now listing what is installed, 2 of 5 in use, and
how to remove one), *Cards on your watch*, *Watch widgets*, then a divider before Settings, Logs and
Sign out.

## Not changed

- The section heading's pin icon stays: it is already in context, on the thing it pins.
- The launcher preview and its sizing controls are unchanged in behaviour; only their copy is
  shorter and they moved from the details screen into the install sheet alone.
- The Wear widget slots screen keeps its layout; only its title changed to match the menu.
