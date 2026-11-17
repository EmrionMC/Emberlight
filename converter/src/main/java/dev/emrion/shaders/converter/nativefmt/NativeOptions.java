package dev.emrion.shaders.converter.nativefmt;

import dev.emrion.shaders.converter.pack.PackSource;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.format.Manifest;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Options of a native pack (§10): values resolved from the settings file, the manifest's {@link Manifest.Options} for the
 * option screen, the {@code #define}s of the prelude, pass conditions and the option screen's translations.
 * Settings-file form (shared with converted packs): bool true/false, enum = value index, colour = NAME_R/_G/_B.
 */
final class NativeOptions {
	private static final String[] RGB = {"_R", "_G", "_B"};

	final NativeManifest m;
	final Map<String, NativeManifest.Option> byName = new LinkedHashMap<>();
	/** Resolved, settings-file form (colour as NAME_R/_G/_B). */
	final Map<String, String> values = new TreeMap<>();
	private final Map<String, String> settings;
	/** The engine conditions that hold for this compilation ({@link Conditions#ENGINE}). */
	final java.util.Set<String> engine = new java.util.HashSet<>();
	private final Path settingsFile;
	private final java.util.Set<String> colorChannels = new java.util.HashSet<>();

	NativeOptions(NativeManifest m, Map<String, String> settings, Path settingsFile, Diagnostics diagnostics) {
		this.m = m;
		this.settings = settings;
		this.settingsFile = settingsFile;
		String file = settingsFile == null ? null : settingsFile.getFileName().toString();
		for (var o : m.options()) {
			byName.put(o.name(), o);
			if (o.type().equals("color")) for (String c : RGB) colorChannels.add(o.name() + c);
			for (var d : decls(o)) {
				String v = settings.get(d.name());
				if (v != null && !valid(d, v.trim())) {
					diagnostics.add(Diagnostic.warning(file, 0, "setting " + d.name() + "=" + v + " is not a valid value; using " + d.defaultValue()));
					v = null;
				}
				values.put(d.name(), v == null ? d.defaultValue() : canonical(d, v.trim()));
			}
		}
	}

	/** The settings-file entries of one option (three for a colour). */
	static List<Manifest.OptionDecl> decls(NativeManifest.Option o) {
		return switch (o.type()) {
			case "bool" -> List.of(new Manifest.OptionDecl(o.name(), "define_bool", "bool", o.defaultValue(), List.of(), NativeManifest.FILE, o.line(), null));
			case "enum" -> {
				List<String> idx = new ArrayList<>();
				for (int i = 0; i < o.values().size(); i++) idx.add(Integer.toString(i));
				yield List.of(new Manifest.OptionDecl(o.name(), "define_value", "int", Integer.toString(o.values().indexOf(o.defaultValue())), idx,
						NativeManifest.FILE, o.line(), null));
			}
			case "color" -> {
				String[] c = o.defaultValue().split(",");
				List<Manifest.OptionDecl> out = new ArrayList<>();
				for (int i = 0; i < 3; i++) {
					TreeSet<BigDecimal> vals = new TreeSet<>();
					for (int k = 0; k <= 20; k++) vals.add(BigDecimal.valueOf(k * 5L, 2));
					vals.add(new BigDecimal(c[i]));
					out.add(new Manifest.OptionDecl(o.name() + RGB[i], "define_value", "float", c[i],
							vals.stream().map(b -> NativeManifest.glslFloat(b.doubleValue())).toList(), NativeManifest.FILE, o.line(), null));
				}
				yield out;
			}
			default -> List.of(new Manifest.OptionDecl(o.name(), "define_value", o.type(), o.defaultValue(), o.values(), NativeManifest.FILE, o.line(), null));
		};
	}

	boolean valid(Manifest.OptionDecl d, String v) {
		if (d.type().equals("bool")) return v.equals("true") || v.equals("false");
		if (colorChannels.contains(d.name())) { // colour channel: any value in 0-1
			try {
				double x = Double.parseDouble(v);
				return x >= 0 && x <= 1;
			} catch (NumberFormatException e) {
				return false;
			}
		}
		return d.allowed().contains(canonical(d, v));
	}

	static String canonical(Manifest.OptionDecl d, String v) {
		if (!d.type().equals("float")) return v;
		try {
			return NativeManifest.glslFloat(Double.parseDouble(v));
		} catch (NumberFormatException e) {
			return v;
		}
	}

	Manifest.Options manifest() {
		List<Manifest.OptionDecl> declared = new ArrayList<>();
		List<String> sliders = new ArrayList<>();
		for (var o : m.options()) {
			declared.addAll(decls(o));
			if (o.type().equals("color")) for (String c : RGB) sliders.add(o.name() + c);
			else if (o.slider()) sliders.add(o.name());
		}
		List<String> unknown = new ArrayList<>();
		for (String k : settings.keySet()) if (!values.containsKey(k)) unknown.add(k);

		Map<String, Map<String, String>> profiles = new LinkedHashMap<>();
		for (String p : m.profiles().keySet()) profiles.put(p, profile(p, 0));

		List<NativeManifest.Page> pages = m.pages().isEmpty() && !m.options().isEmpty()
				? List.of(new NativeManifest.Page("main", 0, 2, m.options().stream().map(NativeManifest.Option::name).toList())) : m.pages();
		Map<String, List<String>> screens = new LinkedHashMap<>();
		Map<String, Integer> columns = new LinkedHashMap<>();
		for (int i = 0; i < pages.size(); i++) {
			var p = pages.get(i);
			String key = i == 0 ? "" : p.name();
			List<String> items = new ArrayList<>();
			for (String it : p.items()) {
				var o = byName.get(it);
				if (o != null && o.type().equals("color")) for (String c : RGB) items.add(it + c);
				else items.add(it);
			}
			screens.put(key, items);
			columns.put(key, p.columns());
		}
		return new Manifest.Options(settingsFile == null ? null : settingsFile.getFileName().toString(), values, declared, unknown, profiles,
				screens, sliders, columns);
	}

	/** A profile with its inherited values, in settings-file form. */
	Map<String, String> profile(String name, int depth) {
		Map<String, String> out = new LinkedHashMap<>();
		var p = m.profiles().get(name);
		if (p == null || depth > 16) return out;
		if (p.containsKey("inherit")) out.putAll(profile(p.get("inherit"), depth + 1));
		p.forEach((k, v) -> {
			if (k.equals("inherit")) return;
			var o = byName.get(k);
			switch (o.type()) {
				case "enum" -> out.put(k, Integer.toString(o.values().indexOf(v)));
				case "color" -> {
					String[] c = v.split(",");
					for (int i = 0; i < 3; i++) out.put(k + RGB[i], c[i]);
				}
				default -> out.put(k, v);
			}
		});
		return out;
	}

	/** Prelude {@code #define}s (§10.1): bool → NAME 1 when on; int/float → value; enum → index + NAME_VALUE; colour → vec3. */
	String defines() {
		StringBuilder b = new StringBuilder();
		for (var o : m.options()) {
			switch (o.type()) {
				case "bool" -> { if (values.get(o.name()).equals("true")) b.append("#define ").append(o.name()).append(" 1\n"); }
				case "enum" -> {
					b.append("#define ").append(o.name()).append(' ').append(values.get(o.name())).append('\n');
					for (int i = 0; i < o.values().size(); i++)
						b.append("#define ").append(o.name()).append('_').append(o.values().get(i).toUpperCase(Locale.ROOT)).append(' ').append(i).append('\n');
				}
				case "color" -> {
					for (String c : RGB) b.append("#define ").append(o.name()).append(c).append(' ').append(values.get(o.name() + c)).append('\n');
					b.append("#define ").append(o.name()).append(" vec3(").append(o.name()).append("_R, ").append(o.name()).append("_G, ")
							.append(o.name()).append("_B)\n");
				}
				default -> b.append("#define ").append(o.name()).append(' ').append(values.get(o.name())).append('\n');
			}
		}
		return b.toString();
	}

	/** The integer value of an option or enum constant in a condition (bool: 1/0). */
	Long conditionValue(String name) {
		if (Conditions.ENGINE.contains(name)) return engine.contains(name) ? 1L : 0L;
		var o = byName.get(name);
		if (o != null) return o.type().equals("bool") ? (values.get(name).equals("true") ? 1L : 0L) : (long) Double.parseDouble(values.get(name));
		String e = Conditions.enumConstant(name, byName);
		return e == null ? null : Long.parseLong(e);
	}

	boolean enabled(NativeManifest.Pass p) { return p.enabled() == null || Conditions.eval(p.enabled(), this::conditionValue) != 0; }

	private static final Pattern NAME = Pattern.compile("\\b[A-Z][A-Z0-9_]*\\b");

	/** Custom-uniform expressions: int/float/enum option names and enum constants become their values (§9). */
	String substitute(String expr) {
		Matcher x = NAME.matcher(expr);
		StringBuilder sb = new StringBuilder();
		while (x.find()) {
			var o = byName.get(x.group());
			String v = null;
			if (o != null && !o.type().equals("bool") && !o.type().equals("color")) v = values.get(o.name());
			else if (o == null) v = Conditions.enumConstant(x.group(), byName);
			x.appendReplacement(sb, Matcher.quoteReplacement(v == null ? x.group() : v));
		}
		return x.appendTail(sb).toString();
	}

	// ---- translations ---------------------------------------------------------------------------------------------------

	private static final Pattern LANG_FILE = Pattern.compile("lang/([a-z0-9_]+)\\.lang");
	private static final Pattern VALUE_KEY = Pattern.compile("value\\.([A-Z0-9_]+)\\.([a-z0-9_]+)");
	private static final Pattern OPTION_KEY = Pattern.compile("option\\.([A-Z0-9_]+)");

	/**
	 * lang/*.lang (§10.5) in the option screen's keys: page.x → screen.x, enum value names → indices, a colour's label → its
	 * three channels. en_us starts with the manifest's labels and comments.
	 */
	static Map<String, Map<String, String>> lang(PackSource src, NativeManifest m) throws IOException {
		Map<String, NativeManifest.Option> options = new LinkedHashMap<>();
		m.options().forEach(o -> options.put(o.name(), o));
		Map<String, Map<String, String>> out = new TreeMap<>();
		Map<String, String> en = out.computeIfAbsent("en_us", k -> new LinkedHashMap<>());
		for (var o : m.options()) {
			String label = o.label() != null ? o.label() : title(o.name());
			if (o.type().equals("color")) {
				String[][] ch = {{"_R", "red"}, {"_G", "green"}, {"_B", "blue"}};
				for (String[] c : ch) en.put("option." + o.name() + c[0], label + ": " + c[1]);
			} else en.put("option." + o.name(), label);
			if (o.comment() != null) en.put("option." + o.name() + ".comment", o.comment());
			if (o.type().equals("enum")) for (int i = 0; i < o.values().size(); i++) en.put("value." + o.name() + "." + i, title(o.values().get(i)));
			if (o.type().equals("bool")) {
				en.put("value." + o.name() + ".true", "On");
				en.put("value." + o.name() + ".false", "Off");
			}
		}
		for (var p : m.pages()) en.put("screen." + p.name(), title(p.name()));
		for (String p : m.profiles().keySet()) en.put("profile." + p, title(p));
		for (String path : src.list()) {
			Matcher lm = LANG_FILE.matcher(path);
			if (!lm.matches()) continue;
			Properties p = new Properties();
			p.load(new StringReader(src.readString(path).orElse("")));
			Map<String, String> b = out.computeIfAbsent(lm.group(1), k -> new LinkedHashMap<>());
			for (String k : new TreeSet<>(p.stringPropertyNames())) {
				String v = p.getProperty(k), key = k;
				if (k.startsWith("page.")) key = "screen." + k.substring(5);
				Matcher vm = VALUE_KEY.matcher(k);
				if (vm.matches() && options.containsKey(vm.group(1)) && options.get(vm.group(1)).type().equals("enum"))
					key = "value." + vm.group(1) + "." + options.get(vm.group(1)).values().indexOf(vm.group(2));
				Matcher om = OPTION_KEY.matcher(k);
				if (om.matches() && options.containsKey(om.group(1)) && options.get(om.group(1)).type().equals("color")) {
					for (String c : RGB) b.put("option." + om.group(1) + c, v + " " + c.substring(1));
					continue;
				}
				b.put(key, v);
			}
		}
		return out;
	}

	static String title(String s) {
		String t = s.toLowerCase(Locale.ROOT).replace('_', ' ');
		return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
	}
}
