package dev.emrion.shaders.mod.uniforms;

import dev.emrion.shaders.format.Manifest;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * Test stand-in for agent (u)'s real {@link UniformSystem}: matrices are identity, a few screen/time built-ins are real,
 * everything else is 0. Enough for composite/final passes to produce finite output.
 */
public final class StubUniformSystem implements UniformSystem {
	private static final Matrix4f IDENTITY = new Matrix4f();
	private final Manifest.UniformLayout layout;
	private final long start = System.nanoTime();
	private int frame;
	private float width, height;

	public StubUniformSystem(Manifest.UniformLayout layout) {
		this.layout = layout;
	}

	@Override
	public void beginFrame(float partialTick) {
		frame++;
		var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		width = target.width;
		height = target.height;
	}

	@Override
	public void writeFrameBlock(ByteBuffer dst) {
		write(dst, layout.size(), layout.members());
	}

	@Override
	public void writePhaseBlock(ByteBuffer dst, Phase phase) {
		write(dst, layout.phase().size(), layout.phase().members());
	}

	private void write(ByteBuffer dst, int size, java.util.List<Manifest.UniformMember> members) {
		int base = dst.position();
		for (int i = 0; i < size; i++) dst.put(base + i, (byte) 0);
		for (var m : members) {
			int o = base + m.offset();
			switch (m.type()) {
				case "mat4" -> IDENTITY.get(o, dst);
				case "mat3" -> { for (int c = 0; c < 3; c++) dst.putFloat(o + c * 16 + c * 4, 1f); }
				case "float" -> dst.putFloat(o, switch (m.name()) {
					case "viewWidth" -> width;
					case "viewHeight" -> height;
					case "aspectRatio" -> width / Math.max(1, height);
					case "frameTimeCounter" -> (System.nanoTime() - start) / 1e9f % 3600f;
					case "near" -> 0.05f;
					case "far" -> 256f;
					case "screenBrightness", "eyeBrightnessSmooth" -> 1f;
					default -> 0f;
				});
				case "int" -> dst.putInt(o, m.name().equals("frameCounter") ? frame % 720720 : 0);
				default -> {}
			}
		}
	}

	@Override
	public void setRenderStage(int irisOrdinal) {}

	@Override
	public Matrix4fc shadowModelView() {
		return IDENTITY;
	}

	@Override
	public Matrix4fc shadowProjection() {
		return IDENTITY;
	}

	@Override
	public Vector4fc clearFogColor() {
		return new Vector4f(0.5f, 0.7f, 1f, 1f);
	}
}
