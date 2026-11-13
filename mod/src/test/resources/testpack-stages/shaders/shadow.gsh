#version 330 compatibility
layout(triangles) in;
layout(triangle_strip, max_vertices = 3) out;
in vec2 texcoord[];
in vec4 glcolor[];
in vec3 viewPos[];
out vec2 gTexcoord;
out vec4 gColor;
void main() {
	vec3 centre = (viewPos[0] + viewPos[1] + viewPos[2]) / 3.0;
	vec3 n = normalize(cross(viewPos[1] - viewPos[0], viewPos[2] - viewPos[0]));
	for (int i = 0; i < 3; i++) {
		gTexcoord = texcoord[i];
		gColor = glcolor[i] * vec4(abs(n) * 0.6 + 0.4, 1.0);
		gl_Position = gl_ProjectionMatrix * vec4(mix(centre, viewPos[i], 0.6), 1.0);
		EmitVertex();
	}
	EndPrimitive();
}
