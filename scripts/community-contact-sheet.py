#!/usr/bin/env python3
"""Package actual Compose renders; requires Pillow. No image is created above 1800px."""
import argparse
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

FAMILIES = [
    ("Mushroom", "Mushroom"), ("Bubble", "Bubble"), ("Minimalist", "UI Minimalist"),
    ("Cupertino", "Control Centre"), ("Catppuccin", "Catppuccin"),
    ("Obsidian", "Obsidian Minimal"), ("Noctis", "Noctis"),
    ("Lcars", "LCARS · Starship"), ("Arctic", "Arctic"), ("Aeon", "Aeon"),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("renders", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    try:
        font = ImageFont.truetype("DejaVuSans.ttf", 20)
    except OSError:
        font = ImageFont.load_default(size=20)
    # Five columns, two rows: exactly ten comparable rendered dashboards.
    for mode in ("Light", "Dark"):
        sheet = Image.new("RGB", (1680, 1260), "#151922" if mode == "Dark" else "#e5e8ee")
        draw = ImageDraw.Draw(sheet)
        for i, (family, label) in enumerate(FAMILIES):
            source = find(args.renders, f"Community_{family}_{mode}_")
            image = Image.open(source).convert("RGB")
            save(image, args.output / f"{family.lower()}-{mode.lower()}.png")
            image.thumbnail((320, 570), Image.Resampling.LANCZOS)
            x, y = 8 + (i % 5) * 336, 12 + (i // 5) * 630
            draw.text((x, y), label, fill="#f4f6fa" if mode == "Dark" else "#20252c", font=font)
            sheet.paste(image, (x, y + 34))
        save(sheet, args.output / f"contact-{mode.lower()}.png")
    for prefix, suffix in (("CommunityDials_", "dials"), ("CommunityCards_", "cards"),
                           ("CommunityCompact_", "compact"), ("CommunityLight_", "light-control"),
                           ("CommunityMedia_", "media-alarm")):
        for family, _ in FAMILIES:
            image = Image.open(find(args.renders, f"{prefix}{family}_")).convert("RGB")
            save(image, args.output / f"{family.lower()}-{suffix}.png")

    compact = Image.new("RGB", (1680, 500), "#e5e8ee")
    draw = ImageDraw.Draw(compact)
    for i, (family, label) in enumerate(FAMILIES):
        image = Image.open(args.output / f"{family.lower()}-compact.png").convert("RGB")
        image.thumbnail((320, 215), Image.Resampling.LANCZOS)
        x, y = 8 + (i % 5) * 336, (i // 5) * 250
        draw.text((x, y + 3), label, fill="#20252c", font=font)
        compact.paste(image, (x, y + 32))
    save(compact, args.output / "contact-compact.png")


def find(root, prefix):
    matches = list(root.glob(f"{prefix}*.png"))
    if len(matches) != 1:
        raise SystemExit(f"Expected one render for {prefix}, got {len(matches)}")
    return matches[0]


def save(image, path):
    image.thumbnail((1800, 1800), Image.Resampling.LANCZOS)
    assert max(image.size) <= 1800
    image.save(path)


if __name__ == "__main__":
    main()
