# Wear Play publishing

`:wear` ships in the **same Play app as the phone** (`ee.schimke.harc`), as a
Wear OS form-factor release. It publishes with Gradle Play Publisher (GPP) to
the dedicated `wear:internal` track, so a Wear upload never replaces the phone
bundle on the shared `internal` track.

| | Phone (`:app`) | Wear (`:wear`) |
|---|---|---|
| Package | `ee.schimke.harc` | `ee.schimke.harc` |
| `versionCode` | `MMmmpp * 10` (e.g. `1450` for 0.1.45) | `MMmmpp * 10 + 2` (e.g. `1452`) |
| Play track | `internal` | `wear:internal` |
| Upload key | `HARC_*` keystore | same keystore (one key lineage per Play app) |
| Release notes | `app/src/main/play/release-notes/en-GB/internal.txt` | `wear/src/main/play/release-notes/en-GB/default.txt` |
| Listing text and graphics | `app/src/main/play/listings/en-GB/` | none: the listing is shared, so Wear screenshots go in the app's `graphics/wear-screenshots/` |

The shared code base keeps every release's phone code above the previous
release's Wear code. Play Publisher refuses an upload whose code is not above
the app's highest existing one, across all tracks, so a per-module scheme
would break the phone upload after the first Wear release. (0.1.44 and
earlier phone builds used plain `MMmmpp`; the ×10 jump is monotonic.)

The listing is shared too, so `full-description.txt` there must mention
Wear OS: Google rejects Wear releases whose description doesn't.

Both versions follow release-please (`x-release-please-version`), so a release
bumps them together; nothing to bump by hand.

## One-time Play Console setup

1. In the existing **Terrazzo** Play app, open **Test and release → Advanced
   settings → Form factors**, add **Wear OS** and accept its terms. That
   creates the Wear form-factor tracks, `wear:internal` among them.
2. Under **Testing → Internal testing**, switch the form-factor selector to
   **Wear OS only** and add your tester list.
3. Add at least one Wear screenshot to the store listing (Console, or commit
   it to `app/src/main/play/listings/en-GB/graphics/wear-screenshots/` and run
   `:app:publishListing`). Play won't review the Wear release without one.
4. The service account in `PLAY_SERVICE_ACCOUNT_JSON` already has access to
   this app for the phone; it needs **Release to testing tracks** (or Release
   manager), which covers the Wear tracks too.
5. Set the repository variable `PLAY_PUBLISH_WEAR=true`
   (**Settings → Secrets and variables → Actions → Variables**).

## Releasing

Once `PLAY_PUBLISH_WEAR` is `true`, every release the release-please PR cuts
also runs `:wear:publishBundle`, uploading a **draft** to `wear:internal`
alongside the phone draft on `internal`. Review and roll out both in the
Console, then promote Wear to `wear:production` there when ready.

Without the variable, publish one release by hand: **Actions → Release → Run
workflow** with the tag and **publish_wear** ticked.

The Wear step needs the same secrets as the phone upload
(`SIGNING_KEYSTORE`, `HARC_KEYSTORE_PASSWORD`, `HARC_KEY_ALIAS`,
`HARC_KEY_PASSWORD`, `PLAY_SERVICE_ACCOUNT_JSON`); without any of them it is
skipped and the run summary says so.

## Locally

```sh
export HARC_KEYSTORE_PATH=/path/upload.jks HARC_KEYSTORE_PASSWORD=… HARC_KEY_ALIAS=… HARC_KEY_PASSWORD=…
./gradlew :wear:bundleRelease                      # signed AAB under wear/build/outputs/bundle/release/
ANDROID_PUBLISHER_CREDENTIALS="$(cat key.json)" ./gradlew :wear:publishBundle
```

Without `ANDROID_PUBLISHER_CREDENTIALS` the Play tasks are disabled; without
`HARC_KEYSTORE_PATH` the bundle is unsigned and Play will reject it.
