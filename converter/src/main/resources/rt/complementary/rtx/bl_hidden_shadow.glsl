// gbuffers_terrain: a fragment raster draws over later (rtx/blocklight.glsl emrRtxBlHidden) skips its RT shadow rays: in foliage
// most leaf fragments are overdrawn, and each traced its own. One shadow-map tap instead, close to the RT shadow where the view
// ray and raster disagree (a leaf texel at another mip level).
if (emrRt_blocklight_hidden != 0 && emrRtxBlHidden()) shadowMult *= shadow2D(shadowtex0, shadowPos).x;
else shadowMult *= GetShadow(shadowPos, lightmap.y, offset, shadowSamples, leaves, playerPos);
