// RTX materials, after the pack reads the labPBR specular map: where the texel has no specular data, the table
// (materials_table.glsl) fills in smoothness and F0 / metal id. Real labPBR maps always win.
if (specularMap.r + specularMap.g < 0.004) {
	specularMap.rg = emrRtxMaterial(mat);
	// all copper oxidation stages share one id: green/teal patina texels are a rough mineral crust, not copper metal
	// (as copper they mirrored the orange floor and an oxidised wall in the sun turned brown)
	if (mat == 10292 && color.g > color.r) specularMap.rg = vec2(0.0);
}
