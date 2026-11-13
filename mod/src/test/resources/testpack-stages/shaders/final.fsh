#version 120
uniform sampler2D colortex0;
uniform sampler2D shadowcolor0;
varying vec2 texcoord;
void main() {
	vec3 color = texture2D(colortex0, texcoord).rgb;
	if (texcoord.x < 0.3 && texcoord.y < 0.3 * 16.0 / 9.0) color = texture2D(shadowcolor0, texcoord / vec2(0.3, 0.3 * 16.0 / 9.0)).rgb;
	
	gl_FragColor = vec4(color, 1.0);
}
