"""BeatPlay brand assets.

Everything is rendered from the canonical emblem at ``app/iconv2.jpeg``:
a 2048x2048 square with the neon "B" mark centred on its dark background.
The app icon (Compose window + tray, Linux package, Windows .ico), the
website favicon/brand mark, the website hero lockup and the promo mark all
come from it, so they can never drift apart.

The hero lockup (``logo-lockup.png``) composes the emblem above the
"BeatPlay" wordmark set in Plus Jakarta Sans ExtraBold — "Beat" in ink
white, "Play" in the brand gradient — matching the site's ``.brand`` styling.
It is a fixed 1280x1148 canvas to match the <img> attributes in index.html.

Run:  python make_logo.py
"""
from __future__ import annotations

import base64
import io
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.abspath(__file__))
APP_SOURCE = os.path.join(ROOT, "app", "iconv2.jpeg")
JAKARTA_FONT = os.path.join(ROOT, "promo", "assets", "PlusJakartaSans.ttf")

# The rounded-square app tile uses the same corner ratio as a squircle.
TILE_RADIUS_RATIO = 0.225
ICON_SIZE = 1024
WEB_ICON_SIZE = 512

# Site palette (web/assets/site.css :root).
INK = (247, 243, 255)
GRAD_STOPS = (
    (0.00, (255, 79, 216)),   # --pink
    (0.52, (168, 85, 247)),   # --violet
    (1.00, (99, 102, 241)),   # --indigo
)
GRAD_ANGLE = 115.0  # CSS linear-gradient(115deg, ...)

# Lockup canvas, fixed to match index.html's <img width/height>.
LOCKUP_W, LOCKUP_H = 1280, 1148
LOCKUP_EMBLEM_TOP = 165
LOCKUP_EMBLEM_HEIGHT = 720
LOCKUP_TEXT_TOP = 905
LOCKUP_FONT_SIZE = 152
LOCKUP_TRACKING = -0.045  # em, same as the site's .brand


def load_app_source() -> Image.Image:
    if not os.path.exists(APP_SOURCE):
        raise SystemExit(f"Logo source not found: {APP_SOURCE}")
    return Image.open(APP_SOURCE).convert("RGB")


def rounded_icon(size: int = ICON_SIZE) -> Image.Image:
    """Square emblem tile with transparent rounded corners."""
    src = load_app_source()
    tile = src.resize((size, size), Image.LANCZOS).convert("RGBA")
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1],
        radius=int(size * TILE_RADIUS_RATIO),
        fill=255,
    )
    tile.putalpha(mask)
    return tile


def _emblem_keyed() -> Image.Image:
    """The emblem with its navy background keyed to alpha, glow preserved.

    The background peak sits around 56-70 (JPEG noise) and the mark core
    starts around 140, so a ramp between 72 and 140 drops the background
    while keeping the halo.
    """
    rgb = np.asarray(load_app_source()).astype(np.float32)
    peak = rgb.max(axis=2)
    alpha = np.clip((peak - 72.0) / 68.0, 0.0, 1.0) * 255.0
    return Image.fromarray(np.dstack([rgb, alpha]).astype(np.uint8), "RGBA")


def _gradient(w: int, h: int) -> Image.Image:
    """The site brand gradient (115deg pink -> violet 52% -> indigo)."""
    x = np.arange(w, dtype=np.float32)
    y = np.arange(h, dtype=np.float32)
    gx, gy = np.meshgrid(x, y)
    ang = math.radians(GRAD_ANGLE)
    dx, dy = math.sin(ang), -math.cos(ang)
    proj = gx * dx + gy * dy
    pmin, pmax = float(proj.min()), float(proj.max())
    t = (proj - pmin) / max(pmax - pmin, 1e-6)
    out = np.zeros((h, w, 3), dtype=np.float32)
    for i in range(3):
        out[..., i] = np.interp(t, [s[0] for s in GRAD_STOPS], [s[1][i] for s in GRAD_STOPS])
    return Image.fromarray(out.astype(np.uint8), "RGB")


def _draw_tracked(draw: ImageDraw.ImageDraw, xy, text, font, tracking_px, fill):
    """Draw text with per-letter tracking (pixels) like the site's .brand."""
    x, y = xy
    for ch in text:
        draw.text((x, y), ch, font=font, fill=fill)
        x += font.getlength(ch) + tracking_px
    return x


def web_lockup() -> Image.Image:
    """Emblem above the "BeatPlay" wordmark, transparent 1280x1148 canvas."""
    emblem = _emblem_keyed()
    # Crop to the visible mark (plus glow) with a little padding.
    alpha = np.asarray(emblem.getchannel("A"))
    ys, xs = np.where(alpha > 16)
    pad = 24
    box = (
        max(xs.min() - pad, 0), max(ys.min() - pad, 0),
        min(xs.max() + pad, emblem.width), min(ys.max() + pad, emblem.height),
    )
    emblem = emblem.crop(box)
    scale = LOCKUP_EMBLEM_HEIGHT / emblem.height
    emblem = emblem.resize(
        (round(emblem.width * scale), LOCKUP_EMBLEM_HEIGHT), Image.LANCZOS
    )

    canvas = Image.new("RGBA", (LOCKUP_W, LOCKUP_H), (0, 0, 0, 0))
    ex = (LOCKUP_W - emblem.width) // 2
    canvas.alpha_composite(emblem, (ex, LOCKUP_EMBLEM_TOP))

    if not os.path.exists(JAKARTA_FONT):
        raise SystemExit(
            f"Brand font not found: {JAKARTA_FONT} "
            "(variable TTF from Google Fonts, weight axis 200-800)"
        )
    font = ImageFont.truetype(JAKARTA_FONT, LOCKUP_FONT_SIZE)
    font.set_variation_by_axes([800])

    word = "BeatPlay"
    tracking_px = LOCKUP_TRACKING * LOCKUP_FONT_SIZE
    total = sum(font.getlength(ch) for ch in word) + tracking_px * (len(word) - 1)
    x = (LOCKUP_W - total) / 2
    y = LOCKUP_TEXT_TOP

    # "Beat" in ink white.
    beat_mask = Image.new("L", (LOCKUP_W, LOCKUP_H), 0)
    d = ImageDraw.Draw(beat_mask)
    _draw_tracked(d, (x, y), "Beat", font, tracking_px, 255)
    canvas = Image.composite(
        Image.new("RGBA", (LOCKUP_W, LOCKUP_H), INK + (255,)), canvas, beat_mask
    )

    # "Play" in the brand gradient.
    play_x = x + font.getlength("Beat") + tracking_px * 4
    play_w = sum(font.getlength(ch) for ch in "Play") + tracking_px * 3
    play_mask = Image.new("L", (LOCKUP_W, LOCKUP_H), 0)
    d = ImageDraw.Draw(play_mask)
    _draw_tracked(d, (play_x, y), "Play", font, tracking_px, 255)
    grad = _gradient(LOCKUP_W, LOCKUP_H).convert("RGBA")
    grad.putalpha(play_mask)
    canvas.alpha_composite(grad)
    return canvas


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
    print("BeatPlay brand assets (from app/iconv2.jpeg):")
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

    write_svg(master)

    # Website favicon / brand mark / hero lockup.
    lockup = web_lockup()
    for folder in ("web/img", "BeatPlay-website/img"):
        save(small, f"{folder}/logo-128.png", optimize=True)
        save(big, f"{folder}/logo.png", optimize=True)
        save(tiny, f"{folder}/favicon-32.png", optimize=True)
        save(lockup, f"{folder}/logo-lockup.png", optimize=True)

    # Promo poster logo.
    save(big, "promo/assets/logo.png", optimize=True)
    print("done")
