#version 330 compatibility
uniform sampler2D tex;
in vec2 gTexcoord;
in vec4 gColor;
void main() {
	vec4 color = texture2D(tex, gTexcoord) * gColor;
	if (color.a < 0.1) discard;
	gl_FragData[0] = color;
}
