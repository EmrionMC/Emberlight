#!/usr/bin/env python3
"""RT-1 geometry check (docs/RAYTRACING.md): raster shot | traced debug-view shot | raster with the traced edges in red.

usage: python3 -I rt_overlay.py <raster-dir> <traced-dir> <out-dir>
Pairs <scene>.png by name (emrion-<scene>.png in both). Traced edges = pixels whose face-normal colour differs from the
right or lower neighbour, so every block edge of the traced image lands on the raster image where its block is.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image


def main(raster_dir, traced_dir, out_dir):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    for traced_path in sorted(Path(traced_dir).glob("*.png")):
        raster_path = Path(raster_dir) / traced_path.name
        if not raster_path.exists():
            continue
        r = np.asarray(Image.open(raster_path).convert("RGB")).astype(np.int16)
        t = np.asarray(Image.open(traced_path).convert("RGB")).astype(np.int16)
        edge = np.zeros(t.shape[:2], bool)
        edge[:, :-1] |= np.abs(t[:, 1:] - t[:, :-1]).sum(2) > 24
        edge[:-1, :] |= np.abs(t[1:, :] - t[:-1, :]).sum(2) > 24
        overlay = r.copy()
        overlay[edge] = (255, 0, 0)
        row = np.concatenate([r, t, overlay], axis=1).astype(np.uint8)
        img = Image.fromarray(row)
        img = img.resize((img.width // 2, img.height // 2), Image.LANCZOS)
        img.save(out / traced_path.name)
        Image.fromarray(overlay.astype(np.uint8)).save(out / ("overlay-" + traced_path.name))
        print(out / traced_path.name)


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
