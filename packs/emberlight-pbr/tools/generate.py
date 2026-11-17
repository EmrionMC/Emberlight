#!/usr/bin/env python3
"""Builds the Emberlight PBR resource pack: LabPBR 1.3 _n/_s maps derived from the local vanilla client jar.

Reads assets/minecraft/textures/block/*.png from the Minecraft client jar on this machine (Loom's cache by default),
writes ONLY our own _n.png/_s.png maps (+ their animation .mcmeta), pack.mcmeta and our generated pack.png to
build/emberlight-pbr/ and zips it to build/emberlight-pbr.zip. No vanilla texture is ever copied into the output.

usage: generate.py [--jar PATH] [--out DIR] [--style rtx|subtle]
"""
import argparse, io, json, os, re, sys, zipfile
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import materials

PACK = Path(__file__).resolve().parent.parent
REPO = PACK.parent.parent
BLOCKS = 'assets/minecraft/textures/block/'


def find_jar():
    ver = re.search(r'^minecraft_version=(.+)$', (REPO / 'gradle.properties').read_text(), re.M).group(1).strip()
    jar = Path.home() / f'.gradle/caches/fabric-loom/{ver}/minecraft-client.jar'
    if not jar.is_file():
        sys.exit(f'{jar} missing: run any Gradle task once (Loom downloads it) or pass --jar')
    return jar


def smoothstep(a, b, x):
    t = np.clip((x - a) / max(b - a, 1e-6), 0, 1)
    return t * t * (3 - 2 * t)


def blur(a):
    """3x3 binomial blur, wrapping (block textures tile)."""
    k = [(-1, .25), (0, .5), (1, .25)]
    a = sum(w * np.roll(a, d, 0) for d, w in k)
    return sum(w * np.roll(a, d, 1) for d, w in k)


def frames(rgba, meta):
    """Split an animation strip into frames (each tiles on its own); one frame for still textures."""
    h, w = rgba.shape[:2]
    anim = (meta or {}).get('animation')
    if anim is None:
        return [rgba]
    fw = anim.get('width', min(w, h))
    fh = anim.get('height', fw if 'width' in anim else min(w, h))
    return [rgba[y:y + fh, x:x + fw] for y in range(0, h - fh + 1, fh) for x in range(0, w - fw + 1, fw)]


def hsv(rgb):
    mx, mn = rgb.max(-1), rgb.min(-1)
    sat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0)
    return mx, sat


def maps(rgba, p, host=None):
    """One frame (float RGBA 0..1) -> (_n, _s) uint8 RGBA arrays."""
    rgb, alpha = rgba[..., :3], rgba[..., 3]
    solid = alpha > 0.1
    lum = rgb @ np.array([0.2126, 0.7152, 0.0722])
    mean = lum[solid].mean() if solid.any() else 0.5
    lum = np.where(solid, lum, mean)
    value, sat = hsv(rgb)
    # height: blurred luminance, normalised per frame (bright = raised); 'sat' for bricks whose mortar is lighter
    # than the bricks (grey mortar = low saturation = low), so the mortar lines sink instead of standing out
    h = blur(np.where(solid, sat, sat[solid].mean() if solid.any() else 0) if p['hsrc'] == 'sat' else lum)
    lo, hi = h.min(), h.max()
    h = (h - lo) / (hi - lo) if hi - lo > 1e-4 else np.ones_like(h)
    dx = (np.roll(h, -1, 1) - np.roll(h, 1, 1)) * 0.5
    dy = (np.roll(h, -1, 0) - np.roll(h, 1, 0)) * 0.5
    s = p['normal'] * 2.0
    n = np.stack([-dx * s, -dy * s, np.ones_like(h)], -1)  # DirectX: +y = down the image, as LabPBR wants
    n /= np.linalg.norm(n, axis=-1, keepdims=True)
    ao = 1 - p['ao'] * (1 - h) ** 2
    height = 1 - p['depth'] * (1 - h)

    # per-texel material fields, starting from the rule
    smooth = np.clip(p['smooth'] + p['svar'] * (lum - mean) * 2, 0, 1)
    f0 = np.full(h.shape, p['f0'] * 255)
    metal = np.zeros(h.shape, bool)
    metal_id = np.full(h.shape, p['metal'] or 0)
    if p['metal']:
        metal[:] = True
    por = np.full(h.shape, p['por'])
    sss = np.full(h.shape, p['sss'])
    emit = p['emit'] * smoothstep(p['elo'], p['ehi'], value) if p['emit'] else np.zeros(h.shape)

    mode = p['mode']
    if mode == 'copper':  # orange copper vs teal patina
        patina = smoothstep(-0.02, 0.10, rgb[..., 1] - rgb[..., 0]) > 0.5
        metal &= ~patina
        smooth = np.where(patina, np.clip(0.30 + (lum - mean), 0.1, 0.5), smooth)
        por = np.where(patina, 0.3, por)
    elif mode == 'trim':  # grey -> iron, saturated yellow -> gold, rest stays the rule's surface
        grey = (sat < 0.22) & (value > 0.12) & (emit < 0.2)
        gold = (sat > 0.45) & (value > 0.55) & (rgb[..., 0] > rgb[..., 2] * 1.6) & (emit < 0.2)
        for msk, mid, sm in ((grey, 230, 0.78), (gold, 231, 0.88)):
            metal |= msk
            metal_id = np.where(msk, mid, metal_id)
            smooth = np.where(msk, sm, smooth)
            por = np.where(msk, 0, por)
        if p['metal']:  # a metal rule: what isn't trim is not metal (e.g. a lantern's flame)
            keep = grey | gold
            metal &= keep
    elif mode == 'ore' and host is not None:
        diff = np.abs(rgb - host[..., :3]).max(-1)
        gem = materials.m(materials.DEFAULT, p['gem'])
        hv, hs = hsv(host[..., :3])
        # crystal texels: more saturated or brighter than the stone they replace (not the dark outline around them)
        g = (diff > 0.06) & ((sat - hs > 0.15) | (value - hv > 0.12) | (diff > 0.3)) & solid
        smooth = np.where(g, np.clip(gem['smooth'] + gem['svar'] * (lum - mean), 0, 1), smooth)
        f0 = np.where(g, gem['f0'] * 255, f0)
        if gem['metal']:
            metal |= g
            metal_id = np.where(g, gem['metal'], metal_id)
        por = np.where(g, 0, por)
        if gem['emit']:
            emit = np.where(g, gem['emit'] * smoothstep(0.25, 0.75, value), emit)
        n = np.where(g[..., None], (n * [0.5, 0.5, 1]) / np.linalg.norm(n * [0.5, 0.5, 1], axis=-1, keepdims=True), n)

    nrm = np.empty(h.shape + (4,), np.uint8)
    nrm[..., 0] = np.round((n[..., 0] * 0.5 + 0.5) * 255)
    nrm[..., 1] = np.round((n[..., 1] * 0.5 + 0.5) * 255)
    nrm[..., 2] = np.round(np.clip(ao, 0, 1) * 255)
    nrm[..., 3] = np.round(np.clip(height, 0, 1) * 254 + 1)  # >= 1: some POM implementations break on 0
    spec = np.empty(h.shape + (4,), np.uint8)
    spec[..., 0] = np.round(smooth * 255)
    spec[..., 1] = np.where(metal, metal_id, np.clip(np.round(f0), 0, 229))
    spec[..., 2] = np.where(sss > 0, np.round(65 + np.clip(sss, 0, 1) * 190), np.round(np.clip(por, 0, 1) * 64))
    spec[..., 3] = np.round(np.clip(emit, 0, 1) * 254)  # 0 = none, 255 (ignored) never used
    # transparent texels: flat, and the rule's surface without emission, so filtering at cutout edges stays clean
    nrm[~solid] = (128, 128, 255, 255)
    spec[~solid, 0] = round(p['smooth'] * 255)
    spec[~solid, 3] = 0
    return nrm, spec


def host_texture(name, tex):
    base = 'deepslate' if name.startswith('deepslate_') else 'netherrack' if name.startswith('nether_') else 'stone'
    return tex.get(base)


def pack_icon(size=128):
    """Our own icon: an ember glow on a dark tile (procedural, no game art)."""
    y, x = np.mgrid[0:size, 0:size] / (size - 1) * 2 - 1
    r = np.sqrt(x * x + (y - 0.15) ** 2 * 0.8)
    flame = np.clip(1 - (np.abs(x) * 1.8 + np.maximum(-y - 0.1, 0) ** 2 * 1.6 + np.maximum(y - 0.45, 0) * 4), 0, 1)
    glow = np.exp(-r * r * 3.0)
    img = np.stack([0.06 + 0.9 * glow + 0.6 * flame, 0.04 + 0.35 * glow + 0.55 * flame ** 1.5,
                    0.05 + 0.08 * glow + 0.35 * flame ** 3], -1)
    return Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8), 'RGB')


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    ap.add_argument('--jar', type=Path)
    ap.add_argument('--out', type=Path, default=REPO / 'build')
    ap.add_argument('--style', choices=sorted(materials.STYLES), default='rtx',
                    help='rtx (default): mirror concrete + neon terracotta; subtle: without them (materials.STYLES)')
    a = ap.parse_args()
    jar = a.jar or find_jar()
    name = 'emberlight-pbr' if a.style == 'rtx' else f'emberlight-pbr-{a.style}'
    out = a.out / name
    tex_out = out / BLOCKS
    if out.exists():
        import shutil
        shutil.rmtree(out)
    tex_out.mkdir(parents=True)

    with zipfile.ZipFile(jar) as z:
        names = sorted(n for n in z.namelist() if n.startswith(BLOCKS) and n.endswith('.png') and '/' not in n[len(BLOCKS):])
        version = json.loads(z.read('version.json'))
        tex, meta = {}, {}
        for n in names:
            key = n[len(BLOCKS):-4]
            tex[key] = np.asarray(Image.open(io.BytesIO(z.read(n))).convert('RGBA'), dtype=np.float64) / 255
            if n + '.mcmeta' in z.namelist():
                meta[key] = json.loads(z.read(n + '.mcmeta'))

    tuned = 0
    for key, rgba in tex.items():
        p, rule = materials.lookup(key, a.style)
        tuned += not materials.is_default(rule)
        host = host_texture(key, tex) if p['mode'] == 'ore' else None
        fs = frames(rgba, meta.get(key))
        if host is not None and any(f.shape != host.shape for f in fs):
            host = None
        nf, sf = zip(*(maps(f, p, host) for f in fs))
        fw = fs[0].shape[1]
        per_row = rgba.shape[1] // fw
        rows_n = [np.concatenate(nf[i:i + per_row], 1) for i in range(0, len(nf), per_row)]
        rows_s = [np.concatenate(sf[i:i + per_row], 1) for i in range(0, len(sf), per_row)]
        n_img, s_img = np.concatenate(rows_n, 0), np.concatenate(rows_s, 0)
        assert n_img.shape[:2] == rgba.shape[:2], key
        Image.fromarray(n_img, 'RGBA').save(tex_out / f'{key}_n.png', optimize=True)
        Image.fromarray(s_img, 'RGBA').save(tex_out / f'{key}_s.png', optimize=True)
        anim = meta.get(key, {}).get('animation')
        if anim is not None:  # same frame layout and timing as the base sprite (functional metadata only)
            for sfx in ('_n', '_s'):
                (tex_out / f'{key}{sfx}.png.mcmeta').write_text(json.dumps({'animation': anim}) + '\n')

    pv = version['pack_version']
    mc = json.loads((PACK / 'pack.mcmeta').read_text())
    mc['pack']['min_format'] = [pv['resource_major'], 0]
    mc['pack']['max_format'] = pv['resource_major']
    if a.style != 'rtx':
        mc['pack']['description'] += f' ({a.style})'
    (out / 'pack.mcmeta').write_text(json.dumps(mc, indent=2) + '\n')
    pack_icon().save(out / 'pack.png', optimize=True)
    for f in ('LICENSE', 'README.md'):
        (out / f).write_bytes((PACK / f).read_bytes())

    zpath = a.out / f'{name}.zip'
    with zipfile.ZipFile(zpath, 'w', zipfile.ZIP_DEFLATED) as z:
        for f in sorted(out.rglob('*')):
            if f.is_file():
                z.write(f, f.relative_to(out).as_posix())
    print(f'{len(tex)} block textures from {jar.name} ({version["id"]}, resource format {pv["resource_major"]}): '
          f'{tuned} tuned, {len(tex) - tuned} default, style {a.style} -> {zpath}')


if __name__ == '__main__':
    main()
