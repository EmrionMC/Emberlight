import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Static check of our mixins on NeoForge (no game start needed): for every injector it compares the target method in vanilla
 * (what Fabric runs) and in NeoForge's patched Minecraft, and reports a missing class/method or a different number of matches
 * for @At INVOKE/FIELD/NEW/RETURN/TAIL. HEAD always matches; CONSTANT/LOAD/STORE and @Local are not compared (review those by
 * hand in patched classes). Each differing point needs a look at NeoForge's patch (userdev jar, patches/...java.patch).
 * Usage (ASM 9 jars from the Gradle cache, after ./gradlew :common:classes :neoforge:compileJava):
 *   java -cp asm.jar:asm-tree.jar neoforge/tools/MixinAudit.java common/build/classes/java/main/dev/emrion/shaders/mod/mixin \
 *     <loom minecraft-merged.jar>:<sodium-fabric.jar>:<bbe-fabric.jar> \
 *     neoforge/build/moddev/artifacts/minecraft-patched-<ver>.jar:<sodium-neoforge nested mod jar>:<bbe-neoforge.jar>
 */
public class MixinAudit {
	static Map<String, ClassNode> load(String jar) throws IOException {
		Map<String, ClassNode> m = new HashMap<>();
		try (var z = new ZipFile(jar)) {
			for (var e : Collections.list(z.entries())) if (e.getName().endsWith(".class")) {
				var cn = new ClassNode();
				new ClassReader(z.getInputStream(e)).accept(cn, ClassReader.SKIP_FRAMES);
				m.put(cn.name, cn);
			}
		}
		return m;
	}
	static Object val(AnnotationNode a, String k) {
		if (a.values == null) return null;
		for (int i = 0; i < a.values.size(); i += 2) if (a.values.get(i).equals(k)) return a.values.get(i + 1);
		return null;
	}
	static List<?> list(Object o) { return o == null ? List.of() : o instanceof List<?> l ? l : List.of(o); }

	public static void main(String[] a) throws Exception {
		Map<String, ClassNode> van = new HashMap<>(), neo = new HashMap<>();
		for (String j : a[1].split(":")) van.putAll(load(j));
		for (String j : a[2].split(":")) neo.putAll(load(j));
		int issues = 0, points = 0;
		List<Path> files;
		try (var s = Files.walk(Path.of(a[0]))) { files = s.filter(p -> p.toString().endsWith(".class")).sorted().toList(); }
		for (Path f : files) {
			var cn = new ClassNode();
			new ClassReader(Files.readAllBytes(f)).accept(cn, 0);
			AnnotationNode mixin = null;
			for (var an : list(cn.invisibleAnnotations)) if (((AnnotationNode) an).desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) mixin = (AnnotationNode) an;
			if (mixin == null) continue;
			List<String> targets = new ArrayList<>();
			for (Object t : list(val(mixin, "value"))) targets.add(((Type) t).getInternalName());
			for (Object t : list(val(mixin, "targets"))) targets.add(((String) t).replace('.', '/'));
			for (String t : targets) {
				ClassNode vt = van.get(t), nt = neo.get(t);
				if (vt == null) { System.out.println("SKIP (not in Minecraft) " + cn.name + " -> " + t); continue; }
				if (nt == null) { System.out.println("MISSING CLASS on NeoForge " + cn.name + " -> " + t); issues++; continue; }
				for (MethodNode m : cn.methods) {
					for (var an0 : list(m.visibleAnnotations).isEmpty() ? list(m.invisibleAnnotations) : concat(m.visibleAnnotations, m.invisibleAnnotations)) {
						var an = (AnnotationNode) an0;
						String type = an.desc.substring(an.desc.lastIndexOf('/') + 1, an.desc.length() - 1);
						if (type.equals("Shadow") || type.equals("Accessor") || type.equals("Invoker") || type.equals("Overwrite")) {
							if (type.equals("Shadow") || type.equals("Overwrite")) {
								String name = m.name.startsWith("emrion$") ? null : m.name;
								if (name != null && find(nt, name, m.desc).isEmpty() && !find(vt, name, m.desc).isEmpty()) { System.out.println("MISSING " + type + " method " + cn.name + "." + name + m.desc); issues++; }
							}
							continue;
						}
						List<?> methods = list(val(an, "method"));
						if (methods.isEmpty()) continue;
						List<AnnotationNode> ats = new ArrayList<>();
						for (Object at : list(val(an, "at"))) ats.add((AnnotationNode) at);
						for (Object ms : methods) {
							String sel = (String) ms;
							var vms = select(vt, sel); var nms = select(nt, sel);
							if (vms.isEmpty()) { System.out.println("WARN selector matches nothing in vanilla " + cn.name + " " + sel); continue; }
							if (nms.size() != vms.size()) { System.out.println("DIFF method count " + cn.name + " " + type + " " + sel + " vanilla=" + vms.size() + " neo=" + nms.size()); issues++; continue; }
							for (int k = 0; k < vms.size(); k++) for (var at : ats) {
								points++;
								String v = (String) val(at, "value"), tgt = (String) val(at, "target");
								Object ord = val(at, "ordinal");
								int cv = count(vms.get(k), v, tgt), cnn = count(nms.get(k), v, tgt);
								if (cv != cnn) { System.out.println("DIFF " + cn.name + " " + type + " " + t + "." + vms.get(k).name + " @At(" + v + (tgt != null ? " " + tgt : "") + (ord != null ? " ordinal=" + ord : "") + ") vanilla=" + cv + " neo=" + cnn); issues++; }
							}
						}
					}
				}
			}
		}
		System.out.println("checked " + points + " injection points, " + issues + " differences");
	}
	static List<Object> concat(List<?> x, List<?> y) { var l = new ArrayList<Object>(list(x)); l.addAll(list(y)); return l; }
	static List<MethodNode> find(ClassNode c, String name, String desc) { return c.methods.stream().filter(m -> m.name.equals(name) && (desc == null || m.desc.equals(desc))).toList(); }
	static List<MethodNode> select(ClassNode c, String sel) {
		String name = sel, desc = null;
		int p = sel.indexOf('(');
		if (p >= 0) { name = sel.substring(0, p); desc = sel.substring(p); }
		if (name.contains(";")) name = name.substring(name.lastIndexOf(';') + 1); // Lowner;name
		if (name.endsWith("*")) name = name.substring(0, name.length() - 1);
		return find(c, name, desc);
	}
	static int count(MethodNode m, String v, String tgt) {
		if (v == null || v.equals("HEAD")) return 1;
		int n = 0;
		for (var insn : m.instructions) {
			int op = insn.getOpcode();
			switch (v) {
				case "RETURN", "TAIL" -> { if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) n++; }
				case "INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING" -> { if (insn instanceof MethodInsnNode mi && matches(tgt, mi.owner, mi.name, mi.desc)) n++; }
				case "FIELD" -> { if (insn instanceof FieldInsnNode fi && matches(tgt, fi.owner, fi.name, ":" + fi.desc)) n++; }
				case "NEW" -> { if (insn instanceof TypeInsnNode ti && op == Opcodes.NEW && tgt != null && (tgt.equals(ti.desc) || tgt.equals("L" + ti.desc + ";") || tgt.contains(ti.desc))) n++; }
				default -> { return -1; } // CONSTANT, JUMP, LOAD, STORE...: not compared
			}
		}
		return n; // TAIL too: an extra return on NeoForge means TAIL no longer runs on every path
	}
	static boolean matches(String tgt, String owner, String name, String desc) {
		if (tgt == null) return true;
		String s = "L" + owner + ";" + name + desc;
		return tgt.equals(s) || tgt.replace(" ", "").equals(s);
	}
}
