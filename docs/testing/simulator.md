# Running the apps in the compose-preview simulator

`compose-preview launch` runs the debug APKs in Robolectric's simulator, with no emulator: the phone
and watch paired over a simulated Data Layer, the watch's system UI, and the phone's home screen.
Use it to check a flow end to end (pinning a card and seeing it on the watch, a widget, a sign-in
error) that a `@Preview` render, one frozen frame, can't show.

```sh
./gradlew :app:assembleDebug :wear:assembleDebug
export ANDROID_HOME=/opt/android-sdk   # or wherever local.properties' sdk.dir points
compose-preview launch app/build/outputs/apk/debug/app-debug.apk \
  --pair wear/build/outputs/apk/debug/wear-debug.apk \
  --form-factor phone --sdk 37 --launcher \
  --java /usr/lib/jvm/java-21-openjdk-amd64 --control stdio
```

- The simulator needs Java 21 (`--java`), and an Android SDK platform (`ANDROID_HOME`).
- `--form-factor phone`: the APK carries the Android XR libraries, so it is otherwise launched as XR.
- `--sdk 37`: the watch's Glance Wear widgets render from API 37 only (`WEAR_WIDGETS_MIN_SDK`).
- Commands with `"device":"watch"` go to the watch.

## Signing in without a browser

The phone signs in to Home Assistant through a browser (AppAuth and a Custom Tab), which the
simulator, and a device or emulator without a browser, can't complete; the sign-in screen then says
"No browser can sign in on this device". Two ways in skip it:

- **Offline demo** (or `terrazzo.test.demo_mode=true`, see [baseline-profiles.md](baseline-profiles.md)):
  canned data, no network.
- **An injected token.** In builds with `BuildConfig.TEST_HOOKS` (debug and the benchmark variants,
  never release), `MainActivity` signs in with a token from its launch extras:

  ```json
  {"cmd":"startActivity","forceStop":true,
   "component":"ee.schimke.harc/ee.schimke.terrazzo.MainActivity",
   "extras":{"terrazzo.test.base_url":"https://homeassistant.local:8123",
             "terrazzo.test.access_token":"<a long-lived token>"}}
  ```

  Create the long-lived token on your Home Assistant profile page (Security → Long-lived access
  tokens). Nothing is stored: there is no refresh token, so the next plain launch is signed out
  again.

## Known simulator gaps

Mostly the simulator's, not the app's; worth checking against a newer compose-preview before working
around them:

- **Placed watch widgets aren't listed.** Glance Wear's `fetchActiveWidgets` needs the system
  `com.google.wear.services.tiles.TilesManager`, which the simulator doesn't provide, so the phone
  never hears which widgets are placed and can't choose their cards. The widget picker and
  rendering a placed widget work.
- **Widget documents and the platform player.** The launcher widget goes through the platform's own
  RemoteCompose player, which picks its operation table from the document header: wire 1.0 is
  document level 6 (no profiles), 1.1 is level 7 with the header's profile bits. An API 37 widget
  carries ColorTheme (op 196, in the level-7 widgets table), so it must keep the 1.1 header; only
  the Android 16 profile's documents drop to 1.0 (`platformWidgetDocument`). Running the phone at
  `--sdk 36` and `--sdk 37` exercises both.
- **AppAuth's browser list** can throw on the simulator's browser package (it reports no
  signatures). The app now builds AppAuth only when a browser sign-in starts and reports that as
  "No browser can sign in on this device" rather than crashing.
