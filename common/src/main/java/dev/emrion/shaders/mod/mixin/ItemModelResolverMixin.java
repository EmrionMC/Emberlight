package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.vertex.BlockIdTable;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vertex.EntityIds;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: the item stack is known only while its render state is filled; its currentRenderedItemId is resolved here (Iris
 * ItemStackStateLayerMixin: block items use the block's id and mark blockEntityId = 1, other items their model id's item id).
 */
@Mixin(ItemModelResolver.class)
public class ItemModelResolverMixin {
	@Inject(method = "appendItemLayers", at = @At("HEAD"))
	private void emrion$itemId(ItemStackRenderState output, ItemStack item, ItemDisplayContext context, Level level, ItemOwner owner, int seed,
			CallbackInfo ci) {
		if (!EmrionVertexFormats.active()) return;
		BlockIdTable ids = EmrionVertexFormats.ids();
		EntityIds.ItemHolder out = (EntityIds.ItemHolder) output;
		if (item.getItem() instanceof BlockItem block && !(item.getItem() instanceof SolidBucketItem)) {
			int id = ids.block(block.getBlock().defaultBlockState());
			out.emrion$setItem(id == -1 ? 0 : id, true);
		} else {
			Identifier model = item.get(DataComponents.ITEM_MODEL);
			out.emrion$setItem(ids.item(model != null ? model : BuiltInRegistries.ITEM.getKey(item.getItem())), false);
		}
	}
}
