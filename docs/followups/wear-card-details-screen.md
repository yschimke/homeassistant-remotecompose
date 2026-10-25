# Wear: what's left for the card details screen

The Wear app has a card details screen (`WearCardDetailsScreen`, see
[`wear-one-handed-gestures.md`](../architecture/wear-one-handed-gestures.md#opening-a-card-full-screen)).
It shows the card, a 24-hour chart of its main entity read with the watch's own token, and the
entity's synced attributes. Still to do:

- **History without a watch token.** The chart needs `WearDirectAccess`'s token. A phone that never
  made one, or a watch that can't reach Home Assistant, shows "History needs Home Assistant access".
  The phone could answer a `/wear/history/v1` request from its own session instead (it already holds
  `HaSnapshot.history`), the way `/wear/tap/v1` relays taps.
- **The card at full-round size.** The full-screen card and the details screen both reuse the Large
  widget frame (200 × 112 dp). A full-round frame needs its own `wearDesignTemplate` size beyond Small
  and Large, plus parity tests in `WearCardDesignParityTest`.
- **More than one entity.** The chart covers the card's main entity only. An `entities` or `glance`
  card could show one chart per row.
- **More of more-info.** The attribute list holds only what the phone syncs (`WEAR_EXTRA_ATTRIBUTES`).
  Brightness, a cover's position or a thermostat's mode would need adding to the sync, or reading
  alongside the history.
