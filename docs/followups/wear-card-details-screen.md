# Wear: a details screen for the full-screen card

The Wear app's full-screen card (`WearCardScreen`, see
[`wear-one-handed-gestures.md`](../architecture/wear-one-handed-gestures.md)) reuses the Large Wear
widget frame: 200 × 112 dp on a round screen that has room for more. It's the same card at a larger
size, with an action button under it, not a details screen.

The phone has one. A long press opens `CardHistoryScreen`, which shows the card, a history chart of
its entity and links. The watch should get the same as a second page of the full-screen card, or a
screen it opens:

- **The card at full-round size.** This needs its own frame and a `wearDesignTemplate` size beyond
  Small and Large, plus parity tests in `WearCardDesignParityTest`.
- **A history graph for the card's entity.** The phone would sync recent history alongside
  `/wear/values` (`HaSnapshot.history` already holds it on the phone), or the watch would read it
  through `WearDirectAccess`.
- **The entity's other attributes,** as more-info shows them: brightness, a cover's position, a
  thermostat's mode.

Until then, a card's more-info tap on the watch opens the full-screen card (`WearTapDispatcher`'s
`onMoreInfo`), which is the closest thing the watch has to a details screen.
