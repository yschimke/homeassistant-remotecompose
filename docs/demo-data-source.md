# Public WASM demo boundary

`/demo` renders `DashboardExperience` with the same `Dashboard` and `HaSnapshot` models
as the shared desktop/mobile host. The renderer owns design selection and the host owns
all data. Adaptive layouts remain inside `DashboardContent`; they receive the card and
snapshot through the existing renderer callback, independent of the data source.

`DemoDataSource.read(): DemoData` is deliberately read-only. It returns the complete
Lovelace dashboard and snapshot together. There is no service/action/client/token field.
`demoFrames(mode, source)` starts immediately with generic fake data. Auto mode reads the
fixed same-origin `/demo-ha/dashboard` and `/demo-ha/api/states` gateway routes, refreshes
every five seconds, and marks the result live only after both reads succeed. Request
budget is four seconds. A failed/stale backend read discards the previous live snapshot,
visibly switches to fake data and retries after fifteen seconds. No backend exception
body is displayed. The backend independently refuses writes; disabled UI is not its
security boundary. See [the gateway contract](../deploy/demo-ha/README.md).

The in-page **Fake data** option cancels live loading and performs no gateway reads.
`/demo/?data=fake` starts in that mode for local development, stable screenshots and
backend-free use. **Live when available** resumes auto mode without leaving the page.
No OAuth or private Home Assistant address is accepted by this route. Neither mode
persists credentials. The fake fixture remains useful when the gateway is not deployed.

The public demo and HA plugin are separate hosts. A signed-in Home Assistant plugin
must provide its own host-owned dashboard/snapshot boundary; it must never redirect the
public demo to a private instance or embed an administrative credential.
