#version 450
// Debug pass 2: intermediate -> MC main color, desaturated with a blue tint; the compute pattern shows in a corner square.
layout(set = 0, binding = 0) uniform sampler2D intermediate;
layout(set = 0, binding = 1) uniform sampler2D pattern;
layout(location = 0) out vec4 outColor;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 c = texelFetch(intermediate, p, 0).rgb;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    vec3 tinted = l * vec3(0.55, 0.75, 1.25);
    ivec2 ps = textureSize(pattern, 0);
    if (p.x < ps.x && p.y < ps.y) tinted = texelFetch(pattern, p, 0).rgb;
    outColor = vec4(tinted, 1.0);
}
