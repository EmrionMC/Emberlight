// DoRefraction: gbuffers_water traced and lit the floor behind water (refraction.glsl), so no screen offset here; only the
// pack's depth behind the surface moves to the traced path, for its fog and light shafts behind the surface.
if (z0 < z1 && z0 > 0.56 && int(texelFetch(colortex6, texelCoord, 0).g * 255.1) == 241 && smoothstep(0.4 * far, 0.6 * far, length(viewPos)) == 0.0) {
	vec3 o = emrRtRfWorld(viewPos), d = normalize(o), nf = vec3(0.0, 1.0, 0.0), r;
	int inst; uint prim; vec2 b;
	if (emrRtRfTrace(o - d * 0.25, d, 0.0, 0.5, 0x02u, inst, prim, b) >= 0.0) nf = emrRtRfNormal(inst, prim, d);
	float inside;
	if (emrRtRfPath(d, nf, texelFetch(colortex4, texelCoord, 0).xyz, true, o, r, inside)) {
		float t = emrRtRfTrace(o, r, 0.01, 256.0, emrRt_refraction_entities != 0 ? 0x05u : 0x01u, inst, prim, b);
		if (t > 0.0) {
			vec4 c = gbufferProjection * vec4(viewPos * ((length(viewPos) + t) / length(viewPos)), 1.0);
			z1 = clamp(c.z / c.w * 0.5 + 0.5, z0, 1.0);
		}
	}
}
return texCoord.xy;
