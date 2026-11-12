#version 450
// Geometry-swap proof: face normals over the albedo, times a tint from our set 1 (dynamic-offset UBO).
layout(set = 0, binding = 3) uniform sampler2D Sampler0;
layout(std140, set = 1, binding = 0) uniform EmrionDebug { vec4 tint; };
layout(location = 0) in vec3 viewPos;
layout(location = 1) in vec2 texCoord;
layout(location = 2) in vec4 vertexColor;
layout(location = 0) out vec4 fragColor;
void main() {
    vec4 albedo = texture(Sampler0, texCoord) * vertexColor;
    vec3 n = normalize(cross(dFdx(viewPos), dFdy(viewPos)));
    fragColor = vec4(mix(albedo.rgb, n * 0.5 + 0.5, 0.7) * tint.rgb, 1.0);
}
