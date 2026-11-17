// One compute pass with ray queries: sun visibility per pixel into rtShadow (1 = lit).
void main() {
	ivec2 px = ivec2(gl_GlobalInvocationID.xy);
	if (any(greaterThanEqual(px, ivec2(el_screenSize)))) return;
	float depth = texelFetch(el_depth, px, 0).r;
	float vis = 1.0;
	if (!el_isSky(depth)) {
		vec3 world = el_screenToWorld((vec2(px) + 0.5) / el_screenSize, depth);
		vec3 n = el_decodeNormal(texelFetch(gNormal, px, 0).rg);
		vec3 l = el_lightDirection;
		if (dot(n, l) <= 0.0) vis = 0.0;
		else {
			// rays towards random points on the sun disc: soft shadows that sharpen near the caster
			vec3 t = normalize(cross(l, abs(l.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
			vec3 b = cross(l, t);
			vec4 noise = el_blueNoise(px);
			vis = 0.0;
			for (int i = 0; i < SHADOW_RAYS; i++) {
				vec2 r = fract(noise.xy + vec2(0.754877, 0.569840) * float(i));
				float a = 6.2831853 * r.x, radius = sqrt(r.y) * SHADOW_SOFTNESS * 0.012;
				vec3 dir = normalize(l + (t * cos(a) + b * sin(a)) * radius);
				vis += el_traceVisibility(world + n * 0.02, dir, 512.0, EL_MASK_OPAQUE | EL_MASK_ENTITIES) ? 1.0 : 0.0;
			}
			vis /= float(SHADOW_RAYS);
		}
	}
	imageStore(rtShadow_img, px, vec4(vis));
}
