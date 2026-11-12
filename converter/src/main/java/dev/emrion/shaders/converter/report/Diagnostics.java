package dev.emrion.shaders.converter.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Thread-safe collector. Stages report here and keep going; nothing in the converter throws for pack errors. */
public final class Diagnostics {
	private final List<Diagnostic> list = Collections.synchronizedList(new ArrayList<>());

	public void add(Diagnostic d) { list.add(d); }

	public void addAll(List<Diagnostic> ds) { list.addAll(ds); }

	public List<Diagnostic> all() { synchronized (list) { return List.copyOf(list); } }

	public boolean hasErrors() {
		synchronized (list) { return list.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR); }
	}
}
