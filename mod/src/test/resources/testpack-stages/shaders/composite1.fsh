#version 120
uniform sampler2D colortex0;
uniform float viewWidth;
uniform float viewHeight;
void main() {
	vec2 uv = gl_FragCoord.xy / vec2(viewWidth, viewHeight);
	/* DRAWBUFFERS:0 */
	gl_FragData[0] = vec4(texture2D(colortex0, uv).rgb * vec3(1.0, 0.3, 0.3), 1.0);
}
