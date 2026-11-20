#!/usr/bin/env python3
"""连接动画 sprite sheet 生成器（2026-11-20）。

把外部 GIF 处理成应用内「正在连接」动画的 sprite sheet：
  1. 键控：按 **饱和度×明度（sv）** 黑场扣底——黑背景与暗晕圈（黑烟观感的来源）
     全透明化，只保留鲜艳的能量主体；
  2. 换色：主体色按归一化亮度映射到目标色（默认应用文字蓝 #0A84FF）；
  3. 拼帧：30 帧（实际帧数自适应）拼成 6 列网格的 WebP（带透明）入 assets，
     运行时逐帧画格子。

用法：
    python3 tools/make_connecting_anim.py                 # 默认参数
    python3 tools/make_connecting_anim.py --black-point 0.2 --gamma 0.6
    python3 tools/make_connecting_anim.py --tint 0,132,255 --quality 90

参数说明：
    --black-point  sv 黑场阈值（0~1）：低于此值的像素全透明。调大→抠得更狠
                   （黑烟更少、主体更细）；调小→保留更多辉光过渡（更饱满）。
    --gamma        中段亮度曲线（>1 变暗、<1 变亮）。
    --tint         主体色 R,G,B（逗号分隔）。
    --quality      WebP 有损质量（0~100）。

生成后重新构建安装即可看到效果（assets 打进 APK）。
"""
import argparse
import colorsys
import os
import sys

from PIL import Image, ImageSequence

SRC_DEFAULT = os.path.expanduser('~/Downloads/16ccead5864746f792a2b673403f1842.gif')
DST_DEFAULT = 'app/src/main/assets/connecting_anim_sheet.webp'
COLS = 6


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--src', default=SRC_DEFAULT, help='源 GIF 路径')
    ap.add_argument('--dst', default=DST_DEFAULT, help='输出 sprite sheet 路径（写入 assets）')
    ap.add_argument('--black-point', type=float, default=0.12, help='sv 黑场阈值（0~1，默认 0.12）')
    ap.add_argument('--gamma', type=float, default=0.7, help='中段亮度曲线（默认 0.7）')
    ap.add_argument('--tint', default='10,132,255', help='主体色 R,G,B（默认 10,132,255 = #0A84FF）')
    ap.add_argument('--quality', type=int, default=90, help='WebP 质量（默认 90）')
    ap.add_argument('--preview', default='connecting_anim_preview.png',
                    help='暗底合成预览图输出路径（空=不输出）')
    args = ap.parse_args()

    tint = tuple(int(v) for v in args.tint.split(','))
    if len(tint) != 3 or any(v < 0 or v > 255 for v in tint):
        print('✗ --tint 格式应为 R,G,B（各 0~255）')
        return 1

    im = Image.open(args.src)
    n = getattr(im, 'n_frames', 1)
    w, h = im.size
    rows = (n + COLS - 1) // COLS
    sheet = Image.new('RGBA', (COLS * w, rows * h), (0, 0, 0, 0))

    def keyed(rgba: Image.Image) -> Image.Image:
        px = rgba.load()
        for y in range(h):
            for x in range(w):
                r, g, b, a = px[x, y]
                _, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
                sv = s * v
                if sv <= args.black_point:
                    px[x, y] = (0, 0, 0, 0)
                else:
                    ln = min(1.0, (sv - args.black_point) / (0.9 - args.black_point)) ** args.gamma
                    px[x, y] = (
                        round(ln * tint[0]),
                        round(ln * tint[1]),
                        round(ln * tint[2]),
                        round(255 * ln),
                    )
        return rgba

    for i, frame in enumerate(ImageSequence.Iterator(im)):
        sheet.paste(keyed(frame.convert('RGBA')), ((i % COLS) * w, (i // COLS) * h))
        print(f'\rframe {i + 1}/{n}', end='')
    print()

    os.makedirs(os.path.dirname(args.dst) or '.', exist_ok=True)
    sheet.save(args.dst, 'WEBP', lossless=False, quality=args.quality, method=6)
    print(f'✓ 已生成 {args.dst}（{sheet.size[0]}x{sheet.size[1]}，{n} 帧，'
          f'{os.path.getsize(args.dst)} bytes）')
    print('  参数：black-point={} gamma={} tint={} quality={}'.format(
        args.black_point, args.gamma, args.tint, args.quality))
    if args.preview:
        dark = Image.new('RGBA', sheet.size, (17, 17, 20, 255))
        dark.alpha_composite(sheet)
        dark.convert('RGB').save(args.preview)
        print(f'✓ 暗底预览图 {args.preview}（调参看这个，别直接看透明 sheet）')
    print('  重新构建安装后生效（assets 打进 APK）。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
