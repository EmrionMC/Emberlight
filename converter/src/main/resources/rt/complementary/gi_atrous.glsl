// RT GI spatial filter: edge-aware a-trous (5x5 B3 spline, step emrGiStep): weights from the geometric normal and the
// distance to the centre texel's plane, so light does not cross depth edges or corners. Young history (disocclusions)
// gets a wider plane tolerance so it converges faster.
// Step 1 reads its taps from a 12x12 shared-memory block; wider steps read the images directly (scattered taps).

layout(rgba16f) uniform readonly image2D emrGiGcur;
vec4 emrGiSrcA(ivec2 q);
vec4 emrGiSrcB(ivec2 q);

shared vec4 emrGiShG[144], emrGiShA[144], emrGiShB[144];
shared vec3 emrGiShP[144];

float emrGiTapWeight(int x, int y, vec4 g, vec3 pos, float tol, vec4 gq, vec3 pq) {
	const float k[3] = float[](0.375, 0.25, 0.0625);
	return k[abs(x)] * k[abs(y)] * pow(max(dot(gq.xyz, g.xyz), 0.0), 32.0) * exp(-abs(dot(g.xyz, pq - pos)) / tol);
}

float emrGiTol(vec4 g, int step, vec4 b) {
	return (0.02 + 0.004 * g.w) * float(step) * (b.a < 4.0 ? 2.0 : 1.0);
}

void emrGiAtrousDirect(ivec2 p, ivec2 size, int step, out vec4 a, out vec4 b) {
	vec4 g = imageLoad(emrGiGcur, p);
	a = emrGiSrcA(p);
	b = emrGiSrcB(p);
	if (g.w <= 0.0) return;
	vec3 pos;
	float z;
	emrGiSurface(p, pos, z);
	float tol = emrGiTol(g, step, b);
	vec4 sa = vec4(0.0), sb = vec4(0.0);
	float ws = 0.0;
	for (int y = -2; y <= 2; y++)
		for (int x = -2; x <= 2; x++) {
			ivec2 q = p + ivec2(x, y) * step;
			if (any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, size))) continue;
			vec4 gq = imageLoad(emrGiGcur, q);
			if (gq.w <= 0.0) continue;
			vec3 pq;
			float zq;
			emrGiSurface(q, pq, zq);
			float w = emrGiTapWeight(x, y, g, pos, tol, gq, pq);
			sa += w * emrGiSrcA(q);
			sb += w * emrGiSrcB(q);
			ws += w;
		}
	if (ws > 1e-4) {
		a = sa / ws;
		b = vec4(sb.rgb / ws, b.a);
	}
}

// Filters this invocation's texel p (returned; outside the image when inside is false). Call from uniform control flow.
void emrGiAtrous(ivec2 size, int step, out ivec2 p, out bool inside, out vec4 a, out vec4 b) {
	ivec2 lid = ivec2(gl_LocalInvocationID.xy), origin = ivec2(gl_WorkGroupID.xy) * 8;
	p = origin + lid;
	inside = all(lessThan(p, size));
	a = vec4(0.0);
	b = vec4(0.0);
	if (emrRt_gi_filter == 0) { // DLSS Ray Reconstruction denoises (gi:filter=0): pass the temporal result through
		if (!inside) return;
		a = emrGiSrcA(p);
		b = emrGiSrcB(p);
		return;
	}
	if (step != 1) {
		if (inside) emrGiAtrousDirect(p, size, step, a, b);
		return;
	}
	for (int i = int(gl_LocalInvocationIndex); i < 144; i += 64) {
		ivec2 q = origin + ivec2(i % 12, i / 12) - 2;
		vec4 gq = vec4(0.0), aq = vec4(0.0), bq = vec4(0.0);
		vec3 pq = vec3(0.0);
		if (all(greaterThanEqual(q, ivec2(0))) && all(lessThan(q, size))) {
			gq = imageLoad(emrGiGcur, q);
			aq = emrGiSrcA(q);
			bq = emrGiSrcB(q);
			float zq;
			if (gq.w > 0.0) emrGiSurface(q, pq, zq);
		}
		emrGiShG[i] = gq;
		emrGiShA[i] = aq;
		emrGiShB[i] = bq;
		emrGiShP[i] = pq;
	}
	barrier();
	if (!inside) return;
	int c = (lid.y + 2) * 12 + lid.x + 2;
	vec4 g = emrGiShG[c];
	a = emrGiShA[c];
	b = emrGiShB[c];
	if (g.w <= 0.0) return;
	vec3 pos = emrGiShP[c];
	float tol = emrGiTol(g, step, b);
	vec4 sa = vec4(0.0), sb = vec4(0.0);
	float ws = 0.0;
	for (int y = -2; y <= 2; y++)
		for (int x = -2; x <= 2; x++) {
			int t = c + y * 12 + x;
			vec4 gq = emrGiShG[t];
			if (gq.w <= 0.0) continue; // outside the image or no surface
			float w = emrGiTapWeight(x, y, g, pos, tol, gq, emrGiShP[t]);
			sa += w * emrGiShA[t];
			sb += w * emrGiShB[t];
			ws += w;
		}
	if (ws > 1e-4) {
		a = sa / ws;
		b = vec4(sb.rgb / ws, b.a);
	}
}
