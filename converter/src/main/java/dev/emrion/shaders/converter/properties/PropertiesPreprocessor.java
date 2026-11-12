package dev.emrion.shaders.converter.properties;

import dev.emrion.shaders.converter.glsl.Preprocessor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runs a .properties file through the preprocessor the way Iris does (iris-spec §4.1, Iris PropertiesPreprocessor):
 * lines are trimmed and blank lines dropped; a line starting with '#' survives only if it starts with a JCPP directive
 * name (prefix match, so "#iffy" counts as "#if"), otherwise it is a comment and becomes empty; every other '#' is
 * deleted; backslashes are hidden from the preprocessor so line continuations reach the properties parser.
 */
public final class PropertiesPreprocessor {
	private PropertiesPreprocessor() {}

	private static final List<String> DIRECTIVES = List.of("#define", "#elif", "#else", "#endif", "#error", "#if",
			"#ifdef", "#ifndef", "#include", "#line", "#pragma", "#undef", "#warning", "#include_next", "#import");
	private static final String BACKSLASH = "IRIS_PASSTHROUGHBACKSLASH";

	/** @param macros all predefined macros (environment + option values) */
	public static Preprocessor.Result preprocess(String source, Map<String, String> macros) {
		String cleaned = source.lines().map(String::trim).filter(s -> !s.isBlank()).map(line -> {
			if (line.startsWith("#")) return DIRECTIVES.stream().anyMatch(line::startsWith) ? line : "";
			return line.replace("#", "");
		}).collect(Collectors.joining("\n")) + "\n";
		Preprocessor.Result r = Preprocessor.run(cleaned.replace("\\", BACKSLASH), macros, Preprocessor.Mode.PROPERTIES);
		return new Preprocessor.Result(r.text().replace(BACKSLASH, "\\"), r.textWithComments().replace(BACKSLASH, "\\"),
				r.hoisted(), r.hoistedLines(), r.warnings());
	}

	/** The text Iris hands to Properties.load (comments kept). */
	public static String process(String source, Map<String, String> macros) {
		return preprocess(source, macros).textWithComments();
	}
}
