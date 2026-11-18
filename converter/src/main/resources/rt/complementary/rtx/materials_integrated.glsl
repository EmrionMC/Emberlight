// RTX materials without a labPBR read to hook (integrated PBR, the default, and PBR off): just before the pack lights the
// terrain, the table's smoothness where the pack's own material code gave the surface no reflectivity at all. The pack's
// materials always win; with no F0 slot here, table metals the pack leaves bare reflect like dielectrics.
if (@integrated && smoothnessG < 0.004 && smoothnessD < 0.004) {
	float emrRtxR = emrRtxMaterial(mat).r;
	smoothnessG = emrRtxR;
	smoothnessD = emrRtxR;
}
DoLighting(color, shadowMult,
