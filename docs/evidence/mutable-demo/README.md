# Mutable public demo evidence

The before images use the preserved pre-change development bundle from integration
commit `73b37aaa9`. The after images use the changed production WASM bundle, exercised
by `scripts/test-demo-browser.py`. Phone images are 390 × 844; wall images are
1440 × 900, all at device scale 1.

| Scenario | Before | After |
| --- | --- | --- |
| Fake data, phone | [Disabled switches](before-fake.png) | [Local controls](after-fake.png) |
| Live fixture, wall | [Read-only readings](before-live.png) | [Lamp controlled on](after-live.png) |

The browser fixture records the exact service POST, changes the lamp state, rejects
a subsequent action to check error handling, then accepts recovery actions. It also
checks local-only fake toggles, live polling, outage fallback and data-mode isolation.
Remote Compose playback uses checked-in binary fixtures; resolver responses and the
HA gateway are mocked. These screenshots do not prove production deployment, real HA
template behavior or hardware GPU rendering. The container provisioning/verifier lane
in `demo-ha.yml` must check the real HA state controls and administrative rejections.
