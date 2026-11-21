#version 120
// Vignette over colortex0, plus the compute result in the bottom-left corner.
uniform sampler2D colortex0;
uniform sampler2D testSampler;
uniform float viewWidth;
uniform float viewHeight;
varying vec2 texcoord;
void main() {
    vec3 c = texture2D(colortex0, texcoord).rgb;
    vec2 d = texcoord - 0.5;
    c *= clamp(1.0 - dot(d, d) * 2.5, 0.0, 1.0);
    vec2 px = texcoord * vec2(viewWidth, viewHeight);
    if (px.x < 256.0 && px.y < 256.0) c = texture2D(testSampler, px / 256.0).rgb;
    gl_FragColor = vec4(c, 1.0);
}
