# addon-server

Home Assistant add-on that serves RemoteCompose dashboards from the HA
host. See [`PLAN.md`](PLAN.md) for the full design.

## Status

Prototype scaffold:

- Ktor/CIO server on `:8099`
- `HaSupervisorBridge` — long-lived WS to HA Core, hydrates a per-entity
  state cache, fans out `state_changed` and `lovelace_updated` events
- REST: `/v1/dashboards`, `/v1/dashboards/{path}`, `/v1/snapshot`,
  `/healthz`, `/readyz`
- WS: `/v1/stream` — per-client subscribe + named-binding state push,
  service calls round-tripped to HA via the bridge
- Add-on packaging in `addon/` (`config.yaml`, `Dockerfile`, `run.sh`)

The `.rc` byte endpoint is a typed 501 placeholder — see PLAN.md "M3" for
the converter port plan.

## Running locally (no add-on supervisor)

```sh
HA_BASE_URL=http://homeassistant.local:8123 \
HA_TOKEN=<long-lived access token> \
./gradlew :addon-server:run
```

Then:

```sh
curl http://localhost:8099/healthz
curl http://localhost:8099/v1/dashboards
curl http://localhost:8099/v1/snapshot | jq '.states | length'
```

## Running as an HA add-on

The add-on runs against the supervisor-injected `SUPERVISOR_TOKEN`; no
extra config required. Once published to a repo and installed, the
endpoints are reachable via ingress (in-frontend) or via the LAN port
exposed in `addon/config.yaml`.

## End-to-end test

`test-docker/run.sh` builds the add-on image, runs it next to a real
HA container, and smoke-checks the public endpoints. See
[`test-docker/README.md`](test-docker/README.md).
