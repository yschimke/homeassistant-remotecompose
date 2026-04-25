# `test-docker/` — end-to-end add-on smoke test

Builds the add-on Docker image (using `addon-server/addon/Dockerfile`),
runs it next to a real Home Assistant Core container, hits the public
endpoints, and tears down. Useful for catching breakage between the
JVM code, the Dockerfile, and the HA WebSocket contract before publishing
the add-on image.

## What it checks

- `addon-server/addon/Dockerfile` builds cleanly against this repo
- `/healthz` returns `200 ok`
- `/readyz` flips to `200` once the supervisor bridge has authenticated
  and hydrated its state cache
- `/v1/snapshot` returns a non-empty `states` map
- `/v1/dashboards` returns a JSON array
- `/v1/cards/*.rc` returns the typed `501` placeholder (until M3 lands)

## Prereqs

- Docker + Docker Compose v2
- `jq`
- `integration/.env.local` containing `HA_TOKEN=<long-lived access token>`.
  Create the token via the existing onboarding flow once:

  ```sh
  cd integration
  docker compose up -d homeassistant   # HA on :${HA_HOST_PORT:-8124}
  # → open the URL, complete onboarding, Profile → Security → create token
  echo "HA_TOKEN=<token>" >> .env.local
  docker compose down
  ```

## Running

```sh
addon-server/test-docker/run.sh
```

On failure the runner dumps `addon-server` logs to
`/tmp/ha-rc-addon-test-server.log` before tearing down.

## Notes

- This runs the add-on outside the supervisor, so there's no
  `SUPERVISOR_TOKEN`. We fall through to the `HA_BASE_URL` + `HA_TOKEN`
  path in [`Config.kt`](../src/main/kotlin/ee/schimke/ha/addon/Config.kt).
- HA is on the compose-internal network as `ha:8123`; the add-on
  publishes `127.0.0.1:18099` so the host smoke checks can hit it without
  clashing with any HA install on `:8124` or another local add-on.
- The compose stack uses project name `ha-rc-addon-test` so it doesn't
  collide with `integration/compose.yaml`.
