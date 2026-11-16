package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.pack.PackScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: players look for shader packs in Video Settings (as with Iris/OptiFine); one button there opens the pack screen. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
	private VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
		super(lastScreen, options, title);
	}

	@Inject(method = "addOptions", at = @At("TAIL"))
	private void emrion$packButton(CallbackInfo ci) {
		if (!EmrionShadersClient.active() || list == null) return;
		Screen self = this;
		list.addBig(Button.builder(Component.literal("Shader Packs (Emberlight)..."), b -> Minecraft.getInstance().gui.setScreen(new PackScreen(self))).build());
	}
}
