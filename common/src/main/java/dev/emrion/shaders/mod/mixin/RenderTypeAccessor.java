package dev.emrion.shaders.mod.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why: block entities draw with copies of the entity render types (same setup, own name), see GeometryHooks.blockEntityType. */
@Mixin(RenderType.class)
public interface RenderTypeAccessor {
	@Accessor("name")
	String emrion$name();

	@Accessor("state")
	RenderSetup emrion$state();
}
