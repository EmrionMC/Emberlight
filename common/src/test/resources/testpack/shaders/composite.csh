#version 430
// Runs before composite_a.csh and composite.fsh (Iris: <pass>.csh, <pass>_a.csh …, then the fullscreen program):
// fills testImg with red; composite_a.csh then draws the rings over it, so red between the rings proves the order.
layout(local_size_x = 8, local_size_y = 8) in;
const ivec3 workGroups = ivec3(32, 32, 1);
layout(rgba8) uniform writeonly image2D testImg;
void main() {
    imageStore(testImg, ivec2(gl_GlobalInvocationID.xy), vec4(1.0, 0.0, 0.0, 1.0));
}
