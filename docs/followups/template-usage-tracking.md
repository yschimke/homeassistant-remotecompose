# Template usage by app

> Status: **proposal**. Nothing here is implemented. It answers open
> question 5 (Telemetry) in
> [`card-design-server.md`](../architecture/card-design-server.md#open-questions).

## Why

Most of a dashboard is a few cards. `scripts/card-usage.py` over the
bundled dashboards finds that **tile, heading, markdown and history-graph
make up ~90% of content cards** (tile alone is ~67%, mostly `sensor.*`).
The `UberCommon` previews show those four under every uber theme. That
data comes from fixtures, though, not from real users. To decide which
templates are worth a bespoke design per theme, and how stale clients
are, we need counts from the field: **which template, on which app
surface, under which uber theme, from which source**.

## Constraint: privacy policy

[`docs/privacy.md`](../privacy.md) says the developer collects nothing
and the app contacts no developer-operated servers apart from the
optional design server. Any tracking changes that promise, so the
policy, the Play data-safety form and an in-app setting have to change
together. **Whether to do this is the owner's call. This doc doesn't
decide it.**

## Proposal: server-side counts first, opt-in client counts second

### Phase 1: count what the design server already serves (no client change)

Clients already ask the design server for designs per surface (see
*Goal* in `card-design-server.md`). Each request names the design id,
variant, surface, RC API level and profile. The server can bump an
in-memory counter per

```
(day, design_id, variant, surface, api_level, app, app_version)
```

and flush it daily to an aggregate table. It stores no IP, no HA URL
and no device id. `app` and `app_version` come from a fixed
`User-Agent` such as `Terrazzo/1.12 (android; surface=widget)`. Rows
with fewer than k=5 clients are dropped before anyone sees them.

This tells us which templates each **app** (phone app, launcher widget,
Wear app, Wear tile/widget, web, embed) pulls, but not how often they
render. A cached design is fetched once and then rendered many times.

### Phase 2: opt-in render counts

Add a `TemplateUsage` counter next to `CardGenerator`. `CardRender`
already says which generator won, "for telemetry / debug only". It
counts renders per `(card type, design id, variant, uber theme id,
source: remote|stale|inbuilt|local|unsupported)` on the device. It
never records entity ids, names or states. Once a day it posts the
bucketed counts with the design refresh, and only when the user has
turned on **Settings → Share anonymous card usage** (off by default).
Counts are rounded to buckets (1, 2–5, 6–20, 21+) so a dashboard can't
be fingerprinted.

`unsupported` is also the cheapest way to find which custom cards to
convert next.

### Read side

A `/stats/templates` page on the design server, behind GitHub auth
like `/my`, shows a table per app: template, theme, share, source mix.
The same rows are exported as a catalog data product so the
`homeassistant-remotecompose` catalog can show usage next to each
design.

## Questions for the owner

1. Is phase 1 (server logs only, no client change) acceptable under the
   current policy wording, or does it already need a policy update?
2. Opt-in (proposed) or opt-out for phase 2?
3. Does `app` need to split the Wear tile from the Wear widget, or is
   per-surface enough?
