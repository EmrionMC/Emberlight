package dev.emrion.shaders.converter.expr;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.emrion.shaders.converter.expr.ExprParser.Access;
import dev.emrion.shaders.converter.expr.ExprParser.Bin;
import dev.emrion.shaders.converter.expr.ExprParser.Call;
import dev.emrion.shaders.converter.expr.ExprParser.El;
import dev.emrion.shaders.converter.expr.ExprParser.Id;
import dev.emrion.shaders.converter.expr.ExprParser.Num;
import dev.emrion.shaders.converter.expr.ExprParser.Un;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Type resolution exactly like stareval's ExpressionResolver: the expected type flows top-down from the declared
 * uniform type, overloads are tried first without implicit casts, then through a final int→float cast, then with
 * implicit casts on the arguments. That decides e.g. that {@code frameCounter % 2} as a float is an int remainder cast
 * to float, and that {@code 1/2} is a float division. Produces the typed JSON AST of FORMAT.md §6.1.
 */
final class Resolver {
	static final class ResolveException extends RuntimeException {
		ResolveException(String message) { super(message); }
	}

	/** Iris BiomeCategories ordinals (CAT_* macros) and precipitation (PPT_*). BIOME_* stay symbolic (registry ids). */
	static final List<String> CATEGORIES = List.of("NONE", "TAIGA", "EXTREME_HILLS", "JUNGLE", "MESA", "PLAINS", "SAVANNA",
			"ICY", "THE_END", "BEACH", "FOREST", "OCEAN", "DESERT", "RIVER", "SWAMP", "MUSHROOM", "NETHER", "MOUNTAIN", "UNDERGROUND");
	static final Map<String, Integer> PRECIPITATION = Map.of("PPT_NONE", 0, "PPT_RAIN", 1, "PPT_SNOW", 2);

	private final Function<String, Type> variables;

	private Resolver(Function<String, Type> variables) { this.variables = variables; }

	/** Parses and resolves expr to the target type; throws ParseException / ResolveException. */
	static JsonObject compile(String expr, Type target, Function<String, Type> variables) {
		El el = ExprParser.parse(expr);
		JsonObject r = new Resolver(variables).resolve(target, el, true, true);
		if (r == null) throw new ResolveException("Couldn't resolve to " + target.id);
		return r;
	}

	private static boolean isConstant(String name) {
		return name.startsWith("BIOME_") || PRECIPITATION.containsKey(name)
				|| (name.startsWith("CAT_") && CATEGORIES.contains(name.substring(4)));
	}

	/** Macro-like constants count as number tokens (Iris substitutes them before parsing). */
	private static boolean isNumberToken(El el) { return el instanceof Num || (el instanceof Id id && isConstant(id.name())); }

	private JsonObject resolve(Type target, El el, boolean nonImplicit, boolean implicit) {
		JsonObject castable;
		Type inner;
		switch (el) {
			case Un u -> { return resolveCall(target, u.op(), List.of(u.arg()), el, nonImplicit, implicit); }
			case Bin b -> { return resolveCall(target, b.op(), List.of(b.lhs(), b.rhs()), el, nonImplicit, implicit); }
			case Call c -> { return resolveCall(target, c.fn(), c.args(), el, nonImplicit, implicit); }
			case Access a -> { return resolveCall(target, "<access$" + a.field() + ">", List.of(a.base()), el, nonImplicit, implicit); }
			case Num n -> {
				castable = number(n.text());
				inner = Type.of(castable.get("type").getAsString());
			}
			case Id id when isConstant(id.name()) -> {
				castable = constant(id.name());
				inner = Type.INT;
			}
			case Id id -> {
				inner = variables.apply(id.name());
				if (inner == null) throw new ResolveException("Unknown variable: " + id.name());
				castable = node("var", inner);
				castable.addProperty("name", id.name());
			}
		}
		if (inner == target) return castable;
		if (!implicit) return null;
		for (Functions.Fn f : Functions.resolve("<cast>", target))
			if (f.params()[0] == inner) return cast(target, castable);
		return null;
	}

	private JsonObject resolveCall(Type target, String name, List<El> args, El src, boolean nonImplicit, boolean implicit) {
		JsonObject result = null;
		if (nonImplicit) result = resolveInternal(target, name, args, src, false);
		if (result != null) return result;
		if (!implicit) return null;
		for (Functions.Fn f : Functions.resolve("<cast>", target)) {
			JsonObject u = resolveCall(f.params()[0], name, args, src, true, true);
			if (u == null) continue;
			if (result != null) throw new ResolveException("Ambiguity in " + name);
			result = cast(target, u);
		}
		if (result != null) return result;
		return resolveInternal(target, name, args, src, true);
	}

	private JsonObject resolveInternal(Type target, String name, List<El> args, El src, boolean implicit) {
		int n = args.size();
		JsonObject result = null;
		Functions.Fn chosen = null;
		functions:
		for (Functions.Fn f : Functions.resolve(name, target)) {
			if (f.params().length != n) continue;
			JsonObject[] params = new JsonObject[n];
			for (int i = 0; i < n; i++) {
				if (f.constant()[i] && !isNumberToken(args.get(i))) continue functions;
				JsonObject e = resolve(f.params()[i], args.get(i), !implicit || n > 1, implicit);
				if (e == null) continue functions;
				params[i] = e;
			}
			if (result != null && f.priority() == chosen.priority())
				throw new ResolveException("Ambiguity in " + name + Functions.key("", f.params()));
			if (chosen == null || f.priority() >= chosen.priority()) {
				result = build(f, params, args, src);
				chosen = f;
			}
		}
		return result;
	}

	private static JsonObject build(Functions.Fn f, JsonObject[] params, List<El> args, El src) {
		String name = f.name();
		JsonObject o;
		if (src instanceof Bin b) {
			o = node("bin", f.ret());
			o.addProperty("operator", Functions.OP_SYMBOL.get(b.op()));
			o.add("lhs", params[0]);
			o.add("rhs", params[1]);
		} else if (src instanceof Un u) {
			o = node("un", f.ret());
			o.addProperty("operator", Functions.OP_SYMBOL.get(u.op()));
			o.add("arg", params[0]);
		} else if (src instanceof Access a) {
			o = node("member", f.ret());
			o.add("arg", params[0]);
			o.addProperty("field", a.field());
		} else if (name.equals("toFloat") || name.equals("toInt")) {
			o = cast(f.ret(), params[0]);
		} else if (name.matches("[ib]?vec[234]")) {
			o = node("vec", f.ret());
			o.addProperty("size", params.length);
			o.add("args", array(params, 0));
		} else {
			o = node("call", f.ret());
			o.addProperty("fn", name);
			boolean hasId = name.equals("smooth") && f.constant()[0];
			if (hasId) o.addProperty("id", args.getFirst() instanceof Num num ? num.text() : ((Id) args.getFirst()).name());
			o.add("args", array(params, hasId ? 1 : 0));
		}
		return o;
	}

	private static JsonArray array(JsonObject[] params, int from) {
		JsonArray a = new JsonArray();
		for (int i = from; i < params.length; i++) a.add(params[i]);
		return a;
	}

	private static JsonObject node(String op, Type type) {
		JsonObject o = new JsonObject();
		o.addProperty("op", op);
		o.addProperty("type", type.id);
		return o;
	}

	private static JsonObject cast(Type target, JsonObject arg) {
		JsonObject o = node("cast", target);
		o.add("arg", arg);
		return o;
	}

	private static JsonObject constant(String name) {
		Integer v = PRECIPITATION.get(name);
		if (v == null && name.startsWith("CAT_")) v = CATEGORIES.indexOf(name.substring(4));
		if (v != null) {
			JsonObject o = node("lit", Type.INT);
			o.addProperty("value", v);
			return o;
		}
		JsonObject o = node("const", Type.INT);
		o.addProperty("name", name);
		return o;
	}

	/** stareval number rules: 0b…, 0x…, leading 0 = octal, else decimal int; otherwise Java Float.parseFloat. */
	static JsonObject number(String s) {
		Integer iv = null;
		try {
			if (s.length() >= 2 && s.charAt(0) == '0') {
				iv = switch (s.charAt(1)) {
					case 'b' -> Integer.parseInt(s.substring(2), 2);
					case 'x' -> Integer.parseInt(s.substring(2), 16);
					default -> Integer.parseInt(s.substring(1), 8);
				};
			} else iv = Integer.parseInt(s);
		} catch (NumberFormatException ignored) {
			// fall through to float
		}
		if (iv != null) {
			JsonObject o = node("lit", Type.INT);
			o.addProperty("value", iv);
			return o;
		}
		try {
			JsonObject o = node("lit", Type.FLOAT);
			o.addProperty("value", Float.parseFloat(s));
			return o;
		} catch (NumberFormatException e) {
			throw new ResolveException("Illegal number: " + s);
		}
	}
}
