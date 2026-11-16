// A terrain hit gets gbuffers_terrain's vanilla-AO curve, which the water's DoLighting has none of: lifted from the pack.
if (emrRtRfTerrain) { @terrainAoCurve } vanillaAO = vanillaAO * 0.9 + 0.1;
