# Console control and placeholder audit

[Matched before / after comparison](comparison.png).

Follow-up to the physical instruments change: every explicit built-in Console command
now has a hardware surface. The shared `consoleKeyChrome` draws a recessed, beveled key
with a shaded cap inside the existing target. `ConsoleControlFace` continues to draw
the transport keys with separate pilot lamps and mechanical rockers.

| Control family | Owning source / physical treatment |
| --- | --- |
| Button, icon-only button | `RemoteHaButton`: existing key / rocker and lamp |
| Printer pause, resume, stop | `RemoteHaBambuPrintControl`: existing transport keys and lamps |
| Media transport, including compact | `RemoteHaMediaControl`: existing transport keys and lamps |
| Entity and entities row toggles | `RemoteHaEntityRow`: existing mechanical rocker |
| Tile icon, compact tile, identity chip | `RemoteHaTile`: rocker or beveled command mount |
| Tile number buttons and mode selectors | `RemoteHaTileFeatures`: beveled step and selection keys |
| Alarm arm/disarm and PIN keys | `RemoteHaAlarmPanel`: beveled text keys |
| Thermostat, humidifier, light steppers | `RemoteHaArcDial`: beveled +/- keys, existing physical rotary dial |
| Area action icons | `RemoteHaArea`: state-tinted key mounts |
| Glance action icons | `RemoteHaGlance`: state-tinted key mounts |
| Picture-glance icons, picture-elements state icons and service buttons | `RemoteHaPicture`: beveled icon / text keys |
| To-do checkboxes | `RemoteHaTodoList`: physical checkbox mount; existing row touch target |

Whole-card navigation, readings, status indicators and inline Markdown hyperlinks are
not separate buttons. Their existing panel surfaces, text and actions remain intact.
External custom cards that are not implemented get a labeled unsupported placeholder.

White tape uses dark lettering, an adhesive shadow, paper edges and subtle cut-edge
fibres. It labels unavailable content, unsupported cards (including iframe fallbacks),
missing image/artwork placeholders, empty history/list/statistics states and printer
pads with no controls. Spool material labels use the same inventory tape, including
`Empty`. Notes preserve existing binding strings and contain no actions. The unavailable
note uses the existing `is_available` visibility expression and disappears on recovery.
Image placeholder notes sit behind actual image pixels, so a successful image covers them.

The sources are shared components, not generated catalog output. Other themes retain
their original treatments. The existing gauge drawing is unchanged.

`before/` renders the merged first-pass sources with the same synthetic fixtures. The new
white-tape regression fails against that baseline. `after/` contains real app-player captures
with the modified sources. Gauge pixels are identical between the two. Feature-rich tiles and empty logbooks use
the direct converter path (their established dataSignature path); the other examples use
published-template capture and playback. `ConsoleDesignRenderTest` also tests the same
switch document becoming unavailable and recovering, checks for actual white tape pixels,
and retains the instrument/state/clock regression coverage from the first change.

Validation: 206 local tests pass (79 component, 5 catalog, 122 converter/action/parity/launcher
tests), plus formatting checks for all three modules. The render suite also covers empty controls
and long/empty spool labels; the compact Console widget test checks baked state and real actions.

Reproduce:

```sh
./gradlew :rc-converter:testDebugUnitTest --tests '*ConsoleDesignRenderTest' \
  -PlovelaceRenderEvidence="$PWD/docs/design/evidence/console-controls-and-tape/after"
```

The builder catalog adds `HaCard/ConsoleServiceLabels`. Publication uses the existing
main-branch catalog pipelines; these images establish local rendering, not deployment.
