#!/usr/bin/env python3
"""Generate the Remote widget's 9-patch backgrounds (Glance can't clip shapes or draw borders).

Each one is drawn supersampled (antialiased chamfers), downscaled to xxhdpi (3 px per dp), then
framed with 9-patch guides: stretch only between the corners, content area = everything (so the
drawable adds no padding; the layout owns it). Reproducible: rerun after changing a colour.

    python3 -I tools/ninepatch.py app/src/main/res/drawable-xxhdpi
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw

DP = 3          # xxhdpi
SS = 8          # supersampling factor


def hexc(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + ((int(h[6:8], 16),) if len(h) == 8 else (255,))


def shape(w, h, c):
    """Chamfered rectangle: top-left and bottom-right corners cut by c (the canvas polygon)."""
    return [(c, 0), (w, 0), (w, h - c), (w - c, h), (0, h), (0, c)] if c else [(0, 0), (w, 0), (w, h), (0, h)]


def make(name, fill, border, chamfer_dp, out):
    c = chamfer_dp * DP
    side = max(3 * c, 12 * DP)                       # room for both corners + a stretchable middle
    w = h = side
    big = Image.new('RGBA', (w * SS, h * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    d.polygon([(x * SS, y * SS) for x, y in shape(w, h, c)], fill=hexc(border))
    b = DP * SS                                      # 1 dp border
    inner_c = max(c * SS - b * 0.414, 0)             # the inner chamfer, so the border keeps its width
    pts = shape(w * SS - 2 * b, h * SS - 2 * b, inner_c)
    d.polygon([(x + b, y + b) for x, y in pts], fill=hexc(fill))
    img = big.resize((w, h), Image.LANCZOS)

    framed = Image.new('RGBA', (w + 2, h + 2), (0, 0, 0, 0))
    framed.paste(img, (1, 1))
    px = framed.load()
    black = (0, 0, 0, 255)
    lo, hi = max(c, 2 * DP) + 1, w - max(c, 2 * DP)   # stretch away from corners and borders
    for x in range(lo, hi + 1):
        px[x, 0] = black
    for y in range(lo, hi + 1):
        px[0, y] = black
    for x in range(1, w + 1):                         # content = everything: no built-in padding
        px[x, h + 1] = black
    for y in range(1, h + 1):
        px[w + 1, y] = black
    framed.save(out / f'{name}.9.png')


if __name__ == '__main__':
    out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
    # The phosphor palette as shipped (theme #76: the deck's palette is effectively fixed).
    make('w_panel', '#030603', '#491c1c', 14, out)          # the widget body
    make('w_chip_off', '#130808', '#491c1c', 0, out)        # LISTEN / SCREENS, off
    make('w_chip_on', '#dc3a3a', '#fa4242', 0, out)         # lit (rgba(250,66,66,.88) on black)
    make('w_chip_mode', '#130808', '#4a3510', 0, out)       # WORK / FREE
    make('w_chip_dead', '#0a0505', '#2a1212', 0, out)       # rig offline
    print('wrote', sorted(p.name for p in out.glob('w_*.9.png')))
