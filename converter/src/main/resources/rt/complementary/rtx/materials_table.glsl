// RTX material table by pack block id: perceptual smoothness (r) and F0 / labPBR metal id (g: 230 iron, 231 gold,
// 234 copper, 255 albedo); vec2(0.0) = not in the table.
vec2 emrRtxMaterial(int mat) {
	// r = perceptual smoothness (1 - roughness). Metals reflect in their own colour (labPBR metal ids tint F0 by the albedo,
	// 255 = albedo as F0); stone, quartz and concrete stay dielectric with weak, Fresnel-only reflections.
	return mat == 10264 ? vec2(0.65, 230.0 / 255.0) : mat == 10312 ? vec2(0.75, 231.0 / 255.0) : mat == 10292 ? vec2(0.62, 234.0 / 255.0)
		: mat == 10248 ? vec2(0.45, 1.0)
		: (mat == 10316 || mat == 10336) ? vec2(0.85, 0.09) : mat == 10372 ? vec2(0.82, 0.04) : mat == 10364 ? vec2(0.6, 0.04)
		: (mat == 10384 || mat == 10388) ? vec2(0.92, 0.02) : mat == 10436 ? vec2(0.75, 0.04)
		// polished granite / diorite only: the pack's polished andesite, deepslate, blackstone and tuff ids also hold bricks,
		// mud, plain tuff and blackstone, which turned into grazing sky mirrors
		: (mat == 10096 || mat == 10100) ? vec2(0.58, 0.04)
		: (mat == 10328 || mat == 10352 || mat == 10608) ? vec2(0.7, 0.04)
		: vec2(0.0); // rougher blocks (concrete, smooth stone) are left out: barely visible, but a reflection ray per pixel
}
