#version 120
// Inverts the scene in the left half, keeps it in the right half.
uniform sampler2D colortex0;
varying vec2 texcoord;
void main() {
    vec3 c = texture2D(colortex0, texcoord).rgb;
    /* DRAWBUFFERS:0 */
    gl_FragData[0] = vec4(texcoord.x < 0.5 ? 1.0 - c : c, 1.0);
}
