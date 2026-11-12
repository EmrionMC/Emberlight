#version 450
// Debug pass 1: MC main color -> RGBA16F intermediate.
layout(set = 0, binding = 0) uniform sampler2D mainColor;
layout(location = 0) out vec4 outColor;
void main() {
    outColor = texelFetch(mainColor, ivec2(gl_FragCoord.xy), 0);
}
