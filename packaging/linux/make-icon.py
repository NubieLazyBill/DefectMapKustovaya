"""Иконка DefectMap для Linux-пакетов (.deb / .rpm / AppImage).

Рисуется программно, чтобы в репозитории не хранить бинарники без источника.
Запуск:  python packaging/linux/make-icon.py
"""

from PIL import Image, ImageDraw

SIZE = 256
BG_TOP = (21, 101, 192)      # #1565C0
BG_BOTTOM = (13, 71, 161)    # #0D47A1
GRID = (255, 255, 255, 38)
PIN = (229, 57, 53)          # #E53935
PIN_DARK = (183, 28, 28)
MARK = (255, 193, 7)         # #FFC107
WHITE = (255, 255, 255)


def gradient(size):
    img = Image.new("RGB", (size, size), BG_TOP)
    draw = ImageDraw.Draw(img)
    for y in range(size):
        t = y / (size - 1)
        color = tuple(int(BG_TOP[i] * (1 - t) + BG_BOTTOM[i] * t) for i in range(3))
        draw.line([(0, y), (size, y)], fill=color)
    return img


def rounded_mask(size, radius, margin):
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle(
        [margin, margin, size - margin, size - margin], radius=radius, fill=255
    )
    return mask


def main():
    base = gradient(SIZE)

    # Технический «планшет» — линии схемы
    overlay = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(overlay)
    for i in range(1, 6):
        y = int(SIZE * i / 6)
        d.line([(20, y), (SIZE - 20, y)], fill=GRID, width=2)
    for i in range(1, 6):
        x = int(SIZE * i / 6)
        d.line([(x, 20), (x, SIZE - 20)], fill=GRID, width=2)
    base = Image.alpha_composite(base.convert("RGBA"), overlay)

    draw = ImageDraw.Draw(base)

    # Маркер-«пин» дефекта
    cx, cy, r = 128, 108, 44
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=PIN)
    draw.ellipse([cx - r + 12, cy - r + 12, cx + r - 12, cy + r - 12], fill=WHITE)
    draw.ellipse([cx - 12, cy - 12, cx + 12, cy + 12], fill=PIN_DARK)
    # «Ножка» пина
    draw.polygon([(cx - 22, cy + 34), (cx + 22, cy + 34), (cx, cy + 92)], fill=PIN)

    # Точки оборудования на схеме
    for (x, y, color, radius) in [
        (58, 190, MARK, 11),
        (196, 176, WHITE, 9),
        (175, 60, WHITE, 8),
        (60, 56, MARK, 8),
    ]:
        draw.ellipse([x - radius, y - radius, x + radius, y + radius], fill=color)

    # Скруглённые углы и прозрачный фон
    mask = rounded_mask(SIZE, radius=46, margin=6)
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    out.paste(base, (0, 0), mask)

    out.save("packaging/linux/defectmap.png", "PNG")
    print("packaging/linux/defectmap.png:", out.size)


if __name__ == "__main__":
    main()
