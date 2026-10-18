# Printing browser fixture

`ambiguous-sensor.rc` is the anonymous published tile design returned on 2026-10-18 by
`https://ha.coo.ee/api/cards/resolve` for:

```json
{"card":{"type":"tile","entity":"sensor.s0"},"cardStyle":"classic","dark":false}
```

SHA-256: `d40fd3b093259ec5ccca3baf549d640350a4ceb7bd07765cae25130ce0ad3e0b`.
The response says `exact: false` and names the shape
`device_class=temperature;domain=sensor`. It explains that 110 shapes have these slots
and that the converter-free request cannot tell which one is appropriate.

The test deliberately offers this real ambiguous design for non-temperature sensors.
The before client paints it; the fixed authenticated client must refuse it. All dashboard
entities, registry records, credentials and image bytes in the browser test are synthetic.
