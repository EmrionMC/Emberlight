// Runs at output resolution after upscaling: display-space work only.
layout(location = 0) out vec4 outColor;
void main() { outColor = vec4(texture(lit, el_uv).rgb, 1.0); }
