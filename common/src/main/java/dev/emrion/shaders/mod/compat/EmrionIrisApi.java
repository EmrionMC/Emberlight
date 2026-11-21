package dev.emrion.shaders.mod.compat;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.emrion.shaders.mod.pack.PackManager;
import dev.emrion.shaders.mod.pack.PackScreen;
import dev.emrion.shaders.mod.runtime.GeometryHooks;
import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntFunction;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.api.v0.IrisApiConfig;
import net.irisshaders.iris.api.v0.IrisProgram;
import net.irisshaders.iris.api.v0.IrisShadowProgram;
import net.irisshaders.iris.api.v0.IrisShadowRenderCallback;
import net.irisshaders.iris.api.v0.IrisTextVertexSink;

/**
 * The Iris API (v0, revision 4) backed by {@link PackManager}; only reachable without Iris. Iris-internal hooks are accepted
 * and ignored, the text vertex sink throws.
 */
public final class EmrionIrisApi implements IrisApi, IrisApiConfig {
	// lambdas, not method references: PackManager needs a running game, the unit test builds its own instance
	public static final EmrionIrisApi INSTANCE = new EmrionIrisApi(() -> PackManager.running(), () -> GeometryHooks.inShadowPass(),
			() -> PackManager.selected() != null && PackManager.enabled(), on -> PackManager.setEnabled(on), () -> PackManager.sunPathRotation());

	private final BooleanSupplier inUse, shadowPass, enabled;
	private final Consumer<Boolean> setEnabled;
	private final DoubleSupplier sunPathRotation;

	EmrionIrisApi(BooleanSupplier inUse, BooleanSupplier shadowPass, BooleanSupplier enabled, Consumer<Boolean> setEnabled, DoubleSupplier sunPathRotation) {
		this.inUse = inUse;
		this.shadowPass = shadowPass;
		this.enabled = enabled;
		this.setEnabled = setEnabled;
		this.sunPathRotation = sunPathRotation;
	}

	@Override
	public int getMinorApiRevision() {
		return 4;
	}

	@Override
	public boolean isShaderPackInUse() {
		return inUse.getAsBoolean();
	}

	@Override
	public boolean isRenderingShadowPass() {
		return inUse.getAsBoolean() && shadowPass.getAsBoolean(); // Iris: only true while a pack is in use
	}

	@Override
	public Object openMainIrisScreenObj(Object parent) {
		return new PackScreen((net.minecraft.client.gui.screens.Screen) parent);
	}

	@Override
	public String getMainScreenLanguageKey() {
		return "key.emberlight.packs";
	}

	@Override
	public IrisApiConfig getConfig() {
		return this;
	}

	@Override
	public boolean areShadersEnabled() {
		return enabled.getAsBoolean();
	}

	@Override
	public void setShadersEnabledAndApply(boolean on) {
		setEnabled.accept(on);
	}

	@Override
	public IrisTextVertexSink createTextVertexSink(int maxQuadCount, IntFunction<ByteBuffer> bufferProvider) {
		throw new UnsupportedOperationException("Emberlight: the Iris text vertex sink is not available (Iris is not installed)");
	}

	@Override
	public float getSunPathRotation() {
		return inUse.getAsBoolean() ? (float) sunPathRotation.getAsDouble() : 0;
	}

	@Override
	public void assignPipeline(RenderPipeline pipeline, IrisProgram program) {
		// shortcut: mod pipelines keep their vanilla handling (GbufferMapping); map them to gbuffer programs if a mod needs it
	}

	@Override
	public void assignPipelineShadow(RenderPipeline pipeline, IrisShadowProgram program) {
	}

	@Override
	public void registerShadowRenderCallback(IrisShadowRenderCallback callback) {
		// shortcut: our shadow pass draws terrain and entities only; call these after shadow_opaque if a mod needs it
	}

	@Override
	public boolean isReverseZDuringShaders() {
		return false; // pack geometry renders forward Z, as Iris
	}
}
