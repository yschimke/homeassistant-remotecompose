# AppFunctions readiness — 2026-10-25

Observed in the registered-machine execution environment for
[this Hive session](https://hive.codes/?session=session-1792931444242-784d89).
This is a setup report, not a successful conversational test report.

| Stage | Observed result |
| --- | --- |
| Java | OpenJDK 21.0.12.1 available |
| Android SDK | Installed real command-line tools, platform-tools 37.0.1, platforms 37.0/37.1, build-tools 37.0.0 and emulator under `/tmp/terrazzo-android-sdk` |
| Device | Authorized wireless Pixel 9, Android 17/API 37; `cmd app_function` offers discovery, state management and deterministic execution |
| Emulator | No AVD configured; `/dev/kvm` is not exposed, so accelerated local emulation is unavailable |
| Terrazzo source build/unit tests | Blocked during Gradle configuration by unavailable Kotlin 2.4.10 artifacts; no compilation/tests ran |
| Terrazzo device install | Published phone APK v0.1.65 installed, versionCode 1650 |
| Testing Agent build/unit tests | Both pristine and memory-key-patched standard debug builds blocked by unavailable KSP 2.3.10 plugin; no APK or Android test result |
| Testing Agent install/launch | Not performed: there is no buildable APK; upstream's deprecated prerelease has no APK assets and says to build from source |
| Discovery | All five expected function IDs found on v0.1.59 and the newly installed v0.1.65 |
| Default access gate | All five disabled; deterministic `getEntityState` returns disabled error code 1002 |
| Service/auth gate | With only `getEntityState` temporarily enabled via API 37 shell, call reaches Terrazzo and returns "Sign in to Home Assistant in the Terrazzo app first" (code 3500); restored to default disabled |
| Successful demo invocation | Not verified: device screen remains locked, preventing demo selection |
| Conversational invocation | Not run; no Gemini key supplied |
| Host setup checks | Seven Python tests pass; patch applies to the pinned upstream revision and preparation is idempotent |

The first published-APK update was rejected because the existing v0.1.59 install had a different
signing key. At the user's explicit request, the old app was uninstalled and v0.1.65 installed
fresh. Local settings/sign-in from the old install were removed. No real home was signed into,
no write function was enabled or invoked, and all five functions were disabled at the end of
the token-independent checks. Opening the app reached the permission-controller activity behind
the lock screen; UI/demo behavior was not inspected.

Installed release artifact:

```text
harc-0.1.65.apk
SHA-256 9094675bb76b09a810c820ea493569322fbe89d5c859deb78a56343285cf7a7d
```

## Build blockers

- [SCH-51](https://linear.app/yschimke/issue/SCH-51): Terrazzo's Gradle 9.8.2 build cannot
  resolve `org.jetbrains.kotlin:kotlin-gradle-plugins-bom:2.4.10`, also reporting missing stdlib
  and Gradle plugin API artifacts. A direct Maven Central request for the BOM returns 404.
- [SCH-52](https://linear.app/yschimke/issue/SCH-52): reviewed upstream
  `a3a2a89528ea14262b88029ab80b29497439a651` cannot resolve
  `com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:2.3.10`; following the plugin
  marker URL's redirects returns 404. Failure is at `agent/build.gradle.kts:20` before tasks run.

Both build failures occurred before repository changes and reproduced with the real SDK set.
No dependencies or checks were downgraded/disabled to work around them. The temporary-key patch
is source preparation only: its added Android unit test and runtime behavior remain unverified.

## Continue on this machine

The downloaded SDK/APK and external upstream checkout are session-local `/tmp` files, not durable
machine configuration. Recreate them using the [runbook](appfunctions.md) if the session ends.
For the existing session:

```sh
export ANDROID_HOME=/tmp/terrazzo-android-sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
adb devices -l
export ANDROID_SERIAL='the-authorized-Pixel-serial-from-adb'
python3 scripts/appfunctions/testing.py doctor --serial "$ANDROID_SERIAL"
python3 scripts/appfunctions/testing.py discover --serial "$ANDROID_SERIAL"
```

1. Unlock the phone, finish the fresh-install permission screen, select **Demo mode**, and enable
   **Settings → Assistants**. Run the deterministic matrix in the runbook. Disable access after
   the suite. API 37 shell calls can run this part even while the agent build is blocked.
2. Resolve the tracked dependency availability blockers, then rerun `build-app` and `build-agent`.
   The reviewed upstream checkout is `/tmp/terrazzo-appfunctions-upstream`; pass `--agent-dir`
   to reuse it. Install/launch the patched standard debug agent and repeat deterministic calls
   through its forms. The helper verifies the installed APK hash before privileged launch.
3. Only then have the user enter a temporary Gemini API key **directly on the device**, into the
   patched agent's masked settings field. Do not send it in chat or repository/tool configuration.
   Run the documented conversational scenarios, record the actual tool trace, force-stop the
   agent to discard its memory-only key, and revoke the key afterward.
