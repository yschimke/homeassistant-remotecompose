# Mobile-owned Wear appearance

The phone owns the theme, appearance mode (System/Light/Dark), global card style, variant/author
choices and per-card or per-type installed design overrides. Wear has no appearance picker and does not read the old watch-local
`theme_style` or `card_style` preferences. Choosing which card a placed widget shows remains a
watch action.

`/wear/settings` carries the mobile theme enum name and card style id, appended as fields 5 and 6.
The phone publishes current preferences at startup and on each change, and republishes when the
Wearable API becomes available again. Field 7 carries the mobile mode (`Follow` for System,
`Light`, or `Dark`), never the phone's resolved night flag. System resolves against Wear's own
configuration; explicit Light/Dark override it. Missing or unknown fields use mobile's defaults
(Material 3, Classic, System). App chrome, card captures, widget content and their cache keys all
include the mode resolved on the watch.

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
Both light and dark Small/Large entries are synced and tagged with `darkTheme` (field 4;
absence means legacy dark), so a watch System change works offline after a restart. For dark,
entries prefer the chosen skin's Wear template, then its phone template, before automatic
designs. Light uses the phone template because existing server Wear exports bake a dark
environment. Phone templates are adapted to the Wear container on the watch.
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
`WearDesignStoreTest`, `WearDashboardRenderingTest` and `SlotWidgetDesignTest`. The rendering tests
compare actual pixels after a synced template change, late blob delivery, live value updates and
recreating the card from persisted state. A paired-device disconnect/reconnect check remains
necessary to validate Play Services transport on real devices.
