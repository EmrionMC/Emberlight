package dev.emrion.shaders.mod.mixin;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.List;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why: RT entities copy a draw's CPU vertex slices before StagedVertexBuffer uploads and frees them. */
@Mixin(StagedVertexBuffer.Draw.class)
public interface StagedDrawAccessor {
	@Accessor("format")
	VertexFormat emrion$format();

	@Accessor("primitiveTopology")
	PrimitiveTopology emrion$topology();

	@Accessor("vertexBufferSlices")
	List<ByteBufferBuilder.Result> emrion$slices();

	@Accessor("vertexCount")
	int emrion$vertexCount();
}
