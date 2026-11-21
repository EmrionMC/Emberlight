#version 400 compatibility
uniform sampler2D texture;
uniform sampler2D lightmap;
in vec2 teTexcoord;
in vec2 teLmcoord;
in vec4 teColor;
void main() {
	vec4 color = texture2D(texture, teTexcoord) * teColor * texture2D(lightmap, teLmcoord);
	if (color.a < 0.1) discard;
	/* DRAWBUFFERS:0 */
	gl_FragData[0] = color;
}
