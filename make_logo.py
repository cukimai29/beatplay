"""BeatPlay brand assets.

One design, every asset: the app icon (Compose window + tray, Linux package,
Windows .ico), the website favicon/brand mark and the promo logo are all rendered
from the same rounded-square "beat" mark, so they can never drift apart again.

Run:  python make_logo.py
"""
from PIL import Image, ImageDraw, ImageFilter
import os

ROOT = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------- design ----
BASE = 512          # design units
S = 4               # supersampling: render at 2048, then downsample
SIZE = BASE * S

INDIGO = (99, 102, 241)    # #6366F1  top-left of the tile
PURPLE = (168, 85, 247)    # #A855F7  bottom-right of the tile
WHITE = (255, 255, 255)

TILE_RADIUS = 113 * S      # squircle corner radius

# Four capsule bars: a rising pulse that settles. Heights are fractions of the
# tallest bar, so the rhythm stays identical at every output size.
BARS = (0.466, 0.754, 1.000, 0.636)
BAR_W = 58 * S
GAP = 28 * S
MAX_H = 236 * S


def _tile_mask(size: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=TILE_RADIUS * size // SIZE, fill=255)
    return mask


def rounded_tile(size: int) -> Image.Image:
    """Gradient squircle with a soft sheen, transparent outside the corners."""
    # Diagonal gradient: build it small, upscale for a smooth ramp.
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
    tile.paste(grad, (0, 0), _tile_mask(size))

    # Glass sheen from the top-left so the tile never reads as flat paint.
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
    """The white pulse bars, clipped to the tile's rounded corners."""
    mark = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(mark)
    bar_w = BAR_W * size // SIZE
    gap = GAP * size // SIZE
    max_h = MAX_H * size // SIZE
    total = len(BARS) * bar_w + (len(BARS) - 1) * gap
    x, cy = (size - total) // 2, size // 2
    for frac in BARS:
        h = max(2, int(round(max_h * frac)))
        d.rounded_rectangle([x, cy - h // 2, x + bar_w, cy - h // 2 + h],
                            radius=bar_w // 2, fill=WHITE)
        x += bar_w + gap
    inside = Image.composite(mark.split()[3], Image.new("L", (size, size), 0), _tile_mask(size))
    mark.putalpha(inside)
    return mark


def render(size: int) -> Image.Image:
    img = rounded_tile(size)
    img.alpha_composite(beat_mark(size))
    return img


def save(img: Image.Image, path: str, **kw) -> None:
    full = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    img.save(full, **kw)
    print(f"  {path}  ({img.size[0]}x{img.size[1]})")


def write_svg() -> None:
    bar_w, gap, max_h = 58, 28, 236
    total = len(BARS) * bar_w + (len(BARS) - 1) * gap
    x0, cy = (BASE - total) // 2, BASE // 2
    bars = []
    for frac in BARS:
        h = int(round(max_h * frac))
        bars.append(f'    <rect x="{x0}" y="{cy - h // 2}" width="{bar_w}" height="{h}" rx="{bar_w // 2}"/>')
        x0 += bar_w + gap
    svg = os.path.join(ROOT, "app/src/main/resources/icon.svg")
    with open(svg, "w", encoding="utf-8") as f:
        f.write(
            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512">\n'
            '  <defs>\n'
            '    <linearGradient id="tile" x1="0" y1="0" x2="1" y2="1">\n'
            '      <stop offset="0" stop-color="#6366F1"/>\n'
            '      <stop offset="1" stop-color="#A855F7"/>\n'
            '    </linearGradient>\n'
            '    <radialGradient id="sheen" cx="0.2" cy="0.14" r="0.8">\n'
            '      <stop offset="0" stop-color="#ffffff" stop-opacity="0.18"/>\n'
            '      <stop offset="1" stop-color="#ffffff" stop-opacity="0"/>\n'
            '    </radialGradient>\n'
            '  </defs>\n'
            '  <rect width="512" height="512" rx="113" fill="url(#tile)"/>\n'
            '  <rect width="512" height="512" rx="113" fill="url(#sheen)"/>\n'
            '  <g fill="#ffffff">\n' + "\n".join(bars) + "\n  </g>\n"
            '</svg>\n'
        )
    print("  app/src/main/resources/icon.svg")


if __name__ == "__main__":
    print("BeatPlay logo assets:")
    master = render(SIZE)
    big = master.resize((BASE, BASE), Image.LANCZOS)
    small = master.resize((128, 128), Image.LANCZOS)

    # App icon: Compose window + tray, and the Linux package icon.
    save(big, "app/src/main/resources/icon.png", optimize=True)
    save(big, "app/icon.png", optimize=True)

    # Windows package icon: every size the shell may ask for.
    save(big, "app/icon.ico", format="ICO",
         sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])

    # Website favicon / brand mark.
    save(small, "web/img/logo-128.png", optimize=True)
    save(small, "BeatPlay-website/img/logo-128.png", optimize=True)

    # Promo poster logo and the legacy 512 hero.
    save(small, "promo/assets/logo.png", optimize=True)
    save(big, "web/img/logo.png", optimize=True)

    write_svg()
    print("done")
