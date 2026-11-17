package dev.emrion.shaders.converter.cli;

import dev.emrion.shaders.converter.Converter;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.spirv.ShadercSpirvCompiler;
import dev.emrion.shaders.format.Manifest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The real converter on a small pack written to a temp dir: manifest details that only the full pipeline shows. */
class SyntheticPackConversionTest {
	@TempDir static Path dir;
	static Converter.Result r;
	static Manifest m;
	static Manifest.Dimension w0, w1;

	static final String VSH = """
			#version 130
			varying vec2 texcoord;
			void main() { gl_Position = ftransform(); texcoord = gl_MultiTexCoord0.xy; }
			""";

	static void write(String path, String text) throws Exception { write(path, text.getBytes()); }

	static void write(String path, byte[] data) throws Exception {
		Path p = dir.resolve("pack/shaders").resolve(path);
		Files.createDirectories(p.getParent());
		Files.write(p, data);
	}

	@BeforeAll
	static void convert() throws Exception {
		write("shaders.properties", """
				customTexture.a=a/noise.png
				customTexture.b=b/noise.png
				customTexture.lut=lut.bin TEXTURE_1D R8 4 RED UNSIGNED_BYTE
				""");
		write("a/noise.png", new byte[]{1});
		write("b/noise.png", new byte[]{2});
		write("lut.bin", new byte[]{0, 1, 2, 3});
		write("composite.vsh", VSH);
		write("composite.fsh", """
				#version 130
				/*
				const int colortex1Format = R32UI;
				*/
				const bool colortex1MipmapEnabled = true;
				uniform sampler2D a;
				uniform sampler1D lut;
				uniform usampler2D colortex1;
				uniform sampler2D shadowcolor0;
				varying vec2 texcoord;
				void main() {
					gl_FragData[0] = texture(a, texcoord) + texture(lut, texcoord.x) + vec4(texture(colortex1, texcoord).r)
							+ textureLod(shadowcolor0, texcoord, 2.0);
				}
				""");
		write("composite1.csh", """
				#version 430
				layout(local_size_x = 2 * 4) in;
				const ivec3 workGroups = ivec3(1, 1, 1);
				layout(rgba8) uniform image2D colorimg2;
				void main() { imageStore(colorimg2, ivec2(gl_GlobalInvocationID.xy), vec4(1.0)); }
				""");
		write("shadowcomp.vsh", VSH);
		write("shadowcomp.fsh", """
				#version 130
				/* DRAWBUFFERS:1 */
				const bool colortex0MipmapEnabled = true;
				uniform sampler2D shadowcolor0;
				varying vec2 texcoord;
				void main() { gl_FragData[0] = textureLod(shadowcolor0, texcoord, 2.0); }
				""");
		write("world1/composite.vsh", VSH);
		write("world1/composite.fsh", """
				#version 130
				uniform sampler2D b;
				varying vec2 texcoord;
				void main() { gl_FragData[0] = texture(b, texcoord); }
				""");

		try (var compiler = new ShadercSpirvCompiler()) {
			r = ConverterCli.defaultConverter(compiler).convert(new Converter.Request(dir.resolve("pack"), null, dir.resolve("cache"), null, null));
		}
		assertTrue(r.diagnostics().stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR), r.diagnostics().toString());
		m = r.manifest();
		w0 = m.dimensions().get(""); // the root folder
		w1 = m.dimensions().get("world1");
	}

	@Test
	void constantExpressionLocalSizeComesFromTheSpirv() {
		assertArrayEquals(new int[]{8, 1, 1}, w0.programs().get("composite1").localSize());
	}

	@Test
	void integerColortexMipmapsSampleNearestMip() { // linear mip interpolation is invalid for integer formats
		var colortex1 = w0.resources().stream().filter(x -> "colortex1".equals(x.target())).findFirst().orElseThrow();
		assertEquals(new Manifest.SamplerDef("nearest", "nearest", "clamp", null), colortex1.sampler());
	}

	@Test
	void rawTexture1dIsA1dTexture() { // not a 2D colortex fallback behind a sampler1D
		var lut = m.textures().stream().filter(t -> t.rawPixel() != null).findFirst().orElseThrow();
		assertEquals(1, lut.dims());
		assertArrayEquals(new int[]{4}, lut.size());
		assertTrue(w0.resources().stream().anyMatch(x -> lut.name().equals(x.texture())));
	}

	@Test
	void shadowcompMipmapDirectivesAddressShadowcolor() {
		assertTrue(w0.frame().stream().anyMatch(c -> c.cmd().equals("mipmaps") && "shadowcolor0".equals(c.target())), w0.frame().toString());
		assertTrue(w0.frame().stream().noneMatch(c -> c.cmd().equals("mipmaps") && "colortex0".equals(c.target())));
		assertEquals(0, w0.targets().stream().filter(t -> t.name().equals("shadowcolor0")).findFirst().orElseThrow().mipLevels());
		assertTrue(w0.resources().stream().anyMatch(x -> "shadowcolor0".equals(x.target()) && x.sampler().mipmap().equals("linear")));
	}

	@Test
	void sameTextureNameInTwoDimensionsKeepsBothFiles() throws Exception {
		Map<String, String> files = new java.util.HashMap<>();
		for (var t : m.textures()) if (t.rawPixel() == null && t.file() != null) files.put(t.name(), Files.readString(r.outputDir().resolve(t.file())));
		String a = w0.resources().stream().map(Manifest.Resource::texture).filter(files::containsKey).findFirst().orElseThrow();
		String b = w1.resources().stream().map(Manifest.Resource::texture).filter(files::containsKey).findFirst().orElseThrow();
		assertNotEquals(a, b);
		assertEquals(List.of("\1", "\2"), List.of(files.get(a), files.get(b)));
	}
}
