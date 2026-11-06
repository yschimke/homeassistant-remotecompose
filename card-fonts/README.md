# Card fonts

Fonts a card design names (`RemoteFontFamily.Named`) that no device ships. They are not compiled
into the app: the design server serves them beside the designs (`<designs>/fonts/fonts.json` and
the files it names), and the app fetches, verifies and caches them with its design sync, then
hands them to both players by name. A launcher widget, whose system player can't load them, keeps
its theme's own face.

`fonts.json` is the players' font manifest (`RcManifestTypefaceLoader`): a family's `name` is the
name a card gives it. The server adds each face's `sha256` when it serves the manifest.

| Family | File | Font | Licence |
| --- | --- | --- | --- |
| `digital7` | `digital7.ttf` | Digital-7 Mono Italic, Sizenko Alexander (Style-7) | Free for personal, non-commercial use |
| `gspot` | `russo-one.ttf` | Russo One, Jovanny Lemonad | SIL Open Font License 1.1 |

See THIRD_PARTY_NOTICES.md.
