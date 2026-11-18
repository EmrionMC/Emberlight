// gbuffers_water output: the hit behind this water or glass fragment, lit by the pack's own DoLighting as gbuffers_terrain and
// deferred1 light the floor (AO, fog), under any glass the path passes, with the fragment's colour over it at its own alpha
// as one opaque colour (blend factor 1), so nothing of the straight floor behind shows.
{
	vec4 emrOut = color;
	bool emrWater = mat == 32000;
	if ((emrWater || uint(mat - 31000) < 1000u || mat == 30012 || mat == 30016 || mat == 32004) && emrRtRfFragment(playerPos, normal, normalM, emrWater)) {
		float emrNdotU = NdotU, emrNdotUmax0 = NdotUmax0;
		int emrGI; uint emrGP; vec2 emrGB;
		float emrGT = emrWater ? emrRtRfGlass(emrRtRfO, emrRtRfR, 0.01, emrRtRfT, emrGI, emrGP, emrGB) : -1.0;
		vec3 emrS = vec3(0.0);
		for (int emrL = 0; emrL < (emrGT > 0.0 ? 2 : 1); emrL++) { // the solid hit, then glass in front of it
			bool emrIsGlass = emrL == 1;
			int emrI = emrIsGlass ? emrGI : emrRtRfInst; uint emrP = emrIsGlass ? emrGP : emrRtRfPrim; vec2 emrB = emrIsGlass ? emrGB : emrRtRfB;
			float emrT = emrIsGlass ? emrGT : emrRtRfT;
			vec3 emrHp = emrRtRfO + emrRtRfR * emrT, emrHn = emrRtRfNormal(emrI, emrP, emrRtRfR);
			float emrAo; vec2 emrLm;
			vec4 emrC = emrRtRfAlbedo(emrI, emrP, emrB, emrRtRfR, emrRtRfPathLen - emrRtRfT + emrT, emrAo, emrLm);
			bool emrEnt = emrRtSection[emrI].z >= 8u;
			int emrId = emrEnt ? 0 : emrRtId(emrRtVerts(emrI), emrRtTri(emrP).x);
			// as gbuffers_terrain sets them up (its own statements, lifted from the pack, on the hit's values): light sources glow,
			// plants are lit through (subsurface), mobs keep their vertex AO
			bool emrNoSmooth = false, emrNoDir = false, emrSource = false;
			float emrEm = 0.0;
			int emrSss = 0;
			{
				int mat = emrId, blockLightEmission = 0, subsurfaceMode = 0;
				vec4 color = emrC;
				vec2 lmCoord = emrLm, lmCoordM = emrLm;
				vec3 worldGeoNormal = emrHn;
				float emission = 0.0, overlayNoiseIntensity = 1.0, sandNoiseIntensity = 1.0, mossNoiseIntensity = 1.0, snowMinNdotU = 0.0;
				bool isLightSource = false, isFoliage = false, noSmoothLighting = false, noDirectionalShading = false;
				@lightSource
				if (mat == 10068 || mat == 10070) { @lavaEmission } else @materialEmission
				if (!emrEnt && emrRtSection[emrI].z == 1u) { // solid terrain: the pack's cross-model rule and its plant ids
					@crossModel
					// foliage and leaves: ids 10003 to 10031 as a bit set
					if (uint(mat - 10003) < 29u && (0x141553F5u >> uint(mat - 10003) & 1u) != 0u) {
						subsurfaceMode = 1;
						noSmoothLighting = true;
						noDirectionalShading = true;
					}
				}
				emrNoSmooth = noSmoothLighting; emrNoDir = noDirectionalShading; emrSource = isLightSource;
				emrEm = emission; emrSss = subsurfaceMode; emrLm = lmCoordM;
			}
			// as rtx/blocklight_apply_terrain.glsl: the red torch and dust texels of powered repeaters and comparators keep the pack's light
			if (emrId == 10644 && emrC.r > 0.5 && emrC.r - max(emrC.g, emrC.b) > 0.125) emrSource = true;
			vec3 emrHv = (gbufferModelView * vec4(emrHp, 1.0)).xyz, emrNv = mat3(gbufferModelView) * emrHn;
			// a plant is lit through like gbuffers_terrain's subsurface mode: full light from any side
			vec3 emrLn = emrSss != 0 ? lightVec : emrNv;
			NdotU = emrSss != 0 ? 1.0 : dot(emrNv, upVec);
			NdotUmax0 = max(NdotU, 0.0);
			emrRtRfAoIn = emrEnt ? 1.0 : emrAo;
			emrRtRfTerrain = !emrEnt && !emrIsGlass;
			vec4 emrLit = vec4(emrC.rgb, 1.0);
			vec3 emrSh = vec3(1.0);
			float emrPk = 0.0, emrDd = 1.0, emrLv = length(emrHv);
			// centerShadowBias (terrain-only, unused for water) marks a refraction hit: RTX block light then traces it as on terrain
			emrRtHitRays = emrRt_refraction_sunRays; // TAA / DLSS average the per-frame jittered ray
			DoLighting(emrLit, emrSh, emrHp, emrHv, emrLv, emrLn, emrLn, dither, emrHn, emrLm, emrNoSmooth, emrNoDir, false,
				emrRt_refraction_blockLight != 0, emrSss, 0.0, 1.0, emrEm @euphoriaArgs);
			emrRtHitRays = 0;
			emrRtRfAoIn = -1.0;
			emrRtRfTerrain = false;
			// gbuffers_terrain's light highlight (rough GGX, the plants' backlight), which the water's DoLighting leaves out; the
			// backlight and the shadow mix are the pack's terrain DoLighting statements, lifted
			vec3 emrNvp = normalize(emrHv);
			{
				int subsurfaceMode = emrSss;
				vec3 nViewPos = emrNvp, shadowMult = emrSh, shadowPos = vec3(0.0), lightHighlight = vec3(0.0);
				float subsurfaceHighlight = 0.0, offset = 0.0, lightmapYM = 0.0, NdotL = 0.0, NdotLM = 0.0;
				bool leaves = false;
				if (subsurfaceMode != 0) { @subsurfaceHighlight }
				@highlightMix
				emrLit.rgb += lightHighlight * (subsurfaceHighlight + GGX(emrNv, emrNvp, lightVec, max(dot(emrNv, lightVec), 0.0), 0.0 @euphoriaF0)) * highlightColor;
			}
			if (!emrEnt && !emrIsGlass && emrRtRfEmission(emrI, emrP) < 6.0) emrLit.rgb *= emrRtRfAo(emrHp, emrHn); // deferred1: the RT AO
			float emrSf = 0.0;
			DoFog(emrLit, emrSf, emrLv, emrHp, dot(emrNvp, upVec), dot(emrNvp, sunVec), dither, false, 0.0);
			emrS = emrIsGlass ? mix(emrS, emrLit.rgb, emrC.a) : emrLit.rgb;
		}
		NdotU = emrNdotU; NdotUmax0 = emrNdotUmax0;
		// fade to the pack's (unrefracted) image towards the render distance, where the bend is sub-pixel
		float emrF = smoothstep(0.4 * far, 0.6 * far, length(playerPos));
		emrOut.a = 1.0 - (1.0 - color.a) * emrF;
		emrOut.rgb = (color.rgb * color.a + (1.0 - color.a) * (1.0 - emrF) * emrS) / emrOut.a;
	}
	gl_FragData[0] = emrOut;
}
