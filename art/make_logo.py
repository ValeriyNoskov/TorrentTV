# Генерация логотипа и иконок ВНосКино.
# Запуск: python3 art/make_logo.py  (требуется Pillow)
# Мастер рисуется в 4x-разрешении и уменьшается — сглаживание без библиотек SVG.
import math
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(ROOT, "art")
os.makedirs(ART, exist_ok=True)

# Фирменные цвета (взяты из текущей темы приложения: тёмно-синий + белый/стальной)
# Жёлтый убран: акцент — белый со стальным отливом
AMBER = (188, 204, 232)          # стальной акцент (перфорация, низ кнопки)
AMBER_LIGHT = (255, 255, 255)    # верх кнопки Play
NAVY_TOP = (30, 40, 94)
NAVY_BOTTOM = (10, 14, 36)
NAVY_TEXT = (13, 17, 23)

SS = 4  # суперсэмплинг


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient_bg(size, top, bottom):
    """Вертикальный градиент фона."""
    w, h = size
    img = Image.new("RGB", size)
    px = img.load()
    for y in range(h):
        c = lerp(top, bottom, y / (h - 1))
        for x in range(w):
            px[x, y] = c
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


def rounded_polygon(draw, pts, radius, fill):
    """Многоугольник со скруглёнными углами (для кнопки Play)."""
    # Рисуем обычный полигон + «утолщённые» линии со стыками — углы скругляются.
    draw.polygon(pts, fill=fill)
    for i in range(len(pts)):
        p1, p2 = pts[i], pts[(i + 1) % len(pts)]
        draw.line([p1, p2], fill=fill, width=radius * 2, joint="curve")


def draw_sprockets(draw, x0, y0, x1, y1, n, hole, color, pad):
    """Перфорация киноплёнки: ряд отверстий между (x0,y0)-(x1,y1)."""
    for i in range(n):
        cy = y0 + (y1 - y0) * (i + 0.5) / n
        draw.rounded_rectangle(
            [x0, cy - hole / 2, x1, cy + hole / 2],
            radius=hole * 0.3, fill=color,
        )


def make_icon(master_px=1024):
    S = master_px * SS
    img = gradient_bg((S, S), NAVY_TOP, NAVY_BOTTOM).convert("RGBA")

    # Свечение за кнопкой Play
    glow = radial_glow((S, S), (S // 2, S // 2), S * 0.34, AMBER, 90)
    img.alpha_composite(glow)

    d = ImageDraw.Draw(img)

    # Киноплёнка: две вертикальные полосы с перфорацией по краям
    strip_w = int(S * 0.06)
    for x0 in (int(S * 0.06), S - int(S * 0.06) - strip_w):
        def strips(d, x0=x0):
            d.rounded_rectangle(
                [x0, int(S * 0.12), x0 + strip_w, int(S * 0.88)],
                radius=strip_w // 3, fill=(255, 255, 255, 45),
            )
            holes = 6
            hole_h = int(S * 0.024)
            for i in range(holes):
                cy = int(S * 0.12) + int(S * 0.76) * (i + 0.5) // holes
                d.rounded_rectangle(
                    [x0 + strip_w // 4, cy - hole_h // 2,
                     x0 + strip_w - strip_w // 4, cy + hole_h // 2],
                    radius=hole_h // 3, fill=(*AMBER, 170),
                )
        overlay(img, strips)

    # Кнопка Play: круг с градиентом янтаря
    cx, cy, R = S // 2, S // 2, int(S * 0.30)
    circle = Image.new("L", (S, S), 0)
    ImageDraw.Draw(circle).ellipse([cx - R, cy - R, cx + R, cy + R], fill=255)
    grad = gradient_bg((S, S), AMBER_LIGHT, AMBER).convert("RGBA")
    grad.putalpha(circle)
    img.alpha_composite(grad)

    # Треугольник Play (тёмно-синий, со сдвигом вправо для баланса);
    # скругление не нужно — суперсэмплинг сам сглаживает углы.
    tri = int(R * 0.46)
    shift = int(R * 0.12)
    d.polygon([(cx - tri * 0.7 + shift, cy - tri),
               (cx - tri * 0.7 + shift, cy + tri),
               (cx + tri * 1.0 + shift, cy)], fill=NAVY_BOTTOM + (255,))

    return img.resize((master_px, master_px), Image.LANCZOS)


def make_banner(w=960):
    """ТВ-баннер 16:9: иконка + название (баннер для лаунчера Android TV)."""
    h = w * 9 // 16
    S = w * SS
    SH = h * SS
    img = gradient_bg((S, SH), NAVY_TOP, NAVY_BOTTOM).convert("RGBA")
    img.alpha_composite(
        radial_glow((S, SH), (int(S * 0.22), SH // 2), int(SH * 0.75), AMBER, 70)
    )
    d = ImageDraw.Draw(img)

    # Перфорация киноплёнки верхней и нижней кромкой
    def film_bands(d):
        for y0, y1 in ((int(SH * 0.06), int(SH * 0.115)),
                       (int(SH * 0.885), int(SH * 0.94))):
            d.rectangle([0, y0, S, y1], fill=(255, 255, 255, 40))
            n = max(6, S // int(SH * 0.22))
            hw = int(SH * 0.018)
            for i in range(n):
                x = int(S * (i + 0.5) / n)
                d.rounded_rectangle(
                    [x - hw, y0 + (y1 - y0) // 4, x + hw, y1 - (y1 - y0) // 4],
                    radius=hw // 2, fill=(*AMBER, 200),
                )
    overlay(img, film_bands)

    # Мини-кнопка Play слева
    cx, cy, R = int(S * 0.17), SH // 2, int(SH * 0.30)
    circle = Image.new("L", (S, SH), 0)
    ImageDraw.Draw(circle).ellipse([cx - R, cy - R, cx + R, cy + R], fill=255)
    grad = gradient_bg((S, SH), AMBER_LIGHT, AMBER).convert("RGBA")
    grad.putalpha(circle)
    img.alpha_composite(grad)
    tri = int(R * 0.44)
    shift = int(R * 0.12)
    d.polygon([(cx - tri * 0.7 + shift, cy - tri),
               (cx - tri * 0.7 + shift, cy + tri),
               (cx + tri * 1.0 + shift, cy)], fill=NAVY_BOTTOM + (255,))

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
    # Автоподбор размера: текст должен занимать не более 62% ширины баннера
    max_w = int(S * 0.62)
    bbox = d.textbbox((0, 0), text, font=font)
    if bbox[2] - bbox[0] > max_w:
        k = max_w / (bbox[2] - bbox[0])
        font = ImageFont.truetype(font.path, int(font.size * k))
        bbox = d.textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    tx, ty = int(S * 0.33), cy - th // 2 - bbox[1]
    # Лёгкая тень для читаемости (через оверлей — честный полупрозрачный чёрный)
    def shadow(d):
        d.text((tx + int(SH * 0.012), ty + int(SH * 0.012)), text,
               font=font, fill=(0, 0, 0, 140))
    overlay(img, shadow)
    d.text((tx, ty), text, font=font, fill=(255, 255, 255, 255))

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

    # Логотип для README (иконка на прозрачном фоне не нужна — квадрат подходит)
    print("Готово. Мастера:", ART)


if __name__ == "__main__":
    main()
