# Editable message card

Actual 640 × 900 Chromium captures from `home-assistant/test/message-inputs.test.mjs`, using the
real TS player bundle and actual HA adapter against deterministic `hass` state/action fixtures.

- `before-entry.png`: empty form, recipient and room are native choice controls.
- `after-entry.png`: entered message and changed choices after live state/options updates and Send.
- `read-only.png`: same card/draft store with read-only policy; no editor or Send button exists.

These are before/after entry states of the new card, not a re-creation of the screenshot's missing
HA entities. The paired rc-players PR includes a legacy-player/capability-fallback comparison.

The test verifies multiline Enter does not send, normal updates retain focus/text, a re-encoded
choice list preserves the message, and Send makes exactly one `hass.callService` call with the
configured script and entered values. It also checks that data never goes to the design resolver.
Fixtures use no real credentials and send no real messages. The Linux image has limited emoji
fonts; the test separately asserts the exact Unicode message value.

Run with `PLAYWRIGHT_MODULE` pointing to Playwright's `index.mjs` and `RC_PLAYER_JS` to the matching
input-capable player bundle:

```
node --test home-assistant/test/message-inputs.test.mjs home-assistant/test/frontend.test.mjs
node --test embed/test/message-inputs.test.mjs home-assistant/test/card-config.test.mjs
```

Apple UIKit/AppKit and physical Wear keyboard/radio behavior require platform CI/device checks.
These browser captures do not establish them.
