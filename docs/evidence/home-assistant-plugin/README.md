# Home Assistant custom-card rendering evidence

`card-before.png` and `card-after.png` were captured locally by
`home-assistant/test/frontend.test.mjs` in Chromium at 640 × 360, device scale 1.
They show the actual TypeScript Remote Compose player rendering the repository's
`tile-light.rc` test document, before and after HA's supplied entity changes from
off to on. The test verifies different rendered pixels, named binding updates,
a real canvas click reaching the signed-in user's `hass.callService`, removed
entity clearing, and permission-error display. The HA object is a fixture, not a
live home. The player used locally was fetched from the existing
`https://ha.coo.ee/embed/v1/rc-player.js` endpoint; CI uses the Gradle-pinned bundle.

![Before](card-before.png)
![After](card-after.png)

CI additionally retains screenshots of the packaged production WASM panel at
`/terrazzo` and the custom card running inside a fresh real Home Assistant instance
in the `home-assistant-plugin` artifact. See the PR checks for that evidence.
