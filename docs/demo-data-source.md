# Public WASM demo boundary

`/demo` always uses the dedicated demo Home Assistant server. It renders
`DashboardExperience` with the same `Dashboard` and `HaSnapshot` models as the shared
host. The renderer owns design selection; the host owns all data. Adaptive layouts
remain inside `DashboardContent` and receive the current card and snapshot.

`DemoDataSource.read(): DemoData` returns a complete Lovelace dashboard and snapshot.
`demoFrames(source)` starts with no data and a connecting status. It reads the fixed
same-origin `/demo-ha/dashboard` and `/demo-ha/api/states` gateway routes, refreshes
every five seconds, and marks the result live only after both reads succeed. The
request budget is four seconds. A failed or stale backend read clears the cards,
shows “Demo Home Assistant unavailable · retrying”, and retries after fifteen seconds.
No backend exception body is displayed. There is no fake mode, fixture fallback,
local state toggle, or data-source selector. Legacy `?data=fake` URLs use HA too.

The backend enforces a named entity/service allowlist; UI controls are not its
security boundary. `PublicDemoDataSource.toggle()` accepts only the demo lamp and
coffee switch, POSTs to their fixed gateway service route, and refreshes HA state.
Errors are visible without changing the displayed state optimistically. All public
visitors share the same HA-owned state. Remote templates can select a local entity
slot with a Toggle action; other encoded actions are ignored. Accessible host
switches work with templates that lack action regions. See
[the gateway contract](../deploy/demo-ha/README.md).

This route accepts no OAuth credentials or private Home Assistant address. The public
demo and authenticated HA plugin remain separate hosts. The plugin supplies its own
host-owned dashboard/snapshot and must never redirect the public demo to a private
instance or embed an administrative credential.

## Entity details

Right-click a single-entity card or an entity row to inspect that entity. The public
demo opens an details dialog showing the current HA state, last-update timestamp
and sanitized public attributes. It keeps polling while details are open. Close returns
to the selected dashboard view; an HA outage clears the details with the dashboard.
Right-click does not issue a service call or change state.

Inside the authenticated HA panel the same gesture uses the host's `hass-more-info`
bridge and HA's native dialog. A standalone authenticated dashboard opens the configured
HA web interface at `/lovelace?more-info-entity-id=<entity_id>` in another tab, using HA's
[more-info URL parameter](https://github.com/home-assistant/frontend/blob/dev/src/common/url/more-info-query-params.ts).
The URL contains no access token. No native HA frontend or administrator capability is
exposed through the public demo gateway.
