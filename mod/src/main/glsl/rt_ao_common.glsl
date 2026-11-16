// RT ambient occlusion, shared by rt_ao.comp (trace + temporal) and rt_ao_filter.comp (spatial).
// Space: the pack's playerPos (world - cameraPosition) = TLAS space. Images keep GL's row order, so pixel (x, y) is the
// pack's gl_FragCoord.xy and NDC needs no flip.
layout(local_size_x = 8, local_size_y = 8) in;
layout(set = 0, binding = 3) uniform sampler2D depthTex;  // depthtex0 after the opaque gbuffers
layout(set = 0, binding = 4) uniform sampler2D historyIn; // rgba16f: ao, sample count, distance to the camera, unused
layout(set = 0, binding = 7) uniform Params { // a UBO: 160 bytes exceed the guaranteed 128 of push constants
    mat4 invViewProj;  // inverse(gbufferProjection * gbufferModelView)
    mat4 prevViewProj; // last frame's gbufferProjection * gbufferModelView
    vec4 camDelta;     // xyz: cameraPosition - previousCameraPosition, w: frame index
    vec4 params;       // xy: size, z: ray length (blocks), w: rays per pixel
} pc;

vec3 playerPos(ivec2 px, float depth) {
    vec4 p = pc.invViewProj * vec4((vec2(px) + 0.5) / pc.params.xy * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

vec3 playerPosAt(ivec2 px) {
    px = clamp(px, ivec2(0), ivec2(pc.params.xy) - 1);
    return playerPos(px, texelFetch(depthTex, px, 0).r);
}

// Geometric normal from depth: per axis the neighbour on the same surface (smaller step), so block edges stay sharp.
// Normal maps are ignored on purpose: their normals point below the face and would make the face occlude itself.
vec3 depthNormal(ivec2 px, vec3 p) {
    vec3 l = playerPosAt(px - ivec2(1, 0)), r = playerPosAt(px + ivec2(1, 0));
    vec3 d = playerPosAt(px - ivec2(0, 1)), u = playerPosAt(px + ivec2(0, 1));
    // at the screen border the clamped neighbour equals p: take the inner one (a zero step made the normal NaN, which
    // tinted the screen edges)
    ivec2 last = ivec2(pc.params.xy) - 1;
    vec3 dx = px.x == 0 ? r - p : px.x == last.x ? p - l : dot(r - p, r - p) < dot(p - l, p - l) ? r - p : p - l;
    vec3 dy = px.y == 0 ? u - p : px.y == last.y ? p - d : dot(u - p, u - p) < dot(p - d, p - d) ? u - p : p - d;
    vec3 n = normalize(cross(dx, dy));
    return dot(n, p) > 0.0 ? -n : n;
}

// The pack treats depth < 0.56 as the hand (its SSAO returns 1 there) and 1.0 is sky.
bool noSurface(float depth) {
    return depth >= 1.0 || depth < 0.56;
}
