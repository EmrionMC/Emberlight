#version 450
// Vertex-data debug view, one attribute per screen quadrant (shaded by the albedo's luminance):
// top-left mc_Entity.x hashed to a colour (unmapped -1 = grey, fluids striped), top-right Normal, bottom-left at_tangent
// (xyz; w = -1 darkened), bottom-right at_midBlock (xyz / 64 + 0.5, emission adds white).
layout(std140, set = 0, binding = 9) uniform Globals {
    ivec3 CameraBlockPos; float GlintAlpha; vec3 CameraOffset; float GameTime; vec2 ScreenSize; int MenuBlurRadius; int UseRgss;
};
layout(set = 0, binding = 3) uniform sampler2D Sampler0;
layout(location = 0) in vec2 texCoord;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) flat in ivec2 entity;
layout(location = 3) in vec3 normal;
layout(location = 4) in vec4 tangent;
layout(location = 5) in vec4 midBlock;
layout(location = 6) in vec2 midTex;
layout(location = 0) out vec4 fragColor;

vec3 hashColor(int id) {
    uint h = uint(id) * 2654435761u;
    h ^= h >> 15; h *= 2246822519u; h ^= h >> 13;
    return vec3(h & 255u, (h >> 8) & 255u, (h >> 16) & 255u) / 255.0;
}

void main() {
    vec4 albedo = texture(Sampler0, texCoord);
    if (albedo.a < 0.1) discard;
    float shade = 0.55 + 0.45 * dot(albedo.rgb * vertexColor.rgb, vec3(0.299, 0.587, 0.114));
    vec2 q = gl_FragCoord.xy / ScreenSize;
    vec3 c;
    if (q.y < 0.5) {
        if (q.x < 0.5) {
            c = entity.x < 0 ? vec3(0.35) : hashColor(entity.x);
            if (entity.y == 1 && mod(gl_FragCoord.x + gl_FragCoord.y, 8.0) < 2.0) c = vec3(1.0);
        } else c = normalize(normal) * 0.5 + 0.5;
    } else {
        if (q.x < 0.5) c = (tangent.xyz * 0.5 + 0.5) * (tangent.w < 0.0 ? 0.4 : 1.0);
        else c = clamp(midBlock.xyz / 64.0 + 0.5, 0.0, 1.0) + vec3(midBlock.w / 15.0);
    }
    fragColor = vec4(c * shade, 1.0);
}
