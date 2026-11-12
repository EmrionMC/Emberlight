#!/usr/bin/env python3
"""Side-by-side images and difference metrics for iris-<scene>.png vs emrion-<scene>.png (docs/BENCHMARK.md).

usage: python3 -I compare_scenes.py <iris-dir> <emrion-dir> <out-dir>
Writes <out-dir>/<scene>.png (Iris | Emrion | |diff| x4) and <out-dir>/report.csv; prints the table.
MAE is per channel on 0..255; SSIM is the mean SSIM of the luma over 8x8 windows (1 = identical).
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image


def box_mean(a, k=8):
    # mean over every k x k window via an integral image ('valid' windows only)
    c = np.pad(a, ((1, 0), (1, 0))).cumsum(0).cumsum(1)
    return (c[k:, k:] - c[:-k, k:] - c[k:, :-k] + c[:-k, :-k]) / (k * k)


def ssim(x, y):
    c1, c2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    mx, my = box_mean(x), box_mean(y)
    vx, vy = box_mean(x * x) - mx * mx, box_mean(y * y) - my * my
    cxy = box_mean(x * y) - mx * my
    return float(np.mean(((2 * mx * my + c1) * (2 * cxy + c2)) / ((mx * mx + my * my + c1) * (vx + vy + c2))))


def luma(a):
    return a @ np.array([0.299, 0.587, 0.114])


def compare(a, b):
    """a, b: float64 HxWx3 arrays on 0..255."""
    d = np.abs(a - b)
    return {'mae_r': d[..., 0].mean(), 'mae_g': d[..., 1].mean(), 'mae_b': d[..., 2].mean(), 'ssim': ssim(luma(a), luma(b))}


def main(iris_dir, emrion_dir, out_dir):
    out_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    for ip in sorted(iris_dir.glob('iris-*.png')):
        scene = ip.stem[len('iris-'):]
        ep = emrion_dir / f'emrion-{scene}.png'
        if not ep.exists():
            print(f'{scene}: no {ep.name}, skipped')
            continue
        ia, ea = Image.open(ip).convert('RGB'), Image.open(ep).convert('RGB')
        if ea.size != ia.size:
            print(f'{scene}: size {ea.size} != {ia.size}, Emrion image resized for the metric')
            ea = ea.resize(ia.size, Image.BILINEAR)
        a, b = np.asarray(ia, np.float64), np.asarray(ea, np.float64)
        m = compare(a, b)
        rows.append((scene, m))
        diff = Image.fromarray(np.clip(np.abs(a - b) * 4, 0, 255).astype(np.uint8))
        w, h = ia.size
        sheet = Image.new('RGB', (w * 3, h))
        for i, im in enumerate((ia, ea, diff)):
            sheet.paste(im, (i * w, 0))
        sheet.resize((w * 3 // 2, h // 2), Image.BILINEAR).save(out_dir / f'{scene}.png')
    lines = ['scene,mae_r,mae_g,mae_b,mae_mean,ssim']
    for scene, m in rows:
        mean = (m['mae_r'] + m['mae_g'] + m['mae_b']) / 3
        lines.append(f"{scene},{m['mae_r']:.2f},{m['mae_g']:.2f},{m['mae_b']:.2f},{mean:.2f},{m['ssim']:.4f}")
    (out_dir / 'report.csv').write_text('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    return 0 if rows else 1


def self_check():
    rng = np.random.default_rng(1)
    a = rng.uniform(0, 255, (32, 48, 3))
    m = compare(a, a)
    assert m['mae_r'] == 0 and abs(m['ssim'] - 1) < 1e-9, m
    m = compare(a, np.clip(a + 10, 0, 255))
    assert 5 < m['mae_g'] <= 10 and m['ssim'] < 1, m
    assert compare(a, rng.uniform(0, 255, a.shape))['ssim'] < 0.2


if __name__ == '__main__':
    self_check()
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    sys.exit(main(*map(Path, sys.argv[1:])))
