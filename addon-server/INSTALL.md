# `addon-server` — installation, testing, connectivity

How to develop on the add-on, how to verify changes, and how the wires
actually go between the add-on, Home Assistant, and your clients.

For the design rationale see [`PLAN.md`](PLAN.md). For a quick orientation
see [`README.md`](README.md).

> Assumes the PR has landed and you're on `main`.

---

## 1. Local development

### Prereqs

| Tool | Version | Why |
|---|---|---|
| JDK 17 | exact | Kotlin toolchain target — every JVM module is compiled to bytecode 17 |
| JDK 21 | exact | Gradle daemon. The Metro KSP plugin (used by `:app` and friends) needs runtime 21+ |
| `./gradlew` | shipped | Pins Gradle 9.3.x |
| Docker + Compose v2 | recent | Add-on packaging + e2e test |
| `jq`, `curl` | any | Smoke checks |

Install both JDKs, then either set `JAVA_HOME` to JDK 21 (daemon) and pass
`-Dorg.gradle.java.installations.paths=…/jdk-17,…/jdk-21` once, or use the
`.github/actions/setup` recipe locally — it sets `JAVA_HOME_17_X64` and
`JAVA_HOME_21_X64` so Gradle's auto-detection finds both.

If you only have JDK 21 installed, the build fails at:

```
Cannot find a Java installation … matching: {languageVersion=17, …}.
Toolchain download repositories have not been configured.
```

The repo doesn't include a foojay toolchain resolver on purpose — CI
pre-installs both JDKs and we'd rather not auto-download a toolchain
during a developer build.

### Build

```sh
./gradlew :addon-server:installDist
```

Produces a runnable distribution at
`addon-server/build/install/addon-server/`:

```
addon-server/build/install/addon-server/
├── bin/addon-server          # POSIX launcher (used by Dockerfile ENTRYPOINT)
├── bin/addon-server.bat
└── lib/                      # all dependency jars
```

### Run against your real Home Assistant

Outside the supervisor we use a long-lived access token (the same one the
existing `integration/` flow uses).

```sh
export HA_BASE_URL=http://homeassistant.local:8123
export HA_TOKEN=<long-lived access token from HA Profile → Security>
export PORT=8099                      # default; pick another if 8099 is taken

./gradlew :addon-server:run            # iterative, with the Gradle daemon
# or
./addon-server/build/install/addon-server/bin/addon-server   # the same thing the docker image runs
```

You should see:

```
INFO  ee.schimke.ha.addon.Main - starting addon-server on :8099, ha=http://homeassistant.local:8123
INFO  ee.schimke.ha.addon.HaSupervisorBridge - HA bridge authenticated
INFO  ee.schimke.ha.addon.HaSupervisorBridge - hydrated <N> entities
INFO  io.ktor.server.Application - Responding at http://0.0.0.0:8099
```

If `/readyz` stays at `503`, check the bridge log line — most likely your
token is wrong or HA is unreachable from the JVM (firewall / wrong host).

### Iterating

- `./gradlew :addon-server:compileKotlin` is the fastest "did it compile"
  loop.
- For interactive runs, `./gradlew :addon-server:run` keeps the Gradle
  daemon warm; restart-after-edit is ~1 s once the daemon is hot.
- The `Main.kt` shutdown hook drains the WS bridge cleanly on `Ctrl-C`,
  so HA doesn't see an abandoned connection between iterations.

### Code layout cheat-sheet

```
addon-server/src/main/kotlin/ee/schimke/ha/addon/
├── Main.kt                       # Ktor server + DI
├── Config.kt                     # env → AddonConfig
├── bridge/
│   ├── HaSupervisorBridge.kt     # one persistent WS to HA Core
│   └── StateCache.kt             # entity_id → EntityState (StateFlow)
└── routes/
    ├── HealthRoutes.kt           # /healthz, /readyz
    ├── DashboardRoutes.kt        # REST /v1/...
    └── StreamRoute.kt            # WS /v1/stream
```

The `.rc` byte encoder lives outside this module — see PLAN.md "M3" for
the rc-converter-jvm port. Today the `.rc` endpoint returns a typed 501.

---

## 2. Testing

There are three test layers, in order of cost.

### a. Compile + unit tests

```sh
./gradlew :addon-server:test
```

No external services required. This is the only layer that runs in CI on
every PR. Use it for any code that doesn't need a real HA on the other end.

### b. Local smoke (no docker)

With `HA_BASE_URL` + `HA_TOKEN` exported as in §1:

```sh
./gradlew :addon-server:run &
PID=$!
sleep 5

curl -s http://localhost:8099/healthz                         # → ok
curl -s http://localhost:8099/readyz                          # → ready
curl -s http://localhost:8099/v1/snapshot | jq '.states | length'
curl -s http://localhost:8099/v1/dashboards | jq

# WS smoke (needs websocat or similar):
echo '{"id":1,"type":"subscribe","entities":["sun.sun"]}' | \
  websocat ws://localhost:8099/v1/stream

kill $PID
```

Good for "did my routing change break anything obvious" — but it doesn't
exercise the Dockerfile path.

### c. Docker end-to-end

The full thing: Dockerfile build, real HA in a sibling container, all
public endpoints. Lives at [`test-docker/`](test-docker/).

```sh
addon-server/test-docker/run.sh
```

What it asserts:

- `addon/Dockerfile` builds against the current tree
- `/healthz` = `200 ok`
- `/readyz` flips to `200` once the bridge has authenticated **and**
  hydrated the state cache
- `/v1/snapshot.states` is non-empty
- `/v1/dashboards` is a JSON array
- `/v1/cards/*.rc` is the typed `501` placeholder (until M3 lands)

On failure, addon-server logs are dumped to
`/tmp/ha-rc-addon-test-server.log` before teardown.

Prereq: `integration/.env.local` must have `HA_TOKEN=<…>`. If you've
already run the reference-capture pipeline once, you have it. If not:

```sh
cd integration
docker compose up -d homeassistant
# → open http://localhost:${HA_HOST_PORT:-8124}, complete onboarding
# → Profile → Security → create a long-lived access token
echo "HA_TOKEN=<token>" >> .env.local
docker compose down
```

### d. Real-add-on install

Ultimate end-to-end: install into a real HA Supervisor, exercise from a
real client. See §3.4 below.

---

## 3. Connectivity

Three connections matter. From bottom up:

```
                      ┌─────────────────────────────────────┐
                      │  HA Supervisor host                 │
                      │                                     │
   client ─── [3]───▶ │   addon-server  ◀──[1]──▶ HA Core   │
                      │                                     │
                      └─────────────────────────────────────┘
                                  ▲
                                  └──[2]── ingress proxy
```

### 3.1 [1] add-on → Home Assistant Core

One persistent WebSocket from `HaSupervisorBridge` to HA, used for both
request/response commands (`lovelace/config`, `get_states`,
`call_service`) and long-running event subscriptions (`state_changed`,
`lovelace_updated`).

Two modes, picked at startup by `Config.fromEnv`:

| Mode | When | Base URL | Auth |
|---|---|---|---|
| **supervisor** | running as an installed add-on | `http://supervisor/core` (injected) | `SUPERVISOR_TOKEN` env var (injected by the supervisor — needs `homeassistant_api: true` in `addon/config.yaml`, which we have) |
| **external** | local dev, the docker e2e test, or any side-car deployment | `HA_BASE_URL` env var (e.g. `http://homeassistant.local:8123`) | `HA_TOKEN` env var (long-lived access token) |

Both append the path `/api/websocket` — HA's actual WS handler. The
supervisor proxy is path-transparent, so `http://supervisor/core/api/websocket`
hits the same handler as `http://homeassistant.local:8123/api/websocket`.

Reconnect is 1→2→4→8→16→30 s exponential backoff, capped at 30 s. After
each reconnect the bridge re-runs:

1. auth handshake
2. `get_states` → repopulates `StateCache`
3. `subscribe_events` for `state_changed`
4. `subscribe_events` for `lovelace_updated`

Clients see a brief gap in `/v1/stream` events but their connection isn't
dropped; the bridge logs `HA bridge disconnected: …` and `HA bridge
authenticated` on either side of the gap.

### 3.2 [2] HA browser frontend → add-on (ingress)

`addon/config.yaml` declares `ingress: true`, `ingress_port: 8099`. The
supervisor reverse-proxies HA's browser session at
`/api/hassio_ingress/<token>/...` straight into our Ktor server. No
authentication code on our side — the supervisor only forwards requests
from already-authenticated HA users.

This path is "free": every existing HA web user can hit our REST + WS
endpoints under their existing login. Nothing else needs to be wired up
for a panel UI later.

### 3.3 [3] LAN client → add-on (Glance widget, Wear, ESP32, …)

For native clients that aren't going through the HA frontend, the add-on
also publishes its port on the LAN. `addon/config.yaml` exposes
`8099/tcp` (default mapping is `null` — the user picks the host port in
the add-on UI). Clients then talk to `http://<ha-host>:<port>/v1/...`.

This path needs explicit auth. The current prototype trusts whoever
reaches the port; the production behaviour described in PLAN.md
"§Auth → External" is `Authorization: Bearer <ha-token>`, validated by
proxying a `GET /api/` to HA Core with the supplied token. **Not yet
implemented** — landing it is M4 in the plan.

### 3.4 Installing the add-on into a real HA Supervisor

Once a published image lives at `ghcr.io/yschimke/ha-remotecompose-{arch}`
(see CI follow-up), users add the repo to their Supervisor and install
in two clicks:

1. **Settings → Add-ons → Add-on Store → ⋮ → Repositories →**
   `https://github.com/yschimke/homeassistant-remotecompose`
2. The store reloads and lists "RemoteCompose Dashboards". Click it,
   "Install", "Start".
3. **Logs** tab shows the standard JVM/Ktor startup. After ~3 s you
   should see the `HA bridge authenticated` line.
4. Open the in-frontend URL via "Open Web UI" (ingress) — for now this
   serves only the JSON endpoints; a panel UI lands later.

For local-on-your-own-machine development of the *real* add-on flow
(supervisor + ingress) without publishing an image:

1. Clone this repo onto the HA host.
2. Symlink `addon-server/addon/` into your local add-on repo dir
   (`/addons/remotecompose`), then add a `repository.yaml` next to it.
3. **Settings → Add-ons → ⋮ → Check for updates** and the supervisor
   builds the Dockerfile locally.

The Dockerfile is repo-context aware (it copies `gradle/`, `ha-model/`,
`ha-client/`, `addon-server/` together), so the supervisor needs the
whole repo on disk, not just `addon-server/addon/`. We can split a
self-contained add-on subtree later if that becomes painful.

### 3.5 Wire format reference

REST + WS shapes are documented in
[`PLAN.md` §"Wire protocol"](PLAN.md#wire-protocol). The short version:

- REST: `/v1/dashboards`, `/v1/dashboards/{path}`, `/v1/snapshot`,
  `/v1/cards/{id}.rc`, `/healthz`, `/readyz`.
- WS `/v1/stream`: client `subscribe`/`unsubscribe`/`call_service`;
  server `ready` / `state` (with `LiveBindings`-style names like
  `light.kitchen.is_on`) / `lovelace_updated` / `error`.

The named bindings in `state` messages match exactly what the existing
`rc-converter` `LiveBindings` bakes into `.rc` documents, so a client can
hand the bindings map straight to its `RemoteDocumentPlayer` named-state
setter without any name translation.
