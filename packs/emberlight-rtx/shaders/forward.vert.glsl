// Particles, weather, translucent entities and the hand: lit forward, composited in the resolve pass.
out vec2 uv;
out vec2 light;
out vec4 tint;
out vec3 normal;
flat out float emission;

void main() {
	uv = el_vertexUV();
	light = el_vertexLight();
	tint = el_vertexColor();
	normal = mat3(el_viewInverse) * el_vertexViewNormal();
	emission = clamp(el_vertexEmission() / 15.0, 0.0, 1.0);
	gl_Position = el_project(el_vertexViewPosition());
}
