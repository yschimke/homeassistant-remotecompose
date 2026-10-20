# Template usage by app

> Status: **implemented** (both phases). Answers open question 5
> (Telemetry) in
> [`card-design-server.md`](../architecture/card-design-server.md#open-questions).
> What users are told is in [`docs/privacy.md`](../privacy.md).

## Why

Most of a dashboard is a few cards. `scripts/card-usage.py` over the
bundled dashboards finds that **tile, heading, markdown and history-graph
make up ~90% of content cards** (tile alone is ~67%, mostly `sensor.*`).
That comes from fixtures, not from real users. To decide which templates
are worth a bespoke design per theme, and how stale clients are, we count
in the field: **which template, on which app surface, under which uber
theme, from which source**.

## Phase 1: count what the design server serves

Every request the app makes to the design server carries a fixed
User-Agent naming the release, never the install
(`UsageUserAgent`: `Terrazzo/<version> (<platform>; surface=<surface>)`,
set on `DesignServerConfig.userAgent`).

The server (`UsageStats`) counts, per UTC day:

- manifest checks, by app, version, platform and surface;
- blob downloads, by the design's card type, variant (a person's own
  design counts as `user`), card style, design surface and RC API level,
  and the app.

`ClientAgent` reduces the User-Agent to those fields; anything else
counts as `other`, so a free-form User-Agent is never stored. No IP
address, device id, token or Home Assistant data is kept. Days are
written under `USAGE_DIR` (`/data/usage` in the image) every minute and on
shutdown, and deleted after `RETENTION_DAYS` (400, about 13 months).

Fetch counts say which templates each app pulls, not how often they
render: a cached design is fetched once and rendered many times. Hence
phase 2.

## Phase 2: opt-in render counts

`TemplateUsage` (ha-client) counts, per UTC day, which template drew each
dashboard card: card type, variant, card style (uber theme id) and source
(`remote`, `local`, `converter`, `unsupported`). A card counts once a day,
under a local fingerprint (a hash of its config) that never leaves the
device. The app's nightly design sync uploads finished days to
`POST /designs/v1/usage`, each row's card count as a bucket
(`1`, `2-5`, `6-20`, `21+`).

The server accepts exactly that schema (`UsageReportCheck`: unknown
fields, exact counts, out-of-range days and non-tag values are refused),
caps the body at 64 KiB and the rate at 600 reports a minute (in memory,
keyed on nothing), and adds one per row to the day's counts.

### The setting: a tri-state, opt-in

*Settings → Share anonymous card usage* is stored as
`UsageSharing`: `Enabled`, `Disabled` or `Default`, starting at
`Default`.

- `Default` means whatever `UsageSharing.DEFAULT_SHARES` says. That one
  constant is **false**: sharing is opt-in.
- `Enabled` and `Disabled` are the user's explicit choice and are never
  overridden. A later release may flip `DEFAULT_SHARES`; only users still
  on `Default` follow it (`TemplateUsageTest` pins this).
- The switch shows the effective value. Touching it writes `Enabled` or
  `Disabled`, never `Default` again.
- While the effective value is off nothing is counted or uploaded, and
  pending counts are deleted (on the change, and again before any
  upload).
- The setting only shows while a design server is set; with none, nothing
  is fetched or sent at all.

## Read side

`/stats/templates` (HTML) and `/api/stats/templates` (JSON) show the last
30 days per app: fetched templates and rendered cards with their share,
and the source mix. Any count with fewer than `K_ANONYMITY` (5) requests
or reports behind it is dropped before anyone sees it. Only GitHub
logins in `STATS_USERS` can open them; with none set (the default) the
routes don't exist.

## Not done

- Only the Android phone app fetches designs and counts renders.
  Launcher widgets, Wear, TV and the desktop/web hosts don't use the
  design server, so they neither send the User-Agent nor count.
- Renders are counted on the dashboard only, not in widgets.
- A catalog data product exporting the same rows is still to do.
