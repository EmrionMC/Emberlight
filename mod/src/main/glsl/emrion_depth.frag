#version 450
// Runtime helper (M2): MC's reverse-Z depth (near 1, clear 0) -> GL-style depthtex0 (near 0, clear 1).
layout(set = 0, binding = 0) uniform sampler2D mcDepth;
void main() {
    gl_FragDepth = 1.0 - texelFetch(mcDepth, ivec2(gl_FragCoord.xy), 0).r;
}
