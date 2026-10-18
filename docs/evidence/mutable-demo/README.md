# Mutable public demo evidence

The public WASM demo now uses only the dedicated demo HA server. It has no fake-data
selector, URL override, fixture fallback or local-only toggle. On an outage it clears
HA cards and controls, shows an unavailable message, and reconnects automatically.
Design and theme selection remain available.

Phone images are 390 × 844; wall images are 1440 × 900, at device scale 1.
The before images use integration commit `73b37aaa9`. Current after images use the
HA-only development WASM bundle.

| Scenario | Before | After |
| --- | --- | --- |
| Phone data | [Client fixture](before-fake.png) | [Real demo HA](after-real-ha-phone.png) |
| Wall data | [Read-only fixture](before-live.png) | [Real demo HA](after-real-ha-wall.png) |
| Wall control | [Read-only fixture](before-live.png) | [Gateway fixture lamp controlled on](after-live.png) |
| HA outage | Fixture fallback | [Phone](after-unavailable-phone.png), [wall](after-unavailable-wall.png) |

The real-HA captures serve the new bundle locally and forward only its `/demo-ha`
GET requests to the deployed public HTTPS gateway. Both sizes observed at least two
successful HA state reads. Even a legacy `?data=fake` URL loaded HA data. No controls
were clicked against the real server. Its old dashboard still says read-only because
the backend and frontend changes have not been deployed.

`scripts/test-demo-browser.py` uses a controlled HTTP gateway fixture to verify exact
service POSTs, successful state refresh, rejected-action recovery, five-second polling,
relative-time updates, initial unavailability and repeated outage/reconnection cycles.
It asserts that unavailable cards and switches disappear without replacement data and
that reconnection restores the server state. Phone and wall checks exercise inline
styles/themes and actual RC playback using checked-in binary fixtures. Resolver
responses and the gateway are mocked in this suite; it does not prove production
deployment, actual HA service behavior or hardware GPU rendering.

The real HA container provisioning/control lane passed separately in
[CI run 39106960286](https://github.com/yschimke/homeassistant-remotecompose/actions/runs/39106960286/job/117614802525):
REST changed lamp and coffee state, WebSocket restored them, administration was
rejected, and a live state_changed event was observed. Docker is unavailable locally.
Historical `after-fake.png` captures are retained as earlier evidence and no longer
describe the current UI.
