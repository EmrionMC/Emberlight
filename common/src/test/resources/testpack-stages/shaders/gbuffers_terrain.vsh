#version 330 compatibility
out vec2 texcoord;
out vec2 lmcoord;
out vec4 glcolor;
out vec3 viewPos;
void main() {
	vec4 v = gl_ModelViewMatrix * gl_Vertex;
	viewPos = v.xyz;
	gl_Position = gl_ProjectionMatrix * v;
	texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
	lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
	glcolor = gl_Color;
}
