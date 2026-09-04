# Wear previews: View player vs embedded player

Evidence for switching `WearWidgetPreviewSnapshot` from `RemoteDocumentPreview`
(View-backed) to `ExperimentalRemoteDocumentPlayer` (embedded, pure Compose).

Commit: `main` @ ca36149. Command, run three times:

    compose-preview show --module wear --json --force=flake-triage-oracle

## Determinism

| player | unstable previews (3 forced renders, 74 each) |
| --- | --- |
| View | 3 — `WearButtonLarge`, `WearGridLarge`, `WearHorizontalStackLarge` |
| embedded | 0 |

Each of the three produced three distinct sha256s; no two runs agreed. For
`WearButtonLarge`:

    run1  81393b95b1c4b074ffdeac168618a57c73c6a8ac81d81da6da0a86331e8bf8c9
    run2  5663116785aeb1f87dcd5edbd512fa0ea7c7dd0133e3d22f5312a6968c80bc98
    run3  53362fe73d3290535a3156b87629eec2fc4a9e9de59ee0a0ed36af1151e0d6f5

All movement is confined to the bbox `(179,207)-(275,303)` — the 96×96 icon chip.

## Images

- `button-view-run1.png`, `button-view-run3.png` — two View-player renders of the
  same commit. The bulb is smeared and doubled, and the halo is offset differently
  in each: these are mid-tween samples of the 0.20s accent animation, not a stable
  drawing.
- `button-embedded.png` — the embedded player. A single crisp bulb in a correctly
  sized chip, byte-identical across runs.

Root cause and the two upstream defects:
[`docs/bugs/rc-alpha18-player-divergence.md`](../../../bugs/rc-alpha18-player-divergence.md).
