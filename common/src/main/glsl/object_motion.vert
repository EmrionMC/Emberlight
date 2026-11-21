#version 450
// Object motion vectors: one triangle list of moving entity / hand geometry, current and previous clip position per vertex
// (unjittered, GL convention), transformed on the CPU (upscale/ObjectMotion).
layout(location = 0) in vec4 curClip;
layout(location = 1) in vec4 prevClip;
layout(location = 0) out vec4 cur;
layout(location = 1) out vec4 prev;
layout(push_constant) uniform Pc {
	vec2 jitter; // this frame's projection jitter in NDC, so we hit the same pixels as the gbuffers
	int hand;
} pc;

void main() {
	cur = curClip;
	prev = prevClip;
	gl_Position = vec4(curClip.xy + pc.jitter * curClip.w, (curClip.z + curClip.w) * 0.5, curClip.w);
}
