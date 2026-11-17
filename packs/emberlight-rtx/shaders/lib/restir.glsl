// ReSTIR DI over the RT light list (Bitterli et al. 2020, "Spatiotemporal reservoir resampling"; RTXDI as the reference
// design), at the half resolution of rt_gi: restir.comp.glsl draws LIGHT_SAMPLES candidates (resampled importance
// sampling), traces one visibility ray for the pick and merges last frame's reservoir at the reprojected pixel;
// rt_gi.comp.glsl merges RS_NEIGHBOURS reservoirs of this frame's neighbours and shades the result.
// Both merges use pairwise MIS (Bitterli 2022, thesis §9.1.3; Lin et al. 2022) with visibility in the target: per
// merged surface one ray tests whether this surface sees its sample and one whether it sees ours. Reservoirs only pass
// on visible samples (an occluded pick drops its weight, keeps its confidence), so a merge whose weights ignore
// visibility counts surfaces that could never produce a sample: 11 % too dark under a village well's roof. The 1/Z
// correction (Alg. 6) exploded into bright blotches in motion (one surface's confidence divides the whole weight sum).
// Measured against an exhaustive reference (every source, one ray each): the frame means agree within 1 %.
// A sample is a light (el_light index) and a point on one of its faces (offset from the light's centre). The face is
// drawn by its projected area toward the surface (lightFacePoint), so the integrand carries that probability
// (rsFaceProb): reused at a pixel that sees the face at a grazing angle, the sample weighs less, as it would be drawn
// there. Without it spatial reuse brightened a lava cave by 10 %.
#ifndef RTX_RESTIR
#define RTX_RESTIR 1
#include "/lib/rt.glsl"

layout(std430, binding = EL_BUFFER_restirBuf) buffer RestirBlock { uvec4 rsData[]; };

// spatial neighbours per pixel and the confidence cap of the history (frames): more neighbours = cleaner, a few % each
#if LIGHT_SAMPLES <= 4
const int RS_NEIGHBOURS = 2;
#elif LIGHT_SAMPLES <= 8
const int RS_NEIGHBOURS = 5;
#elif LIGHT_SAMPLES <= 16
const int RS_NEIGHBOURS = 7;
#else
const int RS_NEIGHBOURS = 8;
#endif
const float RS_MCAP = 20.0;
const float RS_RADIUS = 12.0; // half-res pixels

struct Reservoir { int light; vec3 off; float W; float M; vec3 pos; vec3 n; };
const Reservoir RS_EMPTY = Reservoir(-1, vec3(0.0), 0.0, 0.0, vec3(0.0), vec3(0.0, 1.0, 0.0));

// slot 0: this frame after temporal reuse (restir.comp -> rt_gi); slot 1: final (rt_gi -> next frame's restir.comp)
uint rsIndex(ivec2 hp, int slot) {
	ivec2 hs = imageSize(giRaw_img);
	return (uint(slot) * uint(hs.x * hs.y) + uint(hp.y * hs.x + hp.x)) * 2u;
}
void rsStore(ivec2 hp, int slot, Reservoir r) {
	uint i = rsIndex(hp, slot);
	rsData[i] = uvec4(uint(r.light + 1), packHalf2x16(r.off.xy), packHalf2x16(vec2(r.off.z, r.M)), floatBitsToUint(r.W));
	rsData[i + 1u] = uvec4(floatBitsToUint(r.pos), packUnorm2x16(el_encodeNormal(r.n)));
}
// Garbage (a fresh or resized buffer) reads as empty. A reservoir without a sample (its pick was occluded) keeps its
// confidence M: dropping it made every merge count only the surfaces that saw a light, which lost most of the
// shadowing (+10 % in a lava cave). The light index is not checked against this frame's list here.
Reservoir rsLoad(ivec2 hp, int slot) {
	uint i = rsIndex(hp, slot);
	uvec4 a = rsData[i], b = rsData[i + 1u];
	vec2 zm = unpackHalf2x16(a.z);
	Reservoir r = Reservoir(int(a.x) - 1, vec3(unpackHalf2x16(a.y), zm.x), uintBitsToFloat(a.w), zm.y,
		uintBitsToFloat(b.xyz), el_decodeNormal(unpackUnorm2x16(b.w)));
	if (!(r.M > 0.0 && r.M < 1e4) || any(isnan(r.pos)) || any(isinf(r.pos))) return RS_EMPTY;
	if (r.light < 0 || r.light >= 16384 || !(r.W > 0.0 && r.W < 1e20) || any(greaterThan(abs(r.off), vec3(0.6)))) {
		r.light = -1; r.W = 0.0;
	}
	return r;
}
// drops the sample (not the confidence) when its light is not in this frame's list
void rsCheckLight(inout Reservoir r) { if (r.light >= el_lightCount()) { r.light = -1; r.W = 0.0; } }

// probability that lightFacePoint draws the face holding off (d = surface - light centre); 0 for a face turned away
float rsFaceProb(vec3 d, vec3 off) {
	vec3 ao = abs(off), a = abs(d) + 1e-4;
	int k = ao.x >= ao.y && ao.x >= ao.z ? 0 : ao.y >= ao.z ? 1 : 2;
	return off[k] * d[k] > 0.0 ? a[k] / (a.x + a.y + a.z) : 0.0;
}

// unshadowed diffuse light of a sample at surface (p, n), times its face probability: the integrand of the estimator
// (lighting as before ReSTIR in expectation); its luminance is the target function
vec3 rsRadiance(int li, vec3 off, vec3 p, vec3 n) {
	ElLight L = el_light(li);
	vec3 d = L.position + off - p;
	float dist = max(length(d), 0.05);
	float nl = clamp((dot(n, d) / dist + 0.3) / 1.3, 0.0, 1.0); // wrapped: grazing area light still reaches
	return L.color * lightFalloff(length(L.position - p), L.range) * nl * rsFaceProb(p - L.position, off);
}
float rsTarget(int li, vec3 off, vec3 p, vec3 n) { return li < 0 ? 0.0 : el_luminance(rsRadiance(li, off, p, n)); }

bool rsVisible(vec3 p, vec3 n, int li, vec3 off) {
	vec3 o = p + n * 0.02;
	vec3 d = el_light(li).position + off - o;
	float dist = length(d);
	// opaque terrain and entities (mobs shadow lamps as they shadow the sun); glass lets block light through untinted
	return dist <= 0.06 || el_traceVisibility(o, d / dist, dist - 0.04, MASK_SOLID);
}

// the same surface (reuse allowed): plane distance and normal, like the denoiser's tests
bool rsSimilar(vec3 p, vec3 n, vec3 q, vec3 nq) {
	return dot(n, nq) > 0.9 && abs(dot(q - p, n)) < 0.05 + 0.02 * length(p);
}

// streaming weighted reservoir sampling
struct RsStream { int light; vec3 off; float wsum; float pHat; int src; };
const RsStream RS_NONE = RsStream(-1, vec3(0.0), 0.0, 0.0, -1);
void rsAdd(inout RsStream s, int li, vec3 off, float w, float pHat, float u, int src) {
	s.wsum += w;
	if (w > 0.0 && u * s.wsum < w) { s.light = li; s.off = off; s.pHat = pHat; s.src = src; }
}
// Merges reservoirs r[0..cnt) into one for surface r[0], the canonical one (M > 0); M is capped by the caller. Pairwise MIS
// with k = cnt - 1 others: m_i(y) = a_i / (a_i + b) / k for another surface's sample, a_i = M_i p_i(y) V_i(y),
// b = M_0 / k p_0(y) V_0(y); the canonical sample gets the rest, sum_i b / (a_i + b) / k. The weights sum to 1 for any y.
// The others' samples are visible to them (their weight is zeroed otherwise); centreVisible: r[0]'s sample is known
// visible here (else one more ray). The selected sample is always visible at r[0].
const int RS_MAXD = RS_NEIGHBOURS + 1;
Reservoir rsMerge(Reservoir r[RS_MAXD], int cnt, bool centreVisible, inout Rng rng) {
	RsStream s = RS_NONE;
	Reservoir c = r[0];
	float msum = c.M, k = float(max(cnt - 1, 1)), mc = cnt > 1 ? 0.0 : 1.0;
	float pcc = c.light >= 0 && c.W > 0.0 ? rsTarget(c.light, c.off, c.pos, c.n) : 0.0;
	if (pcc > 0.0 && !centreVisible && !rsVisible(c.pos, c.n, c.light, c.off)) pcc = 0.0;
	for (int i = 1; i < cnt; i++) {
		Reservoir q = r[i];
		msum += q.M;
		if (q.light >= 0 && q.W > 0.0) { // its sample
			float a = q.M * rsTarget(q.light, q.off, q.pos, q.n);
			float p0 = rsTarget(q.light, q.off, c.pos, c.n);
			if (p0 > 0.0 && !rsVisible(c.pos, c.n, q.light, q.off)) p0 = 0.0;
			float b = c.M / k * p0;
			if (p0 > 0.0) rsAdd(s, q.light, q.off, a / (a + b) / k * p0 * q.W, p0, rnd(rng), i);
		}
		if (pcc > 0.0) { // ours, seen from there
			float a = q.M * rsTarget(c.light, c.off, q.pos, q.n);
			if (a > 0.0 && !rsVisible(q.pos, q.n, c.light, c.off)) a = 0.0;
			float b = c.M / k * pcc;
			mc += b / (a + b) / k;
		}
	}
	if (pcc > 0.0) rsAdd(s, c.light, c.off, mc * pcc * c.W, pcc, rnd(rng), 0);
	Reservoir o = c;
	o.light = s.light; o.off = s.off; o.M = msum;
	o.W = s.light >= 0 ? s.wsum / s.pHat : 0.0;
	return o;
}

// The surface a half-res pixel shades this frame: rt_gi's rotating pixel of the 2x2 block; p lifted off the surface.
ivec2 giJitter() { int f = el_frame & 3; return ivec2(f & 1, f >> 1); }
bool rsSurface(ivec2 hp, out ivec2 px, out vec3 world, out vec3 p, out GNormal g) {
	px = min(hp * 2 + giJitter(), ivec2(el_screenSize) - 1);
	float depth = texelFetch(el_depth, px, 0).r;
	if (el_isSky(depth)) return false;
	world = el_screenToWorld((vec2(px) + 0.5) / el_screenSize, depth);
	g = decodeGNormal(texelFetch(gNormal, px, 0));
	p = world + g.geo * (0.004 + 0.0015 * length(world));
	return true;
}
#endif
