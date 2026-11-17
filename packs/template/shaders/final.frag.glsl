// Runs at output resolution (after upscaling): only display-space work here.
layout(location = 0) out vec4 outColor;

void main() {
	outColor = vec4(texture(ldr, el_uv).rgb, 1.0);
}
