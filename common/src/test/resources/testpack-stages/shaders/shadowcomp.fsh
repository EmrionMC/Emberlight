#version 120
uniform sampler2D shadowcolor0;
varying vec2 texcoord;
void main() {
	/* DRAWBUFFERS:0 */
	gl_FragData[0] = vec4(1.0 - texture2D(shadowcolor0, texcoord).rgb, 1.0);
}
