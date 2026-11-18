// RTX materials, after the pack reads the labPBR specular map: where the texel has no specular data, the table
// (materials_table.glsl) fills in smoothness and F0 / metal id. Real labPBR maps always win.
if (specularMap.r + specularMap.g < 0.004) specularMap.rg = emrRtxMaterial(mat);
