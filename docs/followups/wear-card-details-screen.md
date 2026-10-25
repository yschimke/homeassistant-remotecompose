# Wear: what's left for the card details screen

The Wear app has a card details screen (`WearCardDetailsScreen`, see
[`wear-one-handed-gestures.md`](../architecture/wear-one-handed-gestures.md#opening-a-card-full-screen)).
It shows the card at full width, and for each of the card's entities a 24-hour chart and its live
attributes, read with the watch's own token. Still to do:

- **History and attributes without a watch token.** Both need `WearDirectAccess`'s token. A phone
  that never made one, or a watch that can't reach Home Assistant, shows "History needs Home
  Assistant access" and only the synced attributes. The phone could answer a `/wear/history/v1`
  request from its own session instead (it already holds `HaSnapshot.history`), the way
  `/wear/tap/v1` relays taps.
- **A layout made for the round screen.** The full-screen card scales the Large widget layout up to
  fill the screen (`scaledFrame`). A layout designed for a full round screen, with bigger type rather
  than a bigger copy, would need its own `wearDesignTemplate` size beyond Small and Large, plus parity
  tests in `WearCardDesignParityTest`.
