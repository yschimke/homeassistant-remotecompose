# Public demo Home Assistant backend

Dedicated Home Assistant **2026.10.2**, six generic simulated entities, and a public read-only
facade for the Wasm `/demo`. This does not connect to a real home. Deployment requires host access;
creating this configuration or passing unit tests does not mean the public endpoint is deployed.

## Frontend connection contract (v1)

- Base URL: `https://ha.coo.ee/demo-ha`
- WebSocket: `wss://ha.coo.ee/demo-ha/api/websocket`
- No public credential needed. For the existing `HaClient`, use base URL above and access token
  `public-demo` (a compatibility string, not a secret). No OAuth or administrative login.
- Public HTTP reads: `GET /api/`, `/api/config`, `/api/states`, `/api/states/<allowed entity_id>`,
  `/dashboard`, `/healthz`. Prefix every route with `/demo-ha` on the public host.
- `GET /dashboard` returns the checked-in [dashboard.json](dashboard.json), also served by the
  WebSocket `lovelace/config` command. Both return a Lovelace document:
  `{"title":"Demo Home","views":[{"title":"Home","path":"home","cards":[...]}]}`.
- `GET /api/states` / WebSocket `get_states` return an array of HA state objects:

  ```json
  [{"entity_id":"sensor.demo_temperature","state":"21.5",
    "attributes":{"friendly_name":"Demo temperature","device_class":"temperature","unit_of_measurement":"°C"},
    "last_changed":"2026-10-17T12:00:00+00:00","last_updated":"2026-10-17T12:00:00+00:00",
    "context":{"id":null,"parent_id":null,"user_id":null}}]
  ```

  Values are strings as in HA. Only the six IDs in [entities.json](entities.json) are exposed.
  Only named display attributes survive filtering. Config is a fixed public projection with
  `location_name: "Demo Home"`, `version: "demo-readonly-v1"`, `time_zone: "UTC"`,
  `read_only: true`, metric `unit_system`, `internal_url: null`, public `external_url`.
  No raw HA config, user/device metadata, paths, coordinates, history, logs, tokens, or media URLs.
- Cross-origin HTTP reads allow `https://ha.coo.ee` with no cookies. Same-origin use is preferred.
  Browser origins are not the security boundary: the server rejects writes for every client.

### WebSocket protocol and updates

The framing follows the [HA WebSocket API](https://developers.home-assistant.io/docs/api/websocket/).
Server opens with `{"type":"auth_required","ha_version":"demo-readonly-v1"}`. Send
`{"type":"auth","access_token":"public-demo"}`; receive `{"type":"auth_ok","ha_version":"demo-readonly-v1"}`.
The token field is ignored, never forwarded. Commands need positive integer IDs:

```json
{"id":1,"type":"get_states"}
{"id":2,"type":"lovelace/config","url_path":"demo"}
{"id":3,"type":"subscribe_events","event_type":"state_changed"}
```

Responses: `{"id":1,"type":"result","success":true,"result":...}`. Subscription ACK has
`result: null`. Updates reuse the subscription ID:

```json
{"id":3,"type":"event","event":{"event_type":"state_changed","data":{
  "entity_id":"sensor.demo_temperature","old_state":{},"new_state":{}
},"origin":"LOCAL","time_fired":"2026-10-17T12:01:00+00:00",
"context":{"id":null,"parent_id":null,"user_id":null}}}
```

`old_state` and `new_state` have the full state shape above (or null on appearance/removal).
HA templates change each minute; the facade polls once every five seconds **globally**, not per
visitor. Events represent changes between polls; this is not a lossless HA event bus. Subscribe
first, fetch `get_states`, then apply events; fetch again after every reconnect. No replay IDs.

Other allowed commands: `get_config`, `lovelace/dashboards/list` (one `demo` dashboard),
`config/entity_registry/list_for_display` (`{"entities":[]}`), `config/device_registry/list` and
`config/area_registry/list` (empty arrays), `ping` (`{"id":n,"type":"pong"}`),
`unsubscribe_events` with `subscription: <id>`. One subscription per connection; only explicit
`state_changed` is allowed. `lovelace/config` accepts omitted/null `url_path`, `lovelace` or `demo`.

### Error behavior

- Every HTTP verb other than GET/HEAD/OPTIONS returns **403**
  `{"code":"read_only","message":"Demo is read-only"}`. OPTIONS is 204 and changes nothing.
- Unknown paths / entities: **404** `{"code":"not_found"}`. No fallback to upstream HA.
- Unready or last successful poll older than 15 seconds: **503** `{"code":"upstream_unavailable"}`
  for reads/health; state reads on an existing WebSocket return that error code and polling closes
  sockets with 1013 when stale. Reconnect with backoff; do not silently show old data as live.
- Every unlisted WebSocket command, including `call_service`, `fire_event`, `render_template`,
  `lovelace/config/save`, registry updates and token creation, returns
  `{"id":n,"type":"result","success":false,"error":{"code":"read_only","message":"read only"}}`.
  Unknown dashboard/unsubscribe IDs use `not_found`; duplicate subscriptions use `subscription_limit`.
- Malformed JSON / missing or invalid IDs close the connection (1007/1008); frames over 16 KiB
  are refused. Maximum 100 concurrent sockets (new handshakes receive HTTP 429), 30-second
  heartbeat, 90-second inbound inactivity limit. Clients should send periodic `ping` commands.
- All HTTP responses use `Cache-Control: no-store`. Errors never include upstream bodies/secrets.

## Provision on preview.coo.ee

Keep this checkout on the host, separate from the preview server stack. Docker Compose is required.
The companion preview-server PR installs `/demo-ha/*` routing to `demo-ha-gateway:8080` and attaches
Caddy to this stack's **edge** network; it does not route directly to HA.

```sh
cd deploy/demo-ha
# Do not start the public gateway until its secret exists.
docker compose up -d homeassistant
docker compose --profile setup run --rm bootstrap
docker compose up -d --build gateway
curl --fail http://127.0.0.1:8125/healthz
```

Bootstrap generates a random owner password and a 365-day long-lived gateway token. It prints
neither. Owner credentials stay in host-only `private/owner.json` (0600, parent 0700), gateway token
in `private/ha_token` (0600, UID 65532), ignored by git. The token is mounted only into the facade.
Bootstrap stops if owner creation already happened without a saved token; recover using the private
owner file via an operator-only tunnel/container network rather than resetting or exposing HA.
The remaining onboarding steps are intentionally not completed: HA's core-config onboarding step
would install cloud-backed integrations. The facade does not depend on those steps or HA's UI.
Rotate the token privately before its expiry and recreate the gateway; never send it to the frontend.

HA has no host port, host network, host devices, Docker socket, discovery, recorder or external
network route. Both Compose networks are internal; only the gateway has a loopback-bound host
port (8125). The private network contains HA + facade; edge contains facade + Caddy. The public
facade makes exactly one fixed upstream request, `GET /api/states`, with its own credential.
It never relays client headers, bodies, paths or commands. Its public dashboard/config/registries
are fixed reviewed projections. The native HA state API and service API are never exposed.

To enable the preview-server route after this stack is healthy, follow that repository's
`deploy/image/docker-compose.demo-ha.yml` instructions. Inspect Caddy's effective configuration
before reload and preserve all existing frontend/design routes. Backend rollback is to remove that
overlay/route and stop the demo containers, retaining the HA volume and private credentials.

## Verification

```sh
python -m pip install -r requirements.txt
python -m unittest discover -s . -v
python verify_public.py https://ha.coo.ee/demo-ha --stream
```

The unit suite starts a fake upstream and verifies both accepted reads and that denied requests
never reach it. It checks filtering, stale state, malformed frames, subscriptions and changed data.
`.github/workflows/demo-ha.yml` runs the same tests plus a **real HA container**, private onboarding,
and the public verifier including a real minute-based update. This VM has no Docker; use GitHub CI
for that lane. The verifier attempts REST and WebSocket mutations with no admin token and checks
that battery state and dashboard remain unchanged. Run it again against the public HTTPS URL after
deploying; a successful local test is not evidence about the deployed edge.
