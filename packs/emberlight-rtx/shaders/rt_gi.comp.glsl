// Half resolution: one diffuse path per pixel (1-2 bounces) plus direct light from the block light sources (RT light
// list, ReSTIR DI: spatial reuse and the final shadow ray here, lib/restir.glsl). Each half-res pixel traces for one
// full-res pixel of its 2x2 block, rotating every frame.
#define HIT_EMISSION_SCALE 5.0
#define GI_SKIP_LISTED_EMITTERS 1
#include "/lib/restir.glsl"

// share of the cached light a bounce passes on (< 1 keeps the feedback loop from running away on bright albedo)
const float GI_FEEDBACK = 0.9;

// ReSTIR spatial reuse over this frame's temporal reservoirs (restir.comp.glsl) and the light it brings
vec3 blockLights(ivec2 hp, vec3 p, vec3 n, inout Rng rng) {
	Reservoir d[RS_MAXD];
	d[0] = rsLoad(hp, 0);
	rsCheckLight(d[0]);
	d[0].pos = p; d[0].n = n; d[0].M = max(d[0].M, 1.0);
	int cnt = 1;
	ivec2 hs = imageSize(giRaw_img);
	float rot = rnd(rng) * 6.2831853;
	for (int i = 0; i < RS_NEIGHBOURS; i++) {
		float a = rot + float(i) * 2.3999632, rr = sqrt((float(i) + rnd(rng)) / float(RS_NEIGHBOURS)) * RS_RADIUS;
		ivec2 q = clamp(hp + ivec2(round(vec2(cos(a), sin(a)) * rr)), ivec2(0), hs - 1);
		Reservoir r = rsLoad(q, 0);
		rsCheckLight(r);
		if (q == hp || r.M <= 0.0 || !rsSimilar(p, n, r.pos, r.n)) continue;
		d[cnt++] = r;
	}
	// the merge's visibility rays include the final shadow ray: its sample is visible here
	Reservoir o = rsMerge(d, cnt, false, rng);
	vec3 lit = o.light >= 0 ? rsRadiance(o.light, o.off, p, n) * o.W * BLOCKLIGHT_STRENGTH * 5.0 : vec3(0.0);
	o.M = min(o.M, RS_MCAP);
	rsStore(hp, 1, o);
	return lit;
}

void main() {
	ivec2 hp = ivec2(gl_GlobalInvocationID.xy);
	if (any(greaterThanEqual(hp, imageSize(giRaw_img)))) return;
	ivec2 px = min(hp * 2 + giJitter(), ivec2(el_screenSize) - 1);
	float depth = texelFetch(el_depth, px, 0).r;
	if (el_isSky(depth)) { imageStore(giRaw_img, hp, vec4(0.0)); rsStore(hp, 1, RS_EMPTY); return; }
	Rng rng = rngInit(hp, 2);
	vec3 world = el_screenToWorld((vec2(px) + 0.5) / el_screenSize, depth);
	GNormal g = decodeGNormal(texelFetch(gNormal, px, 0));
	vec3 n = g.geo; // GI is low frequency: sample around the geometric normal (stable under normal maps)
	vec3 p = world + n * (0.004 + 0.0015 * length(world));

	vec3 radiance = vec3(0.0);
#if GI_BOUNCES > 0
	vec3 throughput = vec3(1.0);
	vec3 o = p, nn = n;
	float sky = g.light.y;
	for (int b = 0; b < GI_BOUNCES; b++) {
		vec3 dir = cosineHemisphere(nn, b == 0 ? blueNoise2(hp, 3) : rnd2(rng));
		ElHit h = el_traceClosest(o, dir, b == 0 ? 64.0 : 32.0, MASK_SOLID);
		if (!h.hit) { radiance += throughput * missRadiance(dir, b == 0 ? max(sky, 0.25 * (1.0 - caveFactor)) : sky); break; }
		// emitters of the light list are sampled by blockLights() (and the lightmap at bounce hits): hitting them as well
		// counted them twice and turned small flames and lantern faces into fireflies
		// Many bounces for free: a hit that was on screen last frame takes the GI accumulated there (giAcc, not yet
		// written this frame) as the light it receives, instead of the lightmap estimate. That light already holds its
		// own bounces, so rooms lit through a window or by a glowstone wall fill up instead of staying black next to
		// their bright patches. Rejected unless last frame saw this very hit at that pixel (view distance).
		vec3 cg;
		if (cachedGi(h.position, cg)) {
			radiance += throughput * shadeHitAmb(h, dir, b == 0, true, cg * GI_FEEDBACK);
			break;
		}
		// a path that goes on gathers the sky this hit sees with its next ray: only the last hit adds the lightmap's sky
		// estimate (adding it at every hit counted the sky twice on Ultra's two bounces). Block light stays: the next ray
		// does not sample the light list, and listed emitters it hits do not glow here.
		bool last = b + 1 == GI_BOUNCES;
		radiance += throughput * shadeHitAmb(h, dir, b == 0, true, last ? hitAmbient(h) : hitBlockLight(h) * mix(1.0, h.color.a, 0.6));
		if (!last) {
			throughput *= el_srgbToLinear(el_hitAlbedo(h).rgb * h.color.rgb);
			o = h.position + h.normal * 0.01;
			nn = h.normal;
			sky = h.light.y;
		}
	}
#else
	radiance = skyAmbient(n) * g.light.y * g.light.y;
#endif
	radiance += blockLights(hp, p, n, rng);
	// fireflies hurt the denoiser more than a little lost energy (listed emitters do not glow in here: emitterListed)
	float lum = el_luminance(radiance);
	radiance *= min(1.0, 12.0 / max(lum, 1e-4));
	imageStore(giRaw_img, hp, vec4(radiance, 1.0));
}
