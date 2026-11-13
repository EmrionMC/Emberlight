package dev.emrion.shaders.mod.mixin;

import java.util.function.BiConsumer;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.texture.atlas.sources.DirectoryLister;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Why: atlas directory sources would stitch a resource pack's labPBR maps ({@code x_n.png}/{@code x_s.png} next to
 * {@code x.png}) as sprites of their own; they are never drawn but grow the atlas and every PBR atlas built from it (Iris
 * filters them the same way).
 */
@Mixin(DirectoryLister.class)
public class DirectoryListerMixin {
	@ModifyArg(method = "run", at = @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V"))
	private BiConsumer<Identifier, Resource> emrion$skipPbrMaps(BiConsumer<Identifier, Resource> action,
			@Local(argsOnly = true) ResourceManager resources) {
		return (file, resource) -> {
			String base = basePath(file.getPath());
			if (base == null || resources.getResource(file.withPath(base)).isEmpty()) action.accept(file, resource);
		};
	}

	/** {@code a/b_n.png} → {@code a/b.png} (also {@code _s}); null for any other path. */
	private static String basePath(String path) {
		return path.endsWith("_n.png") || path.endsWith("_s.png") ? path.substring(0, path.length() - 6) + ".png" : null;
	}
}
