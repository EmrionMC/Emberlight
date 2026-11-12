package dev.emrion.shaders.converter.glsl;

/** Maps a 1-based line of preprocessed output back to (pack file, 1-based line). */
public interface LineMap {
	String file(int outputLine);

	int line(int outputLine);

	LineMap IDENTITY_UNKNOWN = new LineMap() {
		public String file(int outputLine) { return null; }
		public int line(int outputLine) { return outputLine; }
	};
}
