package dev.emrion.shaders.mod.uniforms;

import dev.emrion.shaders.converter.expr.CustomUniformRuntime;
import dev.emrion.shaders.converter.expr.HostValues;
import dev.emrion.shaders.converter.expr.UniformCatalog;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.upscale.Upscaling;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.ToIntFunction;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * {@link UniformSystem} with Iris semantics; per_tick members update only when game time changes, as in Iris.
 * Call {@link #beginFrame} at or after LevelRenderer.render HEAD, else gbufferProjection is last frame's.
 */
public final class IrisUniformSystem implements UniformSystem, HostValues {
	/** Iris PackDirectives values the uniforms use; half lives in deciseconds. Build with {@link #fromConsts}. */
	public record Directives(float wetnessHalfLife, float drynessHalfLife, float eyeBrightnessHalfLife,
	                         boolean oldHandLight, boolean endFlashShadows) {
		/** Iris quirk: drynessHalflife writes wetnessHalfLife, so "up" is dryness, else wetness, else 600; "down" is always 200. */
		public static Directives fromConsts(Float wetnessHalflife, Float drynessHalflife, Float eyeBrightnessHalflife,
		                                    boolean oldHandLight, boolean endFlashShadows) {
			float up = drynessHalflife != null ? drynessHalflife : wetnessHalflife != null ? wetnessHalflife : 600.0f;
			return new Directives(up, 200.0f, eyeBrightnessHalflife != null ? eyeBrightnessHalflife : 10.0f, oldHandLight, endFlashShadows);
		}
	}

	/** Pseudo-inverse of the singular composite matrix; composites inline their constants, so this is only defined data. */
	private static final Matrix4fc COMPOSITE_INV = new Matrix4f(0.5f, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0, 0, 0, 0.5f, 0.5f, 0, 1);

	private final Builtins b;
	private final int[] store;
	private final int[] scratch = new int[16];

	// Built-in members: provider → store slot, gated per tick or not.
	private final Builtins.Provider[] providers;
	private final int[] providerSlot;
	private final boolean[] perTick;

	// Custom members: runtime index → store slot.
	private final CustomUniformRuntime customs;
	private final int[] customIndex, customSlot;
	private final byte[] customKind; // 0 bool, 1 int, 2 float, 3.. vecN (n = kind − 1)
	private final int packJitterSlot; // Photon's taa_offset (vec2), zeroed while an upscaler jitters instead; -1 = none

	private final BlockPlan frame;
	private final int phaseProj, phaseProjInv, phaseStage, phaseJitter;
	private final Vector4f clearColor = new Vector4f(0, 0, 0, 1);

	public IrisUniformSystem(Manifest.UniformLayout layout, Manifest.Shadow shadow, Manifest.IdMaps idMaps, Directives directives) {
		b = new Builtins(shadow, idMaps, directives);
		customs = new CustomUniformRuntime(layout.custom(), this);
		if (!customs.broken().isEmpty()) EmrionShadersClient.LOG.warn("Custom uniforms that stay 0: {}", customs.broken());

		List<Builtins.Provider> provs = new ArrayList<>();
		List<Integer> pSlots = new ArrayList<>(), cIdx = new ArrayList<>(), cSlots = new ArrayList<>();
		List<Boolean> ticks = new ArrayList<>();
		List<Byte> kinds = new ArrayList<>();
		int n = layout.members().size();
		int[] src = new int[n], dst = new int[n], len = new int[n];
		int words = 0;
		for (int k = 0; k < n; k++) {
			Manifest.UniformMember m = layout.members().get(k);
			int w = m.arraySize() > 0 ? m.arraySize() * m.arrayStride() / 4 : BlockPlan.words(m.type());
			int slot = words;
			src[k] = slot;
			dst[k] = m.offset();
			len[k] = w;
			int custom = "custom".equals(m.source()) ? customs.indexOf(m.name()) : -1;
			Builtins.Def def = custom < 0 && m.arraySize() == 0 ? b.defs.get(m.name()) : null;
			if (custom >= 0) {
				cIdx.add(custom);
				cSlots.add(slot);
				kinds.add(kind(customs.type(custom)));
			} else if (def != null) {
				w = Math.max(w, BlockPlan.words(def.type()));
				provs.add(def.provider());
				pSlots.add(slot);
				ticks.add(UniformCatalog.lookup(m.name()).map(c -> c.frequency().equals("per_tick")).orElse(false));
			} else {
				EmrionShadersClient.LOG.warn("Uniform {} ({}) has no provider; it stays 0", m.name(), m.source());
			}
			words += w;
		}
		store = new int[words];
		frame = new BlockPlan(src, dst, len);
		providers = provs.toArray(new Builtins.Provider[0]);
		providerSlot = pSlots.stream().mapToInt(Integer::intValue).toArray();
		perTick = new boolean[ticks.size()];
		for (int k = 0; k < perTick.length; k++) perTick[k] = ticks.get(k);
		customIndex = cIdx.stream().mapToInt(Integer::intValue).toArray();
		customSlot = cSlots.stream().mapToInt(Integer::intValue).toArray();
		int jitter = -1;
		for (int k = 0; k < customIndex.length; k++)
			if (customs.name(customIndex[k]).equals("taa_offset") && customs.type(customIndex[k]).equals("vec2")) jitter = customSlot[k];
		packJitterSlot = jitter;
		customKind = new byte[kinds.size()];
		for (int k = 0; k < customKind.length; k++) customKind[k] = kinds.get(k);

		Map<String, Integer> po = new java.util.HashMap<>();
		for (var m : layout.phase().members()) po.put(m.name(), m.offset());
		phaseProj = po.get("emr_ProjMat");
		phaseProjInv = po.get("emr_ProjMatInverse");
		phaseStage = po.get("renderStage");
		phaseJitter = po.getOrDefault("emr_Jitter", -1); // -1: a manifest from before the slot
	}

	/** Uses the manifest's layout, the dimension's shadow settings, id maps and pack properties. */
	public static IrisUniformSystem forManifest(Manifest manifest, String dimensionFolder) {
		Map<String, Object> p = manifest.properties() == null ? Map.of() : manifest.properties();
		// shortcut: *Halflife const directives are not exported by the converter yet, read them once it does
		Directives d = Directives.fromConsts(num(p.get("wetnessHalflife")), num(p.get("drynessHalflife")), num(p.get("eyeBrightnessHalflife")),
				!Boolean.FALSE.equals(p.get("oldHandLight")), Boolean.TRUE.equals(p.get("endFlashShadows")));
		Manifest.Dimension dim = manifest.dimensions().get(dimensionFolder);
		return new IrisUniformSystem(manifest.uniforms(), dim == null ? null : dim.shadow(), manifest.idMaps(), d);
	}

	private static Float num(Object o) {
		return o instanceof Number x ? x.floatValue() : null;
	}

	private static byte kind(String type) {
		return switch (type) {
			case "bool" -> 0;
			case "int" -> 1;
			case "float" -> 2;
			default -> (byte) (1 + (type.charAt(type.length() - 1) - '0')); // vecN
		};
	}

	/** Block-state ids from block.properties, for currentSelectedBlockId. */
	public void setBlockIdLookup(ToIntFunction<BlockState> lookup) {
		b.blockIds = lookup;
	}

	// ---- UniformSystem ---------------------------------------------------------------------------------------------

	@Override
	public void beginFrame(float partialTick) {
		if (!b.capture(partialTick)) return;
		for (int k = 0; k < providers.length; k++)
			if (!perTick[k] || b.tickChanged) providers[k].write(store, providerSlot[k]);
		customs.update(this, b.timer.frameTime());
		for (int k = 0; k < customIndex.length; k++) {
			int c = customIndex[k], s = customSlot[k];
			switch (customKind[k]) {
				case 0 -> store[s] = customs.getBool(c) ? 1 : 0;
				case 1 -> store[s] = customs.getInt(c);
				case 2 -> store[s] = Float.floatToRawIntBits(customs.getFloat(c));
				default -> {
					float[] v = customs.getVector(c);
					for (int w = 0; w < v.length; w++) store[s + w] = Float.floatToRawIntBits(v[w]);
				}
			}
		}
		if (packJitterSlot >= 0 && Upscaling.active()) store[packJitterSlot] = store[packJitterSlot + 1] = 0; // FSR/DLSS know only our jitter
		clearColor.set(b.fogColor.x, b.fogColor.y, b.fogColor.z, 1.0f);
	}

	@Override
	public void writeFrameBlock(ByteBuffer dst) {
		frame.write(dst, store);
	}

	@Override
	public void writePhaseBlock(ByteBuffer dst, Phase phase) {
		Matrix4fc p, inv;
		boolean jitter = phaseJitter >= 0 && (phase == Phase.WORLD || phase == Phase.HAND);
		switch (phase) {
			// the upscaler's jitter goes to clip space (emr_Jitter), not into the projection: see GlslTranspiler.JITTER
			case WORLD -> { p = jitter ? Upscaling.unjittered : b.projection; inv = b.projectionInv; }
			case HAND -> { p = jitter ? Upscaling.unjitteredHand : b.handProjection; inv = b.handProjectionInv; }
			case SHADOW -> { p = b.shadowRender; inv = b.shadowRenderInv; }
			default -> { p = IrisMath.COMPOSITE_PROJECTION; inv = COMPOSITE_INV; }
		}
		int base = dst.position();
		putMat4(dst, base + phaseProj, p);
		putMat4(dst, base + phaseProjInv, inv);
		dst.putInt(base + phaseStage, b.renderStage);
		if (phaseJitter >= 0) {
			dst.putFloat(base + phaseJitter, jitter ? Upscaling.ndcJitterX() : 0f);
			dst.putFloat(base + phaseJitter + 4, jitter ? Upscaling.ndcJitterY() : 0f);
		}
	}

	private static void putMat4(ByteBuffer dst, int at, Matrix4fc m) {
		for (int c = 0; c < 4; c++)
			for (int r = 0; r < 4; r++) dst.putFloat(at + 16 * c + 4 * r, m.getRowColumn(r, c));
	}

	@Override
	public void setRenderStage(int irisOrdinal) {
		b.renderStage = irisOrdinal;
	}

	/** Maps player space (world − camera position) to shadow view space; see IrisMath.shadowModelView. */
	@Override
	public Matrix4fc shadowModelView() {
		return b.shadowModelView;
	}

	/** The projection the shadow pass renders with (ortho, or Iris's perspective when shadowMapFov is set). */
	@Override
	public Matrix4fc shadowProjection() {
		return b.shadowRender;
	}

	@Override
	public Matrix4fc gbufferModelView() {
		return b.modelView;
	}

	@Override
	public Matrix4fc gbufferProjection() {
		return b.projection;
	}

	@Override
	public Vector4fc clearFogColor() {
		return clearColor;
	}

	// ---- HostValues (custom uniform inputs, per frame as in Iris) ---------------------------------------------------

	private int[] eval(String name) {
		Builtins.Def d = b.defs.get(name);
		if (d == null) throw new IllegalArgumentException("unknown built-in " + name);
		d.provider().write(scratch, 0);
		return scratch;
	}

	@Override public float getFloat(String name) { return Float.intBitsToFloat(eval(name)[0]); }

	@Override public int getInt(String name) { return eval(name)[0]; }

	@Override public boolean getBool(String name) { return eval(name)[0] != 0; }

	@Override
	public void getFloats(String name, float[] out) {
		int[] v = eval(name);
		boolean mat3 = out.length == 9;
		for (int k = 0; k < out.length; k++) out[k] = Float.intBitsToFloat(v[mat3 ? k / 3 * 4 + k % 3 : k]);
	}

	@Override
	public void getInts(String name, int[] out) {
		System.arraycopy(eval(name), 0, out, 0, out.length);
	}

	@Override
	public OptionalInt biomeId(String constantName) {
		int id = Builtins.biomeConstant(constantName);
		return id < 0 ? OptionalInt.empty() : OptionalInt.of(id);
	}

	// ---- debug -----------------------------------------------------------------------------------------------------

	/** "name = value" for one built-in (debug snapshot; allocates). */
	String describe(String name) {
		return format(name, eval(name));
	}

	/** Same as {@link #describe} but read back from a written block at offset. */
	String describeWords(String name, ByteBuffer block, int offset) {
		int[] v = new int[16];
		for (int w = 0; w < BlockPlan.words(b.defs.get(name).type()); w++) v[w] = block.getInt(offset + 4 * w);
		return format(name, v);
	}

	private String format(String name, int[] v) {
		Builtins.Def d = b.defs.get(name);
		int words = BlockPlan.words(d.type());
		StringBuilder s = new StringBuilder(name).append(" = ");
		boolean ints = d.type().equals("int") || d.type().equals("bool") || d.type().startsWith("ivec");
		if (words > 1) s.append('[');
		for (int w = 0; w < words; w++) {
			if (d.type().startsWith("mat") && w % 4 == 0 && w > 0) s.append(" |");
			if (w > 0) s.append(' ');
			s.append(ints ? Integer.toString(v[w]) : String.format(java.util.Locale.ROOT, "%.5g", Float.intBitsToFloat(v[w])));
		}
		if (words > 1) s.append(']');
		return s.toString();
	}

	/** Custom uniform values (debug). */
	String describeCustoms() {
		StringBuilder s = new StringBuilder();
		for (int k = 0; k < customs.size(); k++) {
			s.append(customs.name(k)).append(" = ");
			switch (customs.type(k)) {
				case "bool" -> s.append(customs.getBool(k));
				case "int" -> s.append(customs.getInt(k));
				case "float" -> s.append(customs.getFloat(k));
				default -> s.append(java.util.Arrays.toString(customs.getVector(k)));
			}
			s.append('\n');
		}
		return s.toString();
	}

	boolean hasWorld() {
		return b.level != null && b.player != null;
	}
}
