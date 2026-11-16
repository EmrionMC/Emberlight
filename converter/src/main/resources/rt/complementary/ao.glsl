// RT ambient occlusion for Complementary r5: the mod's RT AO pass writes emrion_rtao before deferred1; the patched
// GetAmbientOcclusion only reads it.

uniform sampler2D emrRtAo;
