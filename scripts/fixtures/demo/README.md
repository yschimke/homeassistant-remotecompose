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

The demo browser test deliberately returns 404 for other types, exercising their
built-in fallback. The CMP compatibility regression changes a named-variable declaration in memory
to prove that converter-only fixture values never appear.
