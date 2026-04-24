# Terrazzo Style Guide

_A shared design vocabulary for the Terrazzo apps — Mobile (phone &
tablet), Wear, TV, and the per-card home-screen widget. Heavily inspired
by Home Assistant's own look-and-feel with a Material 3 foundation._

## 1. Intent

Terrazzo is a Home Assistant client: a phone holds a dashboard picker, a
wall tablet runs a kiosk, a watch glances at a light switch, a TV shows a
full-day schedule in the hallway. The UI's job is to make an entity
state readable in **one glance, at one distance, with one finger** —
different constraints on every surface.

The design system is a **two-axis toggle**:

1. **Theme style** (the curated palette): _Material 3_ (system defaults,
   dynamic colour on Android 12+) or one of four _Terrazzo_ palettes
   (Home, Mushroom, Minimalist, Kiosk), each a hand-picked seed + a
   Google Fonts pairing chosen for a specific HA context.
2. **Dark mode** (per user): _Follow system_ / _Light_ / _Dark_ on the
   surfaces that support both. Wear is dark-only; TV (Kiosk) is dark-only.

The picker lives in the mobile Settings screen and persists via
`PreferencesStore`. Every other surface reads the same preference —
switching theme on the phone re-captures pinned widgets, and the Wear /
TV apps pick up the new tokens the next time they're launched.

## 2. Architecture

```
rc-components (Android library, shared tokens)
  ├── ThemeStyle.kt         — the enum: Material3 + 4 Terrazzo palettes
  ├── TerrazzoFonts.kt      — GoogleFont provider + FontFamily per family
  ├── TerrazzoTheme.kt      — terrazzoColorScheme (seed → scheme via
  │                            materialkolor) + terrazzoTypographyFor
  └── HaTheme.kt            — HA-card palette + haThemeFor(style, dark)
                               ^ used by RemoteCompose widgets

app (mobile)
  ├── ui/TerrazzoTheme.kt   — wires MaterialTheme + LocalThemeStyle/IsDark
  ├── MainActivity.kt       — reads prefs, provides the theme
  └── TerrazzoApp.kt        — Settings screen with the picker

wear
  └── ui/WearTerrazzoTheme.kt — projects mobile ColorScheme onto Wear's

tv
  └── TvMainActivity.kt     — reuses app-level ColorScheme (M3) + Typography

terrazzo-core
  └── prefs/PreferencesStore.kt — ThemePref + DarkModePref with flow reads
```

**The RemoteCompose document bakes colours at capture-time.** When the
user changes theme, the dashboard view re-keys its `RemotePreview` on
`(style, dark)` so the document re-captures; the widget provider is
re-broadcast with `ACTION_APPWIDGET_UPDATE` so each pinned widget
regenerates its `.rc` document under the new palette. `ColorTheme` in
alpha08 of RemoteCompose doesn't expose a public DSL, so we don't try
to ship a single multi-theme document — regeneration is the simpler
correct answer.

## 3. The four Terrazzo palettes

Every Terrazzo palette is a **seed colour + a Google Font pairing**
chosen for a specific HA user. They are cheap to add (one branch in
[`ThemeStyle`](../rc-components/src/main/kotlin/ee/schimke/ha/rc/components/ThemeStyle.kt)
and one pair of `lightColorScheme` / `darkColorScheme` entries in
[`TerrazzoTheme.kt`](../rc-components/src/main/kotlin/ee/schimke/ha/rc/components/TerrazzoTheme.kt))
so the list is expected to grow over time.

| Palette | Seed | `PaletteStyle` | Font pairing | The user |
|---|---|---|---|---|
| **Home** | `#03A9F4` (HA default blue) | `TonalSpot` | Roboto Flex display · Inter body | "I want it to look like HA" — the default choice. |
| **Mushroom** | `#E89F71` (warm salmon) | `Fidelity` | Figtree (single family) | Community Mushroom-card fans: warm, rounded, organic. |
| **Minimalist** | `#3F4A5C` (slate) | `Neutral` | IBM Plex Sans (single family) | Data-dense dashboards (matt8707 / minimalist-dashboard style). |
| **Kiosk** | `#00897B` (teal) | `Vibrant` + `Contrast.High` | Atkinson Hyperlegible | Wall-mounted tablets, TVs, anywhere read from ≥1 m away. |

Each palette's full Material 3 `ColorScheme` is **derived from the seed at
runtime** via [`materialkolor`](https://github.com/jordond/MaterialKolor)'s
`dynamicColorScheme(...)`. The `PaletteStyle` column is the biggest
lever: `TonalSpot` gives the default M3 feel; `Fidelity` keeps the
primary close to the seed (important for Mushroom, where hue-rotation
produced a purple primary); `Neutral` drains chroma for Minimalist's
mostly-grey surfaces; `Vibrant + Contrast.High` gives Kiosk the
WCAG-AA headroom a wall panel needs.

The fifth option is **Material 3**: the system defaults. On Android 12+
this promotes to _dynamic colour_ (Material You) so the app tracks the
phone's wallpaper. On older Android it's stock `lightColorScheme()` /
`darkColorScheme()`. Typography is the system default. Use this when
you explicitly don't want an HA-branded look.

### Why these four, not more

- **Home** is the anchor. An HA user who installs the app and doesn't
  touch Settings should see a colour that reads as "HA". `#03A9F4` is
  the `--primary-color` in HA's own light theme.
- **Mushroom** is the warmest community card style. Figtree's soft
  geometric shapes carry the Mushroom tone without needing custom
  drawables.
- **Minimalist** covers the opposite pole: users who run a
  neutral-slate dashboard with tabular numerals and want the mobile
  client to match. IBM Plex Sans's numerals are metrically regular,
  which matters on a dashboard full of temperature and power values.
- **Kiosk** is the only palette with an explicit **distance-reading**
  brief. Atkinson Hyperlegible was designed by the Braille Institute
  to maximise letterform distinctiveness for low-vision readers; on a
  wall panel or a TV it turns every glyph into an unconfusable shape.
  This is where a wall-mounted dashboard lives.

### Deliberately **not** done

- **No per-dashboard palette.** One user → one active palette. Picking
  the palette off the dashboard's own `theme:` key is tempting; in
  practice it turns every view transition into a visual jolt.
- **No runtime font downloads in the critical path.** Downloadable
  fonts load asynchronously via the system provider; while the font
  is in flight the compose font loader substitutes a matching
  system weight so no glyph ever blanks.
- **No shadow / elevation theming.** Elevation stays on the Material 3
  defaults for every palette — surface tonality is what differentiates
  them, not depth.
- **No extra typography scale.** All palettes use the stock M3
  type scale (size + line-height); only the `fontFamily` changes.
  Layout stays deterministic across a palette switch.

## 4. Typography

Font sources live in
[`TerrazzoFonts.kt`](../rc-components/src/main/kotlin/ee/schimke/ha/rc/components/TerrazzoFonts.kt).
Every family is served by the **downloadable-fonts provider**
(`com.google.android.gms.fonts`) — no TTFs ship in the APK, certificate
fingerprints live in
[`res/values/font_certs.xml`](../rc-components/src/main/res/values/font_certs.xml).
Debug builds could add bundled variable TTFs for deterministic preview
rendering; we haven't felt the need yet.

### Family catalogue

| Family | Axes / weights used | Role |
|---|---|---|
| **Roboto Flex** | variable (400–700) | Home: display + title |
| **Inter** | 400/500/600/700 | Home: body + label |
| **Figtree** | 400/500/600/700 | Mushroom: display + body (single family) |
| **IBM Plex Sans** | 400/500/600/700 | Minimalist: display + body |
| **Atkinson Hyperlegible** | 400/700 | Kiosk: display + body |

### Wall-display font choice (Kiosk)

Wall tablets and TV dashboards sit at distances where Roboto starts to
blur. Atkinson Hyperlegible's brief is **character distinctiveness** —
every glyph is shaped so it can't be mistaken for any other at low
contrast or small visual angle. The digits `0/O`, `1/l/I`, `6/b`, and
`8/B` all get custom terminals. At 1–3 m viewing distance this beats
every other sans we tested, and the Braille Institute's licensing
explicitly covers embedded and kiosk use.

## 5. Colour roles (Material 3)

Every palette is built from the same seven roles. The HA-card palette
([`HaTheme`](../rc-components/src/main/kotlin/ee/schimke/ha/rc/components/HaTheme.kt))
is derived from these via `haThemeFor(style, dark)` — a renaming from
M3 roles onto the HA vocabulary:

| M3 role | HA card role | Used for |
|---|---|---|
| `surface` | `cardBackground` | Tile card body |
| `background` | `dashboardBackground` | Dashboard grid backdrop |
| `onSurface` | `primaryText` | Card title, entity name |
| `onSurfaceVariant` | `secondaryText` | State value, speaker names |
| `outline` | `divider` | Card stroke, list separators |
| `secondary` | `placeholderAccent` | Shimmer accent |
| `secondaryContainer` | `placeholderBackground` | Shimmer backdrop |

### Guidelines

- **Never hardcode `Color(0xFF…)` in a screen.** If a role is missing,
  extend `HaTheme` or the Material 3 `ColorScheme` — don't paint
  directly. This is what makes the theme switch actually change the
  pixels.
- **State colours ([`HaStateColor`](../rc-converter/src/main/kotlin/ee/schimke/ha/rc/HaStateColor.kt))
  stay fixed across palettes.** A red warning is red in every palette.
  The palette colours describe the _chrome_, not the entity state.
- **Dynamic colour only applies to the Material 3 style.** Terrazzo
  palettes have a brief — dynamic colour would erase it. The
  picker makes the trade-off explicit: user chose branding or user
  chose Material You, never both.

## 6. Per-surface application

### 6.1 Mobile (light + dark)

- The app's top-level `MainActivity` reads `ThemePref` + `DarkModePref`
  from `PreferencesStore` and provides `TerrazzoTheme(style, darkMode)`.
- `TerrazzoTheme` chooses between dynamic colour (Material 3 + Android
  12+), the M3 defaults, or `terrazzoColorScheme(style, dark)`.
- `MaterialTheme` wraps everything; screens read `MaterialTheme.colorScheme`
  + `MaterialTheme.typography`. Two extra composition locals —
  `LocalThemeStyle` and `LocalIsDarkTheme` — are provided so HA-card
  rendering (which needs an `HaTheme`, not a `ColorScheme`) can build
  the right palette.
- `Settings` holds the picker: five radio rows for theme, three filter
  chips for dark mode. Flipping either triggers
  `WidgetRefreshScheduler.refreshAllNow()` so pinned widgets
  re-capture under the new palette.

### 6.2 Wear (dark only)

- Wear's watchface context is always dark. `WearTerrazzoTheme` asks
  `terrazzoColorScheme(style, darkTheme = true)` for the mobile
  `ColorScheme`, then projects it onto the Wear `ColorScheme` via
  `copy(...)` — `surfaceContainer` family, `primaryDim`, `outline`,
  etc.
- The stub is a minimal `AppScaffold` + `ScreenScaffold` with the
  active palette name — a canvas for the real Wear app to land on.
- **No dark-mode knob** on Wear: the system UI doesn't support a
  light watchface context. The `DarkModePref` preference is ignored.

### 6.3 TV (dark only, Kiosk-first)

- `TvMainActivity` hard-wires `ThemeStyle.TerrazzoKiosk` in its stub —
  TV is the natural home for the Kiosk treatment (distance reading +
  ambient room light).
- Uses the app-level `MaterialTheme` because `androidx.tv.material`
  builds on Material 3; Kiosk's surfaces read correctly on a 10-ft
  panel.
- **Light mode is out of scope for TV.** Home control UIs on a TV
  almost always run in dim rooms; a light surface is retina-bleach.

### 6.4 Widget (Glance-via-RemoteCompose)

- `TerrazzoWidgetProvider.Content` does a one-shot blocking read of
  `PreferencesStore` at capture time, derives `HaTheme` via
  `haThemeFor(style, dark)`, wraps the `RenderChild` in
  `ProvideHaTheme(haTheme)`.
- Each `updateAppWidget` call produces a **new `.rc` document** with
  the captured colours — that's the regen-on-theme-change contract.
- Every `ACTION_APPWIDGET_UPDATE` broadcast re-runs the Content
  function. The `refreshAllNow()` helper on `WidgetRefreshScheduler`
  makes that the obvious hook to call from Settings.

## 7. Motion

Motion is currently **stock Material 3** everywhere — we don't
override `MaterialTheme.shapes` or any motion specs. If we want to add
a per-palette motion signature (Mushroom slower / Minimalist
instantaneous / Kiosk ignored on a wall panel), it lands as a small
`MotionSpec` data class alongside the palette — but until we have a
concrete reason, there's only one motion.

Two constraints to keep in mind:

- **`RemotePreview` re-captures on state change.** Compose-in-compose
  motion inside a card renders as a sequence of static documents.
- **Widgets can't run continuous animation.** Only discrete
  `updateAppWidget` calls change what's on screen, on a worker cadence
  (≥ 10 minutes today).

## 8. Adding a new palette

The checklist:

1. Add an entry to
   [`ThemeStyle`](../rc-components/src/main/kotlin/ee/schimke/ha/rc/components/ThemeStyle.kt)
   with `displayName`, `tagline`, `isMaterial3Default = false`.
2. Add a seed colour in the `seedColor` extension.
3. Add `{Name}Light` + `{Name}Dark` `ColorScheme`s in
   [`TerrazzoTheme.kt`](../rc-components/src/main/kotlin/ee/schimke/ha/rc/components/TerrazzoTheme.kt),
   handled by the `when` in `terrazzoColorScheme`.
4. If the palette needs a new font family, declare it in
   [`TerrazzoFonts.kt`](../rc-components/src/main/kotlin/ee/schimke/ha/rc/components/TerrazzoFonts.kt)
   (Google Fonts provider handles it automatically) and wire it into
   `terrazzoTypographyFor`.
5. Add a `ThemePref` entry to
   [`PreferencesStore`](../terrazzo-core/src/androidMain/kotlin/ee/schimke/terrazzo/core/prefs/PreferencesStore.kt)
   and the mapper in
   [`TerrazzoTheme.kt (app)`](../app/src/main/kotlin/ee/schimke/terrazzo/ui/TerrazzoTheme.kt).
6. Settings picker & widget provider pick up the new entry from
   `ThemePref.entries`.

## 9. Known gaps

- **Fonts inside Remote\* composables don't respect `Typography`.**
  `RemoteText` takes explicit `fontSize`/`fontWeight` but no
  `fontFamily` in alpha08. Today Material 3 typography affects app
  chrome only; HA card typography is still the system default. Follow-up
  when the RemoteCompose API stabilises.
- **Wear + TV stubs are placeholders.** Activity + theme wiring are in
  place; the actual Wear glance surfaces (`TransformingLazyColumn` +
  curated screens) and the TV dashboard grid land in follow-ups.
- **No per-dashboard override.** If a user has a purple "Night" dashboard
  and a warm "Morning" dashboard in HA, the app renders both in the
  same selected Terrazzo palette. A dashboard-level theme hint could
  map to a per-view palette if there's demand.
- **Dynamic colour on Terrazzo.** `materialkolor` is now on the
  classpath, so a "wallpaper-seeded Terrazzo" compound is a 20-line
  change (read the wallpaper primary via the platform
  `WallpaperManager` API, feed it into `dynamicColorScheme`). Not
  shipping today because it needs UX — should the user pick a
  `PaletteStyle` separately from the seed, or should seed always
  carry its own style? Follow-up.
