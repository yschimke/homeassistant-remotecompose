# App container tests

End-to-end tests of the phone app against real services: Home Assistant in Docker
([Testcontainers](https://java.testcontainers.org/)) and the app on a connected emulator (adb). The
JVM test drives the device. The on-device steps live in `app/src/androidTest` and skip themselves
unless this harness passes them a Home Assistant URL.

They are off by default, like OkHttp's container tests, because they need Docker and a booted
emulator:

```bash
./gradlew :app-container-tests:test -PcontainerTests=true
```

That builds `:app:assembleDebug` and `:app:assembleDebugAndroidTest` first. The emulator reaches
Home Assistant through `10.0.2.2`. For another device, pass `-Dterrazzo.deviceHostAddress=…` to
the test JVM. With more than one device connected, set `ANDROID_SERIAL`.

## Tests

- `BackupRestoreTest`: sign in, back up with the local backup transport, uninstall (which deletes
  the AndroidKeyStore keys, as a new phone would not have them), reinstall, restore. Then check
  that the app still knows its instance, did not restore the token vault or the mobile_app
  registration (`app/src/main/res/xml/data_extraction_rules.xml`), and signs in again. Evidence
  (each step's instrumentation output, logcat, the Home Assistant log) goes to
  `build/container-tests/`.

CI: `.github/workflows/app-restore-emulator.yml`.

The test changes the emulator's backup transport to the local one and puts it back afterwards.
It also leaves `backup_local_transport_parameters` set.
