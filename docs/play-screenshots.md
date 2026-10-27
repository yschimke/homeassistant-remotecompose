# Real app screenshots for Google Play

Phone, tablet and Wear screenshot slots contain **ADB captures of the running
Android apps**, not Compose previews, mockups, or hand-drawn UI. The app may
show offline sample data, or its honest disconnected/empty states.
No personal Home Assistant account, radio contacts, audio library or credentials
are used. Icon and feature-graphic artwork are separate branding assets.

## Refresh

Build the current app from the source revision being captured. Install it on a
fresh disposable emulator with an explicit serial (never the developer's phone).
Use a display at or below 1800 pixels in either direction **before** capturing:

- Phone: `adb -s emulator-5580 shell wm size 1008x1792`, density 420.
- 7-inch tablet: `wm size 1000x1600`, density 240.
- 10-inch tablet: `wm size 1792x1120` (landscape), density 224.
- Wear (when supported): 480×480 round display, density 320.

These tablet overrides exercise actual responsive layouts; they are not resized
phone PNGs. Changing display size can recreate the activity; wait for the real
screen and all loading to finish, then inspect it visually. Use the real UI to
navigate, including its sample-data mode where available. Do not substitute a
preview activity or create screenshot-only screens. Keep system permission
dialogs, keyboards, loading overlays and test controls out of the frame.

Install Python 3 and Pillow, then capture each reviewed screen:

```sh
python3 scripts/play-captures.py capture --serial emulator-5580 \
  --slot phone-screenshots --name 01-home.png \
  --scenario 'Describe this real app screen and any built-in sample data'
python3 scripts/play-captures.py check
```

The capture command rejects physical devices, oversized displays, and frames
where another package has focus. It writes an opaque PNG without changing the
UI, alongside provenance in `docs/play-captures.json`: scenario, package,
source revision, emulator model/SDK, time, dimensions and SHA-256. Commit both
the new PNG and provenance. Delete records for deliberately retired files.
Review the images, not just their hashes. Keep at least two phone captures,
two captures per tablet class, and the configured Wear capture count where
the app has a Wear target.

## Automation

`scripts/play-captures.py check` validates every screenshot's provenance,
checksum, size, format, count and aspect ratio. The legacy refresh entrypoint
now runs this validator and **cannot copy rendered previews into the listing**.
The weekly workflow checks the committed real captures rather than regenerating
screenshots. Terrazzo can still refresh its icon and feature graphic separately;
its weekly branding PR preserves the checked-in screenshots.

Uploading remains the release workflow's `publishReleaseListing` task. This PR
does not upload or publish a store listing, change rollout tracks, or merge
itself. Manual Play Console edits must also be reflected here, because release
listing publication uses these checked-in files.

## Current scenes

Phone shows Trinitron needle gauges and instrument meters, Nixie weather cards,
and the real installed-design chooser. The 7-inch tablet contrasts Trinitron
instruments with LCD segment displays. The 10-inch tablet shows LCD weather
and Nixie instruments in the actual landscape layout. All use the built-in
offline demo data; no private Home Assistant account is involved.

Enter the debug demo session with `--ez terrazzo.test.demo_mode true`, then
relaunch normally so display changes preserve the last dashboard. Install
Trinitron, LCD and Nixie through Settings → Card design → Browse all designs,
waiting for each background download to complete before capturing. The normal
widget navigation extra `--es ee.schimke.terrazzo.widget.OPEN_NAVIGATE
/meshcore/repeater` opens the sample instrument view; `/climate` opens weather.
No screenshot-only screens or preview activities are used.

Wear uses the existing debug-only `--ez terrazzo.test.seed_pins true` launch
hook, which seeds the same offline store as a demo phone sync. These are
running application screens with sample values, not preview composables.

The design-server front page also bundles two phone captures. Update both
`siteResources` in `design-server/build.gradle.kts` and `Landing.SCREENSHOTS`
when renaming them; its route test verifies that every referenced PNG is served.
