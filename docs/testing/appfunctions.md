# Terrazzo AppFunctions testing

Use Google's [AppFunctions Testing Agent](https://github.com/android/appfunctions/blob/main/agent/README.md)
for troubleshooting, deterministic form-based calls and, later, Gemini conversations. These are
three separate verification stages. A discovered function or a disabled-call error does not prove
successful execution, and a deterministic call does not prove conversational behavior.

## Reviewed versions and prerequisites

The setup pins [android/appfunctions a3a2a895](https://github.com/android/appfunctions/tree/a3a2a89528ea14262b88029ab80b29497439a651/agent),
reviewed on 2026-10-25. Its standard debug agent needs API 36+, compile SDK 37.0, Java 21,
Gradle 9.6.1 and AppFunctions beta01. Terrazzo uses Java 21, Gradle 9.8.2, compile SDK 37.1
and the same AppFunctions beta. The current upstream privileged launcher also handles API 37
caller allowlisting. Do not replace that launch with a normal activity start: the agent needs
`ShellIdentityInstrumentation` to retain `EXECUTE_APP_FUNCTIONS`.

Install Android command-line tools from [Google](https://developer.android.com/studio#command-tools),
set `ANDROID_HOME` to the real SDK root, then install:

```sh
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  'platform-tools' 'platforms;android-37.0' 'platforms;android-37.1' 'build-tools;37.0.0'
export PATH="$ANDROID_HOME/platform-tools:$PATH"
adb devices -l
export ANDROID_SERIAL='the-authorized-test-device-serial'
python3 scripts/appfunctions/testing.py doctor --serial "$ANDROID_SERIAL"
```

Prefer a dedicated unlocked test device or emulator. Hardware emulation on Linux needs exposed
KVM; a wireless authorized phone can work without USB/KVM. An SDK directory containing an empty
`adb` script is not an SDK. `doctor` checks that adb actually identifies itself.

## Repeatable preparation

Run long builds and the privileged launcher using Hive's background-process tools when working
in Hive, so their output and lifetime are visible. These commands also work in a local terminal.

```sh
python3 scripts/appfunctions/testing.py prepare
python3 scripts/appfunctions/testing.py build-app
python3 scripts/appfunctions/testing.py build-agent
python3 scripts/appfunctions/testing.py install-agent --serial "$ANDROID_SERIAL"
python3 scripts/appfunctions/testing.py launch-agent --serial "$ANDROID_SERIAL"
```

The agent checkout defaults to the ignored `.ci-dependencies/appfunctions/`. `--agent-dir` selects
an existing external checkout. Preparation refuses another revision or unpatched local source
changes, and applies [`ephemeral-key.patch`](../../scripts/appfunctions/ephemeral-key.patch).
Only **standard debug** is built. The install helper requires the successful build/test receipt
and matching APK/patch hashes; it refuses a stale APK built before the key patch.

The Terrazzo build disables external crash reporting and runs its debug unit tests. Install its
APK explicitly on the selected test device:

```sh
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
```

`ee.schimke.harc` is also the production package. `install -r` preserves data when signing keys
match. A signature mismatch requires either a matching signed APK or explicit permission to
uninstall and lose app data; this helper never uninstalls the user's app. The published
[v0.1.65 APK](https://github.com/yschimke/homeassistant-remotecompose/releases/tag/v0.1.65)
is a fallback for testing that release, not evidence that the local source build passed.

On Terrazzo's fresh welcome screen select **Demo mode**. On an existing install use its demo
setting, and confirm it shows the bundled demo dashboards before any test call. Do not sign in
to a real Home Assistant for this suite. Then open **Settings → Assistants** and enable access.
All five functions ship disabled; installing the testing agent does not enable them.

## Token-independent discovery and invocation

```sh
python3 scripts/appfunctions/testing.py discover --serial "$ANDROID_SERIAL"
```

Expect exactly these identifiers (with runtime enabled state):
`ee.schimke.terrazzo.assistant.TerrazzoAppFunctions#findEntities`, `#getEntityState`,
`#turnOn`, `#turnOff`, `#toggle` (each uses the full class prefix).
Discovery includes disabled declarations. Before enabling access, a deterministic read should
fail with platform code 1002, "The app function is disabled".

In the testing agent's **Debugging** view select **Terrazzo / ee.schimke.harc**, expand a function,
enter its named parameters and press **Invoke**. No Gemini key is needed. Use this matrix:

| Function | Input | Expected result in demo mode |
| --- | --- | --- |
| `findEntities` | `query="garage", domain="light"` | Includes `light.garage_door_garage_door_light`; at most 25 matches |
| `findEntities` | both blank/omitted | At most 25 entities, with names, domains and current states |
| `findEntities` | `query="no_such_terrazzo_demo_entity"` | Empty `entities` list |
| `getEntityState` | `entityId="sensor.downstairs_temperature"` | Name `Downstairs Temperature`, numeric state and unit `°C` |
| `getEntityState` | `entityId="alarm_control_panel.blink_panel"` | Read succeeds, `canSwitch=false` |
| `getEntityState` | `entityId="invalid"` | `AppFunctionInvalidArgumentException` |
| `getEntityState` | `entityId="light.no_such_terrazzo_demo_entity"` | `AppFunctionElementNotFoundException` |
| `turnOn`, `turnOff`, `toggle` | `entityId="light.garage_door_garage_door_light"` | Each returns the entity's state **before** the call; demo writes do nothing |
| `turnOn`, `turnOff`, `toggle` | `entityId="alarm_control_panel.blink_panel"` | `AppFunctionDeniedException`; no service call |

The bundled demo values drift over time. Do not assert a fixed temperature, a post-call light
state, or equality across calls. A successful switch result is not confirmation of a new state.
The fixture IDs above come from `terrazzo-core/src/androidMain/resources/dashboards/`.

API 37 on the selected Pixel also offers a token-free shell invocation path. For example:

```sh
adb -s "$ANDROID_SERIAL" shell 'cmd app_function execute-app-function \
  --package ee.schimke.harc \
  --function "ee.schimke.terrazzo.assistant.TerrazzoAppFunctions#getEntityState" \
  --parameters '\''{"entityId":"sensor.downstairs_temperature"}'\'' \
  --timeout-duration 20'
```

Check `adb shell cmd app_function help` on the actual build before using shell commands; use the
agent's forms when the platform does not offer `execute-app-function`. Shell invocation validates
the platform/service path, independently of the testing agent's UI and data conversion.
After testing, turn **Settings → Assistants** off again and verify every discovered enabled state
is false. Do not leave a personal device opted in after the suite.

## Conversational scenarios (not yet executed)

In the agent's connected-app settings select Terrazzo only. Start each scenario in a fresh chat
unless it explicitly tests follow-up context. Record the actual tool names/arguments, responses
and final answer, with a pass/fail reason. No real home is needed.

| User prompt | Expected behavior |
| --- | --- |
| "Find the garage light in Terrazzo." | `findEntities` with matching words/domain; use returned ID |
| "What is the downstairs temperature?" | Search then `getEntityState`; report the returned value and °C |
| "Turn on the garage light." | Search/disambiguate, then `turnOn` on the demo ID; do not treat the returned old state as a new reading |
| "Turn it off." (after previous prompt) | Resolve prior entity and call `turnOff` |
| "Toggle the garage light." | Search then `toggle` |
| "Turn on the light." | Ask which light if search returns several candidates |
| "Arm the Blink alarm." | Refuse unsupported alarm control; do not route to a different entity |
| "Set the downstairs temperature to 19 degrees." | Explain the temperature-setting function is unavailable; do not invent an API |
| "Turn on no_such_terrazzo_demo_entity." | Search returns no match; no switch call |
| "Find the garage light." with Assistants off | Explain access is disabled; no invented result |

## Temporary Gemini key: human entry, process lifetime only

Do not paste the token in chat, a tool call, shell history, a URL, Gradle arguments/properties,
repository config or an issue. Upstream's retail flavor embeds its key in the APK; do not use
`--api-key`, `-PGEMINI_API_KEY`, or retail bundles for this work.

Once the **patched standard debug** agent is installed and launched, the user can enter the
temporary Gemini API key directly into its masked **Settings → Gemini API key** field and save.
The patch uses a `MutableStateFlow` instead of DataStore for that key. Other agent settings
still persist. Use a fresh test-agent install; the patch does not scrub a key stored by an older
unpatched install. Do not capture screenshots, UI hierarchy dumps, logcat, or screen recordings
while entering/viewing the key. Keep the key out of prompts; the Gemini client sends it as a
header, not a query parameter.

After the conversational phase, stop the agent process and revoke the temporary key:

```sh
python3 scripts/appfunctions/testing.py stop-agent --serial "$ANDROID_SERIAL"
```

Restarting the patched agent must show an empty key field. Its added Android unit test checks
that a second repository instance cannot read the first instance's temporary key. Until the
patched APK compiles and that test runs, this is a prepared source change, not verified runtime
secret handling. Chat history persists on the device; keep all conversations on demo entities.

## Setup verification

```sh
python3 -m unittest discover -s scripts/appfunctions -p 'test_*.py'
```

These host tests cover explicit device selection, API gating, rejection of stub adb binaries,
remote-shell argument quoting and discovery with duplicate static/runtime IDs. They do not
replace an Android build or device execution. Current machine observations and blockers are in
[`appfunctions-readiness.md`](appfunctions-readiness.md).
