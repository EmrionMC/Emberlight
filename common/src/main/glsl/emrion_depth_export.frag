#version 450
// Runtime helper: the pack's depthtex0 (GL forward depth, render size) into MC's main depth (reverse Z, window size), so that
// mods drawing into the world after it (VoxelMap waypoint beams, ...) are occluded by the terrain as with Iris.
layout(set = 0, binding = 0) uniform sampler2D src;
layout(push_constant) uniform P { vec2 scale; } pc; // source size / window size
void main() {
    gl_FragDepth = 1.0 - texelFetch(src, ivec2(gl_FragCoord.xy * pc.scale), 0).r;
}
