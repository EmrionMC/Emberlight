package dev.emrion.shaders.mod.uniforms;

import dev.emrion.shaders.converter.expr.CustomUniformParser;
import dev.emrion.shaders.converter.expr.UniformCatalog;
import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.converter.plan.Std140LayoutBuilder;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.format.ManifestJson;
import dev.emrion.shaders.mod.EmrionShadersClient;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * {@code -Demrion.debug.uniforms=true}: runs an {@link IrisUniformSystem} next to vanilla rendering and logs every
 * built-in once after {@code emrion.debug.uniforms.frames} (default 600) world frames, plus sanity checks.
 * {@code -Demrion.debug.uniforms.manifest=<manifest.json>} uses a converted pack's layout and custom uniforms
 * (dimension world0) instead of a synthetic layout with every block built-in.
 */
public final class UniformDebug {
	public static final boolean ENABLED = Boolean.getBoolean("emrion.debug.uniforms");
	private static final int FRAMES = Integer.getInteger("emrion.debug.uniforms.frames", 600);

	private static IrisUniformSystem system;
	private static Manifest.UniformLayout layout;
	private static int frames;
	private static boolean done;

	private UniformDebug() {}

	public static void onLevelRender() {
		if (done) return;
		if (system == null) system = create();
		Minecraft mc = Minecraft.getInstance();
		system.beginFrame(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
		if (!system.hasWorld() || ++frames < FRAMES) return;
		done = true;
		log(mc);
	}

	private static IrisUniformSystem create() {
		String path = System.getProperty("emrion.debug.uniforms.manifest");
		if (path != null) {
			try {
				Manifest m = ManifestJson.readManifest(Path.of(path));
				layout = m.uniforms();
				return IrisUniformSystem.forManifest(m, "world0");
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}
		// Synthetic layout: injected members + every block built-in + DEBUG_CUSTOMS, plain std140 packing.
		var props = new java.util.LinkedHashMap<String, String>();
		for (String line : DEBUG_CUSTOMS.strip().split("\n")) {
			int eq = line.indexOf('=');
			props.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
		}
		var diagnostics = new Diagnostics();
		List<Manifest.CustomUniform> customs = CustomUniformParser.parse(props, diagnostics);
		for (var d : diagnostics.all()) EmrionShadersClient.LOG.warn("custom uniform: {}", d);
		List<Manifest.UniformMember> members = new ArrayList<>();
		Map<String, String[]> all = new java.util.TreeMap<>();
		Std140LayoutBuilder.INJECTED.forEach((n, t) -> all.put(n, new String[]{t, "builtin"}));
		for (var b : UniformCatalog.all()) if (b.location().equals("block")) all.put(b.name(), new String[]{b.glslType(), "builtin"});
		for (var c : customs) if (c.uniform()) all.put(c.name(), new String[]{c.type(), "custom"});
		int offset = 0;
		for (var e : all.entrySet()) {
			int words = BlockPlan.words(e.getValue()[0]);
			int align = words >= 3 ? 16 : words * 4;
			offset = (offset + align - 1) / align * align;
			members.add(new Manifest.UniformMember(e.getKey(), e.getValue()[0], offset, 0, 0, e.getValue()[1]));
			offset += words * 4;
		}
		layout = new Manifest.UniformLayout("EmrionUniforms", (offset + 15) / 16 * 16, members, customs,
				Std140LayoutBuilder.PHASE, Std140LayoutBuilder.PUSH);
		// Both wetness halflives 300, as in the target pack, so wetness smoothing matches it.
		return new IrisUniformSystem(layout, new Manifest.Shadow(true, 2048, 256, 1, 0.125f, -100.05f, 156, null, 2, -40,
				new boolean[]{true, true}, "reversed", 64), null, IrisUniformSystem.Directives.fromConsts(300f, 300f, null, true, false));
	}

	private static void log(Minecraft mc) {
		StringBuilder s = new StringBuilder("Uniform snapshot after ").append(frames).append(" frames\n");
		var p = mc.player;
		s.append(String.format("player pos = [%.4f %.4f %.4f] eye = %.4f, camera = %s, dayTime = %d%n",
				p.getX(), p.getY(), p.getZ(), p.getEyeY(), mc.gameRenderer.mainCamera().position(), mc.level.getDefaultClockTime()));
		for (var b : UniformCatalog.all()) s.append("  ").append(system.describe(b.name())).append('\n');
		for (String n : Std140LayoutBuilder.INJECTED.keySet()) s.append("  ").append(system.describe(n)).append('\n');
		s.append(system.describeCustoms());

		// Sanity: the frame block writes every member, matrices are invertible and GL-convention.
		ByteBuffer buf = ByteBuffer.allocateDirect(layout.size()).order(ByteOrder.nativeOrder());
		system.writeFrameBlock(buf);
		// Every per-frame built-in in the written block must equal its provider evaluated now.
		List<String> mismatches = new ArrayList<>();
		for (var m : layout.members()) {
			var cat = UniformCatalog.lookup(m.name());
			if (!m.source().equals("builtin") || cat.isPresent() && !cat.get().frequency().equals("per_frame") && !cat.get().frequency().equals("once")) continue;
			if (!system.describe(m.name()).equals(system.describeWords(m.name(), buf, m.offset()))) mismatches.add(m.name());
		}
		s.append("  frame block: ").append(layout.members().size()).append(" members, ").append(layout.size())
				.append(" bytes, per-frame mismatches vs providers: ").append(mismatches).append('\n');
		ByteBuffer phase = ByteBuffer.allocateDirect(Std140LayoutBuilder.PHASE.size()).order(ByteOrder.nativeOrder());
		for (UniformSystem.Phase ph : UniformSystem.Phase.values()) {
			system.writePhaseBlock(phase, ph);
			Matrix4f m = new Matrix4f().set(phase.asFloatBuffer());
			Matrix4f inv = new Matrix4f().set(phase.position(64).asFloatBuffer());
			phase.position(0);
			boolean inverseOk = new Matrix4f(m).mul(inv).equals(new Matrix4f(), 1e-3f);
			Vector4f near = m.transform(new Vector4f(0, 0, -0.05f, 1)), far = m.transform(new Vector4f(0, 0, -100f, 1));
			s.append(String.format("  phase %s: det=%.4g inverseOk=%s  ndc.z(view z=−0.05)=%.4f ndc.z(view z=−100)=%.4f%n",
					ph, m.determinant(), inverseOk, near.z / near.w, far.z / far.w));
		}
		EmrionShadersClient.LOG.info(s.toString());
	}

	/** Hand-written custom uniforms covering the parser features packs use: %, if(), smooth() with and without id, in(), BIOME_/CAT_ macros, vec and bool types, variables. */
	private static final String DEBUG_CUSTOMS = """
			uniform.float.dbgParity = frameCounter % 2
			uniform.float.dbgSlot = frameCounter % 4
			variable.float.dbgStepX = cameraPosition.x - previousCameraPosition.x
			variable.float.dbgStepZ = cameraPosition.z - previousCameraPosition.z
			variable.float.dbgStep = abs(dbgStepX) + abs(dbgStepZ)
			uniform.float.dbgWalking = smooth(31, if(dbgStep > 0.0 && dbgStep < 1.0, 1, 0), 2, 4)
			uniform.float.dbgSkyLight = smooth(32, eyeBrightness.y / 240.0, 3, 3)
			uniform.float.dbgUnderground = if(isEyeInWater == 0 && eyeAltitude < 40.0, 1.0 - dbgSkyLight, 0.0)
			uniform.float.dbgWetBiome = smooth(33, if(in(biome_precipitation, 1, 2), 1, 0), 10, 10)
			uniform.float.dbgJungle = smooth(34, if(in(biome, BIOME_JUNGLE, BIOME_BAMBOO_JUNGLE), 1, 0), 8, 8)
			uniform.float.dbgOcean = if(biome_category == CAT_OCEAN, 1, 0)
			uniform.float.dbgDim = max(blindness, darknessFactor)
			uniform.float.dbgWeather = smooth(35, rainStrength + thunderStrength, 4, 2)
			uniform.float.dbgFrameTime = smooth(frameTime, 1, 1)
			uniform.vec2.dbgHalfView = vec2(viewWidth / 2.0, viewHeight / 2.0)
			uniform.bool.dbgGrounded = is_on_ground
			variable.float.dbgDayTicks = fmod(worldDay, 50) * 24000.0
			variable.float.dbgTicksSmooth = smooth(worldTime + dbgDayTicks, 0.5, 0.5)
			uniform.float.dbgTicks = if(worldTime > 100 || dbgDayTicks > 0, dbgTicksSmooth, worldTime)
			""";
}
