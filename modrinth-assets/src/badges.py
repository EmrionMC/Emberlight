# Renders the page's badge pills (Emberlight icon + label) to PNG. Needs rsvg-convert and Noto Sans.
# python3 badges.py  ->  ../badge_*.png
import base64, pathlib, subprocess
here = pathlib.Path(__file__).resolve().parent
gem = subprocess.run(["rsvg-convert", "-w", "72", str(here.parent.parent / "docs/branding/emberlight-icon.svg")],
                     check=True, capture_output=True).stdout
gem64 = base64.b64encode(gem).decode()
BADGES = {  # file: (small line, label, accent)
    "fabric": ("Available for", "Fabric", "#dbd0b4"),
    "neoforge": ("Available for", "NeoForge", "#f0a35e"),
    "github": ("Source code on", "GitHub", "#e8e8e8"),
    "discord": ("Join us on", "Discord", "#8f9cf7"),
}
H, S = 64, 1.5  # logical height, render scale
for name, (small, label, accent) in BADGES.items():
    w = 64 + int(max(len(label) * 13.4, len(small) * 6.8)) + 26
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="{w}" height="{H}" viewBox="0 0 {w} {H}">
<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#2a1a12"/><stop offset="1" stop-color="#170f0b"/></linearGradient></defs>
<rect x="1" y="1" width="{w-2}" height="{H-2}" rx="{(H-2)//2}" fill="url(#g)" stroke="#ef7d1f" stroke-opacity="0.55" stroke-width="2"/>
<image x="15" y="13" width="38" height="38" xlink:href="data:image/png;base64,{gem64}" preserveAspectRatio="xMidYMid meet"/>
<text x="64" y="27" font-family="Noto Sans" font-size="12" fill="#c9a98a">{small}</text>
<text x="64" y="49" font-family="Noto Sans" font-weight="700" font-size="22" fill="{accent}">{label}</text>
</svg>'''
    src = here / f"badge_{name}.svg"
    src.write_text(svg)
    subprocess.run(["rsvg-convert", "-z", str(S), "-o", str(here.parent / f"badge_{name}.png"), str(src)], check=True)
    print(name, w * S, H * S)
