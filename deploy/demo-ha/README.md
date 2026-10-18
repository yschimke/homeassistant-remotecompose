# Public demo Home Assistant backend

Dedicated Home Assistant **2026.10.2**, nine public generic simulated entities, and a public entity-control
facade for the Wasm `/demo`. This does not connect to a real home. Deployment requires host access;
creating this configuration or passing unit tests does not mean the public endpoint is deployed.

## Frontend connection contract (v3)

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

  Values are strings as in HA. Only the nine IDs in [entities.json](entities.json) are exposed.
  Only named display attributes survive filtering. Config is a fixed public projection with
  `location_name: "Demo Home"`, `version: "demo-controls-v3"`, `time_zone: "UTC"`,
  `read_only: false`, metric `unit_system`, `internal_url: null`, public `external_url`.
  No raw HA config, user/device metadata, paths, coordinates, history, logs, tokens, or media URLs.
- Cross-origin HTTP reads allow `https://ha.coo.ee` with no cookies. Same-origin use is preferred.
  Browser origins are not the security boundary: the server allows only the named demo controls for every client.

### WebSocket protocol and updates

The framing follows the [HA WebSocket API](https://developers.home-assistant.io/docs/api/websocket/).
Server opens with `{"type":"auth_required","ha_version":"demo-controls-v3"}`. Send
`{"type":"auth","access_token":"public-demo"}`; receive `{"type":"auth_ok","ha_version":"demo-controls-v3"}`.
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

### Public state controls

Only `light.demo_lamp` and `switch.demo_coffee` accept `turn_on`, `turn_off`, and `toggle`,
using their matching `light` or `switch` domain. These simulated entities use private input-boolean state holders:
controls change their HA state rather than following the clock. They start off on a fresh
HA startup; the helpers and their services are not exposed by the gateway. State is shared by visitors.
The generic thermostat controls a private input-boolean heater; no physical devices are connected.
Only `climate.set_temperature` for `climate.demo_thermostat` is allowed, with a numeric
half-degree setpoint from 16 through 28°C and no extra parameters. HVAC-mode, preset,
helper and administrative actions remain blocked. Sensors remain live simulated HA readings.
The dashboard includes thermostat, humidity/battery gauges and power/solar meters.

REST: `POST /api/services/light/toggle` with `{"entity_id":"light.demo_lamp"}` returns the
sanitized refreshed state array. WebSocket: `{"id":4,"type":"call_service","domain":"light",
"service":"toggle","target":{"entity_id":"light.demo_lamp"}}` (or `service_data.entity_id`).
Exactly one entity must be supplied, as a string or a one-element list. Additional service data,
area/device targets, conflicting targets, unlisted entities, domains and services are rejected.
`return_response` may be absent or false. No client payload or upstream response is relayed.
After a successful call, the gateway refreshes its shared snapshot and emits state-change events.
Calls are serialized with polling and refused while the cached upstream state is stale.

The provisioner recreates only the dedicated demo HA container to load the checked-in
configuration, preserving its volume and credentials. It then rebuilds/recreates the gateway
and runs the verifier before frontend publication. The verifier changes and restores the
lamp, coffee switch and thermostat through the real HA REST/WebSocket paths. Rebuilding the gateway alone does not change the running HA configuration. Preserve the existing HA volume, credentials and deployment overlays.

### Error behavior

- Apart from the allowlisted service POSTs above, every HTTP verb other than GET/HEAD/OPTIONS
  returns **403** `{"code":"forbidden","message":"Demo administration is disabled"}`.
  Invalid service payloads return `forbidden`; malformed JSON returns **400**. OPTIONS is 204.
- Unknown paths / entities: **404** `{"code":"not_found"}`. No fallback to upstream HA.
- Unready or last successful poll older than 15 seconds: **503** `{"code":"upstream_unavailable"}`
  for reads/health; state reads on an existing WebSocket return that error code and polling closes
  sockets with 1013 when stale. Reconnect with backoff; do not silently show old data as live.
- Every unlisted WebSocket command, including `fire_event`, `render_template`,
  `lovelace/config/save`, registry updates and token creation, returns
  `{"id":n,"type":"result","success":false,"error":{"code":"forbidden","message":"forbidden"}}`.
  Unknown dashboard/unsubscribe IDs use `not_found`; duplicate subscriptions use `subscription_limit`.
- Malformed JSON / missing or invalid IDs close the connection (1007/1008); frames over 16 KiB
  are refused. Maximum 100 concurrent sockets (new handshakes receive HTTP 429), 30-second
  heartbeat, 90-second inbound inactivity limit. Clients should send periodic `ping` commands.
- All HTTP responses use `Cache-Control: no-store`. Errors never include upstream bodies/secrets.

## Provision on preview.coo.ee

The service definitions, pinned images, generic data and routing overlay are committed config;
there is no manual HA setup, public token to create, or secret to commit. The host still needs
an operator to apply that config. Required access is a shell on `preview.coo.ee` with permission
to use Docker, Docker Compose v2, and checkouts of this repository and `compose-preview-server`.
Docker must be able to pull the pinned images and build the gateway. Python is only needed
outside containers for the final public verifier, which can also run from your laptop.

Keep this checkout on the host, separate from the preview server stack.
The companion preview-server PR installs `/demo-ha/*` routing to `demo-ha-gateway:8080` and attaches
Caddy to this stack's **edge** network; it does not route directly to HA.

```sh
# From this repository's root, on preview.coo.ee:
bash deploy/demo-ha/provision.sh
```

The script checks Docker and Compose config, builds the helper images, starts HA, bootstraps
private credentials, starts the facade, and verifies reads, rejected mutations and a live update
from the isolated edge network. It stops on failure and reports success only after verification.
It works from any directory and pins the Compose file and `public-demo-ha` project even if the
shell exports settings for another stack. Rerun it after pulling reviewed configuration changes;
it retains the HA volume and existing token. It never removes volumes or changes Caddy.
If it fails after starting services, those services remain available for diagnosis; fix the
reported failure and rerun. Do not delete the volume or private directory to retry onboarding.

Bootstrap generates a random owner password and a 365-day long-lived gateway token. It prints
neither. Owner credentials stay in host-only `private/owner.json` (0600, parent 0700), gateway token
in `private/ha_token` (0600, UID 65532), ignored by git. The token is mounted only into the facade.
Bootstrap stops if owner creation already happened without a saved token; recover using the private
owner file via an operator-only tunnel/container network rather than resetting or exposing HA.
The remaining onboarding steps are intentionally not completed: HA's core-config onboarding step
would install cloud-backed integrations. The facade does not depend on those steps or HA's UI.
Rotate the token privately before its expiry and recreate the gateway; never send it to the frontend.

HA has no host port, host network, host devices, Docker socket, discovery, recorder or external
network route. Both Compose networks are internal. Neither HA nor the facade publishes host ports:
Docker internal networks do not provide the host-port reachability a loopback probe would need.
The private network contains HA + facade; edge contains facade + Caddy and the optional verifier.
The verifier has no private network or credentials and probes the same facade address as Caddy. The public
facade reads `GET /api/states` and constructs allowlisted service POSTs with its own credential.
It never relays client headers, bodies, paths or arbitrary commands. Its public dashboard/config/registries
are fixed reviewed projections. The native HA state API and unrestricted service API are never exposed.

### Enable the committed public route

After provisioning passes, use the `compose-preview-server` checkout on the same Docker host.
Its committed [`deploy/image/docker-compose.demo-ha.yml`](https://github.com/yschimke/compose-preview-server/blob/main/deploy/image/docker-compose.demo-ha.yml)
mounts the route and connects Caddy to `public-demo-ha_edge`. The host's existing HA domain must
be `ha.coo.ee`, with DNS/TLS already working. Keep the existing deployment's project name,
environment file, working directory and **every existing Compose overlay**. Add
`docker-compose.demo-ha.yml` to its persistent `COMPOSE_FILE` setting, so future deployments
retain it. Do not replace an existing deployment setting with only the demo overlay.

For example, if the host currently uses `docker-compose.yml:docker-compose.deploy-config.yml`,
the resulting setting and apply commands in that repository's `deploy/image` directory are:

```sh
export COMPOSE_FILE=docker-compose.yml:docker-compose.deploy-config.yml:docker-compose.demo-ha.yml
docker compose config --quiet
docker compose up -d --no-deps caddy
```

This is an example for that existing file list, not a replacement for the host's other overlays.
Persist the resulting file list wherever the host's deploy job reads it; the shell export alone
does not persist across logins. Inspect Caddy's effective configuration before reload and preserve
all existing frontend/design routes. Only the facade is reachable at `/demo-ha/*`; do not add
a route or published port to HA itself. The backend script deliberately cannot guess the host's
preview-stack settings.

Finally, from this checkout on the host **or your laptop**, with Python 3 and network access:

```sh
python3 -m venv /tmp/demo-ha-verify
/tmp/demo-ha-verify/bin/pip install -r deploy/demo-ha/requirements.txt
/tmp/demo-ha-verify/bin/python deploy/demo-ha/verify_public.py https://ha.coo.ee/demo-ha --wait-ready 120 --stream
```

Only a passing run against that HTTPS URL establishes public reads, rejected mutation attempts
and live updates through the deployed route. Keep its output as deployment evidence; it needs
no private credential. A 404 usually means the Caddy overlay is not active; a 503 means the
facade is not ready or its upstream data is stale.

Backend rollback is to remove that
overlay/route and stop the demo containers, retaining the HA volume and private credentials.

## Verification

```sh
python -m pip install -r requirements.txt
python -m unittest discover -s . -v
python verify_public.py https://ha.coo.ee/demo-ha --stream
```

The unit suite starts a fake upstream and verifies both accepted reads and that denied requests
never reach it. It checks filtering, stale state, malformed frames, subscriptions and changed data.
`.github/workflows/demo-ha.yml` runs the same tests plus `provision.sh` against a **real HA container**,
including private onboarding and the verifier with a real minute-based update. This VM has no Docker; use GitHub CI
for that lane. The Compose `verify` profile runs the public verifier inside the isolated edge network, waits up
to 120 seconds for readiness, then requires the same reads, rejected writes and a live update.
The verifier changes and restores both controllable entities through REST and WebSocket,
rejects administrative operations, and checks that battery state and dashboard remain unchanged. Run it again against the public HTTPS URL after
deploying; a successful local test is not evidence about the deployed edge.
