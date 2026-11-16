// RT block-light sources (RtLights, builtin emrion_rt_lights): the one declaration of the buffer for every pass that reads it.
// The capped grid (cells of at most 31 vanilla emitters, camera-relative positions) serves Faithful block light; the full
// list (every source the pack lights, uncapped cells, grid-relative positions) serves RTX block light, its ReSTIR passes
// and RTX GI.
layout(std430) readonly buffer EmrRtLights {
	vec4 emrRtLightOrigin;     // grid min corner relative to the camera (= playerPos space); w = sources in the full list
	ivec4 emrRtLightDims;      // cells x, y, z, cell size in blocks
	vec4 emrRtLightChange[2];  // box where lights were just added or removed, [0].w = GI history cap there (0 = none);
	                           // [1].w = 1 when this frame's full list replaced last frame's (emrBlRemap is valid)
	uint emrRtLightCell[131072]; // capped grid: per cell 32 words: count, light indices
	vec4 emrRtLight[8192];     // capped grid: block centre (playerPos space), w = level + source radius; then colours
	vec4 emrBlPos[16384];      // full list: block centre relative to the grid origin, w = range level + source radius
	vec4 emrBlCol[16384];      // colour (brightest channel 1), a = power (negative: only the pack's coloured light knows it)
	uint emrBlRemap[16384];    // last frame's full-list index -> this frame's, 0xFFFFFFFF = removed
	uint emrBlCellStart[4097]; // per cell the first entry of emrBlIndex; the cell's sources are [start[c], start[c + 1])
	uint emrBlBlock[32768];    // source by grid-relative block position (open addressing, emrBlHash): index + 1, 0 = empty
	uint emrBlIndex[];         // every source whose range reaches the cell
};

// 1 up to 32 blocks from the camera, 0 from 56 on, which is inside the grid wherever the camera sits in its cell. By distance,
// not by the grid edge: the grid moves a cell at a time, so an edge fade made far lighting jump while flying.
float emrRtLightFade(vec3 p) {
	float r = float(min(emrRtLightDims.x, min(emrRtLightDims.y, emrRtLightDims.z)) - 2) * 0.5 * float(emrRtLightDims.w);
	return 1.0 - smoothstep(r - 24.0, r, length(p));
}
