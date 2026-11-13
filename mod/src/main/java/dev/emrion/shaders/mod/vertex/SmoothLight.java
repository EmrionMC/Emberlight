package dev.emrion.shaders.mod.vertex;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Smooth lighting (AO + light blending) of chunk quads as Sodium computes it, because Iris always runs on Sodium. It differs
 * from MC's {@code BlockModelLighter}: a face corner is skipped when both <em>edge</em> neighbours are opaque (MC tests the
 * blocks one further out), zero light values are replaced per channel by the smallest non-zero one of the corner (MC: by the
 * centre, only if it is brighter than 2), opaque full blocks count as unlit, and partial or inset faces blend bilinearly
 * between the face data in front of and inside the block. Written from Sodium's observable behaviour (SmoothLightPipeline,
 * AoFaceData, LightDataAccess); the vanilla-model (non-"enhanced") path only.
 */
public final class SmoothLight {
	private static final ThreadLocal<SmoothLight> CURRENT = ThreadLocal.withInitial(SmoothLight::new);
	/** Per face: two pairs of opposite edge directions (MC's AdjacencyInfo order). */
	private static final Direction[][] EDGES = new Direction[6][];

	static {
		EDGES[Direction.DOWN.ordinal()] = new Direction[] {Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH};
		EDGES[Direction.UP.ordinal()] = new Direction[] {Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH};
		EDGES[Direction.NORTH.ordinal()] = new Direction[] {Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST};
		EDGES[Direction.SOUTH.ordinal()] = new Direction[] {Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP};
		EDGES[Direction.WEST.ordinal()] = new Direction[] {Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH};
		EDGES[Direction.EAST.ordinal()] = new Direction[] {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};
	}

	/** Light data of one block position. */
	private static final class Sample {
		int lm;
		float ao;
		boolean em, op, fo, fc;
	}

	/** Light and AO at the four face corners, corner k between edges CORNER_A[k] and CORNER_B[k]. */
	private static final class Face {
		final float[] ao = new float[4], sl = new float[4], bl = new float[4];
		boolean valid;
	}

	private static final int[] CORNER_A = {0, 0, 1, 1}, CORNER_B = {3, 2, 2, 3};

	private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
	private final Sample center = new Sample(), origin = new Sample(), diag = new Sample();
	private final Sample[] edge = {new Sample(), new Sample(), new Sample(), new Sample()};
	private final Face front = new Face(), inside = new Face();
	private final float[] w = new float[4];

	public static SmoothLight get() { return CURRENT.get(); }

	/**
	 * Computes per-vertex brightness (AO × directional shade) and packed light coords of a quad of block-local vertex positions
	 * {@code xyz} (x0, y0, z0, x1, …) facing {@code dir}, into {@code br} and {@code lm}.
	 */
	public void light(BlockAndTintGetter level, BlockState state, BlockPos pos, Direction dir, @Nullable Direction shadeDir, float[] xyz,
			float[] br, int[] lm) {
		light(level, state, pos, dir, shadeDir, xyz, br, lm, AUTO);
	}

	/** Quad geometry flags: computed from the positions, or forced as Sodium's fluid renderer does (sides aligned, top/bottom irregular). */
	public static final int AUTO = 0, ALIGNED = 1, NON_PARALLEL = 2;

	public void light(BlockAndTintGetter level, BlockState state, BlockPos pos, Direction dir, @Nullable Direction shadeDir, float[] xyz,
			float[] br, int[] lm, int flags) {
		int axis = dir.getAxis().ordinal();
		boolean positive = dir.getAxisDirection() == Direction.AxisDirection.POSITIVE;
		float min = 32, max = -32;
		for (int i = 0; i < 4; i++) {
			float c = xyz[i * 3 + axis];
			min = Math.min(min, c);
			max = Math.max(max, c);
		}
		boolean parallel = flags == AUTO ? min == max : flags == ALIGNED;
		boolean aligned = flags == AUTO ? parallel && (positive ? max > 0.9999f : min < 1.0e-4f) : flags == ALIGNED;
		boolean full = aligned || parallel && state.isCollisionShapeFullBlock(level, pos);
		front.valid = inside.valid = false;
		float shade = level.cardinalLighting().byFace(shadeDir != null ? shadeDir : dir);

		for (int i = 0; i < 4; i++) {
			weights(dir, xyz, i);
			float c = clamp(xyz[i * 3 + axis]);
			float depth = positive ? 1 - c : c;
			float ao, sl, bl;
			if (full || !parallel && near(depth, 0)) {
				Face f = face(level, pos, dir, true);
				ao = blend(f.ao); sl = blend(f.sl); bl = blend(f.bl);
			} else if (near(depth, 1)) {
				Face f = face(level, pos, dir, false);
				ao = blend(f.ao); sl = blend(f.sl); bl = blend(f.bl);
			} else {
				Face n1 = face(level, pos, dir, false), n2 = face(level, pos, dir, true);
				float d2 = 1 - depth;
				ao = blend(n1.ao) * depth + blend(n2.ao) * d2;
				sl = blend(n1.sl) * depth + blend(n2.sl) * d2;
				bl = blend(n1.bl) * depth + blend(n2.bl) * d2;
			}
			br[i] = Math.clamp(ao * shade, 0.0f, 1.0f);
			lm[i] = ((int) sl & 0xFF) << 16 | (int) bl & 0xFF;
		}
	}

	private Face face(BlockAndTintGetter level, BlockPos pos, Direction dir, boolean offset) {
		Face f = offset ? front : inside;
		if (f.valid) return f;
		int x = pos.getX() + (offset ? dir.getStepX() : 0), y = pos.getY() + (offset ? dir.getStepY() : 0),
				z = pos.getZ() + (offset ? dir.getStepZ() : 0);
		sample(center, level, x, y, z);
		int clm = center.lm;
		boolean cem = center.em;
		if (offset && center.fo) { // the face is against an opaque block: take the light of the block itself
			sample(origin, level, pos.getX(), pos.getY(), pos.getZ());
			clm = origin.lm;
			cem = origin.em;
		}
		Direction[] e = EDGES[dir.ordinal()];
		for (int k = 0; k < 4; k++) sample(edge[k], level, x + e[k].getStepX(), y + e[k].getStepY(), z + e[k].getStepZ());
		for (int k = 0; k < 4; k++) {
			Sample a = edge[CORNER_A[k]], b = edge[CORNER_B[k]], c = a;
			if (!(a.op && b.op)) {
				Direction da = e[CORNER_A[k]], db = e[CORNER_B[k]];
				sample(diag, level, x + da.getStepX() + db.getStepX(), y + da.getStepY() + db.getStepY(), z + da.getStepZ() + db.getStepZ());
				c = diag;
			}
			f.ao[k] = (b.ao + a.ao + c.ao + center.ao) * 0.25f;
			int lm = cornerLight(b.lm, a.lm, c.lm, clm, b.em, a.em, c.em, cem);
			f.sl[k] = lm >> 16 & 0xFF;
			f.bl[k] = lm & 0xFF;
		}
		f.valid = true;
		return f;
	}

	private void sample(Sample s, BlockAndTintGetter level, int x, int y, int z) {
		BlockPos p = scratch.set(x, y, z);
		BlockState st = level.getBlockState(p);
		s.em = st.emissiveRendering();
		s.op = !st.isLightPermeable();
		s.fo = st.isSolidRender();
		s.fc = st.isCollisionShapeFullBlock(level, p);
		int lu = st.getLightEmission();
		int bl, sl;
		if (s.fo && lu == 0) {
			bl = sl = 0;
		} else if (s.em) {
			bl = level.getBrightness(LightLayer.BLOCK, p);
			sl = level.getBrightness(LightLayer.SKY, p);
		} else {
			int l = LightCoordsUtil.getLightCoords(LightCoordsUtil.BrightnessGetter.DEFAULT, level, st, p);
			bl = LightCoordsUtil.block(l);
			sl = LightCoordsUtil.sky(l);
		}
		s.lm = LightCoordsUtil.pack(Math.max(bl, lu), sl);
		s.ao = st.getShadeBrightness(level, p);
	}

	/** Average of four packed light coords; zero channels take the smallest non-zero value, emissive ones full block light. */
	static int cornerLight(int a, int b, int c, int d, boolean aem, boolean bem, boolean cem, boolean dem) {
		int ab = a & 0xFF, bb = b & 0xFF, cb = c & 0xFF, db = d & 0xFF;
		if (ab == 0 || bb == 0 || cb == 0 || db == 0) {
			int m = minNonZero(minNonZero(ab, bb), minNonZero(cb, db));
			ab = Math.max(ab, m); bb = Math.max(bb, m); cb = Math.max(cb, m); db = Math.max(db, m);
		}
		int as = a & 0xFF0000, bs = b & 0xFF0000, cs = c & 0xFF0000, ds = d & 0xFF0000;
		if (as == 0 || bs == 0 || cs == 0 || ds == 0) {
			int m = minNonZero(minNonZero(as, bs), minNonZero(cs, ds));
			as = Math.max(as, m); bs = Math.max(bs, m); cs = Math.max(cs, m); ds = Math.max(ds, m);
		}
		a = emissive(ab | as, aem); b = emissive(bb | bs, bem); c = emissive(cb | cs, cem); d = emissive(db | ds, dem);
		return a + b + c + d >> 2 & 0xFF00FF;
	}

	private static int emissive(int lm, boolean em) { return em ? lm & 0xFF0000 | 0xF0 : lm; }

	private static int minNonZero(int a, int b) { return a == 0 ? b : b == 0 ? a : Math.min(a, b); }

	/** Bilinear weights of the four face corners at the vertex (corner k lies towards edges CORNER_A[k] and CORNER_B[k]). */
	private void weights(Direction dir, float[] xyz, int vertex) {
		Direction[] e = EDGES[dir.ordinal()];
		for (int k = 0; k < 4; k++) w[k] = toward(e[CORNER_A[k]], xyz, vertex) * toward(e[CORNER_B[k]], xyz, vertex);
	}

	private static float toward(Direction d, float[] xyz, int vertex) {
		float c = clamp(xyz[vertex * 3 + d.getAxis().ordinal()]);
		return d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? c : 1 - c;
	}

	private float blend(float[] v) { return v[0] * w[0] + v[1] * w[1] + v[2] * w[2] + v[3] * w[3]; }

	private static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

	private static boolean near(float a, float b) { return Math.abs(a - b) < 1.0e-5f; }
}
