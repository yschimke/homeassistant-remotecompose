# Physical Console instruments

[Matched before / after comparison](comparison.png) (980 × 1022).

The `after/` images are actual Remote Compose documents rendered by the app's
`RcComposePlayer` under Robolectric, not mockups. Cards use the same template capture,
slot binding and playback path as the published design gallery. The thermometer uses the
shared measurement component (also exposed by `ha-cards/thermometer` and compact sensor
presentations). Fixtures use synthetic Home Assistant data and a frozen 2026-10-17 05:17:28 UTC.

`before-local/` uses the identical test fixtures against the parent revision's components.
The gauge images are pixel-identical. The baseline run reaches the final assertion and fails
because the old clock has no red LED emitters; the modified source passes it.

`before/` contains the public gallery's original PNGs downloaded on 2026-10-17 from
`https://ha.coo.ee/designs/v1/previews/`:

| File | Published preview |
| --- | --- |
| switch.png | bece13e32f61bbc7-example.png |
| clock.png | c5ee6ea7e2df0aa9-example.png |
| controls.png | 364f7a5e4951f462-example.png |
| gauge.png | 212cb036d5cc7a91-example.png |

The live gallery was inspected with both `mode=light` and `mode=dark`, at serial
1792268193857. Both select the same Console assets: Console intentionally has one
instrument palette, and its design keys normalize both requested dashboard modes to dark.
This change preserves that behavior. The state matrix includes both requested modes,
96 dp switch / 180 dp clock widths, off / on / unavailable switches, row rockers,
playing / paused media, pause / resume / disabled printer controls, UTC / Tokyo clocks, second ticks, midnight rollover,
and low / high temperature.
The same switch, thermometer and clock documents receive state/time changes; a static
screenshot alone is not used as proof of live updates.

Local validation: 79 component tests, 5 catalog tests, and 13 focused converter/render tests
passed, plus formatting checks for all three modules.

Reproduce the app renders:

```sh
./gradlew :rc-converter:testDebugUnitTest --tests '*ConsoleDesignRenderTest' \
  -PlovelaceRenderEvidence="$PWD/docs/design/evidence/physical-console/after"
```

Publication remains the established process. `.github/workflows/design-server.yml`
regenerates the gallery library and builds its server image on PRs; on `main` it also
publishes the image. `.github/workflows/design-artifacts-ha-cards.yml` publishes the
builder catalog (including `HaCard/ConsoleControls`) from `main`. The screenshots here
are local verification; they do not claim that the changed assets are deployed live.
