# Ten community-inspired Uber families

Selection checked 2026-10-18 against the linked primary repositories. This is a
curated set of recognizable whole-interface directions, **not a global top-ten
installation ranking**. Stars measure repository interest, not active installs;
Catppuccin and Obsidian have audiences beyond Home Assistant. Kodi skins add
media-centre design languages rather than claiming equivalent HA adoption.
The independently delivered IKEA Showroom and the physical Console retain their own designs.

| Inspiration / primary source | GitHub stars at selection | Why selected / original adaptation |
|---|---:|---|
| [Mushroom](https://github.com/piitaya/lovelace-mushroom) | 5,214 | Established HA card collection; friendly garden surfaces, petal scales, seed controls, tonal readings. |
| [Bubble Card](https://github.com/Clooos/Bubble-Card) | 4,655 | Broad HA control collection and community module ecosystem; floating capsules, round bead scales, satellite thumbs. |
| [UI Lovelace Minimalist](https://github.com/UI-Lovelace-Minimalist/UI) | 2,075 | Whole-dashboard modular HA system; compact modules, sparse index tabs, bold readings and stepped histories. |
| [iOS HA themes](https://github.com/basnijholt/lovelace-ios-themes) | 881 | Recognizable mobile control language with HA adoption; Control Centre modules, concentric activity rings, filled sliders. |
| [Catppuccin](https://github.com/catppuccin/catppuccin) | 19,845 | Widely ported palette ecosystem; Latte/Mocha workspace with stitched lozenges and soft tabs. |
| [Obsidian Minimal](https://github.com/kepano/obsidian-minimal) | 5,440 | Mature configurable whole-app theme; editorial serif readings, ruled scales and quiet notebook chrome. |
| [Noctis](https://github.com/aFFekopp/noctis) | 307 | Long-standing HACS dark-blue theme; original night-navigation orbit scales, twin rails and outlined satellite thumbs. A complementary light mode is provided here. |
| [HA LCARS](https://github.com/th3jesta/ha-lcars) | 592 | Distinctive HA science-fiction interface system; original starship elbow bands and segmented command keys. No franchise artwork or font. |
| [Arctic Horizon 2](https://github.com/jurialmunkey/skin.arctic.horizon.2) | 307 | Recognizable Kodi media-centre skin lineage; open horizon needles, suspended markers, spare ice rails and lightweight text. |
| [Aeon Nox SiLVO](https://github.com/MikeSiLVO/skin.aeon.nox.silvo) | 156 | Established Kodi cinematic skin lineage; original marquee frames, diamond indexes and serif readouts. |

Counts were read from GitHub's repository API (`stargazers_count`). The ten are
selected for relevance, recognition and visual diversity. Existing Expressive,
Braun, LCD, Nixie and Trinitron already cover other broad directions, so these
families do not reuse their instrument drawings. Existing `TerrazzoMushroom` and
`TerrazzoMinimalist` are palettes; these new selectable **card styles** additionally
change instruments, control faces, type weights, readout arrangement and shapes.

## Design and functional coverage

Each family has its own `HaVisualFamily` and both light and dark palettes. The
family grammar is used by every Layer 2 arc, bar, thermometer and battery, by the
climate/light/humidifier controls, and history and statistics charts. Card composition now also belongs to the family: it is not just a change of palette
or meter face. The same bound name, value and range slots reflow into ten layouts:

| Family | Composition |
|---|---|
| Mushroom | Round instrument badge beside a friendly name/value stack. |
| Bubble | Horizontal capsule, trailing value lozenge, full-width bead track. |
| UI Minimalist | Value-first module, small corner instrument, accessory grid. |
| Control Centre | Tall filled slider beside a large reading; accessory tiles, slider-based climate/light controls and a compact media transport. |
| Catppuccin | Workspace tab header, monospace readout, segmented footer and window marks. |
| Obsidian Minimal | Left notebook margin, ruled heading, editorial reading and footnote. |
| Noctis | Split instrument/readout navigation panel, right edge rail. |
| LCARS | Asymmetric command spine, top segmented rail and right-aligned readout. |
| Arctic | Open value-first landscape panel, low horizon scale and wide media row. |
| Aeon | Cinema frame, side poster-like fill column and media banner. |

Specialized card layouts consume the same chrome, text and control shapes:

| Surface | Treatment / live contract |
|---|---|
| Sensor, entity, entities, tile, button, glance, gauge, area | Family chrome/type/control shapes; meters and switch thumbs derive from existing numeric/on/off/availability bindings. |
| Thermostat, humidifier, light | Family control ring, existing labels, target marker, stepper actions and re-encode signature. |
| History, statistics, statistic | Family trace/columns and readout; existing history/statistics signature refreshes baked windows. |
| Media, alarm, calendar, logbook, todo, weather, map, image, Bambu and shutter cards | Shared family palette, typography, shapes and marking accents; existing specialized content, actions and signatures remain owned by their converters. |
| Markdown, heading, clock, stacks/grids/wrappers, unsupported placeholders | Existing content and semantics, family chrome/type and inherited child style. Console's physical clock remains its own. |
| Editable custom cards | No converter, property, input or action schema changes; text/control parts inherit the style when those cards are integrated. |

No new live binding name is introduced. `HaReading` remains the source of
`state` / `numeric_state`; the host continues to reproduce those values.
All scale geometry uses `RemoteFloat` at playback. The new switch face is inside
the existing action target and reads the same animated progress; it owns no new
action. Availability keeps the existing dimming/overlay and neutral-state path.
Semantic warning/critical colours are preserved in all ten themes. The new text
palettes and capture-time marking accents used for status text are tested at
4.5:1 on their card surfaces in both modes.

`CommunityFamilyLiveRenderTest` pushes low/high values into the same cached document,
checks all four meter regions independently of text, checks switch movement and
asserts no capture occurs during the update. Screenshot coverage is complementary,
not evidence of this live-data contract by itself.
The launcher test also bakes all ten families in both modes, checks concrete
readings and action payloads, and requires colour expressions to fold for the
platform player. Community widget captures use settled values: a startup tween
would prevent constant colour folding in segmented scales. Live in-app documents
keep their animations and named updates.

## Discovery and publication

- `HaUberTheme.all` registers the new styles for design capture, per-card selection,
  launcher, Wear and TV hosts; `CardStylePref` adds phone Settings entries.
- The shared demo's existing dropdown and the dashboard studio's existing selector
  include the new IDs. No chooser architecture changes.
- `ha-cards/uber-theme` policy accepts the IDs, so arbitrary catalog components inherit them.
- Named `Community_*`, `CommunityDials_*`, `CommunityLight_*`, `CommunityCards_*`,
  `CommunityMedia_*` previews use the normal PNG plus `.rc` sidecar publication
  path. `CommunityCompact_*` uses a separate document per actual widget cell,
  so it intentionally publishes a PNG without a misleading single-document sidecar. The card-design generator already
  walks every registered theme/mode, so it captures the same supported templates.
- `catalog.spec.json` explicitly selects all 70 new previews for the published sticker
  sheet. A test compares that selection with the compiled preview functions, so a
  family cannot disappear from the gallery while remaining selectable in the app.
- Existing CI publishes catalogs/design libraries after integration. Adding source
  entries does not deploy or update an already-running server; the browser needs
  the newly generated library before it can resolve these styles.

## Size and platform boundaries

Meters scale from their playback canvas. The existing compact/fixed card tiers,
48dp action rows and host sizing rules remain in force. Launcher and Wear hosts
can retain their system palette; their **style-only** mode uses these instruments
and type/shapes without replacing host colours. Family palettes apply when the
host asks for card-style colours. A 1×1 widget still uses the existing reduced
content tier; it cannot show a full dashboard or all dial steppers. The compact
proof uses a 240×80dp thermostat: the existing launcher dial gate is width-only,
so a short cell wider than 260dp chooses the full dial and clips it. That shared
host/converter limitation is documented with the evidence; this style change does
not replace the sizing architecture.

Android/Compose rendering, browser/desktop RemoteCompose playback and published
`.rc` templates share geometry, but font rasterization differs by platform. The
host font families are used rather than downloaded fonts, SF Pro, LCARS fonts,
or product artwork. Control Centre uses opaque layered surfaces for legibility;
backdrop blur is not available in the shared card format. Cross-platform device
validation and deployment status are recorded with the rendered evidence.

## Licenses and original work

All shapes/layout adaptations in this change are original code, using existing
RemoteCompose primitives. No upstream source, CSS, images, logos, icons or fonts
were copied. Names identify inspirations and do not imply endorsement.

Catppuccin's Latte/Mocha palette values are reused under its MIT license; the
copyright and permission notice are in
[`THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md) and packaged in
`rc-components/src/main/resources/META-INF/NOTICE-catppuccin.txt`.
Mushroom is Apache-2.0; Bubble, UI Minimalist, iOS themes, Obsidian Minimal and
HA LCARS report MIT. No material from those implementations is redistributed.
Noctis does not declare a repository license, so its code/assets/palette are not
copied. Arctic Horizon 2 and Aeon Nox SiLVO declare CC BY-NC-SA 4.0 (Aeon also lists
separately licensed parts); no material from either is reused. Their broad
navigation/spacing/cinematic idioms inform original drawings only.

Rendered comparisons and validation results live in
[the evidence directory](evidence/community-families/README.md).
