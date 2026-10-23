# Template usage by app

> Status: **implemented** (both phases) on the phone, its launcher
> widgets, and the desktop and web app; see [Form factors](#form-factors)
> for Wear and TV. Answers open question 5
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

## Form factors

Each report row carries a `surface` (`UsageKey.surface`): `phone`,
`widget`, `desktop` or `web`. `phone` is the default and is left out of
the JSON, so a ledger or report from before surfaces reads the same, and
the server reads a row without one as `phone`. (A new client sending
`widget` to a server older than this change gets a 400 and drops that
day, so the server ships first.)

| Form factor | Where cards render | Design server today | Setting | What it sends |
| --- | --- | --- | --- | --- |
| Phone dashboard | Phone app (`cardTemplateFor`, converter fallback in `DashboardViewScreen`) | Yes: manifest + blobs (`DesignRepository`), nightly `CardDesignSync` | Phone Settings | Fetch counts (User-Agent `android; surface=phone`); with opt-in, rows with `surface` `phone` |
| Launcher widgets | Phone process (`TerrazzoWidgetProvider` bakes the launcher design or captures the converter) | No fetches of their own (local launcher designs) | The phone's | With opt-in, installed live widgets count as rows with `surface` `widget`, uploaded with the phone's. Install previews and demo widgets don't count. |
| Card style samples | Phone (`CardStyleSample`) | No | — | Nothing: samples aren't the user's cards (`recordUsage = false`). |
| Desktop app | `app-ui` JVM (`AuthenticatedCard`: a library design via `/api/cards/resolve` + blob, else the app's own card) | Yes: resolve + blobs from the "Card design server" setting (`https://ha.coo.ee`) | app-ui Settings; choice in `~/.terrazzo/usage/sharing`, ledger in `~/.terrazzo/usage/ledger.json` | Blob fetches tagged `?client=Terrazzo/<v> (desktop; surface=desktop)`; with opt-in, rows with `surface` `desktop`, uploaded 10 s after launch |
| Web app (HA panel, hosted site) | `app-ui` Wasm, same code as desktop | Yes, as desktop (cross-origin) | app-ui Settings; choice and ledger in the page origin's `localStorage` | Blob fetches tagged `?client=Terrazzo/<v> (web; surface=web)` (a page can't set its User-Agent); with opt-in, rows with `surface` `web`. The usage endpoint answers the CORS preflight. The public demo counts nothing. |
| Wear (widgets, app) | On the watch (`CardWidget`, Glance Wear capture) from card configs and values the phone syncs | No: designs are captured on the watch | Watch (`WearPrefs`) | **Nothing.** The watch has no design server, and reports would need a new watch→phone data path. |
| TV kiosk | On the TV (`TvKioskCards`) | No | TV (`TvPrefs`) | **Nothing.** No design server configured, so nothing to send to. |

Desktop and web build their counts with the shared `TemplateUsage` and
`UsageSharing` (`app-ui` `AppCardUsage`), so the rules are the phone's:
off by default, an explicit choice is never overridden, and turning it
off deletes pending counts. The app-ui hosts have no periodic sync, so
they upload finished days once a launch.

## Not done

- Wear and TV (see above). Covering Wear would need either a design
  server on the watch or a watch→phone report path; TV would need a
  design server setting.
- A catalog data product exporting the same rows is still to do.
