// RTX materials, after the pack reads the labPBR specular map: where the texel has no specular data, a table by block id
// fills in smoothness (r) and F0 / metal id (g: 230 iron, 231 gold, 234 copper, 255 albedo). Real labPBR maps always win.

if (specularMap.r + specularMap.g < 0.004) {
	// r = perceptual smoothness (1 - roughness). Metals reflect in their own colour (labPBR metal ids tint F0 by the albedo,
	// 255 = albedo as F0); stone, quartz and concrete stay dielectric with weak, Fresnel-only reflections.
	vec2 emrRtxS = mat == 10264 ? vec2(0.65, 230.0 / 255.0) : mat == 10312 ? vec2(0.75, 231.0 / 255.0) : mat == 10292 ? vec2(0.62, 234.0 / 255.0)
		: mat == 10248 ? vec2(0.45, 1.0)
		: (mat == 10316 || mat == 10336) ? vec2(0.85, 0.09) : mat == 10372 ? vec2(0.82, 0.04) : mat == 10364 ? vec2(0.6, 0.04)
		: (mat == 10384 || mat == 10388) ? vec2(0.92, 0.02) : mat == 10436 ? vec2(0.75, 0.04)
		: (mat == 10096 || mat == 10100 || mat == 10104 || mat == 10112 || mat == 10480 || mat == 10712) ? vec2(0.58, 0.04)
		: (mat == 10328 || mat == 10352 || mat == 10608) ? vec2(0.7, 0.04)
		: vec2(0.0); // rougher blocks (concrete, smooth stone) are left out: barely visible, but a reflection ray per pixel
	specularMap.rg = emrRtxS;
}
