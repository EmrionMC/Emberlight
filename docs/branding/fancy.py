# Fancy ember gem: 11x13 emerald-like octagon silhouette at 2x detail (22x26), faceted, glowing core, embers.
import math
S=2; W,H=11*S,13*S; CUT=3*S
def inoct(x,y,w,h,c,ox=0,oy=0):
    x-=ox;y-=oy
    if x<0 or y<0 or x>=w or y>=h: return False
    return not (x+y<c or (w-1-x)+y<c or x+(h-1-y)<c or (w-1-x)+(h-1-y)<c)
P={ # palette (dark->light)
 'K':'#3d1308','o':'#6e1f0c','r':'#a8300f','a':'#d2461a','y':'#ef7d1f','m':'#f9a12a','h':'#ffc447','l':'#ffe18a','w':'#fff6d6'}
# table (inner flat facet)
TW,TH,TC=11,15,3; TX,TY=(W-TW)//2,(H-TH)//2
cx,cy=(W-1)/2,(H-1)/2
px={}
for y in range(H):
    for x in range(W):
        if not inoct(x,y,W,H,CUT): continue
        edge=not all(inoct(x+dx,y+dy,W,H,CUT) for dx,dy in((1,0),(-1,0),(0,1),(0,-1)))
        if edge:
            px[(x,y)]='o' if (x+y)>(W+H)/2-3 else 'r'   # outline, warmer on the lit side
            continue
        if inoct(x,y,TW,TH,TC,TX,TY):
            # table: hot core gradient
            d=math.hypot((x-cx)/TW*2,(y-cy)/TH*2)
            t=d-0.25*((x-cx)+(y-cy))/W   # light from top-left
            px[(x,y)]='w' if t<0.25 else 'l' if t<0.5 else 'h' if t<0.8 else 'm'
            continue
        # crown/pavilion facets: decide by direction from centre
        ang=math.degrees(math.atan2(y-cy,x-cx))%360
        # 8 sectors starting at right(0), going clockwise (y down)
        sec=int(((ang+22.5)%360)//45)
        shade={0:'a',1:'r',2:'a',3:'y',4:'m',5:'h',6:'m',7:'y'}[sec]  # top-left facets lit, bottom-right dark
        px[(x,y)]=shade
# facet edge lines (table corners to outer corners) slightly brighter/darker for crispness
for (x,y),c in list(px.items()):
    pass
# rim highlight along upper-left outline + sparkle on table
for (x,y) in [(6,1),(7,1),(8,1),(4,3),(3,4),(2,5),(1,6),(1,7)]:
    if (x,y) in px: px[(x,y)]='l'
for (x,y) in [(8,7),(8,8),(9,7),(7,8)]:
    if (x,y) in px: px[(x,y)]='w'
# glow halo: stepped rings in warm darks
solid=set(px)
GL=['#2a120b','#331509','#3f1a0a','#4f200b']
halo={}
for ring,col in zip((6,4,3,2),GL):
    for y in range(-ring,H+ring):
        for x in range(-ring,W+ring):
            if (x,y) in solid: continue
            if any((x+dx,y+dy) in solid for dx in range(-ring,ring+1) for dy in range(-ring,ring+1) if dx*dx+dy*dy<=ring*ring):
                halo[(x,y)]=col
# embers / sparkles
EMB=[(-4,4,'h'),(-3,3,'w'),(24,7,'m'),(25,6,'h'),(19,-4,'h'),(20,-5,'w'),(1,-3,'m'),(-2,20,'y'),(23,22,'h')]
def svg(pxsize,pad,bg,rx):
    w=(W+2*pad)*pxsize; h=(H+2*pad)*pxsize; out=[]
    if bg: out.append(f'<rect width="{w}" height="{h}" rx="{rx}" fill="{bg}"/>')
    for (x,y),c in halo.items(): out.append(f'<rect x="{(x+pad)*pxsize}" y="{(y+pad)*pxsize}" width="{pxsize}" height="{pxsize}" fill="{c}"/>')
    for (x,y),c in px.items(): out.append(f'<rect x="{(x+pad)*pxsize}" y="{(y+pad)*pxsize}" width="{pxsize}" height="{pxsize}" fill="{P[c]}"/>')
    for x,y,c in EMB:
        out.append(f'<rect x="{(x+pad)*pxsize}" y="{(y+pad)*pxsize}" width="{pxsize}" height="{pxsize}" fill="{P[c]}"/>')
    return w,h,"\n".join(out)
w,h,b=svg(24,8,"#170f0b",56)
open("fancy-icon.svg","w").write(f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" shape-rendering="crispEdges">{b}</svg>')
w,h,b=svg(6,8,None,0)
open("fancy-icon-transparent.svg","w").write(f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" shape-rendering="crispEdges">{b}</svg>')
