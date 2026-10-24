# RemoteFlexBox against androidx FlexBox: render evidence

Renders of the four `FlexBox_*` previews in `previews/.../FlexBoxPreviews.kt`. Each line hands the
same `RemoteFlexBoxConfig` / `RemoteFlexConfig` to androidx `FlexBox` (left, through
`asFlexBoxConfig()` / `asFlexConfig()`) and to `RemoteFlexBox`, captured into a `.rc` document and
played through `HaEmbeddedPlayer` (right). Rendered with
`compose-preview show --module previews --filter FlexBox_`; downscaled to 1800 px only, no other
edits.

| Image | What it shows |
| --- | --- |
| `justify-content.png` | all six `justifyContent` values, with and without `columnGap`: identical |
| `align-items.png` | `alignItems` Start / Center / End / Stretch / Baseline: identical, except Stretch in a wrap-content box, which fills the offered height |
| `grow-basis.png` | `grow`, `basis` (dp, fraction, `FlexBasis.Percent`), `RowReverse`, `alignSelf(Stretch)`. Grow with a gap overflows by the gaps; grow from an auto basis ignores content width; an over-full line does not shrink |
| `wrap-column.png` | `FlexWrap.Wrap` and `FlexDirection.Column`. Wrap drops `rowGap` and does not grow items to fill a line; columns are identical |

The divergences are the player's, and each is listed in the `RemoteFlexBox` KDoc.
