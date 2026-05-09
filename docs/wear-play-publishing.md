# Wear Play publishing checklist

This repo is configured to publish the `:wear` app module using Gradle Play Publisher (GPP).

## Local/CI publish commands

- Dry-run style build: `./gradlew :wear:bundleRelease`
- Upload internal track draft: `./gradlew :wear:publishBundle`

Publishing is gated by `ANDROID_PUBLISHER_CREDENTIALS` (path to service-account JSON).

## Play Console steps (one-time)

1. Publish Wear under the **same package name** as mobile: `ee.schimke.harc` (same signing key lineage).
2. In **Setup > App access / Content** complete required declarations.
3. In the same Play app, add/enable the Wear form factor and create an Internal testing release if prompted.
4. Create a Google Cloud service account and grant Play Console permissions (at least Release manager for this app).
5. Download the JSON key and set `ANDROID_PUBLISHER_CREDENTIALS=/path/key.json` in CI secrets/runtime.
6. Add store listing text/graphics under `wear/src/main/play/listings/en-US/...` and track notes under `wear/src/main/play/release-notes/en-US/internal.txt`.
7. Upload first draft with `:wear:publishBundle`, then review and roll out in Console.

## Ongoing release flow

1. Bump `wear` `versionCode`/`versionName`.
2. Build/sign release AAB.
3. Run `:wear:publishBundle`.
4. Promote from Internal -> Closed/Open/Production in Play Console when ready.
