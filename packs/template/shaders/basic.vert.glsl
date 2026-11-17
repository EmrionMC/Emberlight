out vec4 tint;

void main() {
	tint = el_vertexColor();
	gl_Position = el_project(el_vertexViewPosition());
}
