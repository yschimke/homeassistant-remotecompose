# Public demo and HA integration evidence

All data shown here is generic fixture data. Captures use Chromium at device scale
1: phone 390 × 844, wall 1440 × 900, HA fixture 1200 × 800, and an element capture
for the TS card. No capture exceeds 1800 px in either dimension.

| Surface | Before | After |
| --- | --- | --- |
| Phone demo | [Connection screen](demo-mobile-before.png) | [Classic](demo-mobile-classic.png), [Console](demo-mobile-console.png), [offline](demo-mobile-offline.png) |
| Wall demo | [Connection screen](demo-wall-before.png) | [Classic](demo-wall-classic.png), [Console](demo-wall-console.png) |
| HA Lovelace in WASM | New surface | [Dashboard with a ticking relative-time value](ha-wasm-dashboard.png) |
| HA custom card in TS player | New surface | [Initial live value](ha-ts-card.png), [updated name/on state](ha-ts-card-updated.png) |

The before captures use the last successful production website artifact from
run `39009930954`, commit `43a6add201b4a3733aafda440a1644adc8bc4387`.
After captures use this branch's development WASM bundle and the pinned TS
player `2.11.0`. Current mobile tile templates are committed under
`home-assistant/test/fixtures/`, with their provenance and hashes. Browser tests
return those templates for light cards and deliberately return 404 for other
types to exercise built-in fallback; the fallback messages in these screenshots
are expected. The separate offline test aborts all design-service requests and
verifies that the dashboard remains interactive after the fetch failure.

## Verified locally

- Focused Kotlin/WASM compilation and development webpack build.
- `:app-ui:jvmTest`, `:rc-player-ui:jvmTest`: five tests, including repeated
  entity positions/privacy and rejection of unpopulated converter bindings.
- `:design-server:test --tests '*DemoSiteTest'`: serving the demo, WASM MIME,
  missing assets and keeping unrelated routes separate.
- The complete demo browser script: phone/wall layouts, actual RC playback,
  inline design/theme changes, view navigation, no credentials or WebSockets,
  read-only fallback and continued interaction after a browser fetch failure.
- The HA frontend browser suite uses the actual WASM and TS players. It checks
  initial/live readings, relative-time ticks without another HA update, rejected
  converter-only templates, default read-only taps, enabled current-session
  service calls, native `<ha-card>` ownership and the trusted-parent bridge.
- Installer tests and a locally packaged development installer. Production
  optimization, wider checks and the production installer run in GitHub CI.

The HA browser object is a fixture, not a running Home Assistant installation.
No real dashboard or private HA data was accessed. These captures do not prove
a deployed `/demo` or installation compatibility on a running HA version.

## Deployment and external limitations

The image and edge route are in homeassistant-remotecompose PR #880 and
compose-preview-server PR #1725. This session has no preview-box credentials or
Docker. Deployment, external verification and installation on real HA remain
pending. The separate demo backend is PR #878; this work leaves it to its owner.

Two existing issues were observed outside this change. Linear is unavailable in
this Hive account, so neither was filed elsewhere:

- The public manifest listed Console SHA
  `d3ea45c392d8cbf49a52f2a819197acccd71ee35d73db7acd5a867d7e88ea50f`, but
  [its blob](https://ha.coo.ee/designs/v1/blobs/d3ea45c392d8cbf49a52f2a819197acccd71ee35d73db7acd5a867d7e88ea50f.rc)
  returned HTTP 404 on 2026-10-17. The Console fixture comes from a successful
  library CI artifact instead. Hive error key: `0fb493776a53`
  (`observed:yschimke/homeassistant-remotecompose:published console template blob returns http # while its sha is listed in manifest`).
- The published tile's TS-player layout can clip a longer updated entity name
  even though the binding and paint cache contain the full new string. The
  updated card capture shows this; the test verifies the new name in the player's
  paint cache and the on/off update. Hive error key: `680b55a226f9`
  (`observed:yschimke/homeassistant-remotecompose:ts player clips a longer live entity name to the template text width`).
