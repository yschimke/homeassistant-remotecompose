# Plugin releases and dashboard selection

## Panel regression (#903)

`dashboard-before.png` reproduces the old vague error using the tested main
package from commit `399fcb4` (workflow run
[39039505202](https://github.com/yschimke/homeassistant-remotecompose/actions/runs/39039505202))
against a synthetic HA WebSocket fixture whose default Lovelace config is missing.
It uses the original packaged panel and WASM assets, not a hand-drawn mockup.

The after captures come from the production WASM build and actual panel adapter,
running `node --test home-assistant/test/dashboards.test.mjs` against deterministic
HA protocol fixtures. They are 1200 × 800:

- [Picker](dashboard-picker.png): readable YAML and hidden storage dashboards are
  listed; the missing default and generated Home strategy are excluded.
- [Hidden dashboard](dashboard-hidden.png): selecting its accessible button loads
  its configuration without changing YAML or restarting HA.
- [Unavailable URL](dashboard-unavailable.png): an unknown/non-Lovelace bookmarked
  dashboard has an actionable error, and the test returns to Dashboards and selects
  a working one.

The same test also checks an explicit dashboard URL, the configured default,
strategy-specific error text, and an empty picker. These fixtures do not establish
installation through the real HACS UI or connectivity to the public design resolver.
The separate installed-HA CI suite extracts the HACS ZIP into a disposable real HA
instance and exercises multi-dashboard selection and URL behavior there.

## Website

[Before](website-before.png) is the public ha.coo.ee homepage, captured in Chromium.
[After](website-after.png) is the locally running compiled design server with an
empty library fixture, so its card teaser is absent. Both are 1280 × 1600. The
installation link and section are visible, and the link targets the repository's
installation/update/rollback guide. No website deployment was performed.

## Packaging and checks

The packaging tests compare every file in the manual ZIP with the HACS ZIP,
exercise HACS extraction into `custom_components/terrazzo`, verify checksums and
reject mismatched/invalid release versions. The source manifest remains unchanged.
Local checks passed for Python packaging tests, JavaScript configuration/player
and compiled-WASM browser tests, JVM client/app/server tests, production WASM
build, Kotlin formatting and actionlint on the release workflows.

No GitHub release was published, no production HA configuration was changed, and
no HA restart was performed. Real HACS installation and release upload await the
first published release containing this setup.
