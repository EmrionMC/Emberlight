// RTX GI radiance cache (gi:cache, RAYTRACING.md §39): a world-space spatial hash grid of the light arriving at surfaces,
// after NVIDIA's SHaRC ("Spatially Hashed Radiance Cache", RTXGI SDK; the algorithm reimplemented here, no SDK code).
// gi_cache_update.csh traces short paths from a sparse set of pixels and adds each path vertex's incoming light (direct
// light plus the light its own bounce brought, read from the cache one vertex further: infinite bounces over frames) to
// that vertex's voxel; gi_cache_resolve.csh blends the frame's sums into the entries and evicts entries nothing updated for
// a while; gi_trace.csh ends its bounce ray at the cached light where the hit's voxel has enough samples.
// Voxel: cubes of emrRt_gi_cacheVoxel blocks up to emrRt_gi_cacheNear blocks from the camera, doubling twice with distance
// (at most 4x, so a voxel never spans a one-block wall with open floor on both sides), aligned to the block grid, keyed by
// the face direction too (the two sides of a wall are separate entries). 2^20 entries, linear probing over 8 slots; eviction
// leaves holes in the probe chains, so lookups and inserts search all 8 slots for the key before an insert claims an empty one.
layout(r32ui) uniform uimage2D emrGiCk; // check hash per entry, 0 = empty
layout(r32ui) uniform uimage2D emrGiCs; // this frame's sums: rows 0-8 = sun, sky, block light rgb (x4096), row 9 = samples (top bit: changed)
layout(rgba16f) uniform image2D emrGiCr; // resolved: rows 0-2 = (sun, samples), (sky, frames since a sample), (block light, 0)

const uint EMR_GC_MASK = 0xFFFFFu;
const float EMR_GC_SCALE = 4096.0;

ivec2 emrGcAt(uint i, int row) {
	return ivec2(int(i & 1023u), int(i >> 10u) + row * 1024);
}

// Slot hash and check hash (never 0) of the voxel holding surface point p (camera-relative) with front normal n.
uvec2 emrGcKey(vec3 p, vec3 n, out float size) {
	int lvl = clamp(int(floor(log2(max(length(p), 1e-3) / emrRt_gi_cacheNear))) + 1, 0, 2);
	size = emrRt_gi_cacheVoxel * float(1 << lvl);
	vec3 a = abs(n);
	uint face = a.x >= a.y && a.x >= a.z ? (n.x > 0.0 ? 0u : 1u) : a.y >= a.z ? (n.y > 0.0 ? 2u : 3u) : (n.z > 0.0 ? 4u : 5u);
	// a point on a block face lies on a voxel boundary: step into the block behind it, so a face always lands in one voxel
	ivec3 c = ivec3(floor((p + cameraPosition - n * 0.02) / size));
	uvec3 h = emrGiPcg(emrGiPcg(uvec3(c)) ^ uvec3(face | uint(lvl) << 3u, 0x2545F491u, 0x9E3779B9u));
	return uvec2(h.x, max(h.y, 1u));
}

bool emrGcChanged(vec3 p) { // inside the box where block lights were just placed or removed (RtLights)
	vec4 ch = emrRtLightChange[0];
	return ch.w > 0.0 && all(greaterThanEqual(p, ch.xyz)) && all(lessThanEqual(p, emrRtLightChange[1].xyz));
}

// The slot holding check hash k.y within k.x's 8 probe slots, ~0u if none. atomic: during the update, where other
// invocations insert keys concurrently (a plain load racing their compare-swaps is undefined).
uint emrGcFind(uvec2 k, bool atomic) {
	for (uint i = 0u; i < 8u; i++) {
		uint s = (k.x + i) & EMR_GC_MASK;
		if ((atomic ? imageAtomicOr(emrGiCk, emrGcAt(s, 0), 0u) : imageLoad(emrGiCk, emrGcAt(s, 0)).r) == k.y) return s;
	}
	return ~0u;
}

// The cached light arriving at the surface point p (normal n facing the ray) that a ray reached after t blocks, if its voxel
// has an entry with enough samples. Not for a hit closer than the voxel size (the voxel would average light from behind the
// ray's origin: corners), nor inside a light-change box (the entries there still hold the old light for a few frames).
bool emrGiCacheGet(vec3 p, vec3 n, float t, bool atomic, out vec3 sun, out vec3 sky, out vec3 blk) {
	sun = sky = blk = vec3(0.0);
	float size;
	uvec2 k = emrGcKey(p, n, size);
	if (t < size || emrGcChanged(p)) return false;
	uint s = emrGcFind(k, atomic);
	if (s == ~0u) return false;
	vec4 a = imageLoad(emrGiCr, emrGcAt(s, 0));
	if (a.w < emrRt_gi_cacheMin) return false;
	sun = a.rgb;
	sky = imageLoad(emrGiCr, emrGcAt(s, 1)).rgb;
	blk = imageLoad(emrGiCr, emrGcAt(s, 2)).rgb;
	return true;
}

// Looks the voxel up for gi_cache_update.csh: false if not cached (or too few samples).
bool emrGcRead(vec3 p, vec3 n, out vec3 sun, out vec3 sky, out vec3 blk) {
	return emrGiCacheGet(p, n, 1e9, true, sun, sky, blk);
}

uint emrGcFix(float x, float r) { // fixed point with random rounding (no bias for faint light); NaN and negatives give 0
	return x > 0.0 ? uint(min(x, 256.0) * EMR_GC_SCALE + r) : 0u;
}

// Adds one sample of the light arriving at p to its voxel (inserting the entry).
void emrGcAdd(vec3 p, vec3 n, vec3 sun, vec3 sky, vec3 blk, inout uvec3 seed) {
	float size;
	uvec2 k = emrGcKey(p, n, size);
	uint s = emrGcFind(k, true);
	for (uint i = 0u; i < 8u && s == ~0u; i++) {
		uint prev = imageAtomicCompSwap(emrGiCk, emrGcAt((k.x + i) & EMR_GC_MASK, 0), 0u, k.y);
		if (prev == 0u || prev == k.y) s = (k.x + i) & EMR_GC_MASK;
	}
	if (s == ~0u) return;
	// the count first: at most 4095 samples a frame keep each sum (<= 2^20 per sample) within 32 bits
	if ((imageAtomicAdd(emrGiCs, emrGcAt(s, 9), 1u) & 0x7FFFFFFFu) >= 4095u) {
		imageAtomicAdd(emrGiCs, emrGcAt(s, 9), ~0u); // -1
		return;
	}
	seed = emrGiPcg(seed);
	vec3 r = vec3(seed & 0xFFFFu) / 65536.0;
	float v[9] = float[9](sun.r, sun.g, sun.b, sky.r, sky.g, sky.b, blk.r, blk.g, blk.b);
	for (int c = 0; c < 9; c++) {
		uint q = emrGcFix(v[c], r[c % 3]);
		if (q != 0u) imageAtomicAdd(emrGiCs, emrGcAt(s, c), q);
	}
	if (emrGcChanged(p)) imageAtomicOr(emrGiCs, emrGcAt(s, 9), 0x80000000u); // top bit: a sample from a light-change box
}
