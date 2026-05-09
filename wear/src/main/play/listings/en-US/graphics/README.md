# Wear Play Store graphics

Put Wear listing assets in this folder tree; Gradle Play Publisher uploads them on `:wear:publishBundle`.

| Directory | Required? | Notes |
|---|---|---|
| `icon/` | yes | Wear hi-res icon, PNG, 512x512. |
| `feature-graphic/` | yes | PNG/JPEG, 1024x500. |
| `wear-screenshots/` | yes (>=2) | PNG/JPEG screenshots from Wear emulator/device. |

Recommended naming: `01-home.png`, `02-widget-picker.png`, etc.
