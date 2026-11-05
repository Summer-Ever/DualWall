"""把 1024 的方图转成自适应图标所需的分层资源。

要点：
- 源图是「圆角方形 + 黑色四角」，自适应图标需要满幅（mask 由系统施加），
  所以先识别出圆角方形本体，再把四角用渐变延展填满。
- 主体（两个画框 + 上下箭头）缩到约 64dp，兼顾圆形与方圆遮罩。
"""
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

SRC = "docs/icon-source.png"
OUT = "app/src/main/res"
BOOT = ".icon"  # 中间产物，已被 .gitignore 忽略
# 源图圆角方形本体约在 61..963，向内收到 100..924 以避开其边缘的暗色描边与投影
CROP = (120, 120, 904, 904)

CONTENT_DP = 69.0
CANVAS = 1024


def load():
    return Image.open(SRC).convert("RGB")


def content_bbox(im):
    a = np.array(im).astype(int)
    white = a.min(axis=2) > 195
    ys, xs = np.where(white)
    return xs.min(), ys.min(), xs.max(), ys.max()


def plate_mask(im, shrink=9):
    """圆角方形本体（排除四角纯黑）。"""
    a = np.array(im).astype(int)
    m = Image.fromarray(((a.max(axis=2) > 14) * 255).astype(np.uint8))
    return m.filter(ImageFilter.MinFilter(shrink))


def place(im, mask, scale, size):
    x0, y0, x1, y1 = content_bbox(im)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    w = round(im.width * scale)
    new = im.resize((w, w), Image.LANCZOS)
    new_mask = mask.resize((w, w), Image.LANCZOS)
    ox, oy = round(size / 2 - cx * scale), round(size / 2 - cy * scale)

    canvas = Image.new("RGB", (size, size))
    canvas.paste(new, (ox, oy))
    valid = Image.new("L", (size, size), 0)
    valid.paste(new_mask, (ox, oy))
    return canvas, valid


def extend(canvas, valid, rounds=300):
    """未覆盖区按邻域扩散填充，再与本体做柔化过渡。"""
    arr = np.array(canvas).astype(np.float32)
    known = np.array(valid) > 127
    arr[~known] = 0

    for _ in range(rounds):
        if known.all():
            break
        acc = np.zeros_like(arr)
        cnt = np.zeros(arr.shape[:2], np.float32)
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1),
                       (1, 1), (1, -1), (-1, 1), (-1, -1)):
            sv = np.roll(known, (dy, dx), (0, 1))
            sa = np.roll(arr, (dy, dx), (0, 1))
            if dy == 1:
                sv[0, :] = False
            elif dy == -1:
                sv[-1, :] = False
            if dx == 1:
                sv[:, 0] = False
            elif dx == -1:
                sv[:, -1] = False
            acc += sa * sv[..., None]
            cnt += sv
        fill = (~known) & (cnt > 0)
        arr[fill] = acc[fill] / cnt[fill][:, None]
        known |= fill

    filled = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
    filled = filled.filter(ImageFilter.GaussianBlur(34))
    soft = valid.filter(ImageFilter.GaussianBlur(34))
    return Image.composite(canvas, filled, soft)


def fit_background(body, canvas=1024, ox=0, ring=26):
    """用画面边缘一圈像素拟合二次曲面，作为四周的背景延展。
    源图背景本就是平滑渐变，二次曲面足以贴合，且不会把图形反射出去。"""
    arr = np.array(body).astype(np.float64)
    h, w, _ = arr.shape
    ys, xs = np.mgrid[0:h, 0:w]
    edge = (xs < ring) | (xs >= w - ring) | (ys < ring) | (ys >= h - ring)

    def basis(px, py):
        sx = (px / w) * 2 - 1
        sy = (py / h) * 2 - 1
        return np.stack([np.ones_like(sx), sx, sy, sx * sx, sy * sy, sx * sy], axis=1)

    coef, *_ = np.linalg.lstsq(basis(xs[edge], ys[edge]), arr[edge], rcond=None)
    resid = np.abs((basis(xs[edge], ys[edge]) @ coef) - arr[edge]).mean()

    cy, cx = np.mgrid[0:canvas, 0:canvas]
    fitted = (basis((cx - ox).ravel(), (cy - ox).ravel()) @ coef).reshape(canvas, canvas, 3)
    return Image.fromarray(np.clip(fitted, 0, 255).astype(np.uint8)), resid


def build_master():
    im = load().crop(CROP)
    x0, y0, x1, y1 = content_bbox(im)
    scale = (CONTENT_DP / 108.0 * CANVAS) / (x1 - x0)
    w = round(im.width * scale)
    body = im.resize((w, w), Image.LANCZOS)

    ox = (CANVAS - w) // 2
    bg, resid = fit_background(body, CANVAS, ox)
    print(f"背景拟合残差 {resid:.1f}/255")

    canvas = bg.copy()
    canvas.paste(body, (ox, ox))

    # 交界处羽化，避免残差造成的细边
    mask = Image.new("L", (CANVAS, CANVAS), 0)
    ImageDraw.Draw(mask).rectangle([ox + 6, ox + 6, ox + w - 7, ox + w - 7], fill=255)
    soft = mask.filter(ImageFilter.GaussianBlur(30))
    full = Image.composite(canvas, bg, soft)
    full.save(f"{BOOT}/master_108.png")
    return full


def rounded_mask(n, radius_ratio):
    m = Image.new("L", (n * 4, n * 4), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, n * 4 - 1, n * 4 - 1],
                                        radius=int(n * 4 * radius_ratio), fill=255)
    return m.resize((n, n), Image.LANCZOS)


def circle_mask(n):
    m = Image.new("L", (n * 4, n * 4), 0)
    ImageDraw.Draw(m).ellipse([0, 0, n * 4 - 1, n * 4 - 1], fill=255)
    return m.resize((n, n), Image.LANCZOS)


def export(full):
    dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
    for name, f in dens.items():
        d = f"{OUT}/mipmap-{name}"
        os.makedirs(d, exist_ok=True)

        fg = round(108 * f)
        full.resize((fg, fg), Image.LANCZOS).save(f"{d}/ic_launcher_foreground.png", optimize=True)

        legacy = round(48 * f)
        icon = full.resize((legacy, legacy), Image.LANCZOS)
        icon.putalpha(rounded_mask(legacy, 0.234))
        icon.save(f"{d}/ic_launcher.png", optimize=True)

        rnd = full.resize((legacy, legacy), Image.LANCZOS)
        rnd.putalpha(circle_mask(legacy))
        rnd.save(f"{d}/ic_launcher_round.png", optimize=True)

    full.resize((512, 512), Image.LANCZOS).save(f"{BOOT}/icon_512.png", optimize=True)


def preview(full):
    bg = (243, 244, 246)
    tiles = []
    for radius in (None, 0.30, 0.234):
        im = full.resize((256, 256), Image.LANCZOS)
        m = Image.new("L", (1024, 1024), 0)
        d = ImageDraw.Draw(m)
        if radius is None:
            d.ellipse([0, 0, 1023, 1023], fill=255)
        else:
            d.rounded_rectangle([0, 0, 1023, 1023], radius=int(1024 * radius), fill=255)
        im.putalpha(m.resize((256, 256), Image.LANCZOS))
        t = Image.new("RGBA", (256, 256), bg + (255,))
        t.paste(im, (0, 0), im)
        tiles.append(t)
    tiles.append(full.resize((192, 192), Image.LANCZOS).convert("RGBA"))
    W = 12 + sum(t.width + 12 for t in tiles)
    sheet = Image.new("RGBA", (W, 280), bg + (255,))
    x = 12
    for t in tiles:
        sheet.paste(t, (x, 24), t)
        x += t.width + 12
    sheet.save(f"{BOOT}/preview.png")


if __name__ == "__main__":
    master = build_master()
    export(master)
    preview(master)
    print("done")
