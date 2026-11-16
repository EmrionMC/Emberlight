// gbuffers_terrain: the red torch and dust texels of powered repeaters and comparators keep the pack's light like light
// sources. The pack lights them with its floodfill light from the repeater itself, which RT does not know (no vanilla light
// emission), so S / U took it away and the heads went dull red-brown (RT off they glow).
blockLighting = emrRtxBlockLight(blockLighting, playerPos, worldGeoNormal,
	isLightSource || mat == 10644 && color.r > 0.5 && color.r - max(color.g, color.b) > 0.125, lightmap.x);
