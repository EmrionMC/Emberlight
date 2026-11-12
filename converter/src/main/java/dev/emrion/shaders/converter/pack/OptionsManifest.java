package dev.emrion.shaders.converter.pack;

import dev.emrion.shaders.converter.options.OptionLine;
import dev.emrion.shaders.converter.options.OptionSet;
import dev.emrion.shaders.converter.options.OptionValues;
import dev.emrion.shaders.converter.options.Profiles;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.converter.properties.RawProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds FORMAT.md §4 {@link Manifest.Options}. */
final class OptionsManifest {
	private OptionsManifest() {}

	static Manifest.Options build(Path settingsFile, OptionValues values, Map<String, String> settings, Profiles profiles,
	                              RawProperties raw, String root) {
		OptionSet set = values.options();
		List<Manifest.OptionDecl> declared = new ArrayList<>();
		set.booleans().values().forEach(o -> declared.add(decl(o, root)));
		set.strings().values().forEach(o -> declared.add(decl(o, root)));
		return new Manifest.Options(
				settingsFile == null ? null : settingsFile.getFileName().toString(),
				values.resolved(), declared, OptionValues.unknownKeys(set, settings), profiles.profiles(),
				raw.screens(), raw.sliders(), raw.columns());
	}

	private static Manifest.OptionDecl decl(OptionSet.Option o, String root) {
		OptionLine d = o.decl();
		String kind = d.isConst() ? "const" : d.isBoolean() ? "define_bool" : "define_value";
		String type = d.isBoolean() ? "bool" : d.declType() != null ? d.declType() : inferType(d.defaultValue());
		OptionSet.Location loc = o.locations().getFirst();
		return new Manifest.OptionDecl(d.name(), kind, type, d.defaultValue(), d.allowed(), root + loc.file(), loc.line() + 1,
				d.comment());
	}

	static String inferType(String v) {
		if (v.matches("[+-]?\\d+")) return "int";
		try {
			Float.parseFloat(v);
			return "float";
		} catch (NumberFormatException e) {
			return "string";
		}
	}
}
