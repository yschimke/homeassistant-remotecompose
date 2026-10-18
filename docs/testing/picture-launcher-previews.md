# Picture launcher previews

The **Make it yours** sheet in `app/` renders Android `RemoteViews.DrawInstructions`.
The launcher provider requires Android 16 (API 36); Android 15 cannot play its
layout documents. The screenshot's status-bar styling does not establish the
platform. The Apple app has no implementation of this launcher preparation sheet.

## Failure and fix

Bundled camera cards include `camera_image`, `camera_view`, `aspect_ratio`, or
custom actions. Those configurations do not have a launcher design template.
They use `TerrazzoWidgetProvider.captureDirect` instead. Previously that capture
inherited `PictureImageStrategy.DefaultAppUrl`, which is appropriate for the app
player with its image loader, but leaves `entity_picture` as URL bitmap data.

On Android 16, native `AndroidRemoteContext.loadBitmap` handles a URL bitmap with
`new URL(...).openStream()`. A server-relative camera URL has no scheme/base and
fails during RemoteViews inflation; a demo URL has an unsupported scheme. An
absolute authenticated camera URL still cannot inherit the app's bearer or image
loader. The sheet consequently shows its capture/inflation error and disables
installation. Reducing the widget size cannot repair this missing image channel.

Direct capture now discovers the converter's actual bitmap URLs, fetches them
through the same `HaImageStack` used by the template baker, and recaptures with
`PictureImageStrategy.Inline` **inside** the capture composition. Failed or invalid
images use the existing icon/name/state rendering. Nested cards use the same path.
No image URL or bearer is needed by the launcher. Template baking remains the
preferred path when a card supports it.

Widget image decoding requests at most 512 px before encoding. PNGs are bounded
to 256 KiB per image and 512 KiB total per render, leaving transaction room for the
RemoteCompose document and actions. Unchanged image URLs are fetched fresh during
preview and provider updates. The cache of fetched results is local to one render,
including failures. Image logs retain status/dimensions and omit URLs and exception
messages that might contain signed query strings.

The fix does not change image authentication: relative URLs remain pinned to the
widget's server; matching-host bearers and signed image URLs use the existing
image stack. Off-host images receive no Home Assistant bearer. It also does not
change converter semantics, the recent keyboard-inset fix, or editable inputs.
The other picture-family converters currently draw their existing placeholders;
this fixes the image-bearing `picture-entity` path rather than adding new image
channels to those converters.

## Regression checks

- `LauncherWidgetPicturesTest`: realistic non-templated camera configuration,
  nested stack, four cell sizes (including 1×1), fresh image bytes, invalid/unavailable images,
  retained host actions, and no URLs/tokens in the fallback document.
- `WidgetImageLoadingTest`: real local HTTP PNG, same-host bearer, preserved
  signed query, no bearer on an external host, bounded decode, changed pixels at
  the same URL, and no credentials in image logs.
- Existing `HaBearerAuthInterceptorTest` and `WidgetImagesTargetTest`: auth and
  widget-server isolation.
- `WidgetPreviewParityTest`: native Android playback of templated and direct
  pictures, four sizes (including 1×1), a 1200×600 local PNG, unchanged-URL pixel updates and
  unavailable-image fallback.
- `WidgetPreparationTest`: native reproduction of the old URL-only capture, a
  successful demo camera preview, cancellation/confirmation, persisted edited
  config, installed launcher pixels, a provider broadcast updating the camera
  frame, and size-option changes. Screenshots use `picture-` names and survive
  test cleanup in `Pictures/TerrazzoWidgetTests`.

Run the native tests on a disposable Android 16 emulator with a pin-capable
launcher:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ee.schimke.terrazzo.widget.WidgetPreparationTest,ee.schimke.terrazzo.widget.WidgetPreviewParityTest
```

The CI wrapper pulls the screenshots into `build/widget-test-screenshots` before
the emulator exits. They are included in the `android-test-reports` artifact.

## Captured evidence

[Native reports and inspected screenshots](evidence/picture-launcher/README.md)
record the Android 16 reproduction, confirmed installation and provider update.
