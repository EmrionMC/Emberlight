package dev.emrion.shaders.mod.mixin;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why: {@code FrontendGpuDevice.backend} is private, and the {@code VulkanDevice} behind it holds every raw handle we need. */
@Mixin(FrontendGpuDevice.class)
public interface FrontendGpuDeviceAccessor {
	@Accessor("backend")
	GpuDeviceBackend emrion$backend();
}
