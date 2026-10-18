# Public bug reports

Use **Report a bug** in Android, desktop/web, iOS, or Wear Settings, or the TV
preferences pane. Desktop/web and iOS also expose it before connecting.

The app warns that the issue and everything included will be **PUBLIC**. After
**Review on GitHub**, GitHub shows an editable issue draft. The user supplies a
title, can edit/remove the version and platform, optionally adds reproduction
steps and expected/actual behavior, and explicitly submits using their own account.
The app does not submit an issue or require GitHub credentials.

Only app version and platform are prefilled. No Home Assistant connection state,
tokens, URLs, entities, home configuration, logs, files, or screenshots are read
by the feedback flow. Attachments can only be selected by the user on GitHub;
the warning asks them to review those for private information.

If a browser cannot be opened, the app keeps the public warning and displays a
selectable tracker address to open manually. Web popup blocking is detected;
GitHub receives neither a browser referrer nor access to the app through its opener.
On a watch without a browser, the fallback tells users to open the tracker on
their phone. Wear system-back dismisses the review before leaving Settings.

## Automated checks

- `./gradlew :app-ui:jvmTest`: draft contents, encoding, native URL handler success/failure.
- `./gradlew :app:testDebugUnitTest --tests ee.schimke.terrazzo.ReportFeedbackTest`:
  warning before handoff, cancellation without opening a URL, and missing-browser recovery.
- `./gradlew :app-ui:wasmJsBrowserDistribution` then
  `python scripts/test-wasm-browser.py app-ui/build/dist/wasmJs/productionExecutable`:
  Settings/connection entry points, cancellation, public warning, intercepted GitHub
  draft, absence of fixture credentials/entities/server address, and blocked popups.
  The test intercepts the GitHub navigation; it never submits an issue.
- `WearNavigationTest.systemBackReturnsThroughDashboardHierarchyAndSettings`:
  entering and backing out of the Wear public-issue warning.

## Device checks

On Android, desktop, iOS, TV, and Wear, open the action, read the warning, cancel,
and confirm no browser opens. Repeat and continue to GitHub. Check the draft is
editable, contains the correct app version/platform, and contains no Home Assistant
data. Do not submit a test issue. With no browser available (or popups blocked on
web), check the recovery address and retry/cancel actions. On small screens and
with large text, check scrolling, action visibility, and back navigation.

iOS device/simulator checks require macOS and Xcode. Browser tests use an offline
fixture; real GitHub sign-in and submission remain deliberate user actions.

## Evidence (2026-10-18)

The production Wasm browser test passed, including all feedback assertions and
existing login/live-update/reconnect/embedded-panel flows. All 27 shared JVM tests,
the two Android feedback UI tests (Robolectric), Android/TV/Wear compilation, and
repository-wide `ktfmtCheck` passed. Screenshots were inspected at 1200×800 and
360×800; the server shown behind the dialog is an offline test fixture.

- [Before opening the warning: Settings entry point](../evidence/report-feedback/feedback-settings.png)
- [After opening: public warning and review action](../evidence/report-feedback/feedback-public-warning.png)
- [Blocked popup recovery](../evidence/report-feedback/feedback-popup-blocked.png)
- [Small-screen warning](../evidence/report-feedback/feedback-small-screen.png)

Physical-device/desktop UI runs, Wear instrumentation, and iOS/Xcode validation
were not available in the Linux session. No test issue was posted. A temporary
build-tool-only workaround for sandbox localhost resolution was needed; see
[SCH-26](https://linear.app/yschimke/issue/SCH-26/hive-sandbox-cannot-resolve-localhost-causing-yarn-network-mutex-to).
