"""Render the BeatPlay brand mark into the Android launcher resources.

The design is the same one used by the desktop app, website and promo art
(``D:\\musik\\make_logo.py``): the neon "B" emblem from ``tools/logobaru.jpeg``,
a 1536x1536 square with the emblem above the "BeatPlay" wordmark. Keeping the
source file in this repo means the launcher icons can be regenerated without a
desktop checkout.

Run:  python tools/make_icons.py
"""
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
SOURCE = ROOT / "tools" / "logobaru.jpeg"

# Center the visible emblem, including its glow. The source canvas includes
# empty space above the mark and the wordmark below it, so a full top crop
# makes the launcher icon look vertically low.
EMBLEM_CROP = (285, 150, 1305, 1170)
TILE_RADIUS_RATIO = 0.225
MASTER_SIZE = 1024

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def rounded_tile(size: int) -> Image.Image:
    """The emblem tile with transparent rounded corners (legacy launcher icons)."""
    src = Image.open(SOURCE).convert("RGB").crop(EMBLEM_CROP)
    tile = src.resize((size, size), Image.LANCZOS).convert("RGBA")
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1],
        radius=int(size * TILE_RADIUS_RATIO),
        fill=255,
    )
    tile.putalpha(mask)
    return tile


def circle_tile(size: int, master: Image.Image) -> Image.Image:
    """Same tile clipped to a circle for ic_launcher_round."""
    out = master.copy()
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    out.putalpha(mask)
    return out


def adaptive_foreground(size: int) -> Image.Image:
    """Full-bleed emblem tile; the launcher mask decides the visible shape.

    The tile keeps its own transparent rounded corners, and the background
    gradient matches its near-black fill, so circle/rounded/squircle masks all
    crop the same artwork instead of letterboxing it inside a small square.
    """
    src = Image.open(SOURCE).convert("RGB").crop(EMBLEM_CROP)
    mark = src.resize((size, size), Image.LANCZOS).convert("RGBA")
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1],
        radius=int(size * TILE_RADIUS_RATIO),
        fill=255,
    )
    mark.putalpha(mask)
    return mark


def monochrome(size: int) -> Image.Image:
    """Themed-icon silhouette: solid white emblem, no glow."""
    src = Image.open(SOURCE).convert("RGB").crop(EMBLEM_CROP)
    width = int(size * 0.80)
    height = round(width * src.height / src.width)
    grey = src.resize((width, height), Image.LANCZOS).convert("L")
    mask = grey.point(lambda v: 255 if v >= 96 else 0).convert("L")
    mark = Image.new("RGBA", (width, height), (255, 255, 255, 0))
    mark.putalpha(mask)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(mark, ((size - mark.width) // 2, (size - mark.height) // 2))
    return canvas


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"Logo source not found: {SOURCE}")

    master = rounded_tile(MASTER_SIZE)
    foreground = adaptive_foreground(432)
    mono = monochrome(432)

    for density, size in DENSITIES.items():
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        legacy = master.resize((size, size), Image.LANCZOS)
        legacy.save(folder / "ic_launcher.png", optimize=True)
        legacy.save(folder / "ic_launcher_static.png", optimize=True)
        circle_tile(size, legacy).save(folder / "ic_launcher_round.png", optimize=True)
        circle_tile(size, legacy).save(folder / "ic_launcher_static_round.png", optimize=True)

    drawable = RES / "drawable-xxxhdpi"
    drawable.mkdir(parents=True, exist_ok=True)
    foreground.save(drawable / "ic_launcher_foreground.png", optimize=True)
    mono.save(drawable / "ic_launcher_monochrome.png", optimize=True)

    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)
    master.resize((512, 512), Image.LANCZOS).save(nodpi / "app_logo.png", optimize=True)

    print("Android icons regenerated in", RES)


if __name__ == "__main__":
    main()
