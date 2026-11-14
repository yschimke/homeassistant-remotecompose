# Third-party icon data in rc-converter

## Home Assistant icon tables

`src/commonMain/kotlin/ee/schimke/ha/rc/icons/HaIconTables.kt` lists Home Assistant's default
entity icons, taken from [Home Assistant core](https://github.com/home-assistant/core) 2026.10.2
(`homeassistant/components/*/icons.json`) and the
[Home Assistant frontend](https://github.com/home-assistant/frontend) 20260930.4
(`src/data/icons.ts`). Both projects are licensed under the Apache License 2.0
(https://www.apache.org/licenses/LICENSE-2.0). Copyright Home Assistant contributors.
[`scripts/generate-ha-icon-tables.py`](../../scripts/generate-ha-icon-tables.py) regenerates it
and pins those versions.

## Material Design Icons

The glyphs those tables name are drawn by `:rc-mdi-icons`; see
[its notice](../../rc-mdi-icons/NOTICE.md).
