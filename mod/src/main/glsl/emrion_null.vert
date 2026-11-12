#version 450
// Draws nothing (every vertex outside the clip volume): the gbuffers stand-in for a vanilla pipeline no pack program can serve.
void main() {
	gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
}
