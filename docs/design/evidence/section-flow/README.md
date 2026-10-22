# Section flow in the player: render evidence

Renders of `previews/.../SectionFlexPreviews.kt`: the climate board's sections, with their `grid`
cards lifted into the section, laid out two ways. Rendered with
`compose-preview show --module previews --filter SectionFlow`; joined side by side and downscaled to
1800 px, no other edits.

| Image | What it shows |
| --- | --- |
| `host-vs-remote-mobile.png` | 411 dp: `LovelaceSectionCardFlow` (left, one document per card, placed by the host) against `RemoteSectionCardFlow` (right, one document per section, flowed by the player). Same rows, widths and gaps |
| `host-vs-remote-tablet.png` | the same at 800 dp, where the cards split into wider rows. Same again |
| `one-capture-two-widths.png` | `SectionFlowRemoteReflow`: one section captured once at 800 dp and played at 411 dp and 800 dp. The document picks its row split from the width it is played at |
