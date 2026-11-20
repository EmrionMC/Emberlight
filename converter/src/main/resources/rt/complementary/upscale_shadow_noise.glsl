// The pack keeps the shadow noise still on entities and the hand, which its TAA skips. The upscaler accumulates them (they
// have motion vectors), so a still pattern stays as visible grain in every penumbra (the elytra on the player's back).
return fract(52.9829189 * fract(0.06711056 * gl_FragCoord.x + 0.00583715 * gl_FragCoord.y) + goldenRatio * mod(float(frameCounter), 3600.0));
