"""Render the BeatPlay brand mark into the Android launcher resources.

The design is the same one used by the desktop app (``D:\\musik\\make_logo.py``):
the neon "B" emblem. The current source (``tools/iconv2.jpeg``) is a 2048x2048
square with the emblem centred on its dark background and no wordmark, so the
whole canvas is the emblem tile. The previous wordmark artwork is kept as
``tools/logobaru.jpeg`` for reference.

Run:  python tools/make_icons.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
SOURCE = ROOT / "tools" / "iconv2.jpeg"

# The new artwork is a full-canvas square emblem; no crop needed.
TILE_RADIUS_RATIO = 0.225
MASTER_SIZE = 1024

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# The notification / status-bar mark renders at 24dp.
NOTIF_DENSITIES = {
    "mdpi": 24,
    "hdpi": 36,
    "xhdpi": 48,
    "xxhdpi": 72,
    "xxxhdpi": 96,
}


def emblem() -> Image.Image:
    """The square emblem artwork as RGBA."""
    return Image.open(SOURCE).convert("RGBA")


def rounded_tile(size: int) -> Image.Image:
    """The emblem tile with transparent rounded corners (legacy launcher icons)."""
    tile = emblem().resize((size, size), Image.LANCZOS)
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
    gradient matches its dark fill, so circle/rounded/squircle masks all
    crop the same artwork instead of letterboxing it inside a small square.
    """
    return rounded_tile(size)


def monochrome(size: int) -> Image.Image:
    """Themed-icon silhouette: solid white emblem, no glow.

    The background of the artwork sits around grey 35 and the faint halo fades
    out below grey 78, so a ramp between 78 and 102 keeps the core of the mark
    with smooth edges and drops the glow.
    """
    src = emblem().convert("L").resize((size, size), Image.LANCZOS)
    mask = src.point(lambda v: max(0, min(255, round((v - 78) * 255 / 24))))
    mark = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    mark.putalpha(mask)
    return mark


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

    # Notification / status-bar mark: white silhouette at every density.
    for density, size in NOTIF_DENSITIES.items():
        folder = RES / f"drawable-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        mono.resize((size, size), Image.LANCZOS).save(folder / "small_icon.png", optimize=True)

    print("Android icons regenerated in", RES)


if __name__ == "__main__":
    main()
