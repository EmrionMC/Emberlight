// Vertex-data debug view (-Demrion.debug.vertex=true): EMRION_BLOCK attributes at their canonical locations (FORMAT.md §7.1).
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
layout(location = 5) in vec3 Normal;
layout(location = 6) in ivec2 mc_Entity;
layout(location = 7) in vec2 mc_midTexCoord;
layout(location = 8) in vec4 at_tangent;
layout(location = 9) in vec4 at_midBlock;

layout(location = 0) out vec2 texCoord;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) flat out ivec2 entity;
layout(location = 3) out vec3 normal;
layout(location = 4) out vec4 tangent;
layout(location = 5) out vec4 midBlock;
layout(location = 6) out vec2 midTex;

void main() {
    vec3 pos = Position + vec3(ChunkPosition - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    texCoord = UV0;
    vertexColor = Color;
    entity = mc_Entity;
    normal = Normal;
    tangent = at_tangent;
    midBlock = at_midBlock;
    midTex = mc_midTexCoord;
}
