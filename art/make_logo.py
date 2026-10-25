# Генерация логотипа и иконок ВНосКино.
# Запуск: python3 art/make_logo.py  (требуется Pillow)
# Мастер рисуется в 4x-разрешении и уменьшается — сглаживание без библиотек SVG.
# Стиль: современный минимализм — диагональный градиент, glassmorphism,
# Play-глиф с градиентом и скруглёнными углами, без киноплёнки.
import math
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(ROOT, "art")
os.makedirs(ART, exist_ok=True)

# Палитра: глубокий сине-фиолетовый фон + сине-голубой акцент (без жёлтого)
BG_TOP = (16, 24, 52)        # тёмно-синий
BG_BOTTOM = (34, 20, 74)     # тёмно-фиолетовый
ACCENT_A = (56, 189, 248)    # голубой (cyan-400)
ACCENT_B = (99, 102, 241)    # индиго (indigo-500)
NAVY_TEXT = (13, 17, 23)

SS = 4  # суперсэмплинг


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(size, top, bottom, diagonal=False):
    """Вертикальный или диагональный градиент."""
    w, h = size
    img = Image.new("RGB", size)
    px = img.load()
    for y in range(h):
        for x in range(w):
            if diagonal:
                t = (x / w + y / h) / 2
            else:
                t = y / (h - 1)
            px[x, y] = lerp(top, bottom, t)
    return img


def radial_glow(size, center, radius, color, max_alpha):
    """Мягкое радиальное свечение (отдельный RGBA-слой)."""
    w, h = size
    layer = Image.new("L", size, 0)
    px = layer.load()
    cx, cy = center
    r2 = radius * radius
    for y in range(h):
        dy2 = (y - cy) ** 2
        for x in range(w):
            d2 = (x - cx) ** 2 + dy2
            if d2 < r2:
                t = 1 - math.sqrt(d2) / radius
                px[x, y] = int(max_alpha * t * t)
    rgba = Image.new("RGBA", size, color + (0,))
    rgba.putalpha(layer)
    return rgba


def overlay(img, draw_fn):
    """Рисование с полупрозрачными цветами через отдельный слой.

    ImageDraw пишет RGBA-пиксели напрямую (без смешивания), поэтому любая
    полупрозрачная заливка должна идти через оверлей + alpha_composite.
    """
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(layer))
    img.alpha_composite(layer)


def rounded_triangle(size, pts, radius):
    """Маска треугольника со скруглёнными углами.

    Приём: полигон + линии со скруглёнными стыками (joint='curve').
    Радиус умеренный, чтобы острые вершины не «зарастали».
    """
    mask = Image.new("L", size, 0)
    d = ImageDraw.Draw(mask)
    r = radius
    d.polygon(pts, fill=255)
    for i in range(len(pts)):
        p1, p2 = pts[i], pts[(i + 1) % len(pts)]
        d.line([p1, p2], fill=255, width=r * 2, joint="curve")
    # Круглые «пятачки» на вершинах для ровного скругления
    for p in pts:
        d.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=255)
    return mask


def play_glyph(img, cx, cy, size, grad_a, grad_b):
    """Play-треугольник с градиентной заливкой и скруглёнными углами."""
    S = img.size[0]
    t = size  # половина высоты треугольника
    shift = int(t * 0.18)  # оптический сдвиг вправо
    pts = [(cx - t * 0.75 + shift, cy - t),
           (cx - t * 0.75 + shift, cy + t),
           (cx + t * 1.05 + shift, cy)]
    mask = rounded_triangle(img.size, pts, radius=max(2, int(t * 0.30)))
    grad = gradient(img.size, grad_a, grad_b, diagonal=True).convert("RGBA")
    grad.putalpha(mask)
    img.alpha_composite(grad)


def make_icon(master_px=1024):
    S = master_px * SS
    img = gradient((S, S), BG_TOP, BG_BOTTOM, diagonal=True).convert("RGBA")

    # Два мягких цветных пятна — «аврора» в духе современных лого
    img.alpha_composite(radial_glow((S, S), (int(S * 0.28), int(S * 0.22)),
                                    int(S * 0.60), ACCENT_A, 70))
    img.alpha_composite(radial_glow((S, S), (int(S * 0.80), int(S * 0.85)),
                                    int(S * 0.60), ACCENT_B, 75))

    # Стеклянная подложка (glassmorphism): полупрозрачный скруглённый квадрат
    pad = int(S * 0.10)
    rr = int(S * 0.20)

    def glass(d):
        d.rounded_rectangle([pad, pad, S - pad, S - pad], radius=rr,
                            fill=(255, 255, 255, 20),
                            outline=(255, 255, 255, 55), width=int(S * 0.009))
    overlay(img, glass)

    # Play-глиф в верхней части, крупный
    play_glyph(img, S // 2, int(S * 0.38), int(S * 0.155),
               (255, 255, 255), ACCENT_A)

    # Название под глифом
    font = None
    for path in ("/System/Library/Fonts/Supplemental/Arial Bold.ttf",
                 "/System/Library/Fonts/Helvetica.ttc"):
        if os.path.exists(path):
            try:
                font = ImageFont.truetype(path, int(S * 0.135))
                break
            except OSError:
                pass
    text = "ВНосКино"
    d = ImageDraw.Draw(img)
    max_w = int(S * 0.66)
    bbox = d.textbbox((0, 0), text, font=font)
    if bbox[2] - bbox[0] > max_w:
        k = max_w / (bbox[2] - bbox[0])
        font = ImageFont.truetype(font.path, int(font.size * k))
        bbox = d.textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    tx, ty = (S - tw) // 2, int(S * 0.63) - th // 2 - bbox[1]

    def shadow(d):
        d.text((tx + int(S * 0.006), ty + int(S * 0.006)), text,
               font=font, fill=(0, 0, 0, 150))
    overlay(img, shadow)
    d.text((tx, ty), text, font=font, fill=(255, 255, 255, 255))

    # Градиентная линия-акцент под названием
    line_y = ty + bbox[3] + int(S * 0.045)

    def accent_line(d):
        d.rounded_rectangle([tx, line_y, tx + tw, line_y + int(S * 0.014)],
                            radius=int(S * 0.007), fill=(*ACCENT_A, 235))
    overlay(img, accent_line)

    # Искры-блики для живости
    def sparkles(d):
        for (sx, sy, r, a) in ((int(S * 0.20), int(S * 0.24), int(S * 0.014), 200),
                               (int(S * 0.82), int(S * 0.30), int(S * 0.010), 170),
                               (int(S * 0.76), int(S * 0.20), int(S * 0.007), 150)):
            d.ellipse([sx - r, sy - r, sx + r, sy + r], fill=(255, 255, 255, a))
    overlay(img, sparkles)

    return img.resize((master_px, master_px), Image.LANCZOS)


def make_banner(w=960):
    """ТВ-баннер 16:9: стеклянная плитка с Play + название."""
    h = w * 9 // 16
    S = w * SS
    SH = h * SS
    img = gradient((S, SH), BG_TOP, BG_BOTTOM, diagonal=True).convert("RGBA")
    img.alpha_composite(radial_glow((S, SH), (int(S * 0.16), int(SH * 0.15)),
                                    int(SH * 1.1), ACCENT_A, 50))
    img.alpha_composite(radial_glow((S, SH), (int(S * 0.85), int(SH * 0.95)),
                                    int(SH * 1.0), ACCENT_B, 55))

    # Стеклянная плитка с Play-глифом слева
    pad = int(SH * 0.13)
    rr = int(SH * 0.20)

    def glass(d):
        d.rounded_rectangle([pad, pad, pad + SH - 2 * pad, SH - pad],
                            radius=rr, fill=(255, 255, 255, 20),
                            outline=(255, 255, 255, 50), width=int(SH * 0.012))
    overlay(img, glass)
    ccx, ccy = pad + (SH - 2 * pad) // 2, SH // 2
    play_glyph(img, ccx, ccy, int((SH - 2 * pad) * 0.30),
               (255, 255, 255), ACCENT_A)

    # Название
    font = None
    for path in ("/System/Library/Fonts/Supplemental/Arial Bold.ttf",
                 "/System/Library/Fonts/Helvetica.ttc"):
        if os.path.exists(path):
            try:
                font = ImageFont.truetype(path, int(SH * 0.30))
                break
            except OSError:
                pass
    if font is None:
        raise SystemExit("Не найден системный шрифт с кириллицей")
    text = "ВНосКино"
    tx = pad + (SH - 2 * pad) + int(S * 0.055)
    # Автоподбор: текст должен поместиться от tx до правого края с отступом
    max_w = S - tx - int(S * 0.04)
    bbox = ImageDraw.Draw(img).textbbox((0, 0), text, font=font)
    if bbox[2] - bbox[0] > max_w:
        k = max_w / (bbox[2] - bbox[0])
        font = ImageFont.truetype(font.path, int(font.size * k))
        bbox = ImageDraw.Draw(img).textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    ty = SH // 2 - th // 2 - bbox[1]

    def shadow(d):
        d.text((tx + int(SH * 0.010), ty + int(SH * 0.010)), text,
               font=font, fill=(0, 0, 0, 140))
    overlay(img, shadow)
    ImageDraw.Draw(img).text((tx, ty), text, font=font,
                             fill=(255, 255, 255, 255))
    # Тонкая градиентная линия-акцент под названием
    line_y = ty + bbox[3] + int(SH * 0.055)

    def accent_line(d):
        d.rounded_rectangle([tx, line_y, tx + tw, line_y + int(SH * 0.018)],
                            radius=int(SH * 0.009), fill=(*ACCENT_A, 230))
    overlay(img, accent_line)

    return img.resize((w, h), Image.LANCZOS)


def main():
    icon = make_icon(1024)
    icon.save(os.path.join(ART, "icon_master.png"))

    # Иконки приложения (mipmap)
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for dpi, s in sizes.items():
        dst = os.path.join(ROOT, f"app/src/main/res/mipmap-{dpi}/ic_app.png")
        icon.resize((s, s), Image.LANCZOS).save(dst)
        print("OK", dst, f"{s}x{s}")

    # ТВ-баннеры (drawable-*)
    b = make_banner(960)
    b.save(os.path.join(ART, "banner_master.png"))
    bsizes = {"mdpi": 320, "hdpi": 480, "xhdpi": 640, "xxhdpi": 960}
    for dpi, w in bsizes.items():
        dst = os.path.join(ROOT, f"app/src/main/res/drawable-{dpi}/tv_banner.png")
        b.resize((w, w * 9 // 16), Image.LANCZOS).save(dst)
        print("OK", dst, f"{w}x{w*9//16}")

    print("Готово. Мастера:", ART)


if __name__ == "__main__":
    main()
