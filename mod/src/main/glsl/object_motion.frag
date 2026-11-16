#version 450
layout(location = 0) in vec4 cur;
layout(location = 1) in vec4 prev;
layout(location = 0) out vec2 motion;
layout(set = 0, binding = 0) uniform sampler2D depthTex;
layout(push_constant) uniform Pc {
	vec2 jitter;
	int hand;
} pc;

void main() {
	float scene = texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r;
	// visible surface = this triangle: same depth within ~2% of the distance (the pack may still jitter by a fraction of a pixel)
	float z = gl_FragCoord.z;
	if (pc.hand != 0) {
		// Iris hand depth: NDC z scaled by 1/8 (IrisMath.HAND_DEPTH); no depth attachment, so without this the hidden back
		// and side faces of the held item would overwrite its front; + one pixel of slope for edge-on faces under jitter
		if (scene >= 0.56 || abs(z - scene) > 0.0025 * (0.5 - (z - 0.5) * 8.0) + fwidth(z) + 1e-6) discard;
	} else if (scene < 0.56 || abs(z - scene) > 0.02 * (1.0 - z) + 1e-7) discard;
	if (prev.w <= 1e-6 || cur.w <= 1e-6) discard;
	motion = (prev.xy / prev.w - cur.xy / cur.w) * 0.5;
}
