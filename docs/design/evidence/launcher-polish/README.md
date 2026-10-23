# Launcher polish: dials, gauge, statistic, button, alarm panel and Wear rows

Before/after crops from the `previews` module's `CardPreviewMatrix_*` renders
(`compose-preview show --module previews --filter CardPreviewMatrix_ --json`), on top of #1132.
Each image is the matrix cell (or cells) a fix touched, before on the left, after on the right.
The rules are [card-information-hierarchy.md](../../../architecture/card-information-hierarchy.md)'s:
value, then icon, then name (dropped only at 1×1 for an evocative icon), then secondary text, then
controls; the value is never ellipsised, and nothing is half-drawn.

The matrix plays its launcher cells without a capture size, so the cards' playback gates choose
the tier there. A real launcher widget is captured at its size; `sweep-after-*.png` are
`LauncherGridSweepTest`'s renders of that path on Android's grid (1×1 to 4×3, columns left to
right, rows top to bottom), with the light, thermostat, statistic and alarm panel added to the
sweep by this change.

| Image | Fix |
| --- | --- |
| `1-light-2x2-3x3.png`, `1-thermostat-2x2-3x3.png`, `1-humidifier-2x2-3x3.png` | 2×2 and 3×3 take a dial-filled tier: the name over a dial that fills the cell, the value (and the mode, target or bulb) inside it, no steppers. 4×4 and the Wear tiers are unchanged. |
| `2-gauge-1x1.png` | A one-column gauge sets its value at label size, whole, over the name; the range drops. |
| `3-statistic-2x1-3x2.png` | The launcher statistic trims its padding so the period fits a 2×1 instead of being cut off; 3×2 moves by the 2 dp gaps. |
| `3-button-1x1-2x1.png` | A `show_state` button too short for the state under the name takes a dense body: a smaller chip over the name and the state. The 1×1 keeps the state too (it used to drop it). |
| `3-todo-list-wear-large.png` | Wear Large lists the title over the first two items in shorter rows, without the section captions. |
| `3-5-bambu-print-status-wear.png` | Wear Small: a small ring beside `P2S` over `34 %` (was an empty blob). Wear Large: the printer's name over the ring beside the percentage and stage; the job's lines drop (were cut off). |
| `4-alarm-panel-3x4.png` | Narrower than the full card but tall enough: the status, the ARM buttons and the keypad (44 dp keys). A shorter one (3×2 on Android's grid, in the sweep) keeps the buttons without the keypad, and so does a full-width one too short for the keypad (4×1, 4×2), which used to cut it off. |
| `5-weather-forecast-wear.png`, `5-wear-module-weather-small.png` | Wear stacks the condition over the temperature beside the icon, both whole (was `Par…`); Large adds the entity's name. |

The renders that changed, by pixel comparison of every `CardPreviewMatrix_*` and every `wear`
preview: the cells above, and nothing else. Every other matrix preview and the 84 other `wear`
previews are pixel-identical.
