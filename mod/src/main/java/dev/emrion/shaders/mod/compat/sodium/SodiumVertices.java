package dev.emrion.shaders.mod.compat.sodium;

import dev.emrion.shaders.mod.vertex.BlockContext;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;

/** Copies the worker thread's current block (BlockContext) into a quad's Sodium vertices. */
public final class SodiumVertices {
	private SodiumVertices() {}

	public static void tag(ChunkVertexEncoder.Vertex[] quad) {
		if (!EmrionVertexFormats.active()) return;
		BlockContext c = BlockContext.get();
		for (ChunkVertexEncoder.Vertex v : quad) c.copyTo((VertexBlockData) v);
	}
}
