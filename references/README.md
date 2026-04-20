# Reference screenshots

Ground-truth PNGs captured from a real HA frontend via
`integration/scripts/capture-references.sh` (Puppeteer + the seeded HA in
`integration/compose.yaml`).

**These files are committed fixtures** — they change only when we
deliberately re-capture (new card type, new preview variant, or HA
releases a visual change worth absorbing). The pixel-diff tests in
`:integration` compare our converter output against these.

## Layout

```
references/
  tile/
    temperature_sensor.png
    light_on.png
  entities/
    ...
```

One subdirectory per card `type:`, one PNG per `@Preview` fixture.

## Updating

```sh
cd integration
./scripts/capture-references.sh
```

After capture, review the diff (`git diff --stat`, then open changed PNGs
in an image viewer) and commit with a message explaining *why* the
reference moved (HA upgrade? new `@Preview`? fix to lovelace YAML?).
