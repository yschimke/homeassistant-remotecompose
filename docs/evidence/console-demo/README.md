# Console demo instruments

Before: [previous sparse Console dashboard](../public-demo-integration/demo-wall-console.png).
After: [wall climate and power](wall-climate.png), [phone climate](phone-climate.png),
[phone power](phone-power.png), [wall controls](wall-controls.png).

These captures use the development WASM bundle, the checked-in demo dashboard configuration,
and the published Console gauge/thermostat RC binaries pinned in `scripts/fixtures/demo`.
The browser gateway is a controlled fixture with nine HA-shaped entities; these are not
captures of the deployed site or proof of actual HA service behavior. The production app
has no fixture-data mode. Phone captures are 390 × 844, wall captures 1440 × 900.

The browser tests press both the thermostat's RC ± buttons and native Cooler/Warmer
buttons, check the exact live-target service calls, click the coffee card body twice,
and verify that polling refreshes temperature and power without resolving templates again.
They also compare frozen-clock frames 100 ms and 600 ms after a desktop resize to ensure
text layout snaps rather than tweening through clipped widths. Analog value motion remains enabled.

The player currently exports inactive thermostat layout alternatives as accessibility text;
tracked separately as [SCH-13](https://linear.app/yschimke/issue/SCH-13/rc-thermostat-exposes-inactive-layout-text-to-screen-readers).
