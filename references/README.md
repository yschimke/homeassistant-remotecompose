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

A fresh checkout needs no manual setup: with no `integration/.env.local`, the script runs
`scripts/onboard.sh`, which completes HA's onboarding through its API and writes the token. The
capture rewrites every reference, so commit only the ones you meant to move.

Cards with no reference yet, and why:

- `picture-entity`: HA draws the camera's live frame, our preview its placeholder, so the two can't
  agree.
- `logbook`: its entries carry real timestamps, so a capture would differ on every run.

After capture, review the diff (`git diff --stat`, then open changed PNGs
in an image viewer) and commit with a message explaining *why* the
reference moved (HA upgrade? new `@Preview`? fix to lovelace YAML?).
