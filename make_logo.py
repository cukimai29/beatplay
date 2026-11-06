"""BeatPlay brand assets.

One design, every asset. Everything is rendered from the canonical logo at
``web/img/logobaru.jpeg`` so the app icon (Compose window + tray, Linux package,
Windows .ico), the Android launcher, the website favicon/brand lockup and the
promo mark can never drift apart again.

Run:  python make_logo.py
"""
from __future__ import annotations

import base64
import io
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
SOURCE = os.path.join(ROOT, "web", "img", "logobaru.jpeg")

# The emblem sits above the wordmark. This square crop keeps the full neon glow
# and stops just before the "BeatPlay" letters.
# Center the visible mark (including its glow) rather than the square source canvas.
# The source artwork is vertically low because the wordmark sits underneath it.
EMBLEM_CROP = (285, 150, 1305, 1170)
# Emblem + wordmark, with a little breathing room.
LOCKUP_CROP = (121, 98, 1415, 1259)

# The rounded-square app tile uses the same corner ratio as a squircle.
TILE_RADIUS_RATIO = 0.225
ICON_SIZE = 1024
WEB_ICON_SIZE = 512


def load_source() -> Image.Image:
    if not os.path.exists(SOURCE):
        raise SystemExit(f"Logo source not found: {SOURCE}")
    return Image.open(SOURCE).convert("RGB")


def rounded_icon(size: int = ICON_SIZE) -> Image.Image:
    """Square emblem tile with transparent rounded corners."""
    src = load_source().crop(EMBLEM_CROP).resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1],
        radius=int(size * TILE_RADIUS_RATIO),
        fill=255,
    )
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(src, (0, 0), mask)
    return out


def lockup() -> Image.Image:
    """Emblem + wordmark with the near-black JPEG background keyed to alpha.

    The alpha comes from the brightest channel (the JPEG background is
    near-black, so it keys out cleanly) while the colour is left untouched, so
    compositing it on the dark website reproduces the original glow instead of
    blowing the faint halo out to full saturation.
    """
    try:
        import numpy as np
    except ImportError:  # pragma: no cover - dev tooling only
        return load_source().crop(LOCKUP_CROP)

    src = load_source().crop(LOCKUP_CROP)
    rgb = np.asarray(src).astype(np.float32)
    peak = rgb.max(axis=2)
    alpha = np.clip((peak - 8.0) / 247.0, 0.0, 1.0) * 255.0
    rgba = np.dstack([rgb, alpha]).astype(np.uint8)
    return Image.fromarray(rgba, "RGBA")


def save(img: Image.Image, path: str, **kw) -> None:
    full = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    img.save(full, **kw)
    print(f"  {path}  ({img.size[0]}x{img.size[1]})")


def write_svg(master: Image.Image) -> None:
    """Portable vector wrapper around the rendered tile (data-URI PNG)."""
    buf = io.BytesIO()
    master.resize((WEB_ICON_SIZE, WEB_ICON_SIZE), Image.LANCZOS).save(
        buf, format="PNG", optimize=True
    )
    encoded = base64.b64encode(buf.getvalue()).decode("ascii")
    radius = int(WEB_ICON_SIZE * TILE_RADIUS_RATIO)
    svg = (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" '
        'width="512" height="512">\n'
        '  <defs>\n'
        f'    <clipPath id="tile"><rect width="512" height="512" rx="{radius}"/></clipPath>\n'
        '  </defs>\n'
        f'  <image href="data:image/png;base64,{encoded}" width="512" height="512" '
        'clip-path="url(#tile)"/>\n'
        '</svg>\n'
    )
    path = os.path.join(ROOT, "app/src/main/resources/icon.svg")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(svg)
    print("  app/src/main/resources/icon.svg")


if __name__ == "__main__":
    print("BeatPlay logo assets:")
    master = rounded_icon(ICON_SIZE)
    big = master.resize((WEB_ICON_SIZE, WEB_ICON_SIZE), Image.LANCZOS)
    small = master.resize((128, 128), Image.LANCZOS)
    tiny = master.resize((32, 32), Image.LANCZOS)

    # App icon: Compose window + tray, and the Linux package icon.
    save(big, "app/src/main/resources/icon.png", optimize=True)
    save(big, "app/icon.png", optimize=True)

    # Windows package icon: every size the shell may ask for.
    save(master, "app/icon.ico", format="ICO",
         sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64),
                (128, 128), (256, 256)])

    # Website favicon / brand mark.
    for folder in ("web/img", "BeatPlay-website/img"):
        save(small, f"{folder}/logo-128.png", optimize=True)
        save(big, f"{folder}/logo.png", optimize=True)
        save(tiny, f"{folder}/favicon-32.png", optimize=True)

    # Full lockup for the hero / brand sections.
    mark = lockup()
    scale = 1280 / mark.width
    mark = mark.resize((1280, round(mark.height * scale)), Image.LANCZOS)
    for folder in ("web/img", "BeatPlay-website/img"):
        save(mark, f"{folder}/logo-lockup.png", optimize=True)

    # Promo poster logo.
    save(big, "promo/assets/logo.png", optimize=True)

    write_svg(master)
    print("done")
