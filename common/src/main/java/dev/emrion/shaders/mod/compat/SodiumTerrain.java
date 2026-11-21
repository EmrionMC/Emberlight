package dev.emrion.shaders.mod.compat;

/**
 * Sodium's chunk renderer, reached by name. Sodium (a required dependency) replaces vanilla meshing; compat.sodium holds
 * the code that needs Sodium's types.
 */
public final class SodiumTerrain {
	private SodiumTerrain() {}

	/**
	 * Sodium's terrain pipelines (solid, cutout, translucent), created now through a throwaway DefaultChunkRenderer so we can
	 * build ours at pack load (Sodium caches them in a static map, so its renderers later draw with these same objects).
	 */
	public static java.util.List<com.mojang.renderpearl.api.pipeline.RenderPipeline> pipelines() {
		try {
			Class<?> type = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType");
			Class<?> passType = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass");
			Class<?> shader = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer");
			Class<?> oit = Class.forName("net.minecraft.client.renderer.oit.OitStage");
			Object format = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats").getMethod("getCurrent").invoke(null);
			Object renderer = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer").getConstructor(type).newInstance(format);
			Class<?> paramsType = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer$ShaderCompilationParameters");
			var paramsCtor = paramsType.getDeclaredConstructor(boolean.class);
			paramsCtor.setAccessible(true);
			// the same parameters DefaultChunkRenderer.begin uses, else Sodium clears its pipeline cache on the next frame
			Object params = paramsCtor.newInstance(net.caffeinemc.mods.sodium.client.SodiumClientMod.options().performance.useSphericalRenderFog);
			var compile = shader.getDeclaredMethod("compileProgram", passType, oit, paramsType);
			compile.setAccessible(true);
			var out = new java.util.ArrayList<com.mojang.renderpearl.api.pipeline.RenderPipeline>();
			try {
				for (Object pass : (Object[]) Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses").getField("ALL").get(null))
					out.add((com.mojang.renderpearl.api.pipeline.RenderPipeline) compile.invoke(renderer, pass, null, params));
			} finally {
				shader.getMethod("delete").invoke(renderer);
			}
			return out;
		} catch (ReflectiveOperationException | RuntimeException e) {
			dev.emrion.shaders.mod.EmrionShadersClient.LOG.warn("Could not create Sodium's terrain pipelines at pack load; they are built on first use", e);
			return java.util.List.of();
		}
	}
}
