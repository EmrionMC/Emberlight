#version 430
// Animated rings into testImg over composite.csh's red (shown by final in the bottom-left corner).
layout(local_size_x = 8, local_size_y = 8) in;
const ivec3 workGroups = ivec3(32, 32, 1);
layout(rgba8) uniform writeonly image2D testImg;
uniform float frameTimeCounter;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    vec2 uv = vec2(p) / 256.0;
    float rings = 0.5 + 0.5 * sin(length(uv - 0.5) * 40.0 - frameTimeCounter * 4.0);
    if (rings > 0.5) imageStore(testImg, p, vec4(uv, rings, 1.0));
}
