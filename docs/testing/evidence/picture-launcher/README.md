# Native picture launcher evidence

Captured by `WidgetPreparationTest` on the default Android 16 (API 36) x86_64
emulator, not a Compose-only preview. Images are 320×640 px.

[Native CI job](https://github.com/yschimke/homeassistant-remotecompose/actions/runs/39144819129/job/117726412634)
ran commit `3b8ed7d34`: **22 app instrumented tests, zero failures/errors/skips**.
`native-results.json` records the suite counts and individual widget test results
extracted from the downloaded JUnit report.

| Evidence | What it establishes |
| --- | --- |
| [Original failure](picture-before-preview-error.png) | The old URL-only direct capture, injected into the real sheet, reproduces the error and disabled installation. This is a legacy-path reproducer on the fixed branch, not a checkout of the previous revision. |
| [Prepared preview](picture-prepared-preview.png) | The complete 1×1 camera preview is visible in the setup grid and Install widget is enabled. |
| [Launcher confirmation](picture-launcher-confirmation.png) | Android's pin dialog receives a real camera image for the locally edited card; the native test also checks that the edited name is persisted. |
| [Installed widget](picture-installed-widget.png) | The launcher hosts the image-bearing widget after confirmation. |
| [Updated widget](picture-installed-updated.png) | An actual provider broadcast updates the installed widget's camera frame; the test compares pixels inside launcher widget bounds. The native journey matches the installed scene to the prepared preview, then requires changed pixels after the next demo frame. At 1×1, clouds and the small timestamp change without requiring an arbitrary number of distinct colors. |

The legacy-path log identifies the failure during native inflation:

```text
WidgetPreparationPreview: Could not inflate launcher preview
java.lang.RuntimeException: java.net.MalformedURLException: no protocol: /api/camera_proxy/…
```

The same run passed native template/direct image playback at four sizes including 1×1,
changed image bytes at an unchanged local URL, unavailable-image fallback, and a
real local HTTP PNG test checking same-host bearer auth, signed query preservation,
off-host bearer exclusion, bounded decode and absence of signed tokens in image logs.

The integrated run includes the merged single-provider 1×1 defaults. The original
tile journey still verifies initial 1×1 placement and real horizontal/vertical
launcher resizing beyond 2×2. Its expanded tile is visible beside the camera.
