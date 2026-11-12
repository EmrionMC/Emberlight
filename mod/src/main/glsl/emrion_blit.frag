#version 450
// Runtime helper: copies a same-size image texel by texel (MC scene -> colortex0, colortex0 -> screen when a pack has no final).
layout(set = 0, binding = 0) uniform sampler2D src;
layout(location = 0) out vec4 outColor;
void main() {
    outColor = texelFetch(src, ivec2(gl_FragCoord.xy), 0);
}
