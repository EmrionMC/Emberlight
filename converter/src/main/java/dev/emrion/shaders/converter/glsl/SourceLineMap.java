package dev.emrion.shaders.converter.glsl;

import java.util.List;

/** LineMap for preprocessor output: the first {@code hoisted} lines are #version/#extension, line k maps to {@code k - hoisted}. */
public final class SourceLineMap implements LineMap {
	private final IncludeExpander.Expanded src;
	private final List<Integer> hoistedLines;
	private final String prefix;

	/** @param prefix prepended to the expander's absolute paths, e.g. "shaders" */
	public SourceLineMap(IncludeExpander.Expanded src, List<Integer> hoistedLines, String prefix) {
		this.src = src;
		this.hoistedLines = hoistedLines;
		this.prefix = prefix;
	}

	/** Input line of the include-expanded text, 0 if out of range. */
	public int inputLine(int outputLine) {
		int in = outputLine <= hoistedLines.size() ? hoistedLines.get(outputLine - 1) : outputLine - hoistedLines.size();
		return in >= 1 && in <= src.lineCount() ? in : 0;
	}

	@Override
	public String file(int outputLine) {
		int in = outputLine >= 1 ? inputLine(outputLine) : 0;
		return in == 0 ? null : prefix + src.files().get(src.fileOfLine()[in - 1]);
	}

	@Override
	public int line(int outputLine) {
		int in = outputLine >= 1 ? inputLine(outputLine) : 0;
		return in == 0 ? 0 : src.lineOfLine()[in - 1];
	}

	/** Maps a line of the include-expanded input (e.g. from a preprocessor warning) to its file. */
	public String fileOfInput(int inputLine) {
		return inputLine >= 1 && inputLine <= src.lineCount() ? prefix + src.files().get(src.fileOfLine()[inputLine - 1]) : null;
	}

	public int lineOfInput(int inputLine) {
		return inputLine >= 1 && inputLine <= src.lineCount() ? src.lineOfLine()[inputLine - 1] : 0;
	}
}
