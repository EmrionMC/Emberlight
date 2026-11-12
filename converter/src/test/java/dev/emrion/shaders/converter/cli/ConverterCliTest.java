package dev.emrion.shaders.converter.cli;

import dev.emrion.shaders.converter.Converter;
import dev.emrion.shaders.converter.report.Diagnostic;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ConverterCliTest {
	Converter.Request seen;
	final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

	int run(List<Diagnostic> diagnostics, String... args) {
		Converter fake = request -> {
			seen = request;
			return new Converter.Result(Path.of("out/key"), null, diagnostics, false, 3, diagnostics.isEmpty() ? 0 : 1);
		};
		return ConverterCli.run(args, () -> fake, new PrintStream(buffer, true));
	}

	@Test
	void parsesArgumentsIntoRequest() {
		run(List.of(), "pack.zip", "-", "out", "--dimension", "world0", "--no-cache");
		assertEquals(Path.of("pack.zip"), seen.pack());
		assertNull(seen.settings());
		assertEquals(Path.of("out"), seen.cacheRoot());
		assertEquals(List.of("world0"), seen.dimensions());
		assertFalse(seen.useCache());
	}

	@Test
	void exitCodeReflectsErrorsAndListIsCapped() {
		// manifest null → failure even without diagnostics
		assertEquals(1, run(List.of(), "p", "s.txt", "o"));
		var errors = IntStream.range(0, 25).mapToObj(i -> Diagnostic.error("f", i, "e" + i)).toList();
		buffer.reset();
		assertEquals(1, run(errors, "p", "s.txt", "o"));
		String text = buffer.toString();
		assertTrue(text.contains("stages ok: 3, failed: 1, errors: 25"), text);
		assertTrue(text.contains("5 more"), text);
		assertTrue(seen.useCache());
	}

	@Test
	void usageErrors() {
		assertEquals(2, run(List.of(), "only-one"));
		assertEquals(2, run(List.of(), "a", "b", "c", "--bogus"));
		assertEquals(2, run(List.of(), "a", "b", "c", "--dimension"));
	}
}
