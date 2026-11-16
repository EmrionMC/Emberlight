package dev.emrion.shaders.mod.compat.sodium;

/** Fields our mixin adds to Sodium's ChunkVertexEncoder.Vertex: the block a quad belongs to (Iris's ChunkVertexExtension). */
public interface VertexBlockData {
	/** id = block.properties id (−1 unmapped), renderType 0 block / 1 fluid, emission 0–15, block position inside the section. */
	void emrion$setBlock(int id, int renderType, int emission, int x, int y, int z);

	int emrion$id();

	int emrion$renderType();

	int emrion$emission();

	int emrion$x();

	int emrion$y();

	int emrion$z();
}
