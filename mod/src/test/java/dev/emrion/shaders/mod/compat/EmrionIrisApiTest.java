package dev.emrion.shaders.mod.compat;

import static org.junit.jupiter.api.Assertions.*;

import net.irisshaders.iris.api.v0.IrisApi;
import org.junit.jupiter.api.Test;

class EmrionIrisApiTest {
	boolean running, shadow, selected = true, enabled = true;

	EmrionIrisApi api() {
		return new EmrionIrisApi(() -> running, () -> shadow, () -> selected && enabled, on -> enabled = on, () -> 25);
	}

	@Test
	void reportsPackStateAndOnlyShadowWhileInUse() {
		IrisApi a = api();
		assertEquals(4, a.getMinorApiRevision());
		assertFalse(a.isShaderPackInUse());
		shadow = true;
		assertFalse(a.isRenderingShadowPass(), "no shadow pass without a pack in use");
		assertEquals(0, a.getSunPathRotation());
		running = true;
		assertTrue(a.isShaderPackInUse());
		assertTrue(a.isRenderingShadowPass());
		assertEquals(25, a.getSunPathRotation());
		shadow = false;
		assertFalse(a.isRenderingShadowPass());
	}

	@Test
	void configTogglesShaders() {
		IrisApi a = api();
		assertTrue(a.getConfig().areShadersEnabled());
		a.getConfig().setShadersEnabledAndApply(false);
		assertFalse(enabled);
		assertFalse(a.getConfig().areShadersEnabled());
		a.getConfig().setShadersEnabledAndApply(true);
		selected = false;
		assertFalse(a.getConfig().areShadersEnabled(), "no pack selected: not enabled");
		assertThrows(UnsupportedOperationException.class, () -> a.createTextVertexSink(1, ByteBuffer -> null));
	}
}
