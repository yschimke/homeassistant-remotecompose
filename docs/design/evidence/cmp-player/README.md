# Card playback on the CMP player: render evidence

Renders for the switch from the AndroidX View player to the Compose Multiplatform
`RcComposePlayer` (`rc-player-compose` 2.5.0). Rendered locally with
`compose-preview render --module previews --filter <name> --output <png>` (CLI 2.45.0);
crops and downscales only, no other edits.

| Image | What it shows |
| --- | --- |
| `sizing-before-run1.png`, `sizing-before-run3.png` | `Sizing_WidthPinnedHeightAdaptive` on `main`, two of three renders at one commit: two distinct hashes |
| `sizing-after.png` | the same preview on the CMP player: three renders, one hash |
| `wrap-height-cmp.png` | `WrapContentHeightCmp`: wrap-content height on the CMP player (#153 without View priming) |
| `image-url-before.png`, `image-url-after.png` | `ImageUrl_FakeCoil_Light`: the View player rejected the fetched bitmap's size; the CMP player draws it |
| `climate-before.png`, `climate-after.png` | `DashboardClimateMobile`: camera images now load; thermostat tiles now show the live `is_on` binding (see the PR) |
| `matrix-tile-before.png`, `matrix-tile-after.png` | `CardPreviewMatrix_Tile_matrix`: app and Wear cells on CMP, launcher-widget cells still on the AndroidX player |
| `wear-entities-large-cmp.png` | `WearEntitiesLarge` on the CMP player: byte-identical to the embedded player |
