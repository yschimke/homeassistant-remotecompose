These are values-free published tile templates for `mobile-app`, API 8,
`shape=domain=light`, captured on 2026-10-17. Classic comes from
`https://ha.coo.ee/designs/v1/manifest.json`; Console comes from the
`card-design-library` artifact of successful GitHub run `39029657819` because the
deployed Console blob returned 404. They exercise current mobile designs in WASM playback
without requiring network access in the browser tests.

| File | Theme | SHA-256 |
| --- | --- | --- |
| tile-classic.rc | baked:Material3:light | 873016777d3bd5df990dc9164133da2c3273f7cf29868f81922cec079f9aa0ff |
| tile-console.rc | style:console:dark | a2bcd9860ef88c17f20d2659bc9ee3260307cfe2d7756c92f63a8ad4ae7166d3 |

The baseline demo browser cases deliberately returns 404 for other types, exercising their
built-in fallback. The CMP compatibility regression changes a named-variable declaration in memory
to prove that converter-only fixture values never appear.

Console instrument fixtures were fetched from the public resolver/blob endpoint on
2026-10-18 and verified against their published SHA-256. They exercise the actual
thermostat dial and meter in the browser with host-owned HA fixture data:

| File | Theme | SHA-256 |
| --- | --- | --- |
| gauge-console.rc | style:console:dark | 723e8d5bba810e946c1084f6c0be873eda8208b450b3e6589bb7b858a4b4cad0 |
| thermostat-console.rc | style:console:dark | 8df12e221139208e5a5eaac1170670398bbbc5f513e141254ede9b1c5347c554 |

JVM compatibility tests require that both templates are playable and every declared
live binding is supplied by the host. Browser tests change temperatures/readings without
re-resolving the template, and verify state controls. Other fixture card types still
exercise the built-in renderer via a deliberate 404.
