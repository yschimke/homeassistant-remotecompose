# Card support TODO

Cards to promote from placeholder / add support for, in order of work:

- [ ] `conditional` — wraps another card; renders the inner card only when
  every entry in `conditions:` matches against the snapshot. Trivial:
  reuse `RenderChild` for the inner card, evaluate conditions on the
  snapshot. No new RC components needed.
- [ ] `map` — entity locations on a static-map background. Simplified:
  render a card chrome with the configured entity list as labelled dots
  on a flat tile. No real tile fetching from a player; treat geometry as
  a normalised bounding box around the entities.
- [ ] `history-graph` — sparkline per entity over `hours_to_show`. Pull
  history from the snapshot when present; otherwise render an empty axis
  so the card still occupies its slot.
- [ ] `custom:ha-bambulab-*-card` — HACS cards from
  [`greghesp/ha-bambulab-cards`](https://github.com/greghesp/ha-bambulab-cards).
  No shared schema; register one converter per variant. Visual reference
  on the integration's docs site (`docs.page/greghesp/ha-bambulab`).
  Variants (from each card module's `const.ts`):
    - `custom:ha-bambulab-ams-card` — AMS spool grid
    - `custom:ha-bambulab-spool-card` — single spool detail
    - `custom:ha-bambulab-print_status-card` — current job + progress
    - `custom:ha-bambulab-print_control-card` — pause / resume / cancel
      pad (legacy alias `custom:ha-bambulab-skipobject-card`)
  Each starts as a chrome card showing the configured printer entity +
  state; real per-card visuals can land later.
