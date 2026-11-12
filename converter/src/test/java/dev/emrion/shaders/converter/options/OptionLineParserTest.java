package dev.emrion.shaders.converter.options;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OptionLineParserTest {
	private static OptionLine opt(String line) {
		return OptionLineParser.parse(line).option();
	}

	@Test
	void valueDefineNeedsAllowedList() {
		OptionLine o = opt("  #define SHADOW_QUALITY 2 //[-1 0 1 2 3] Shadow quality");
		assertEquals(OptionLine.Kind.DEFINE_STRING, o.kind());
		assertEquals("2", o.defaultValue());
		assertEquals(List.of("-1", "0", "1", "2", "3"), o.allowed());
		assertEquals("Shadow quality", o.comment());
		assertNull(opt("#define SHADOW_QUALITY 2"));
		assertNull(opt("#define SHADOW_QUALITY 2 // no list"));
	}

	@Test
	void defaultIsAppendedToAllowed() {
		assertEquals(List.of("1.0", "2.0", "1.50"), opt("#define X 1.50 // [1.0 2.0]").allowed());
	}

	@Test
	void floatAndWordValues() {
		assertEquals("1.00", opt("#define POM_DEPTH 1.00 //[0.5 1.00]").defaultValue());
		assertEquals("2.0f", opt("#define F 2.0f //[2.0f]").defaultValue());
		assertEquals("HIGH", opt("#define Q HIGH //[LOW HIGH]").defaultValue());
	}

	@Test
	void booleanDefines() {
		OptionLine on = opt("#define TAA");
		assertEquals(OptionLine.Kind.DEFINE_BOOL, on.kind());
		assertEquals("true", on.defaultValue());
		OptionLine off = opt("  ///  #define BLOOM // Bloom effect");
		assertEquals("false", off.defaultValue());
		assertEquals("Bloom effect", off.comment());
	}

	@Test
	void commentedValueDefineIsRejected() {
		assertNull(opt("//#define X 1 //[1 2]"));
	}

	@Test
	void functionLikeAndJunkAreRejected() {
		assertNull(opt("#define F(x) x"));
		assertNull(opt("#define X 1 + 2 //[1]"));
		assertNull(opt("int x; // #define Y"));
	}

	@Test
	void constOptionsAreWhitelisted() {
		OptionLine sd = opt("const float shadowDistance = 256.0; //[64.0 256.0]");
		assertEquals(OptionLine.Kind.CONST_STRING, sd.kind());
		assertEquals("float", sd.declType());
		assertNull(opt("const float myConst = 1.0; //[1.0 2.0]"));
		assertNull(opt("const int shadowMapResolution = 2048;"), "string const needs an allowed list");
		OptionLine b = opt("const bool shadowHardwareFiltering = true;");
		assertEquals(OptionLine.Kind.CONST_BOOL, b.kind());
		assertNull(opt("const bool shadowHardwareFiltering = 1;"));
		assertNull(opt("const vec4 shadowDistance = vec4(1.0);"));
	}

	@Test
	void ifdefReferencesMustBeBare() {
		assertEquals("X", OptionLineParser.parse("  #ifdef X").reference());
		assertEquals("Y", OptionLineParser.parse("#ifndef Y  ").reference());
		assertNull(OptionLineParser.parse("#ifdef X // c").reference());
		assertNull(OptionLineParser.parse("#if defined X").reference());
	}
}
