"""Render the BeatPlay brand mark into the Android launcher resources.

The design is the same one used by the desktop app, website and promo art
(``D:\\musik\\make_logo.py``): a gradient squircle with four white capsule
bars.  Kept in the Android repo as well so the launcher icons can be
regenerated without the desktop checkout.

Run:  python tools/make_icons.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"

BASE = 512
S = 4
SIZE = BASE * S
INDIGO = (99, 102, 241)
PURPLE = (168, 85, 247)
WHITE = (255, 255, 255)
TILE_RADIUS = 113 * S
BARS = (0.466, 0.754, 1.000, 0.636)
BAR_W = 58 * S
GAP = 28 * S
MAX_H = 236 * S


def tile_mask(size: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=TILE_RADIUS * size // SIZE, fill=255
    )
    return mask


def circle_mask(size: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    return mask


def rounded_tile(size: int) -> Image.Image:
    steps = 160
    rows = []
    for y in range(steps):
        ty = y / (steps - 1)
        for x in range(steps):
            t = (x / (steps - 1) + ty) / 2
            rows.append(tuple(int(INDIGO[i] + (PURPLE[i] - INDIGO[i]) * t) for i in range(3)))
    grad = Image.new("RGB", (steps, steps))
    grad.putdata(rows)
    grad = grad.resize((size, size), Image.BILINEAR)

    tile = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    tile.paste(grad, (0, 0), tile_mask(size))

    sheen = Image.new("L", (size, size), 0)
    sd = ImageDraw.Draw(sheen)
    cx, cy, r = int(size * 0.20), int(size * 0.14), int(size * 0.80)
    sd.ellipse([cx - r, cy - r, cx + r, cy + r], fill=255)
    sheen = sheen.filter(ImageFilter.GaussianBlur(size * 0.07))
    glow = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    glow.putalpha(sheen.point(lambda p: int(p * 0.16)))
    tile.alpha_composite(glow)
    return tile


def beat_mark(size: int) -> Image.Image:
    mark = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(mark)
    bar_w = BAR_W * size // SIZE
    gap = GAP * size // SIZE
    max_h = MAX_H * size // SIZE
    total = len(BARS) * bar_w + (len(BARS) - 1) * gap
    x, cy = (size - total) // 2, size // 2
    for frac in BARS:
        h = max(2, int(round(max_h * frac)))
        d.rounded_rectangle(
            [x, cy - h // 2, x + bar_w, cy - h // 2 + h],
            radius=bar_w // 2,
            fill=WHITE,
        )
        x += bar_w + gap
    inside = Image.composite(mark.split()[3], Image.new("L", (size, size), 0), tile_mask(size))
    mark.putalpha(inside)
    return mark


def full_icon(size: int, master: Image.Image) -> Image.Image:
    return master.resize((size, size), Image.LANCZOS)


def beat_only(size: int) -> Image.Image:
    return beat_mark(SIZE).resize((size, size), Image.LANCZOS)


def app_logo_vector() -> str:
    # Capsule bars from the 512-unit design (see make_logo.py).
    bars = [(98, 110), (184, 178), (270, 236), (356, 150)]
    paths = []
    for x, h in bars:
        start = 256 - h // 2 + 29
        straight = h - 58
        paths.append(
            f'        <path android:fillColor="#FFFFFFFF" '
            f'android:pathData="M{x},{start} a29,29 0 0 1 58,0 v{straight} '
            f'a29,29 0 0 1 -58,0 z" />'
        )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    xmlns:aapt="http://schemas.android.com/aapt"\n'
        '    android:width="512dp"\n'
        '    android:height="512dp"\n'
        '    android:viewportWidth="512"\n'
        '    android:viewportHeight="512">\n'
        '    <path android:pathData="M113,0 H399 A113,113 0 0 1 512,113 V399 '
        'A113,113 0 0 1 399,512 H113 A113,113 0 0 1 0,399 V113 A113,113 0 0 1 113,0 Z">\n'
        '        <aapt:attr name="android:fillColor">\n'
        '            <gradient android:type="linear" android:startX="0" android:startY="0" '
        'android:endX="512" android:endY="512">\n'
        '                <item android:offset="0" android:color="#FF6366F1" />\n'
        '                <item android:offset="1" android:color="#FFA855F7" />\n'
        '            </gradient>\n'
        '        </aapt:attr>\n'
        '    </path>\n'
        + "\n".join(paths) + "\n"
        '</vector>\n'
    )


def main() -> None:
    master = rounded_tile(SIZE)
    master.alpha_composite(beat_mark(SIZE))
    full_master = master
    round_master = master.copy()
    round_master.putalpha(
        Image.composite(round_master.getchannel("A"), Image.new("L", (SIZE, SIZE), 0), circle_mask(SIZE))
    )

    densities = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }
    for density, size in densities.items():
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        full_icon(size, full_master).save(folder / "ic_launcher.png", optimize=True)
        round_master.resize((size, size), Image.LANCZOS).save(folder / "ic_launcher_round.png", optimize=True)
        full_icon(size, full_master).save(folder / "ic_launcher_static.png", optimize=True)
        round_master.resize((size, size), Image.LANCZOS).save(folder / "ic_launcher_static_round.png", optimize=True)

    foreground = RES / "drawable-xxxhdpi"
    foreground.mkdir(parents=True, exist_ok=True)
    beat_only(432).save(foreground / "ic_launcher_foreground.png", optimize=True)
    beat_only(432).save(foreground / "ic_launcher_monochrome.png", optimize=True)

    (RES / "drawable").mkdir(parents=True, exist_ok=True)
    (RES / "drawable" / "app_logo.xml").write_text(app_logo_vector(), encoding="utf-8")
    print("Android icons regenerated in", RES)


if __name__ == "__main__":
    main()
