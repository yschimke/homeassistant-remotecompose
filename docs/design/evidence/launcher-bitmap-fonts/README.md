# Card fonts on launcher widgets

`heading-launcher.png` is a Rugged Digital heading card's real launcher capture (Robolectric, 320
dpi, 180 × 80 dp), played by the CMP player:

- left, the capture the launcher gets today, which drops G-SPOT for the theme's own face;
- middle, the capture kept in G-SPOT and run through the design server's `launcher-widgets`
  bitmap-font filter (`WidgetFilters`, the real card fonts and rasterizer), played with no G-SPOT
  loaded, as the launcher plays it;
- right, the same capture played with G-SPOT loaded, as the app draws it.

The filter needs `coreTextAsBitmapFonts` to convert the launcher's `TextLayout`
([yschimke/rc-players#817](https://github.com/yschimke/rc-players/pull/817)). The LCD clock's
Digital-7 is left off the launcher: its digits stand taller than their line, which `TextLayout`
can't lengthen, so their glyphs would be cut short.

## On the phone

`on-device.png` is a Rugged Digital heading card and LCD clock captured directly (Robolectric, 320
dpi), so with the card's own contents, played by the CMP player: left, the capture the launcher gets
today; right, the same card with **Widget card fonts: Drawn on this phone**, its card fonts drawn by
rc-players' Android rasterizer over the cached G-SPOT and Digital-7, in the same Robolectric run
(`LauncherDesignsTest.onTheDeviceADirectCaptureIsDrawnFromItsOwnGlyphs`). Nothing is sent. The
clock's time is computed (`TextFromFloat` digits and a colon), so it gets those glyphs and a slot of
five; its Digital-7 digits stand taller than their line and are drawn whole.
