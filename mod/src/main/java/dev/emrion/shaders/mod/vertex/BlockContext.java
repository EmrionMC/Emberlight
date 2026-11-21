package dev.emrion.shaders.mod.vertex;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * The block currently being meshed on this worker thread (Iris's BlockContextHolder). Set by the Sodium meshing hooks before
 * each block/fluid is tesselated, copied into each Sodium vertex. One instance per thread, no locks.
 */
public final class BlockContext {
	private static final ThreadLocal<BlockContext> CURRENT = ThreadLocal.withInitial(BlockContext::new);

	/** mc_Entity.x, mc_Entity.y (0 = block, 1 = fluid), at_midBlock.w, block position inside the section. */
	int id = -1, renderType, emission, x, y, z;
	/** A banner: Better Block Entities meshes its cloth as translucent (BlockRendererMixin keeps it cutout). */
	public boolean banner;

	public static BlockContext get() { return CURRENT.get(); }

	public void block(BlockState state, int localX, int localY, int localZ) {
		set(EmrionVertexFormats.ids().block(state), 0, state.getLightEmission(), localX, localY, localZ);
		banner = state.getBlock() instanceof net.minecraft.world.level.block.AbstractBannerBlock;
	}

	/** Iris: fluids use the id of their legacy block state (water → minecraft:water), render type 1. */
	public void fluid(BlockState state, FluidState fluid, int localX, int localY, int localZ) {
		set(EmrionVertexFormats.ids().block(fluid.createLegacyBlock()), 1, state.getLightEmission(), localX, localY, localZ);
		banner = false;
	}

	/** Hands this block to a Sodium vertex (compat.sodium; typed loosely so this class needs no Sodium types). */
	public void copyTo(dev.emrion.shaders.mod.compat.sodium.VertexBlockData v) {
		v.emrion$setBlock(id, renderType, emission, x, y, z);
	}

	private void set(int id, int renderType, int emission, int x, int y, int z) {
		this.id = id;
		this.renderType = renderType;
		this.emission = emission;
		this.x = x;
		this.y = y;
		this.z = z;
	}
}
