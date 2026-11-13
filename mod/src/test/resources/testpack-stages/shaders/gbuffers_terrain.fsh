#version 330 compatibility
uniform sampler2D texture;
uniform sampler2D lightmap;
in vec2 gTexcoord;
in vec2 gLmcoord;
in vec4 gColor;
void main() {
	vec4 color = texture2D(texture, gTexcoord) * gColor * texture2D(lightmap, gLmcoord);
	if (color.a < 0.1) discard;
	/* DRAWBUFFERS:0 */
	gl_FragData[0] = color;
}
