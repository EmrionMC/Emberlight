package dev.emrion.shaders.mod.vertex;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * The block currently being meshed on this worker thread (Iris's BlockContextHolder). Set by the SectionCompiler hooks before
 * each block/fluid is tesselated, read by {@link ExtendedBufferBuilder} per vertex. One instance per thread, no locks.
 */
public final class BlockContext {
	private static final ThreadLocal<BlockContext> CURRENT = ThreadLocal.withInitial(BlockContext::new);

	/** mc_Entity.x, mc_Entity.y (0 = block, 1 = fluid), at_midBlock.w, block position inside the section. */
	int id = -1, renderType, emission, x, y, z;
	/** True while a section compile tesselates a block model on this thread (separateAo applies to chunk meshes only). */
	public boolean inSection;
	/** The fluid block a section compile is tesselating on this thread (Sodium-style fluid lighting), or null. */
	public net.minecraft.client.renderer.block.@org.jspecify.annotations.Nullable BlockAndTintGetter fluidLevel;
	public net.minecraft.core.@org.jspecify.annotations.Nullable BlockPos fluidPos;
	public @org.jspecify.annotations.Nullable FluidState fluidState;

	public static BlockContext get() { return CURRENT.get(); }

	public void block(BlockState state, int localX, int localY, int localZ) {
		set(EmrionVertexFormats.ids().block(state), 0, state.getLightEmission(), localX, localY, localZ);
	}

	/** Iris: fluids use the id of their legacy block state (water → minecraft:water), render type 1. */
	public void fluid(BlockState state, FluidState fluid, int localX, int localY, int localZ) {
		set(EmrionVertexFormats.ids().block(fluid.createLegacyBlock()), 1, state.getLightEmission(), localX, localY, localZ);
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
