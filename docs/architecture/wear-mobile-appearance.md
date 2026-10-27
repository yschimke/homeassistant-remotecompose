# Mobile-owned Wear appearance

The phone owns the theme, global card style, variant/author choices and per-card or per-type
installed design overrides. Wear has no appearance picker and does not read the old watch-local
`theme_style` or `card_style` preferences. Choosing which card a placed widget shows remains a
watch action.

`/wear/settings` carries the mobile theme enum name and card style id, appended as fields 5 and 6.
The phone publishes current preferences at startup and on each change, and republishes when the
Wearable API becomes available again. Wear uses the dark version of the selected palette, as its
surfaces are dark-only. Missing or unknown fields use mobile's defaults (Material 3, Classic).

Wear is dark-only: mobile's System/Light/Dark preference is neither sent nor used. When the
mobile theme is Material 3, Wear resolves its own watch-face/system dynamic colors through
Wear Material 3's `dynamicColorScheme(context)`. A watch without dynamic-color support, or with
system dynamic colors disabled, uses the fixed dark Material 3 fallback. A selected Terrazzo
palette uses its fixed dark colors even when the watch enables dynamic colors. The phone's
wallpaper colors are not copied to the watch.

App chrome and widget configuration observe the system color toggle and configuration changes,
and re-read colors on resume. Placed widgets refresh on system color changes. Local fallback
card captures use the same resolved colors, with every resolved role included in memory/disk
cache keys, so watch-face changes cannot replay an old palette. Installed templates retain the
selected design's artwork. The persisted mobile theme choice continues to control this behavior
while disconnected and after restart; watch system color changes need no new phone payload.

Wear persists settings before using them on subsequent launches. Its main UI starts from the
repository's synchronous disk-backed state; widgets, warm-up, widget configuration and launcher
icons read the same settings file. Disconnecting does not clear it. Settings from an older
DataItem cannot overwrite a newer persisted choice. The background listener stores settings even
when no activity is running and refreshes placed widgets.

The phone resolves designs from the same loaded installed-design cache as its dashboard. It
publishes content-addressed templates at `/wear/design/<sha256>` and an index at
`/wear/slot-designs`. Pinned cards have their existing pin keys for older widgets; pinned,
section and dashboard cards also have entries keyed by the fingerprint of the config Wear
receives. Override lookup still uses the original mobile config, before Bambu device resolution.
Each Small/Large entry prefers the chosen skin's Wear template, then that skin's phone template,
before automatic designs. Phone templates are adapted to the Wear container on the watch.
Payloads above the 90 KB inline budget travel as Data Layer assets, including large templates
and indexes; the receiver handles both inline and asset payloads on events and cold reads.

The app and placed widgets both consume this index and its verified bytes. The app keeps live
bindings and seeds current values before the first frame; widgets bake the current snapshot.
Index and blob arrivals independently invalidate app rendering and refresh placed widgets, so
an index arriving before its blob does not leave a fallback on screen. Index writes do not prune
blobs: a delayed old index could otherwise delete a new blob that arrived ahead of its index.
The phone explicitly deletes obsolete blob DataItems after publishing the new index; sign-out
clears the cache. Settings, index and verified templates survive offline app restarts.

Regression coverage lives in `WearAppearanceSyncTest`, `WearCardDesignPickerTest`,
`WearDesignStoreTest`, `WearDashboardRenderingTest`, `WearDynamicThemeRenderingTest` and
`SlotWidgetDesignTest`. The rendering tests
compare actual pixels after a synced template change, late blob delivery, live value updates and
recreating the card from persisted state. A paired-device disconnect/reconnect check remains
necessary to validate Play Services transport on real devices.
