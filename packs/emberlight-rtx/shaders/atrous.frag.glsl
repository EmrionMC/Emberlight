// Spatial part of the GI denoiser: one edge-aware a-trous step (5x5 B3-spline kernel, spacing STEP), run three times
// (1, 2, 4). Edge stops: plane distance, geometric normal and luminance, the latter scaled by the local standard
// deviation and shrinking as the history grows (variance-guided, after SVGF). Reads giAcc in the first step, giFilt after.
#include "/lib/common.glsl"
#include "/lib/materials.glsl"

layout(location = EL_OUT_giFilt) out vec4 outGi;

#if STEP == 1
#define SRC giAcc
#else
#define SRC giFilt
#endif

float pow64(float x) { x *= x; x *= x; x *= x; x *= x; x *= x; return x * x; }

void main() {
	ivec2 px = ivec2(gl_FragCoord.xy);
	vec4 c = texelFetch(SRC, px, 0);
	float depth = texelFetch(el_depthNoHand, px, 0).r;
	if (el_isSky(depth)) { outGi = c; return; }
	// plane distances in view space (the view matrix only rotates): one transform per tap less than in world space
	vec3 view = el_screenToView(el_uv, depth);
	float dist = length(view);
	vec3 n = decodeGNormal(texelFetch(gNormal, px, 0)).geo;
	vec3 nv = mat3(el_view) * n;
	ivec2 size = ivec2(el_screenSize);
	float hist = c.a; // giAcc's history length (each step passes it on in a)

	// local luminance spread (3x3 at this step's spacing); the samples are reused by the 5x5 filter below
	vec3 inner[9];
	float m1 = 0.0, m2 = 0.0;
	for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
		vec3 s = texelFetch(SRC, clamp(px + ivec2(x, y) * STEP, ivec2(0), size - 1), 0).rgb;
		inner[(y + 1) * 3 + x + 1] = s;
		float l = el_luminance(s);
		m1 += l; m2 += l * l;
	}
	m1 /= 9.0; m2 /= 9.0;
	float sd = sqrt(max(m2 - m1 * m1, 0.0));
#if STEP == 1
	// leftover fireflies: an outlier far above its neighbours is pulled down before it gets preserved by the edge stop
	float lc0 = el_luminance(c.rgb);
	if (lc0 > m1 + 2.0 * sd + 0.02) c.rgb *= (m1 + 2.0 * sd + 0.02) / lc0;
#endif
	float lc = el_luminance(c.rgb);
	// a relative floor keeps the edge stop from freezing low-frequency blotches of the accumulated noise in place
	float sigma = 4.0 * sd + 0.35 * max(lc, m1) + 1e-3;
	// freshly disoccluded pixels (edges uncovered by motion) hold a few samples: blur them across the surface instead of
	// keeping their noise (geometry still stops the filter)
	sigma *= mix(6.0, 1.0, clamp(hist / 8.0, 0.0, 1.0));
	float invSigma = 1.0 / sigma, invPlane = 1.0 / (0.02 * dist + 0.04);

	const float k[3] = float[3](0.375, 0.25, 0.0625);
	// the centre (as clamped above, not the outlier again) has weight k0^2: same plane, same normal, same luminance
	vec3 sum = c.rgb * (k[0] * k[0]);
	float wsum = k[0] * k[0];
	for (int y = -2; y <= 2; y++) for (int x = -2; x <= 2; x++) {
		if (x == 0 && y == 0) continue;
		ivec2 q = px + ivec2(x, y) * STEP;
		if (any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, size))) continue;
		float dq = texelFetch(el_depthNoHand, q, 0).r;
		if (el_isSky(dq)) continue;
		vec3 vq = el_screenToView((vec2(q) + 0.5) / el_screenSize, dq);
		vec3 nq = decodeGNormal(texelFetch(gNormal, q, 0)).geo;
		vec3 s = abs(x) <= 1 && abs(y) <= 1 ? inner[(y + 1) * 3 + x + 1] : texelFetch(SRC, q, 0).rgb;
		float w = k[abs(x)] * k[abs(y)]
			* exp(-abs(dot(vq - view, nv)) * invPlane)
			* pow64(max(dot(n, nq), 0.0))
			* exp(-abs(el_luminance(s) - lc) * invSigma);
		sum += s * w;
		wsum += w;
	}
	outGi = vec4(sum / max(wsum, 1e-6), c.a);
}
