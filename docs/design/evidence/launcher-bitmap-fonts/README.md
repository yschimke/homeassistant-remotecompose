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
