// Translucent geometry and the hand, lit forward.
out vec2 uv;
out vec2 light;
out vec4 tint;
out vec3 normal;
out vec3 world;
flat out int blockId;

void main() {
	uv = el_vertexUV();
	light = el_vertexLight();
	tint = el_vertexColor();
	normal = mat3(el_viewInverse) * el_vertexViewNormal();
	vec3 view = el_vertexViewPosition();
	world = el_viewToWorld(view);
	blockId = el_blockId();
	gl_Position = el_project(view);
}
