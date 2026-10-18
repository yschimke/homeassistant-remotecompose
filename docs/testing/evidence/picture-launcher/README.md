# Native picture launcher evidence

Captured by `WidgetPreparationTest` on the default Android 16 (API 36) x86_64
emulator, not a Compose-only preview. Images are 320×640 px.

[Native CI job](https://github.com/yschimke/homeassistant-remotecompose/actions/runs/39130155521/job/117683496304)
ran commit `863fd27c`: **21 app instrumented tests, zero failures/errors/skips**.
`native-results.json` records the suite counts and individual widget test results
extracted from the downloaded JUnit report.

| Evidence | What it establishes |
| --- | --- |
| [Original failure](picture-before-preview-error.png) | The old URL-only direct capture, injected into the real sheet, reproduces the error and disabled installation. This is a legacy-path reproducer on the fixed branch, not a checkout of the previous revision. |
| [Launcher confirmation](picture-launcher-confirmation.png) | Android's pin dialog receives a real camera image and the locally edited widget name. |
| [Installed widget](picture-installed-widget.png) | The launcher hosts the image-bearing widget after confirmation. |
| [Updated widget](picture-installed-updated.png) | An actual provider broadcast updates the installed widget's camera frame; the test compares pixels inside launcher widget bounds. The demo camera OSD advances from `13:50` to `14:00`. |

The legacy-path log identifies the failure during native inflation:

```text
WidgetPreparationPreview: Could not inflate launcher preview
java.lang.RuntimeException: java.net.MalformedURLException: no protocol: /api/camera_proxy/…
```

The same run passed native template/direct image playback at three sizes,
changed image bytes at an unchanged local URL, unavailable-image fallback, and a
real local HTTP PNG test checking same-host bearer auth, signed query preservation,
off-host bearer exclusion, bounded decode and absence of signed tokens in image logs.

The first setup screenshot framed the controls with the camera below the fold.
The screenshot test now scrolls the preview fully into view before capture;
its clearer setup evidence is collected by the subsequent native run.
