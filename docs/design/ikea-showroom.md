# IKEA Showroom

An independent IKEA-inspired whole-card design (`ikea`), selectable under **Settings → Card style →
IKEA Showroom**, in launcher look preferences and in the desktop/Wasm demo's style menu. It uses
original code drawings, not photographs, logos or product artwork. It is not an IKEA integration
or an assertion that an arbitrary Home Assistant entity is an IKEA device.

## Design vocabulary

- **KALLAX:** square, substantial frames with a fine timber edge and inset laminate face. The
  common `cardChrome` applies this to supported cards, lists, graphs, images and specialist cards.
  Eight open compartments replace continuous bar meters; statistics columns resemble shelf uprights.
- **STYRBAR:** the full light card becomes a moulded square with a cruciform set of four 48dp keys.
  On and Off are explicit commands, left/right lower/raise brightness. The current percentage or
  Off sits above the remote; this is a reading rather than an extra button. No decorative colour
  cycling control promises an action the light converter does not support.
- **BILRESA:** a split paddle with I/O markings and a moving state lamp replaces row toggles and
  tile controls. Compact light widgets use the same paddle beside the light's label and reading.
  The compact card retains its existing whole-card toggle; it does not squeeze four keys into a chip.
- **VARMBLIXT:** rounded opal rings give gauges, climate and humidity dials a soft lighting identity.
  A separate target index keeps a thermostat's room value distinct from its set point.
- **LADDA:** four clearly separated rechargeable-cell compartments show battery charge. Temperature
  uses a slim timber-backed rail, with printed index marks and a continuous live fill.

Paper-white and birch tones in light mode become warm labels on charcoal furniture in dark mode.
Blue is a navigation/control accent; state colours retain their normal semantic meaning. A warning
remains a warning. Primary, secondary and link text have at least 4.5:1 contrast on the card face;
key text has the same minimum on the moulded control surface. Labels use the shared `HaType` scale,
readings keep `HaReading`, and decoration does not reduce existing action targets.

## References and typography

Product references inspected on 18 October 2026:

- [STYRBAR](https://www.ikea.com/us/en/p/styrbar-remote-control-smart-white-80488370/): white square
  smart remote, on/off, brightness and spectrum control.
- [BILRESA wheel](https://www.ikea.com/gb/en/p/bilresa-remote-control-white-smart-scroll-wheel-90617456/)
  and [dual button](https://www.ikea.gr/en/products/bilresa-smart-remote-control-dual-button/10604165/):
  current compact smart controls. The UI uses explicit taps, not an unsupported scroll-wheel gesture.
- [KALLAX](https://www.ikea.com/us/en/p/kallax-shelf-unit-white-stained-oak-effect-20637293/):
  square compartments, white laminate and wood-effect edges.
- [VARMBLIXT](https://www.ikea.com/gb/en/p/varmblixt-led-table-wall-lamp-orange-glass-round-20525144/)
  and [LADDA](https://www.ikea.com/us/en/p/ladda-rechargeable-battery-hr06-aa-1-2v-00509814/):
  ring lighting and clean rechargeable-cell forms.

IKEA's customized **Noto IKEA** is not bundled: no redistribution grant for that customized face
was established. Standard [Noto Sans](https://github.com/notofonts/get-noto) is available under
SIL OFL 1.1, but the current shared RemoteCompose card path describes a font family name rather
than carrying a TTF and registering it in Android, browser and platform-widget players. Merely
shipping a TTF in the Android app would not make a published `.rc` use it on those other players.

This design therefore requests `RemoteFontFamily.SansSerif`, the existing portable, locally available
fallback: Roboto on normal Android installations, and the player's host sans on other platforms.
Bold values and regular untracked body text approximate the clean Noto-led catalogue hierarchy.
It redistributes no proprietary font and requires no online font provider. Exact Noto IKEA glyphs
are a documented platform limitation, not an unverified embedding claim.

## Publication and live values

`HaUberTheme.all` registers the style. `CardDesignTemplates` includes its id and light/dark mode
in design keys; `GenerateCardDesignLibrary` iterates that registry and publishes both modes through
the existing manifest/blob/catalog pipeline. No generated library is checked into this source repo.
Six named previews (overview, dials, remaining cards, each light/dark) are in `:previews` and
`catalog.spec.json`, so the normal main-branch publication makes them discoverable in the gallery.
Adding a source design does not deploy or refresh the running public design server.

Every Layer 2 meter computes geometry from canvas size and the existing bound fraction. Paddle
state uses `is_on`; availability and labels retain their shared contracts. The light converter
keeps its existing `dataSignature` re-encode path and template bindings for `brightness_pct` and
`value_fraction`. Its explicit On/Off template actions are remapped to the user's actual entity;
brightness steps are still rebuilt from the latest snapshot. No binding suffix or host contract
was added. Input/selector/keyboard integration paths are unchanged.

## Rendering limits

Chromium was exercised with the shipped TypeScript player at 900×1000 and 390×844. The light,
climate, entity and media documents load in both modes. Its media artwork placeholder is blank
where the Compose player shows a music-note panel; this also occurs with Classic and is tracked in
[SCH-20](https://linear.app/yschimke/issue/SCH-20/typescript-player-omits-media-control-artwork-placeholder-visible-in).
The public demo is read-only by design. No rebuilt Wasm site or physical Android/Wear device was
exercised here, and the running public catalog needs the normal main-branch publication to gain
this style.

The compact adaptive ladder deliberately drops the four-key remote and retains the paddle and
readout. System-colour launcher/Wear hosts keep their own palette and suppress outer card chrome,
while instruments and control shapes still follow IKEA Showroom. Platform launcher widgets need
Android 16's layout operations, as documented by the existing widget tests. The existing
RemoteStateLayout fill/centring limitation can leave tall Fixed cells aligned toward the top;
this design uses the established ladder rather than introducing another layout strategy.

Local rendered evidence and reproducible checks are in [evidence/ikea-showroom](evidence/ikea-showroom/README.md).
