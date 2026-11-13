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
 * The Iris API (v0, revision 4 as Iris 1.11.7 for 26.3) backed by {@link PackManager}, for mods that adapt to shaders.
 * Only reachable when Iris is not installed: {@link IrisApiShim} then puts the API classes on the class path and their
 * {@code IrisApiInternal} returns {@link #INSTANCE}. Iris-internal hooks (custom pipeline assignment, shadow-pass
 * callbacks, the text vertex sink) have no equivalent here: they are accepted and ignored, or throw for the sink.
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
		return "key.emrionshaders.packs";
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
		throw new UnsupportedOperationException("Emrion Shaders: the Iris text vertex sink is not available (Iris is not installed)");
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
		return false; // pack geometry renders forward Z (ARCHITECTURE §4.1), as Iris
	}
}
