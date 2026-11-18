// Deferred lighting: diffuse (sun + denoised GI/block lights + a lightmap fill), specular (sun highlight + ray-traced
// reflections with an ambient floor), emission. Sky pixels get the pack's sky.
#include "/lib/common.glsl"
#include "/lib/materials.glsl"
#include "/lib/sky.glsl"

layout(location = EL_OUT_lit) out vec4 outLit;
#ifdef EL_RR_ACTIVE
#define giFilt giAcc // the a-trous passes do not run under Ray Reconstruction
#endif

void main() {
	ivec2 px = ivec2(gl_FragCoord.xy);
	float depth = texelFetch(el_depth, px, 0).r;
	vec3 world = el_screenToWorld(el_uv, depth);
	vec3 v = normalize(world);
	if (el_isSky(depth)) { outLit = vec4(skyRadiance(v), 1.0); return; }

	vec4 ga = texelFetch(gAlbedo, px, 0);
	vec3 albedo = el_srgbToLinear(ga.rgb);
	GNormal g = decodeGNormal(texelFetch(gNormal, px, 0));
	vec3 n = g.n;
	vec3 sn = specularNormal(g.n, g.geo);
	Mat m = decodeMat(texelFetch(gMat, px, 0));
	float rough = roughnessOf(m.smoothness);
	bool metal = isMetal(m.f0);
	vec3 f0 = matF0(m, albedo);
	float nv = max(dot(sn, -v), 1e-3);
	float ao = mix(1.0, ga.a, 0.5);

	vec3 l = lightDir();
	vec3 sun = texelFetch(shadowAcc, px, 0).rgb * lightColor();
	float caustic = texelFetch(sunRaw, px, 0).a; // rt_direct.comp.glsl: water caustics, refracted (>= 0) or reflected (< 0)
	if (caustic >= 0.0) sun *= caustic;
	vec3 gi = texelFetch(giFilt, px, 0).rgb;
	// slightly wrapped so a normal-mapped texel tilted away from the sun is shaded, not black
	float nl = clamp((dot(n, l) + 0.08) / 1.08, 0.0, 1.0) * step(0.0, dot(g.geo, l) + 0.02);

	// lightmap fill: the many-bounce light near emitters that one GI bounce misses (flush floor lights, lamp corners)
	vec3 fill = lightmapBlock(g.light.x) * 0.3;
	vec3 diffuse = sun * nl + (gi + fill) * ao;
	if (caustic < 0.0) diffuse += lightColor() * -caustic * vec3(0.85, 0.95, 1.0);
	// subsurface scattering (LabPBR SSS, built-in for plants): light wraps past the terminator, and thin plants let the
	// sun through, strongest when the camera looks toward it (forward scattering), coloured by the leaf
	float sss = matSss(m.kind) * SSS;
	if (sss > 0.0) {
		vec3 scatter = sqrt(albedo / max(max(albedo.r, max(albedo.g, albedo.b)), 1e-3));
		float w = 0.6 * sss;
		diffuse += sun * max(clamp((dot(n, l) + w) / (1.0 + w), 0.0, 1.0) - nl, 0.0) * scatter;
		if (matThin(m.kind)) {
			float phase = 0.5 + 3.0 * pow(max(dot(v, l), 0.0), 6.0) * rtxBoost();
			diffuse += sun * max(-dot(g.geo, l), 0.0) * phase * sss * scatter;
		}
	}
	// held light (torch in hand): unshadowed, close range
	float held = float(max(el_heldLight, el_heldLight2)) / 15.0;
	if (held > 0.0) {
		float d = length(world);
		vec3 hc = el_srgbToLinear(max(el_heldLightColor, el_heldLightColor2));
		if (dot(hc, hc) < 1e-4) hc = BLOCK_LIGHT;
		diffuse += hc * held * 3.0 * max(dot(n, -v), 0.0) / (1.0 + d * d * 0.15) * (1.0 - smoothstep(0.0, held * 15.0, d)) * BLOCKLIGHT_STRENGTH;
	}

	vec3 spec = vec3(0.0), reflGlow = vec3(0.0);
	vec3 kd = metal ? vec3(0.0) : vec3(1.0);
	// what a surface would reflect without a ray: its diffuse light plus the sky it sees
	vec3 r = reflect(v, sn);
	// The GI already holds the sky a surface receives; the sky term only adds what a reflection pointing up sees, so a
	// rough lobe at a grazing angle (grass seen from standing height) reflects its surroundings, not a cyan sky sheen.
	// A rough lobe averages the whole hemisphere, which the GI already is: the sky term fades out with roughness (at
	// sunset it laid a blue-grey sheen over forest grass, brighter than its green diffuse light)
	vec3 envAmbient = gi + fill + skyAmbient(r) * g.light.y * g.light.y * clamp(r.y * 1.5, 0.0, 1.0)
		* (1.0 - smoothstep(0.3, 0.75, 1.0 - m.smoothness));
	if (m.smoothness > 0.05) {
		vec3 fe = envBrdf(f0, 1.0 - m.smoothness, nv);
		// fade from the traced reflection to the ambient estimate toward the cut-off: a hard switch made texels on either
		// side of it (bricks whose smoothness varies per texel) a patchwork of reflecting and non-reflecting ones
		float traced = REFL_MAX_ROUGH > 0.0 ? 1.0 - smoothstep(REFL_MAX_ROUGH * 0.55, REFL_MAX_ROUGH, rough) : 0.0; // reflections off: -1
		vec3 rf = envAmbient * 1.2;
		vec4 ra = texelFetch(reflAcc, px, 0);
		if (traced > 0.0) rf = mix(rf, ra.rgb, traced);
		reflGlow = ra.rgb * (ra.a * traced) * fe * rtxBoost();
		// metals: a second, rough lobe (50 %) that sees the ambient light and the sun, as worn and scratched metal does:
		// a pure mirror only showed what it reflected (the floor in front of a gold block), so gold and copper read as
		// tinted glass; with it they keep their own colour and texture in any surroundings
		// (not on perfect mirrors: smoothness 1 is a mirror on purpose, Emberlight PBR's tinted concrete)
		if (metal) rf = mix(rf, envAmbient * 1.2 + sun * nl * 0.25, 0.5 * smoothstep(0.0, 0.01, rough));
		spec += rf * fe * rtxBoost();
		kd *= 1.0 - fe;
	} else {
		kd *= 1.0 - fresnelSchlick(f0, nv) * 0.5;
	}
	// sun highlight: a little wider on metals so polished blocks catch the sun (alpha floors 0.08^2 / 0.04^2)
	spec += sunSpecular(sn, -v, l, max(rough, metal ? 0.0064 : 0.0016), f0) * sun * 2.0;

	vec3 c = albedo * kd * diffuse + spec;
#if DEBUG_VIEW == 1
	c = gi;
#elif DEBUG_VIEW == 2
	c = texelFetch(giAcc, px, 0).rgb;
#elif DEBUG_VIEW == 3
	c = texelFetch(shadowAcc, px, 0).rgb;
#elif DEBUG_VIEW == 4
	c = texelFetch(reflAcc, px, 0).rgb;
#elif DEBUG_VIEW == 5
	c = kd * diffuse + spec;
#endif
	c += emissiveColor(albedo) * m.emission * EMISSIVE_STRENGTH * 2.0 * rtxBoost();
	// a = bloom gain (bloom.frag.glsl): own glow, or the share of the image that is glowing neon seen in a reflection
	float glowShare = el_luminance(reflGlow) / max(el_luminance(c), 1e-4);
	outLit = vec4(c, 1.0 + 8.0 * NEON_GLOW * max(m.emission, min(glowShare, 1.0)));
}
