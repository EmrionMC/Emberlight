package dev.emrion.shaders.mod.mixin.sodium;

import dev.emrion.shaders.mod.compat.sodium.VertexBlockData;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: translucent quads are kept (copied) for sorting and encoded later, so the block data must travel with the vertex. */
@Mixin(ChunkVertexEncoder.Vertex.class)
public class ChunkVertexMixin implements VertexBlockData {
	@Unique private int id = -1, renderType, emission, x, y, z;

	@Override
	public void emrion$setBlock(int id, int renderType, int emission, int x, int y, int z) {
		this.id = id;
		this.renderType = renderType;
		this.emission = emission;
		this.x = x;
		this.y = y;
		this.z = z;
	}

	@Override public int emrion$id() { return id; }
	@Override public int emrion$renderType() { return renderType; }
	@Override public int emrion$emission() { return emission; }
	@Override public int emrion$x() { return x; }
	@Override public int emrion$y() { return y; }
	@Override public int emrion$z() { return z; }

	@Inject(method = "copyVertexTo", at = @At("HEAD"))
	private static void emrion$copy(ChunkVertexEncoder.Vertex from, ChunkVertexEncoder.Vertex to, CallbackInfo ci) {
		VertexBlockData f = (VertexBlockData) from;
		((VertexBlockData) to).emrion$setBlock(f.emrion$id(), f.emrion$renderType(), f.emrion$emission(), f.emrion$x(), f.emrion$y(), f.emrion$z());
	}
}
