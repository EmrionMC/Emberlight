#!/usr/bin/env python3
"""Checks a built Emberlight PBR pack against the vanilla jar and LabPBR 1.3; prints coverage.

usage: check.py [--jar PATH] [--pack build/emberlight-pbr.zip] [--style rtx|subtle] [--list]
Exit status 1 on any error. --list prints every block texture with its state (tuned / default / MISSING).
"""
import argparse, io, json, sys, zipfile
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import materials
from generate import BLOCKS, REPO, find_jar


def check_maps(name, base, n, s):
    """LabPBR 1.3 checks for one texture; returns a list of problems."""
    errs = []
    for sfx, im in (('_n', n), ('_s', s)):
        if im.mode != 'RGBA':
            errs.append(f'{name}{sfx}: mode {im.mode}, want RGBA')
        if im.size != base.size:
            errs.append(f'{name}{sfx}: size {im.size} != base {base.size}')
    if errs:
        return errs
    a = np.asarray(base.convert('RGBA'))[..., 3] > 25
    nv, sv = np.asarray(n).astype(int), np.asarray(s).astype(int)
    xy = (nv[..., :2] / 255 * 2 - 1)[a]
    if (np.sum(xy * xy, -1) > 1.02).any():
        errs.append(f'{name}_n: normal xy longer than 1 (z cannot be reconstructed)')
    if (nv[..., 3][a] < 1).any():
        errs.append(f'{name}_n: height 0 (LabPBR recommends >= 1)')
    g = sv[..., 1][a]
    if ((g > 237) & (g < 255)).any():
        errs.append(f'{name}_s: F0 values 238..254 (undefined metal ids)')
    if (sv[..., 3][a] == 255).any():
        errs.append(f'{name}_s: emission 255 (means "ignored"; use 0 for none)')
    return errs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--jar', type=Path)
    ap.add_argument('--pack', type=Path, default=REPO / 'build/emberlight-pbr.zip')
    ap.add_argument('--style', choices=sorted(materials.STYLES), default='rtx')
    ap.add_argument('--list', action='store_true')
    a = ap.parse_args()
    errs, rows, emissive, metal = [], [], [], []
    # every literal name in RULES must reach its own rule (an earlier glob such as '*grass*' silently took grass blocks)
    for pats, _ in materials.RULES:
        for pat in pats:
            if not any(c in pat for c in '*?[') and materials.lookup(pat, 'subtle')[1] is not pats:
                errs.append(f'{pat}: rule shadowed by {materials.lookup(pat, "subtle")[1][:3]}')
    with zipfile.ZipFile(a.jar or find_jar()) as jz, zipfile.ZipFile(a.pack) as pz:
        pnames = set(pz.namelist())
        mc = json.loads(pz.read('pack.mcmeta'))['pack']
        fmt = json.loads(jz.read('version.json'))['pack_version']['resource_major']
        lo = mc['min_format'][0] if isinstance(mc['min_format'], list) else mc['min_format']
        hi = mc['max_format'][0] if isinstance(mc['max_format'], list) else mc['max_format']
        if not lo <= fmt <= hi:
            errs.append(f'pack.mcmeta: formats {lo}..{hi} exclude the game format {fmt}')
        if 'pack.png' not in pnames:
            errs.append('pack.png missing')
        # legal guard: nothing but our maps, their mcmeta and the pack files
        for f in pnames:
            if f.endswith('.png') and f != 'pack.png' and not (f.endswith('_n.png') or f.endswith('_s.png')):
                errs.append(f'{f}: not a _n/_s map - vanilla textures must never be shipped')
        for jn in sorted(jz.namelist()):
            if not (jn.startswith(BLOCKS) and jn.endswith('.png')) or '/' in jn[len(BLOCKS):]:
                continue
            name = jn[len(BLOCKS):-4]
            _, rule = materials.lookup(name, a.style)
            stem = jn[:-4]
            if f'{stem}_n.png' not in pnames or f'{stem}_s.png' not in pnames:
                rows.append((name, 'MISSING'))
                errs.append(f'{name}: _n/_s missing')
                continue
            base = Image.open(io.BytesIO(jz.read(jn)))
            n = Image.open(io.BytesIO(pz.read(f'{stem}_n.png')))
            s = Image.open(io.BytesIO(pz.read(f'{stem}_s.png')))
            errs += check_maps(name, base, n, s)
            if jn + '.mcmeta' in jz.namelist() and 'animation' in json.loads(jz.read(jn + '.mcmeta')):
                for sfx in ('_n', '_s'):
                    if f'{stem}{sfx}.png.mcmeta' not in pnames:
                        errs.append(f'{name}{sfx}: animated base texture but no .mcmeta')
            sv = np.asarray(s)
            if sv[..., 3].max() > 0:
                emissive.append(name)
            if a.style == 'rtx':  # the style's promise: mirror concrete and iron, glowing plain terracotta only
                if name.endswith('_concrete') and not ((sv[..., 0] == 255) & (sv[..., 1] == 255)).all():
                    errs.append(f'{name}_s: rtx style wants a perfect tinted mirror (smoothness 255, metal 255)')
                if name == 'iron_block' and not ((sv[..., 0] == 255) & (sv[..., 1] == 237)).all():
                    errs.append(f'{name}_s: rtx style wants an untinted silver mirror (smoothness 255, metal 237)')
                glazed = name.endswith('_glazed_terracotta')
                if not glazed and name.endswith('terracotta') and sv[..., 3].min() < 200:
                    errs.append(f'{name}_s: rtx style wants plain terracotta to glow as a solid neon tube')
                if glazed and sv[..., 3].max() > 0:
                    errs.append(f'{name}_s: glazed terracotta must not glow (its patterns look odd lit)')
            if (sv[..., 1] >= 230).any():
                metal.append(name)
            rows.append((name, 'default' if materials.is_default(rule) else 'tuned'))
    if a.list:
        for name, state in rows:
            print(f'{state:8} {name}')
    count = {k: sum(1 for _, s in rows if s == k) for k in ('tuned', 'default', 'MISSING')}
    print(f'block textures: {len(rows)}  tuned: {count["tuned"]}  default: {count["default"]}  missing: {count["MISSING"]}')
    print(f'emissive: {len(emissive)}  metal: {len(metal)}  rules: {len(materials.RULES)}')
    for e in errs[:50]:
        print('ERROR', e)
    if len(errs) > 50:
        print(f'... {len(errs) - 50} more errors')
    sys.exit(1 if errs else 0)


if __name__ == '__main__':
    main()
