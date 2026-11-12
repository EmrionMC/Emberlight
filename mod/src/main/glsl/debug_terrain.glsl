// Shared vertex stage of the geometry-swap proof (included by debug_terrain_{mdi,ubo}.vert).
// Set-0 bindings are placeholders (index into VertexLayouts.SET0_CANONICAL + Projection=8, Globals=9), patched at load.
layout(std140, set = 0, binding = 9) uniform Globals {
    ivec3 CameraBlockPos; float GlintAlpha; vec3 CameraOffset; float GameTime; vec2 ScreenSize; int MenuBlurRadius; int UseRgss;
};
layout(std140, set = 0, binding = 8) uniform Projection { mat4 ProjMat; };
layout(std140, set = 0, binding = 1) uniform TerrainUniform { mat4 ModelViewMat; ivec2 TextureSize; };
#ifndef MULTIDRAW
layout(std140, set = 0, binding = 2) uniform ChunkSection { ivec3 ChunkPosition; float ChunkVisibility; };
#else
layout(location = 10) in ivec3 ChunkPosition;
layout(location = 11) in float ChunkVisibility;
#endif
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;

layout(location = 0) out vec3 viewPos;
layout(location = 1) out vec2 texCoord;
layout(location = 2) out vec4 vertexColor;

void main() {
    vec3 pos = Position + vec3(ChunkPosition - CameraBlockPos) + CameraOffset;
    vec4 vp = ModelViewMat * vec4(pos, 1.0);
    gl_Position = ProjMat * vp;
    viewPos = vp.xyz;
    texCoord = UV0;
    vertexColor = Color;
}
