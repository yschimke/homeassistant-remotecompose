# Public WASM demo evidence

All data shown here is generic fixture data. Captures use Chromium at device scale
1: phone 390 × 844, wall 1440 × 900. No capture exceeds 1800 px in either dimension.

| Surface | Before | After |
| --- | --- | --- |
| Phone demo | [Connection screen](demo-mobile-before.png) | [Classic](demo-mobile-classic.png), [Console](demo-mobile-console.png), [offline](demo-mobile-offline.png) |
| Wall demo | [Connection screen](demo-wall-before.png) | [Classic](demo-wall-classic.png), [Console](demo-wall-console.png) |

The before captures use the last successful production website artifact from
run `39009930954`, commit `43a6add201b4a3733aafda440a1644adc8bc4387`.
After captures use this branch's development WASM bundle and CMP player. Current mobile tile templates are committed under
`scripts/fixtures/demo/`, with their provenance and hashes. Browser tests
return those templates for light cards and deliberately return 404 for other
types to exercise built-in fallback; the fallback messages in these screenshots
are expected. The separate offline test aborts all design-service requests and
verifies that the dashboard remains interactive after the fetch failure.

## Verified locally

- Focused Kotlin/WASM compilation and development webpack build.
- `:app-ui:jvmTest`, `:rc-player-ui:jvmTest`: ten tests, including fake-mode
  isolation, outage recovery, request timeout, cancellation, HA timestamp decoding,
  repeated entity positions/privacy and rejection of unpopulated converter bindings.
- `:design-server:test --tests '*DemoSiteTest'`: serving the demo, WASM MIME,
  missing assets and keeping unrelated routes separate.
- Real WASM browser checks at phone and wall sizes: RC playback, inline design/theme
  changes, view navigation, no credentials or WebSockets, disabled native controls,
  and continued interaction after design-service fetch failure.
- Real WASM/Ktor HTTP integration with a local public-gateway fixture: initial live
  reading, relative-time updates without another HA event, five-second refresh,
  outage dropping live data for labelled fake data,
  fake mode making no gateway requests, and switching back to live.
  [Live reading](demo-wall-live.png), [explicit fallback](demo-wall-live-fallback.png).

The gateway in these browser checks is a fixture, not a running Home Assistant.
Production optimization and wider checks run in GitHub CI. These captures do not
prove a deployed `/demo`. HA plugin implementation and installed-HA evidence belong
to merged PR #879. Its authenticated hosted entry is preserved alongside `/demo`;
64 HA-client JVM tests and its two TS-player/session-bridge browser tests also pass
locally after integration.

## Deployment and external limitations

The image and edge route are in homeassistant-remotecompose PR #880 and
compose-preview-server PR #1725. This session has no preview-box credentials or
Docker. External deployment remains pending. Backend PR #878 is merged, with
its companion edge route in preview-server PR #1724; both public URLs still return
404 at verification on 2026-10-17. See [the data-source contract](../../demo-data-source.md).

Two existing issues were observed outside this change. Linear is unavailable in
this Hive account, so neither was filed elsewhere:

- The public manifest listed Console SHA
  `d3ea45c392d8cbf49a52f2a819197acccd71ee35d73db7acd5a867d7e88ea50f`, but
  [its blob](https://ha.coo.ee/designs/v1/blobs/d3ea45c392d8cbf49a52f2a819197acccd71ee35d73db7acd5a867d7e88ea50f.rc)
  returned HTTP 404 on 2026-10-17. The Console fixture comes from a successful
  library CI artifact instead. Hive error key: `0fb493776a53`
  (`observed:yschimke/homeassistant-remotecompose:published console template blob returns http # while its sha is listed in manifest`).
- The published tile's TS-player layout can clip a longer updated entity name
  even though the binding and paint cache contain the full new string. Earlier HA fixture tests verified the full new string in the paint cache; this
  observation has been handed to the plugin worker. Hive error key: `680b55a226f9`
  (`observed:yschimke/homeassistant-remotecompose:ts player clips a longer live entity name to the template text width`).
