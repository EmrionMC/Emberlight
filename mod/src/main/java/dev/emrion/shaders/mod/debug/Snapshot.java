package dev.emrion.shaders.mod.debug;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.pack.PackManager;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Debug snapshot (F9 or /emberlight snapshot): a screenshot plus snapshot.json with everything needed to reproduce the
 * view (pose, world, time, weather, effects, options, pack settings, Emberlight config) in
 * {@code <gameDir>/emberlight/snapshots/<timestamp>/}. The autotest step "snapshot FILE" replays it.
 */
public final class Snapshot {
	/** /emberlight snapshot: ticks until the snapshot (0 = none). */
	public static int delay;
	/** Note for the pending /emberlight snapshot (null: ask with the note screen). */
	public static String pendingNote;

	private Snapshot() {}

	public static void take(Minecraft mc) {
		take(mc, null);
	}

	/** note null: ask for one afterwards; "" skips. */
	public static void take(Minecraft mc, String note) {
		if (mc.player == null || mc.level == null) return;
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
		Path dir;
		JsonObject j;
		try {
			dir = newDir(mc.gameDirectory.toPath().resolve("emberlight/snapshots"), stamp);
			j = collect(mc);
			Files.writeString(dir.resolve("snapshot.json"), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(j));
		} catch (Exception e) {
			EmrionShadersClient.LOG.error("Snapshot failed", e);
			mc.gui.hud.setOverlayMessage(Component.literal("Snapshot failed: " + e).withStyle(ChatFormatting.RED), false);
			return;
		}
		Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), img -> net.minecraft.util.Util.ioPool().execute(() -> { // PNG encode off the render thread
			try (img) {
				img.writeToFile(dir.resolve("screenshot.png"));
			} catch (IOException e) {
				EmrionShadersClient.LOG.warn("Snapshot screenshot failed", e);
			}
		}));
		var p = j.getAsJsonObject("player");
		String summary = String.format(java.util.Locale.ROOT, "%s %s %.3f %.3f %.3f yaw %.2f pitch %.2f, %s, time %d, %s, pack %s, RT %s | %s",
				j.getAsJsonObject("world").get("name").getAsString(), j.getAsJsonObject("world").get("dimension").getAsString(),
				p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble(), p.get("yaw").getAsFloat(), p.get("pitch").getAsFloat(),
				j.getAsJsonObject("world").get("biome").getAsString(), j.getAsJsonObject("time").get("dayTime").getAsLong(),
				j.getAsJsonObject("weather").get("state").getAsString(), j.getAsJsonObject("pack").get("name").getAsString(),
				dev.emrion.shaders.mod.rt.RtConfig.enabled ? dev.emrion.shaders.mod.rt.RtConfig.describe() : "off", dir);
		mc.keyboardHandler.setClipboard(summary);
		EmrionShadersClient.LOG.info("Snapshot saved: {}", summary);
		mc.gui.hud.getChat().addClientSystemMessage(Component.literal("Snapshot saved (F9), path copied: ").append(Component.literal(dir.getFileName().toString())
				.withStyle(s -> s.withUnderlined(true).withClickEvent(new ClickEvent.OpenFile(dir.toFile())))));
		lastSummary = summary;
		if (note == null) mc.gui.setScreen(new SnapshotNoteScreen(dir));
		else if (!note.isBlank()) addNote(mc, dir, note);
	}

	private static String lastSummary = "";

	/** base/stamp, or base/stamp-2, -3, ... when a snapshot in the same second already took it. */
	static Path newDir(Path base, String stamp) throws IOException {
		Files.createDirectories(base);
		for (int i = 1; ; i++) {
			try {
				return Files.createDirectory(base.resolve(i == 1 ? stamp : stamp + "-" + i));
			} catch (java.nio.file.FileAlreadyExistsException taken) {
				// next suffix
			}
		}
	}

	/** Stores what the user says looks wrong: note.txt, "note" in snapshot.json, and in the copied summary. */
	static void addNote(Minecraft mc, Path dir, String note) {
		if (note == null || note.isBlank()) return;
		try {
			Files.writeString(dir.resolve("note.txt"), note.strip() + "\n");
			Path json = dir.resolve("snapshot.json");
			JsonObject j = com.google.gson.JsonParser.parseString(Files.readString(json)).getAsJsonObject();
			j.addProperty("note", note.strip());
			Files.writeString(json, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(j));
			mc.keyboardHandler.setClipboard(lastSummary + " | note: " + note.strip());
		} catch (Exception e) {
			EmrionShadersClient.LOG.warn("Snapshot note failed", e);
		}
	}

	static JsonObject collect(Minecraft mc) throws IOException {
		var pl = mc.player;
		var level = mc.level;
		JsonObject j = new JsonObject();
		j.addProperty("version", 1);
		j.addProperty("taken", LocalDateTime.now().toString());

		JsonObject p = new JsonObject();
		p.addProperty("x", pl.getX());
		p.addProperty("y", pl.getY());
		p.addProperty("z", pl.getZ());
		p.addProperty("yaw", pl.getYRot());
		p.addProperty("pitch", pl.getXRot());
		p.addProperty("bodyYaw", pl.yBodyRot);
		p.addProperty("eyeY", pl.getEyeY());
		p.addProperty("gameMode", mc.gameMode == null ? "unknown" : mc.gameMode.getPlayerMode().getName());
		p.addProperty("flying", pl.getAbilities().flying);
		p.addProperty("underwater", pl.isUnderWater());
		BlockPos eye = BlockPos.containing(pl.getEyePosition());
		p.addProperty("eyeBlockLight", level.getBrightness(LightLayer.BLOCK, eye));
		p.addProperty("eyeSkyLight", level.getBrightness(LightLayer.SKY, eye));
		p.addProperty("selectedSlot", pl.getInventory().getSelectedSlot());
		RegistryAccess reg = level.registryAccess();
		p.addProperty("mainHand", item(pl.getMainHandItem(), reg));
		p.addProperty("offHand", item(pl.getOffhandItem(), reg));
		JsonArray hotbar = new JsonArray();
		for (int i = 0; i < 9; i++) hotbar.add(item(pl.getInventory().getItem(i), reg));
		p.add("hotbar", hotbar);
		JsonArray effects = new JsonArray();
		for (var e : pl.getActiveEffects()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", e.getEffect().getRegisteredName());
			o.addProperty("amplifier", e.getAmplifier());
			o.addProperty("duration", e.getDuration());
			effects.add(o);
		}
		p.add("effects", effects);
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			JsonObject t = new JsonObject();
			t.addProperty("pos", hit.getBlockPos().toShortString());
			t.addProperty("face", hit.getDirection().getName());
			t.addProperty("state", level.getBlockState(hit.getBlockPos()).toString());
			p.add("target", t);
		}
		j.add("player", p);

		JsonObject w = new JsonObject();
		var server = mc.getSingleplayerServer();
		w.addProperty("name", server != null ? server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString()
				: mc.getCurrentServer() != null ? mc.getCurrentServer().name : "unknown");
		if (server != null) {
			w.addProperty("levelName", server.getWorldData().getLevelName());
			w.addProperty("seed", server.overworld().getSeed());
		}
		if (mc.getCurrentServer() != null && server == null) w.addProperty("server", mc.getCurrentServer().ip);
		w.addProperty("dimension", level.dimension().identifier().toString());
		w.addProperty("biome", level.getBiome(pl.blockPosition()).getRegisteredName());
		j.add("world", w);

		JsonObject t = new JsonObject();
		t.addProperty("dayTime", level.getDefaultClockTime());
		t.addProperty("gameTime", level.getGameTime());
		t.addProperty("moonPhase", mc.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.MOON_PHASE, 1f).index());
		j.add("time", t);
		JsonObject wx = new JsonObject();
		float rain = level.getRainLevel(1f), thunder = level.getThunderLevel(1f);
		wx.addProperty("state", thunder > 0.5f ? "thunder" : rain > 0.5f ? "rain" : "clear");
		wx.addProperty("rain", rain);
		wx.addProperty("thunder", thunder);
		j.add("weather", wx);

		var o = mc.options;
		JsonObject c = new JsonObject();
		c.addProperty("camera", o.getCameraType().name());
		c.addProperty("fov", o.fov().get());
		c.addProperty("fovEffectScale", o.fovEffectScale().get());
		c.addProperty("bobView", o.bobView().get());
		c.addProperty("gamma", o.gamma().get());
		c.addProperty("renderDistance", o.renderDistance().get());
		c.addProperty("effectiveRenderDistance", o.getEffectiveRenderDistance());
		c.addProperty("guiScale", o.guiScale().get());
		c.addProperty("hudHidden", mc.gui.hud.isHidden());
		var win = mc.getWindow();
		c.addProperty("windowWidth", win.getScreenWidth());
		c.addProperty("windowHeight", win.getScreenHeight());
		c.addProperty("framebufferWidth", win.getWidth());
		c.addProperty("framebufferHeight", win.getHeight());
		c.addProperty("fullscreen", o.fullscreen().get());
		// loaded resource packs: PBR packs change materials (mirror-like concrete etc.)
		var packs = new com.google.gson.JsonArray();
		mc.getResourcePackRepository().getSelectedIds().forEach(packs::add);
		c.add("resourcePacks", packs);
		j.add("options", c);

		JsonObject pack = new JsonObject();
		String sel = PackManager.selected();
		pack.addProperty("name", sel == null ? "off" : sel);
		pack.addProperty("enabled", PackManager.enabled());
		pack.addProperty("running", PackManager.running());
		if (sel != null) {
			JsonObject settings = new JsonObject();
			Path f = PackManager.settingsFile(sel);
			if (Files.isRegularFile(f)) dev.emrion.shaders.converter.options.IrisSettings.read(f).forEach(settings::addProperty);
			pack.add("settings", settings);
		}
		j.add("pack", pack);
		Properties cfg = read(EmrionShadersClient.configFile());
		dev.emrion.shaders.mod.rt.RtConfig.store(cfg); // the live values (also ones not saved yet)
		dev.emrion.shaders.mod.upscale.UpscaleConfig.store(cfg);
		dev.emrion.shaders.mod.framegen.FrameGenConfig.store(cfg);
		dev.emrion.shaders.mod.framegen.Reflex.store(cfg);
		dev.emrion.shaders.mod.hdr.Hdr.store(cfg);
		j.add("emberlight", props(cfg));

		JsonObject sys = new JsonObject();
		var gpu = RenderSystem.getDevice().getDeviceInfo();
		sys.addProperty("gpu", gpu.name());
		sys.addProperty("vendor", gpu.vendorName());
		sys.addProperty("driver", gpu.driverInfo());
		sys.addProperty("backend", gpu.backendName());
		sys.addProperty("fps", mc.getFps());
		sys.addProperty("presentedFps", dev.emrion.shaders.mod.framegen.Presenter.presentedFps);
		sys.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
		sys.addProperty("java", System.getProperty("java.version"));
		JsonObject mods = new JsonObject();
		FabricLoader.getInstance().getAllMods().stream().map(m -> m.getMetadata()).sorted(java.util.Comparator.comparing(m -> m.getId()))
				.forEach(m -> mods.addProperty(m.getId(), m.getVersion().getFriendlyString()));
		sys.add("mods", mods);
		JsonArray hitches = new JsonArray();
		Path hl = mc.gameDirectory.toPath().resolve("logs/emberlight-hitches.log");
		if (Files.isRegularFile(hl)) {
			List<String> lines = Files.readAllLines(hl);
			lines.subList(Math.max(0, lines.size() - 20), lines.size()).forEach(hitches::add);
		}
		sys.add("lastHitches", hitches);
		j.add("system", sys);
		return j;
	}

	/** An item as the "item replace" command takes it: id[component=snbt,...] (empty: minecraft:air). */
	static String item(ItemStack s, RegistryAccess reg) {
		if (s.isEmpty()) return "minecraft:air";
		var ops = reg.createSerializationContext(NbtOps.INSTANCE);
		StringBuilder sb = new StringBuilder(BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
		List<String> parts = new java.util.ArrayList<>();
		if (net.minecraft.core.component.DataComponentPatch.CODEC.encodeStart(ops, s.getComponentsPatch()).result().orElse(null) instanceof net.minecraft.nbt.CompoundTag t)
			for (String k : t.keySet()) parts.add(k.startsWith("!") ? k : k + "=" + t.get(k)); // removed components are "!id": {}
		if (!parts.isEmpty()) sb.append('[').append(String.join(",", parts)).append(']');
		if (s.getCount() > 1) sb.append(' ').append(s.getCount());
		return sb.toString();
	}

	private static Properties read(Path f) throws IOException {
		Properties p = new Properties();
		if (Files.isRegularFile(f)) try (Reader r = Files.newBufferedReader(f)) {
			p.load(r);
		}
		return p;
	}

	private static JsonObject props(Properties p) {
		JsonObject o = new JsonObject();
		new TreeMap<>(p).forEach((k, v) -> o.addProperty((String) k, (String) v));
		return o;
	}
}
