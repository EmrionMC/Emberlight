#!/usr/bin/env python3
"""Sums a VMA detailed-stats JSON (written by the autotest `mem` step with -Demrion.debug.vram=true) by owner.

usage: vram_report.py emrion-vma-N.json [--all]
Prints MiB per category for device-local memory types, plus VMA block slack (block bytes not used by allocations)."""
import json, re, sys
from collections import defaultdict

RULES = [  # (category, regex on the allocation name); first match wins
    ("MC block atlas", r"^tex minecraft:textures/atlas/blocks\.png$"),
    ("PBR block atlas normals", r"^tex minecraft:textures/atlas/blocks_n\.png$"),
    ("PBR block atlas specular", r"^tex minecraft:textures/atlas/blocks_s\.png$"),
    ("PBR other atlases", r"^tex .*atlas/.*_[ns]\.png$"),
    ("MC other atlases", r"^tex .*textures/atlas/"),
    ("ours: SSBO", r"^raw emrion (ssbo|buffer)|^raw emrion \d+$"),
    ("ours: floodfill_img(+copy)", r"floodfill"),
    ("ours: voxel/wsr/puddle/leaves images", r"^raw emrion .*(voxel|wsr|puddle|leaves)"),
    ("ours: other raw", r"^raw "),
    ("ours: shadow maps", r"^tex emrion shadow"),
    ("ours: colortex alt", r"^tex emrion colortex\d+ alt$"),
    ("ours: colortex main", r"^tex emrion colortex\d+$"),
    ("ours: depth copies", r"^tex emrion depthtex"),
    ("ours: other textures", r"^tex emrion"),
    ("MC chunk sections (UberBuffer)", r"(?i)^buf .*(section|chunk|UberBuffer)"),
    ("MC render targets", r"(?i)^tex .*(main|target|depth|color|framebuffer|lightmap)"),
    ("MC other textures", r"^tex "),
    ("MC other buffers", r"^buf "),
    ("sparse atlases (all, base + PBR)", r"^sparse atlas"),
    ("unnamed (MC transient/staging)", r""),
]

def main():
    d = json.load(open(sys.argv[1]))
    show_all = "--all" in sys.argv
    device_types = set()
    for heap in d["MemoryInfo"].values():
        if "DEVICE_LOCAL" in heap["Flags"]:
            device_types |= {t for t, p in heap["MemoryPools"].items() if "HOST_VISIBLE" not in p["Flags"] or True}
    cats, names = defaultdict(int), defaultdict(int)
    block, used = 0, 0
    for tname, pool in d["DefaultPools"].items():
        if tname not in device_types: continue
        allocs = list(pool["DedicatedAllocations"])
        for b in pool["Blocks"].values():
            block += b["TotalBytes"]
            allocs += [s for s in b["Suballocations"] if s["Type"] != "FREE"]
        for a in pool["DedicatedAllocations"]: block += a["Size"]
        for a in allocs:
            name = a.get("Name", "")
            used += a["Size"]
            names[name] += a["Size"]
            for cat, rx in RULES:
                if re.search(rx, name):
                    cats[cat] += a["Size"]
                    break
    mib = lambda x: x / 1048576
    for cat, _ in RULES:
        if cats[cat]: print(f"{mib(cats[cat]):9.1f} MiB  {cat}")
    print(f"{mib(used):9.1f} MiB  = allocations (device-local heaps)")
    print(f"{mib(block - used):9.1f} MiB  VMA block slack (free space inside 32-256 MiB blocks)")
    print(f"{mib(block):9.1f} MiB  = VMA device memory")
    if show_all:
        for n, s in sorted(names.items(), key=lambda x: -x[1])[:60]: print(f"{mib(s):9.1f}  {n}")

main()
