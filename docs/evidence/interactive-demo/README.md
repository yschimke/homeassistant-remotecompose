# Interactive shared demo evidence

2026-10-18, Chromium software rendering, 1440×900. `before-public.png` shows the actual
public demo using demo-readonly-v1. `after-controls.png` and `after-simulation.png` use the
published WASM frontend with only `/demo-ha/*` requests redirected to an isolated real HA
2026.10.2 runtime and the controls-v4 gateway. These are local integration evidence, not
production rollout evidence. Current published renderer sometimes falls back to built-in
cards; that does not stop the actual host controls or changing numeric readings.

`python scripts/test-live-demo-browser.py --gateway http://127.0.0.1:8080` passed: lamp switch,
coffee button, thermostat target, second-visitor state convergence, visible simulator update,
retention of user control states, and restoration of initial controls. With no `--gateway`,
it checks the public deployment and deliberately rejects an older backend contract.

`verify_public.py --stream` also passed against the isolated backend: sanitized reads, nine
generic entities, administrative/unknown write denial, REST service changes, WebSocket
restoration and an actual simulator state_changed event. All 41 focused unit tests passed.
No host SSH deployment credential or HA deployment workflow is available in this session.
