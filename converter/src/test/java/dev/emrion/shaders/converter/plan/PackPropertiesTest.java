package dev.emrion.shaders.converter.plan;

import org.junit.jupiter.api.Test;

import static dev.emrion.shaders.converter.plan.PlannerTestSupport.props;
import static org.junit.jupiter.api.Assertions.*;

class PackPropertiesTest {
	@Test
	void defaultsAndComplementaryFlags() {
		var d = PackProperties.from(props());
		assertEquals(true, d.get("sun"));
		assertEquals(false, d.get("shadowPlayer"));
		assertEquals("default", d.get("shadowCulling"));
		assertFalse(d.containsKey("clouds"));
		assertFalse(d.containsKey("particlesOrdering"));
		var c = PackProperties.from(props("sun", "false", "clouds", "off", "separateAo", "true", "shadow.culling", "reversed",
				"particles.ordering", "mixed", "beacon.beam.depth", "true", "shadowBlockEntities", "false"));
		assertEquals(false, c.get("sun"));
		assertEquals("off", c.get("clouds"));
		assertEquals(true, c.get("separateAo"));
		assertEquals("reversed", c.get("shadowCulling"));
		assertEquals("mixed", c.get("particlesOrdering"));
		assertEquals(true, c.get("beaconBeamDepth"));
		assertEquals(false, c.get("shadowBlockEntities"));
	}
}
