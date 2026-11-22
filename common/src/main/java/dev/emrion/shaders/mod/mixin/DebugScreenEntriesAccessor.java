package dev.emrion.shaders.mod.mixin;

import java.util.Map;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Why: Fabric has no API for F3 entries (NeoForge: RegisterDebugEntriesEvent); MC's register and its immutable profiles. */
@Mixin(DebugScreenEntries.class)
public interface DebugScreenEntriesAccessor {
	@Invoker("register")
	static Identifier emrion$register(Identifier id, DebugScreenEntry entry) {
		throw new AssertionError();
	}

	@Accessor("PROFILES")
	@Mutable
	static void emrion$setProfiles(Map<DebugScreenProfile, Map<Identifier, DebugScreenEntryStatus>> profiles) {
		throw new AssertionError();
	}
}
