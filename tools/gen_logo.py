#!/usr/bin/env python3
"""
Hytale-style plugin logo generator (the same one used for the AeroWars logo).

Renders a plugin name as a chunky 3D "title" (Minecraft-title vibe) with a bevel/extrude, a colored top
face (rainbow / vertical gradient / solid), a dark outline and a drop shadow, on a transparent background.
Optional subtitle underneath. With --square it renders a CurseForge-style rounded avatar instead.

Usage:
  # AntiXray wordmark banner (docs hero + README)
  python gen_logo.py --text "AntiXray" --out logo.png --style gradient \
      --top 35E0D0 --bottom 6B2BEB --subtitle "X-ray Protection for Hytale"

  # AntiXray square icon (nav logo / favicon source)
  python gen_logo.py --text "AntiXray" --out icon.png --square --sqsize 320 \
      --style gradient --top 35E0D0 --bottom 6B2BEB

Options:
  --text       title text (required)
  --out        output PNG path (required)
  --style      rainbow | gradient | solid            (default: gradient)
  --top        top gradient / solid colour (hex)     (default: FFD24D)
  --bottom     bottom gradient colour (hex)          (default: E8892B)
  --subtitle   optional subtitle line (banner only)
  --size       title font size in px                 (default: 220)
  --font       path to a .ttf/.otf font              (default: SourceCodePro-Black)
  --square     render a rounded square avatar instead of a banner
  --sqsize     square avatar size in px              (default: 320)
"""
import argparse
import colorsys
import re
from PIL import Image, ImageDraw, ImageFont

DEFAULT_FONT = "/usr/share/fonts/adobe-source-code-pro-fonts/SourceCodePro-Black.otf"


def hex2rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def shade(rgb, f):
    """f<1 darkens, f>1 lightens (clamped)."""
    return tuple(max(0, min(255, round(c * f))) for c in rgb)


def gradient_img(w, h, top, bottom):
    img = Image.new("RGBA", (w, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        r = round(top[0] * (1 - t) + bottom[0] * t)
        g = round(top[1] * (1 - t) + bottom[1] * t)
        b = round(top[2] * (1 - t) + bottom[2] * t)
        for x in range(w):
            px[x, y] = (r, g, b, 255)
    return img


def rainbow_img(w, h):
    img = Image.new("RGBA", (w, h))
    px = img.load()
    for x in range(w):
        hue = (x / max(1, w - 1)) * 0.83  # red -> violet
        r, g, b = colorsys.hsv_to_rgb(hue, 0.95, 1.0)
        col = (round(r * 255), round(g * 255), round(b * 255), 255)
        for y in range(h):
            px[x, y] = col
    return img


def render(text, font_path, size, style, top, bottom, subtitle):
    font = ImageFont.truetype(font_path, size)
    outline = max(3, size // 26)
    extrude = max(4, size // 14)
    sub_size = max(18, size // 6)
    sub_font = ImageFont.truetype(font_path, sub_size) if subtitle else None

    tmp = ImageDraw.Draw(Image.new("RGBA", (4, 4)))
    tb = tmp.textbbox((0, 0), text, font=font, stroke_width=outline)
    tw, th = tb[2] - tb[0], tb[3] - tb[1]
    sub_h = 0
    if subtitle:
        sb = tmp.textbbox((0, 0), subtitle, font=sub_font, stroke_width=max(2, outline // 2))
        sub_w, sub_h = sb[2] - sb[0], sb[3] - sb[1]
        sub_gap = size // 8

    pad = outline + extrude + size // 8
    w = tw + pad * 2
    h = th + pad * 2 + (sub_h + (size // 8) if subtitle else 0)
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    ox, oy = pad - tb[0], pad - tb[1]

    # soft drop shadow
    sh = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(sh).text((ox + extrude + 3, oy + extrude + 6), text, font=font, fill=(0, 0, 0, 150),
                            stroke_width=outline, stroke_fill=(0, 0, 0, 150))
    from PIL import ImageFilter
    img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(size // 40 + 1)))

    # 3D extrude (dark side), back-to-front. Neutral dark for rainbow; shaded colour otherwise.
    side = (46, 40, 54) if style == "rainbow" else shade(bottom if style != "solid" else top, 0.42)
    for i in range(extrude, 0, -1):
        draw.text((ox + i, oy + i), text, font=font, fill=(*side, 255))

    # Top face = a dark OUTLINED base glyph, then the coloured fill painted INSIDE it (so the outline ring
    # survives). Drawing a transparent-fill outline pass would erase the fill (PIL replaces on RGBA).
    stroke_col = (30, 26, 38) if style == "rainbow" else shade(bottom if style != "solid" else top, 0.30)
    draw.text((ox, oy), text, font=font, fill=(*stroke_col, 255),
              stroke_width=outline, stroke_fill=(*stroke_col, 255))
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).text((ox, oy), text, font=font, fill=255)  # glyph interior only (no stroke)
    if style == "rainbow":
        fill = rainbow_img(w, h)
    elif style == "gradient":
        fill = gradient_img(w, h, top, bottom)
    else:
        fill = Image.new("RGBA", (w, h), (*top, 255))
    img.paste(fill, (0, 0), mask)

    # top highlight sweep for a glassy game-logo sheen
    hl = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    hd = ImageDraw.Draw(hl)
    hd.text((ox, oy), text, font=font, fill=(255, 255, 255, 70))
    hlmask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(hlmask).rectangle([0, 0, w, oy + th // 3], fill=255)
    img.alpha_composite(Image.composite(hl, Image.new("RGBA", (w, h), (0, 0, 0, 0)), hlmask))

    # subtitle
    if subtitle:
        sy = oy + th + sub_gap
        sx = (w - sub_w) // 2 - sb[0]
        draw.text((sx, sy), subtitle, font=sub_font, fill=(235, 238, 245, 255),
                  stroke_width=max(2, outline // 2), stroke_fill=(20, 24, 32, 255))

    return img.crop(img.getbbox())


def split_lines(text):
    """Split a name into up to two stacked lines (by spaces, else camelCase)."""
    if " " in text:
        parts = text.split()
    else:
        parts = re.findall(r"[A-Z][a-z0-9]*|[a-z0-9]+", text) or [text]
    if len(parts) <= 1:
        return [text]
    if len(parts) == 2:
        return parts
    mid = (len(parts) + 1) // 2
    return ["".join(parts[:mid]), "".join(parts[mid:])]


def render_square(text, font_path, size, style, top, bottom):
    """A CurseForge-style square avatar: rounded badge, gradient/rainbow fill, stacked 3D name."""
    s = size
    margin = max(3, s // 20)
    radius = s // 5
    inner = s - margin * 2
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # rounded background
    bg = rainbow_img(s, s) if style == "rainbow" else \
        gradient_img(s, s, top, bottom) if style == "gradient" else Image.new("RGBA", (s, s), (*top, 255))
    rr = Image.new("L", (s, s), 0)
    ImageDraw.Draw(rr).rounded_rectangle([margin, margin, s - margin, s - margin], radius=radius, fill=255)
    img.paste(bg, (0, 0), rr)
    # subtle top sheen
    sheen = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    ImageDraw.Draw(sheen).rounded_rectangle([margin, margin, s - margin, margin + inner // 2],
                                            radius=radius, fill=(255, 255, 255, 38))
    img.alpha_composite(Image.composite(sheen, Image.new("RGBA", (s, s), (0, 0, 0, 0)), rr))
    # dark border
    draw.rounded_rectangle([margin, margin, s - margin, s - margin], radius=radius,
                           outline=(22, 24, 32, 255), width=max(3, s // 40))

    lines = split_lines(text)
    outline = max(2, s // 48)
    avail_w = inner - s // 8
    avail_h = inner - s // 8
    # fit the largest font where every line fits width and the stack fits height
    fs = s
    while fs > 8:
        f = ImageFont.truetype(font_path, fs)
        widths, total_h = [], 0
        for ln in lines:
            b = draw.textbbox((0, 0), ln, font=f, stroke_width=outline)
            widths.append(b[2] - b[0])
            total_h += (b[3] - b[1])
        gap = fs // 8
        total_h += gap * (len(lines) - 1)
        if max(widths) <= avail_w and total_h <= avail_h:
            break
        fs -= 2
    font = ImageFont.truetype(font_path, fs)

    # measure to vertically center the stack
    heights, boxes = [], []
    for ln in lines:
        b = draw.textbbox((0, 0), ln, font=font, stroke_width=outline)
        boxes.append(b)
        heights.append(b[3] - b[1])
    gap = fs // 8
    block_h = sum(heights) + gap * (len(lines) - 1)
    y = (s - block_h) // 2
    for ln, b, hh in zip(lines, boxes, heights):
        lw = b[2] - b[0]
        x = (s - lw) // 2 - b[0]
        draw.text((x, y - b[1]), ln, font=font, fill=(255, 255, 255, 255),
                  stroke_width=outline, stroke_fill=(20, 22, 30, 255))
        y += hh + gap
    return img


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--text", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--style", default="gradient", choices=["rainbow", "gradient", "solid"])
    ap.add_argument("--top", default="FFD24D")
    ap.add_argument("--bottom", default="E8892B")
    ap.add_argument("--subtitle", default=None)
    ap.add_argument("--size", type=int, default=220)
    ap.add_argument("--font", default=DEFAULT_FONT)
    ap.add_argument("--square", action="store_true")
    ap.add_argument("--sqsize", type=int, default=320)
    a = ap.parse_args()
    if a.square:
        img = render_square(a.text, a.font, a.sqsize, a.style, hex2rgb(a.top), hex2rgb(a.bottom))
    else:
        img = render(a.text, a.font, a.size, a.style, hex2rgb(a.top), hex2rgb(a.bottom), a.subtitle)
    img.save(a.out)
    print(f"wrote {a.out}  ({img.width}x{img.height})")


if __name__ == "__main__":
    main()
