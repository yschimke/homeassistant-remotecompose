# Dashboard preview images: a race with the capture

## Symptom
Dashboard previews flagged "changed" on PRs that don't touch rendering, for example #872, a change only to the Wear sync code. The same preview differed between two CI renders:

- **`DashboardViewClimateHomeMobile`**, 1.50 % of the image: the "Front (driveway)" camera card was a grey placeholder in one run (baseline `b2b9269`) and the image in the other (`e1a882c`).

  ![camera: placeholder vs image](ci-climate-camera-placeholder-vs-image.png)

- **`DashboardGithubTablet`**, 0.02 %: one README badge was missing in one run.

  ![badge: missing vs present](ci-github-badge-missing-vs-present.png)

## Root cause
The previews' `BitmapLoader` returns fixed bytes synchronously, so the image itself never varies. What varies is when it lands.

`HaRcComposePlayer` adapts a plain `BitmapLoader` to the Compose player with a blocking load on `Dispatchers.IO`. A preview capture waits for the UI to be idle, and nothing it waits on waits for that IO work. On a loaded CI runner the capture sometimes ran first and drew the placeholder.

Rendered alone, the previews were stable (three `--rerun` renders each, byte-identical):

| Preview | md5 |
|---|---|
| `DashboardGithubTablet` | `5009f6902e3bbcaf2976732773da5347` |
| `DashboardSecurityMobile` | `aeef5f02dbb9cfb2fb95b2b249c0bdcf` |
| `DashboardViewClimateHomeMobile` | `b5c660dc686eeedeb3b923fb7fa5c922` |

The renders stayed identical with all four cores busy, so the race needs CI's timing to show up locally. `FrozenClockImageLoadTest` makes it deterministic instead: a loader that takes 500 ms. On `main` it fails with 0 image pixels at idle.

## Fix
- **Inline decode under a frozen clock:** `HaRcComposePlayer` decodes a plain `BitmapLoader`'s image on the player's own dispatcher when `LocalHaClock` is frozen (previews and tests), so the image is drawn before the UI is idle. Live playback keeps `Dispatchers.IO`.
- **Frozen clock for dashboards:** dashboard previews now freeze `LocalHaClock`, as `CardHost` already does for single cards. That turns the inline decode on for them and pins relative times and the player's time source.

## After
Command: `./gradlew :previews:composePreviewRender --rerun -PcomposePreview.filter=<Preview>`, three runs each. The hashes are identical to the table above in every run, and identical to the pre-fix renders, so nothing visible moves; the race is gone. `FrozenClockImageLoadTest` passes.
