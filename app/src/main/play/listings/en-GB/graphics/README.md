# Play Store graphics

These images are rendered from `@Preview` composables, not drawn by
hand: `scripts/render-play-screenshots.sh` (phone, tablet and Wear
screenshots, from the `Play_*` previews in `:app` and `:wear`) and
`scripts/render-play-assets.sh` (icon and feature graphic, from
`:previews`). The **Play Store graphics** workflow reruns both weekly and
opens a PR when the output changes.

Gradle Play Publisher (GPP) uploads them with `:app:publishReleaseListing`, not
`publishBundle`. Releases run it when the `PLAY_PUBLISH_LISTING` repository
variable is `true`; it replaces the Play Console's listing text and images
with what's committed here. All files in a given directory are treated as
the set of images for that asset, in filename order.

| Directory | Required? | Format | Dimensions | Notes |
|---|---|---|---|---|
| `icon/` | yes | PNG, 32-bit, no transparency | exactly **512 × 512** | Hi-res app icon shown on the Play listing. Different from the launcher icon. |
| `feature-graphic/` | yes | PNG / JPEG | exactly **1024 × 500** | Banner at the top of the listing. No transparency. |
| `phone-screenshots/` | yes (≥ 2, ≤ 8) | PNG / JPEG | between 320 px and 3840 px on either side; aspect ratio 16:9 or 9:16 | The screenshots Google shows on phone listings. |
| `seven-inch-screenshots/` | recommended (≥ 2, ≤ 8) | PNG / JPEG | between 320 px and 3840 px on either side | Shown on 7″-class tablets. Currently 1200×1920 placeholders. |
| `ten-inch-screenshots/` | recommended (≥ 2, ≤ 8) | PNG / JPEG | between 320 px and 3840 px on either side | Shown on 10″-class tablets. Currently 1600×2560 placeholders. |
| `wear-screenshots/` | needed once Wear ships (≥ 1, ≤ 8) | PNG / JPEG | square, e.g. 384 × 384 | Wear OS screenshots for the shared listing. `:wear` publishes into this same Play app, so its graphics live here, not under `wear/`. No round masks or watch frames. |
| `tv-banner/` | leave empty | — | — | TV app variant publishes from `tv/`, not here. |

Tip: capture phone screenshots on a clean Pixel emulator running the
release build — Play has no minimum content requirement but they will
be visible to internal testers and (eventually) the public.

Filenames don't matter; sort order does. Use `01-dashboard.png`,
`02-discovery.png`, etc. if you want to control ordering.
