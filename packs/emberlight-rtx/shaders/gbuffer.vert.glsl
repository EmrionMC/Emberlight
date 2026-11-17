// Opaque geometry: terrain, entities, block entities, opaque particles.
out vec2 uv;
out vec2 light;
out vec4 tint;
out vec3 normal;
out vec3 world;
flat out int blockId;
flat out float emission;
flat out vec2 midUV;

void main() {
	uv = el_vertexUV();
	light = el_vertexLight();
	tint = el_vertexColor();
	normal = mat3(el_viewInverse) * el_vertexViewNormal();
	vec3 view = el_vertexViewPosition();
	world = el_viewToWorld(view);
	blockId = el_blockId();
	// entities, block entities, held/dropped items and worn armour: their ids from emberlight.json "items"/"entities"
	// share the block table's material numbers. blockEntityId 1 only marks a block item (Iris), it is no material.
	if (blockId < 0) {
		int item = el_itemId(), be = el_blockEntityId(), ent = el_entityId();
		blockId = item > 0 ? item : be > 1 ? be : ent > 0 ? ent : -1;
	}
	midUV = el_midUV();
	emission = clamp(el_vertexEmission() / 15.0, 0.0, 1.0);
	gl_Position = el_project(view);
}
