"""
P-Meeting premium app background generator.

Produces a 1440x2560 portrait wallpaper in two tonal variants that share the
same geometry and seed:

  dark  -> deep navy / midnight base (default, used by the dark theme)
  light -> soft blue-white base (used by the light theme)

Shared design language for both:
  - soft diagonal depth gradient
  - muted teal, cyan and violet light blooms kept away from the centre
  - translucent audio-waveform ribbons and a few fine contour lines
  - sparse interconnected node mesh near the edges only
  - implied (never literal) capsule/microphone silhouettes
  - whisper-faint digital bars and a corner-faded dot grid
  - vignette keeping the centre quiet and the top/bottom readable

No text, logos, people or photographic elements are ever drawn.
Deterministic: fixed random seed, so the files are reproducible.

Usage:  python generate_background.py [dark|light|both]
"""

import math
import random
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageChops

W, H = 1440, 2560
SEED = 20261119
OUT_DIR = r"E:\P-Music\P-Meeting\design"

# ----------------------------------------------------------------------------
# palettes
# ----------------------------------------------------------------------------
DARK = dict(
    stops=[
        (0.00, (9, 15, 29)),
        (0.14, (11, 19, 36)),
        (0.32, (9, 18, 34)),
        (0.50, (8, 17, 32)),
        (0.66, (9, 21, 36)),
        (0.80, (8, 17, 30)),
        (0.92, (7, 13, 25)),
        (1.00, (5, 10, 20)),
    ],
    teal=(30, 124, 136),
    cyan=(126, 208, 220),
    violet=(104, 84, 160),
    blue_grey=(124, 156, 182),
    diag=((20, 52, 66), (7, 13, 26), 22),
    sweep=(52, 128, 148),
    band_stops=[
        [(0.00, (28, 110, 126)), (0.55, (44, 150, 168)), (1.00, (96, 120, 178))],
        [(0.00, (30, 122, 138)), (0.60, (36, 132, 150)), (1.00, (70, 96, 158))],
        [(0.00, (34, 116, 140)), (1.00, (58, 104, 164))],
    ],
    contour=(126, 208, 220),
    contour_halo=(30, 124, 136),
    capsule=(126, 208, 220),
    shade=(3, 7, 15),
    vignette=(4, 8, 16),
    scale=1.0,
    bloom_scale=1.0,
    vignette_alpha=0.88,
    shade_alpha=0.92,
    mesh_scale=1.0,
    suffix="",
)

LIGHT = dict(
    stops=[
        (0.00, (250, 252, 255)),
        (0.14, (243, 247, 254)),
        (0.32, (236, 243, 253)),
        (0.50, (232, 240, 252)),
        (0.66, (228, 237, 251)),
        (0.80, (223, 233, 249)),
        (0.92, (217, 228, 246)),
        (1.00, (211, 223, 243)),
    ],
    teal=(52, 146, 158),
    cyan=(70, 170, 188),
    violet=(124, 110, 182),
    blue_grey=(132, 162, 194),
    diag=((214, 231, 249), (236, 245, 255), 30),
    sweep=(120, 186, 202),
    band_stops=[
        [(0.00, (170, 214, 226)), (0.55, (150, 204, 220)), (1.00, (176, 178, 226))],
        [(0.00, (176, 216, 228)), (0.60, (160, 210, 224)), (1.00, (182, 186, 228))],
        [(0.00, (178, 218, 230)), (1.00, (186, 190, 230))],
    ],
    contour=(64, 148, 168),
    contour_halo=(96, 178, 196),
    capsule=(96, 178, 198),
    shade=(188, 207, 235),
    vignette=(198, 216, 241),
    scale=0.95,
    bloom_scale=0.50,
    vignette_alpha=0.50,
    shade_alpha=0.55,
    mesh_scale=0.55,
    suffix="-light",
)


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def vgrad(size, stops):
    w, h = size
    col = Image.new("RGB", (1, h))
    px = col.load()
    for y in range(h):
        t = y / (h - 1)
        for i in range(len(stops) - 1):
            t0, c0 = stops[i]
            t1, c1 = stops[i + 1]
            if t0 <= t <= t1:
                px[0, y] = lerp(c0, c1, (t - t0) / max(t1 - t0, 1e-6))
                break
    return col.resize((w, h), Image.BILINEAR)


def diag_tint(size, c_from, c_to, alpha_max):
    w, h = size
    sw, sh = w // 4 + 1, h // 4 + 1
    layer = Image.new("RGBA", (sw, sh), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for i in range(sw + sh):
        t = i / (sw + sh - 1)
        a = int(alpha_max * (1 - abs(t - 0.5) * 2) ** 0.85)
        d.line([(i, 0), (0, i)], fill=lerp(c_from, c_to, t) + (a,))
    return layer.resize(size, Image.BICUBIC)


def blob(size, center, radius, color, alpha, blur, squash=1.0):
    w, h = size
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).ellipse(
        [center[0] - radius, center[1] - radius * squash,
         center[0] + radius, center[1] + radius * squash],
        fill=255,
    )
    mask = mask.filter(ImageFilter.GaussianBlur(blur)).point(lambda v: int(v * alpha))
    layer = Image.new("RGB", size, color)
    layer.putalpha(mask)
    return layer


def paint(mask, color, blur=0.0, alpha=1.0):
    if blur > 0:
        mask = mask.filter(ImageFilter.GaussianBlur(blur))
    if alpha != 1.0:
        mask = mask.point(lambda v: int(v * alpha))
    layer = Image.new("RGB", mask.size, color)
    layer.putalpha(mask)
    return layer


def paint_gradient(mask, stops, blur=0.0, alpha=1.0):
    if blur > 0:
        mask = mask.filter(ImageFilter.GaussianBlur(blur))
    if alpha != 1.0:
        mask = mask.point(lambda v: int(v * alpha))
    layer = vgrad(mask.size, stops)
    layer.putalpha(mask)
    return layer


def wave(x0, x1, y0, y1, a1, f1, p1, a2, f2, p2, steps=600, taper=True):
    pts = []
    for i in range(steps):
        t = i / (steps - 1)
        x = x0 + (x1 - x0) * t
        y = y0 + (y1 - y0) * t
        edge = math.sin(math.pi * min(max(t, 0.0), 1.0)) ** 0.30 if taper else 1.0
        y += a1 * math.sin(2 * math.pi * f1 * t + p1) * edge
        y += a2 * math.sin(2 * math.pi * f2 * t + p2) * edge
        pts.append((x, y))
    return pts


def soften_curve(pts, blur=1.0):
    m = Image.new("L", (W, H), 0)
    ImageDraw.Draw(m).line(pts, fill=255, width=2, joint="curve")
    return m.filter(ImageFilter.GaussianBlur(blur))


def build(p):
    random.seed(SEED)
    s = p["scale"]

    # 1. base -------------------------------------------------------------
    img = vgrad((W, H), p["stops"]).convert("RGBA")
    img = Image.alpha_composite(img, diag_tint((W, H), *p["diag"]))

    # 2. light blooms -----------------------------------------------------
    for cx, cy, rad, col, al, bl, sq in [
        (0.00, 0.84, 0.50, p["teal"], 0.26, 160, 0.95),
        (0.90, 1.04, 0.42, p["violet"], 0.16, 180, 0.80),
        (1.00, 0.22, 0.30, p["cyan"], 0.10, 180, 1.00),
        (0.98, 0.66, 0.24, p["teal"], 0.08, 190, 1.00),
        (-0.06, 0.10, 0.26, p["blue_grey"], 0.05, 190, 1.00),
        (1.06, 0.09, 0.26, p["violet"], 0.07, 180, 1.00),
    ]:
        img = Image.alpha_composite(
            img, blob((W, H), (cx * W, cy * H), rad * H, col, al * s * p["bloom_scale"], bl, sq)
        )

    # 3. implied microphone capsules --------------------------------------
    for cx_f, cy_f, cw_f, chh_f, r_f, alpha in [
        (0.055, 0.315, 0.050, 0.135, 0.050, 0.045),
        (0.960, 0.575, 0.042, 0.105, 0.042, 0.040),
        (0.705, 0.995, 0.038, 0.075, 0.034, 0.035),
    ]:
        m = Image.new("L", (W, H), 0)
        cx, cy = cx_f * W, cy_f * H
        ImageDraw.Draw(m).rounded_rectangle(
            [cx - cw_f * W, cy - chh_f * H * 0.5,
             cx + cw_f * W, cy + chh_f * H * 0.5],
            radius=r_f * W, fill=255,
        )
        m = m.filter(ImageFilter.GaussianBlur(60))
        img = Image.alpha_composite(img, paint(m, p["capsule"], blur=12, alpha=alpha * s))

    # 4. translucent aurora ribbons ---------------------------------------
    for (y0, y1, a1, f1, ph1, a2, f2, ph2, thick, blur, alpha), stops in zip(
        [
            (0.560, 0.470, 0.045, 0.62, 1.2, 0.012, 1.85, 0.0, 150, 70, 0.13),
            (0.780, 0.700, 0.035, 1.10, 3.4, 0.010, 2.40, 1.1, 110, 80, 0.10),
            (0.330, 0.245, 0.030, 0.85, 5.0, 0.008, 2.20, 2.0, 90, 90, 0.07),
        ],
        p["band_stops"],
    ):
        top = wave(-0.12 * W, 1.12 * W, y0 * H, y1 * H, a1 * H, f1, ph1, a2 * H, f2, ph2)
        bottom = [(x, y + thick) for x, y in top]
        m = Image.new("L", (W, H), 0)
        ImageDraw.Draw(m).polygon(top + bottom[::-1], fill=255)
        img = Image.alpha_composite(img, paint_gradient(m, stops, blur=blur, alpha=alpha * s))

    # 5. fine waveform contours -------------------------------------------
    for y0, y1, a1, f1, ph1, a2, f2, ph2, alpha in [
        (0.840, 0.500, 0.048, 1.30, 0.9, 0.014, 3.05, 2.2, 0.26),
        (0.745, 0.360, 0.062, 1.02, 2.7, 0.020, 2.45, 0.5, 0.20),
        (0.910, 0.735, 0.026, 1.80, 4.2, 0.010, 3.55, 1.4, 0.22),
        (0.640, 0.585, 0.075, 0.74, 5.1, 0.016, 2.05, 3.1, 0.13),
    ]:
        m = soften_curve(wave(-0.06 * W, 1.06 * W, y0 * H, y1 * H, a1 * H, f1, ph1, a2 * H, f2, ph2), 1.2)
        img = Image.alpha_composite(img, paint(m, p["contour_halo"], blur=26, alpha=alpha * 0.65 * s))
        img = Image.alpha_composite(img, paint(m, p["contour"], blur=1.0, alpha=alpha * s))

    upper = wave(-0.08 * W, 1.08 * W, 0.300 * H, 0.120 * H,
                 0.016 * H, 0.62, 2.4, 0.005 * H, 1.55, 0.8)
    um = soften_curve(upper, 1.4)
    img = Image.alpha_composite(img, paint(um, p["contour_halo"], blur=30, alpha=0.10 * s))
    img = Image.alpha_composite(img, paint(um, p["blue_grey"], blur=1.2, alpha=0.12 * s))

    # 6. sparse node mesh --------------------------------------------------
    mesh = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    md = ImageDraw.Draw(mesh)

    def cluster(xr, yr, count, links, alpha):
        nodes = [(random.uniform(*xr) * W, random.uniform(*yr) * H) for _ in range(count)]
        for i, (x, y) in enumerate(nodes):
            near = sorted(
                ((math.dist((x, y), n), n) for j, n in enumerate(nodes) if j != i),
                key=lambda q: q[0],
            )[:links]
            for _, (nx, ny) in near:
                md.line([(x, y), (nx, ny)], fill=p["blue_grey"] + (int(alpha),), width=2)
        for x, y in nodes:
            r = random.uniform(1.6, 3.0)
            md.ellipse([x - r, y - r, x + r, y + r],
                       fill=p["contour"] + (int(min(alpha * 3, 70)),))

    cluster((0.010, 0.170), (0.30, 0.82), 10, 3, 13 * p["mesh_scale"])
    cluster((0.30, 0.74), (0.900, 0.996), 8, 3, 11 * p["mesh_scale"])
    cluster((0.830, 0.996), (0.020, 0.130), 6, 2, 8 * p["mesh_scale"])
    cluster((0.010, 0.09), (0.880, 0.990), 5, 2, 8 * p["mesh_scale"])
    img = Image.alpha_composite(img, mesh.filter(ImageFilter.GaussianBlur(0.7)))

    # 7. digital waveform bars --------------------------------------------
    bars = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    bd = ImageDraw.Draw(bars)

    def bar_run(x0f, x1f, base_yf, amp_yf, freq, alpha, hmax, gap, bw):
        x = x0f * W
        end = x1f * W
        i = 0
        while x < end:
            t = (x - x0f * W) / max((x1f - x0f) * W, 1)
            fade = math.sin(math.pi * t) ** 0.9
            y = base_yf * H + amp_yf * H * math.sin(2 * math.pi * freq * t + 0.4)
            hh = (2 + hmax * abs(math.sin(2 * math.pi * freq * 1.6 * t + i * 0.5))) * fade
            bd.rounded_rectangle([x, y - hh / 2, x + bw, y + hh / 2], radius=bw / 2,
                                 fill=p["contour"] + (int(alpha * fade * s),))
            x += gap
            i += 1

    bar_run(0.035, 0.545, 0.888, 0.020, 1.05, 24, 36, 13, 4)
    bar_run(0.070, 0.300, 0.932, 0.010, 1.55, 12, 18, 15, 3)
    img = Image.alpha_composite(img, bars.filter(ImageFilter.GaussianBlur(0.5)))

    # 8. corner-faded dot grid --------------------------------------------
    dots = Image.new("L", (W, H), 0)
    dd = ImageDraw.Draw(dots)
    step = 34
    for y in range(36, H, step):
        for x in range(26, W, step):
            dd.ellipse([x, y, x + 1.2, y + 1.2], fill=255)
    dots = dots.filter(ImageFilter.GaussianBlur(0.9))

    sw, sh = W // 8, H // 8
    fs = Image.new("L", (sw, sh), 0)
    fp = fs.load()
    for y in range(sh):
        for x in range(sw):
            nx = (x / sw - 0.5) * 2.0
            ny = (y / sh - 0.5) * 2.0
            d = math.hypot(nx * 0.86, ny)
            fp[x, y] = int(255 * (max(0.0, min(1.0, (d - 0.34) / 0.52)) ** 1.5))
    fade = fs.filter(ImageFilter.GaussianBlur(9)).resize((W, H), Image.BICUBIC)
    dots = ImageChops.multiply(dots, fade)
    img = Image.alpha_composite(img, paint(dots, p["blue_grey"], alpha=0.13 * s))

    # 9. vignette + readability shading ------------------------------------
    vig = Image.new("L", (W, H), 0)
    vd = ImageDraw.Draw(vig)
    cx, cy = W / 2, H / 2
    for i in range(150):
        t = i / 149
        a = int(130 * (t ** 2.3))
        rx = W * 0.86 * (1 - 0.60 * t)
        ry = H * 0.82 * (1 - 0.60 * t)
        vd.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], outline=a, width=12)
    for i in range(0, 120):
        t = i / 119
        a = int(90 * (1 - t) ** 1.6)
        vd.line([(0, i), (i, 0)], fill=a, width=3)
        vd.line([(W - i, H), (W, H - i)], fill=a, width=3)
    vig = vig.filter(ImageFilter.GaussianBlur(110))
    img = Image.alpha_composite(img, paint(vig, p["vignette"], alpha=p["vignette_alpha"]))

    top = Image.new("L", (W, H), 0)
    td = ImageDraw.Draw(top)
    for y in range(int(H * 0.26)):
        td.line([(0, y), (W, y)], fill=int(140 * (1 - y / (H * 0.26)) ** 2.1))
    top = top.filter(ImageFilter.GaussianBlur(50))
    img = Image.alpha_composite(img, paint(top, p["shade"], alpha=p["shade_alpha"]))

    bot = Image.new("L", (W, H), 0)
    bd3 = ImageDraw.Draw(bot)
    for y in range(int(H * 0.78), H):
        t = max(0.0, min(1.0, (y - H * 0.78) / (H * 0.22)))
        bd3.line([(0, y), (W, y)], fill=int(150 * (t ** 1.8)))
    bot = bot.filter(ImageFilter.GaussianBlur(50))
    img = Image.alpha_composite(img, paint(bot, p["shade"], alpha=p["shade_alpha"]))

    # 10. fine grain -------------------------------------------------------
    noise = Image.effect_noise((W, H), 10).convert("L")
    noise = ImageChops.blend(Image.new("L", (W, H), 128), noise, 0.5)
    grain = ImageChops.overlay(img.convert("RGB"), Image.merge("RGB", (noise, noise, noise)))
    img = Image.blend(img.convert("RGB"), grain, 0.14).convert("RGBA")
    return img.convert("RGB")


def emit(mode, p):
    out = build(p)
    base = f"{OUT_DIR}\\p-meeting-background{p['suffix']}"
    out.save(base + "-1440x2560.png", optimize=True)
    out.save(base + "-1440x2560.webp", quality=94, method=6)
    out.resize((1080, 1920), Image.LANCZOS).save(
        base + "-1080x1920.jpg", quality=92, optimize=True, progressive=True
    )
    print(mode, "->", base + "-1440x2560.png")


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "both"
    if which in ("dark", "both"):
        emit("dark", DARK)
    if which in ("light", "both"):
        emit("light", LIGHT)
