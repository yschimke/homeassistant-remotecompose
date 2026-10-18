# WASM printing dashboard rendering

This evidence uses a synthetic authenticated Home Assistant transport and a narrow printing
view based on the reported screenshots. It contains no user states, device identifiers, or
credentials. The solid-colour preview images are test data, not photographs of a real print.
Screenshots are 390×844, 320×800 and 1280×900 CSS pixels, at device scale 1.

The baseline is main `e6423ed7152ac922f68631cdca169a1bdf8a7242`. Both builds use the
repository's existing dependency pins: RemoteCompose alpha21 and CMP player 2.13.1;
no dependency upgrade or upstream workaround removal is part of this fix.

## Confirmed causes

* WASM `NativeCard` treated image entities as scalar values. Home Assistant's image state
  is an update timestamp; the actual preview is its `entity_picture` attribute. Rendering
  the state with headline typography produced the wrapped timestamp cards.
* The anonymous catalog request supplies entity domains but cannot describe a real sensor's
  device class or complete shape. The deployed resolver returned `exact: false`, a
  `device_class=temperature;domain=sensor` design, and explained that it had selected the
  first of 110 tile shapes. The client accepted this response for progress, layer counts,
  timestamps and other unrelated sensors. The same wrong template caused unavailable
  labels/overlays to compete for a narrow cell.
* Every lazy `LibraryCard` owned its resolution/download effect. Leaving and re-entering
  composition discarded its bytes and recreated a loading/fallback layout, then fetched
  and inserted the design again. Light/switch templates also added another host-labelled
  switch row beneath the template, using the full entity name.
* Conditional cards fell through to the generic unsupported placeholder.
* Host fallback cards inherited the app's default Material palette rather than the chosen
  card style.

## Result and limits

Authenticated dashboards reject non-exact resolver guesses and use host cards with the
configured name, canonical `formatState`, readable unavailable/unknown text, and the selected
Classic, Braun, LCD or Console palette. Light/switch cards retain one host control, rather
than a template plus a duplicate long-label control. Exact, bindable templates remain playable.
The public curated demo keeps its existing template-selection behavior.

A dashboard-owned bounded cache shares resolutions and in-flight requests across lazy items;
returning items can immediately use completed bytes/images. Snapshot values remain local and
continue to update independently of cached designs. Transient failures can retry on a later
visit. Picture fetches stay on the configured HA origin, authenticate through the current token
provider, refuse redirects/cross-origin targets, and key on URL, state and last-updated revision.
Changing a frame with the same URL still refetches and replaces its pixels. Unavailable,
missing, failed or inaccessible images show a readable preview placeholder. These are still
images, not camera video streams or a full implementation of every picture-card option.

State and numeric conditions support legacy/entity_id forms, state lists, entity thresholds,
and/or/not combinations and nesting. Hidden children are omitted before grid packing and
hidden top-level cards reserve no item. Conditions requiring unsupported HA context (user,
CSS screen queries, durations, templates, etc.) explain that limitation without showing the
wrapped content. This is not a claim of full Home Assistant condition compatibility.

The Bambu custom status card is a **basic live-reading fallback**. It uses only the explicitly
configured entity prefix or entities belonging to the configured printer device, never a
globally guessed printer. Missing registry/configuration produces an honest explanation.
It does not run the custom card's JavaScript, draw its custom printer visualisation, or expose
its print controls. Full custom-card compatibility has not been verified.

## Validation

Browser checks use CI’s Playwright 1.62.0 and Pillow 12.3.0. The initial baseline captures
used Playwright 1.64.0; these are comparable viewport evidence, not pixel-golden comparisons.

Run:

```sh
./gradlew :app-ui:wasmJsBrowserDistribution :app-ui:jvmTest :ha-client:jvmTest :rc-player-ui:jvmTest :app-ui:ktfmtCheck
python scripts/test-printing-dashboard-browser.py app-ui/build/dist/wasmJs/productionExecutable
python scripts/test-wasm-browser.py app-ui/build/dist/wasmJs/productionExecutable
python scripts/test-adaptive-dashboard-browser.py app-ui/build/dist/wasmJs/productionExecutable
python scripts/test-demo-browser.py app-ui/build/dist/wasmJs/productionExecutable
```

The printing browser test checks actual fetched pixels, same-URL live frame replacement,
conditional state changes, readable missing values, and resolution counts across scroll-away /
scroll-back cycles. The browser also checks full-contrast ink in the lower LCD Start/ETA value region, including
unavailable/unknown values and a live timestamp replacement. Light cards use an amber lit
bulb with rays for on and a neutral outline for off. Live on/off changes update both the glyph
and switch without issuing a service call. Pixel checks cover all four palettes; JVM checks
cover lit-icon contrast against light and dark card surfaces. The switch check also verifies its checked state before and after a live HA update, preventing
a parent button from swallowing the switch’s accessibility state. The JVM regressions cover condition rules, printer isolation, picture
URL/token/frame handling, and request sharing across cancellation, failure and bounded eviction.
Before evidence is captured by passing `--before` against the baseline build; that mode makes
no claim that the fixed assertions pass. These checks do not substitute for testing against
the user's actual Home Assistant instance or physical iPhone.

## Other work

The iOS fix (#916) and Android document-update fix (#920) are already on this baseline;
no native transport, player-sizing or upstream workaround changes are included here.
PR #922 adds catalog ownership, persistent template caching and refresh. It overlaps
`LibraryCard.kt` and the style-menu area of `TerrazzoMultiplatformApp.kt`; this change does not
implement its persistent cache, publication or discovery work. When reconciling those branches,
retain its versioned refresh/persistence alongside this dashboard-lifetime in-flight cache,
and apply exact-match validation before trusting cached authenticated-dashboard templates.

The separate existing markdown-link rendering problem visible in the supplied screenshot is
tracked as [SCH-33](https://linear.app/yschimke/issue/SCH-33/wasm-dashboard-markdown-renders-dashboard-links-as-literal-syntax).
This patch does not expand the markdown renderer or implement dashboard navigation.

## Rendered comparisons

| View | Main before | Fixed |
| --- | --- | --- |
| Phone, current print and images | [Before](before-phone.png) | [After](after-phone.png) |
| Phone, live metrics and controls | [Before](before-readings-phone.png) | [After](after-readings-phone.png) |
| Phone, LCD | [Before](before-lcd-phone.png) | [After](after-lcd-phone.png) |
| 320px viewport | [Before](before-narrow.png) | [After](after-narrow.png) |
| Desktop | [Before](before-wall.png) | [After](after-wall.png) |

Other selected fallback palettes: [Braun](after-braun-phone.png), [Console](after-console-phone.png).
The lower fixed captures follow the live updates from 100%/128 to 62%/129 and the condition
change from finished to running. Coloured preview pixels change from blue to red on a same-URL
image update. The initial captures show the original fixture values.

Observed validation: production build and formatting passed; 36 app-ui, 75 ha-client and
3 rc-player-ui JVM tests passed. The focused printing browser suite passed all three widths
and all four authenticated card palettes. The general authenticated, adaptive/chat and public
curated-demo browser suites passed as well. Existing toolchain/deprecation/Skiko warnings remain;
this work does not change their versions or remove their workarounds.

## Light state and LCD readability

These captures include the Start/ETA values above Controls and the same chamber light
after live on/off state updates. They use the synthetic fixture described above.

| Palette | On | Off |
| --- | --- | --- |
| Classic | [Lit](light-classic-on-phone.png) | [Unlit](light-classic-off-phone.png) |
| LCD | [Lit](light-lcd-on-phone.png) | [Unlit](light-lcd-off-phone.png) |
| Console | [Lit](light-console-on-phone.png) | [Unlit](light-console-off-phone.png) |
| Braun | [Lit](light-braun-on-phone.png) | [Unlit](light-braun-off-phone.png) |
