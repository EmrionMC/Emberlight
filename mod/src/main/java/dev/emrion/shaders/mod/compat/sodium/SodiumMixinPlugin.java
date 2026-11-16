package dev.emrion.shaders.mod.compat.sodium;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies emrionshaders.sodium.mixins.json only when Sodium is installed (it is optional, docs/SODIUM.md), and not next to
 * Iris (the dev Iris reference run, where the mod stays inert on OpenGL and the user's Sodium is another beta).
 */
public final class SodiumMixinPlugin implements IMixinConfigPlugin {
	private static final boolean SODIUM = FabricLoader.getInstance().isModLoaded("sodium") && !FabricLoader.getInstance().isModLoaded("iris");

	@Override public void onLoad(String mixinPackage) {}
	@Override public String getRefMapperConfig() { return null; }
	@Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return SODIUM; }
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
