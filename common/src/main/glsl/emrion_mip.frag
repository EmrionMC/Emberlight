#version 450
// One mip level of a colour target from the level above, as glGenerateMipmap does: each destination texel averages its whole
// source footprint, weighted by overlap. vkCmdBlitImage's bilinear stretch only reads the footprint's centre on odd sizes,
// so a 3x2 level reduced to 1x1 kept only its middle column (packs that meter exposure from the top levels saw that).
layout(set = 0, binding = 0) uniform sampler2D src; // a view of the source level only
layout(location = 0) out vec4 outColor;
void main() {
    ivec2 S = textureSize(src, 0);
    ivec2 D = max(S / 2, ivec2(1));
    vec2 scale = vec2(S) / vec2(D);
    vec2 lo = floor(gl_FragCoord.xy) * scale, hi = lo + scale;
    vec4 sum = vec4(0.0);
    float total = 0.0;
    for (int y = int(lo.y); y < int(ceil(hi.y)); y++) {
        float wy = min(hi.y, float(y + 1)) - max(lo.y, float(y));
        for (int x = int(lo.x); x < int(ceil(hi.x)); x++) {
            float w = wy * (min(hi.x, float(x + 1)) - max(lo.x, float(x)));
            if (w <= 0.0) continue;
            sum += texelFetch(src, ivec2(x, y), 0) * w;
            total += w;
        }
    }
    outColor = sum / total;
}
