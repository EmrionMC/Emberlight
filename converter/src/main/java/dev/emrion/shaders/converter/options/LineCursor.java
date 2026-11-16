package dev.emrion.shaders.converter.options;

/**
 * Cursor over one source line with Iris's option-line token rules (ParsedString). Its quirks decide what counts as an option,
 * so they are kept.
 */
final class LineCursor {
	private String text;

	LineCursor(String text) {
		this.text = text;
	}

	boolean literal(String token) {
		if (!text.startsWith(token)) return false;
		text = text.substring(token.length());
		return true;
	}

	boolean whitespace() {
		if (text.isEmpty() || !Character.isWhitespace(text.charAt(0))) return false;
		text = text.trim();
		return true;
	}

	/** "//" plus any further slashes. */
	boolean comment() {
		if (!text.startsWith("//")) return false;
		int i = 2;
		while (i < text.length() && text.charAt(i) == '/') i++;
		text = text.substring(i);
		return true;
	}

	boolean contains(String s) {
		return text.contains(s);
	}

	boolean atEnd() {
		return text.isEmpty();
	}

	String rest() {
		return text;
	}

	String word() {
		int p = 0;
		while (p < text.length() && isWordChar(text.charAt(p))) p++;
		return p == 0 ? null : take(p);
	}

	private String number() {
		int p = 0;
		int n = text.length();
		while (p < n) {
			if (p + 1 < n) {
				if (!Character.isDigit(text.charAt(p)) && !Character.isDigit(text.charAt(p + 1))) break;
			} else if (!Character.isDigit(text.charAt(p))) {
				break;
			}
			p++;
		}
		if (p > 0 && p + 1 < n && (text.charAt(p) == 'f' || text.charAt(p) == 'F')) p++;
		try {
			Float.parseFloat(text.substring(0, p));
		} catch (NumberFormatException e) {
			return null;
		}
		return take(p);
	}

	String wordOrNumber() {
		String num = number();
		return num != null ? num : word();
	}

	private String take(int p) {
		String r = text.substring(0, p);
		text = text.substring(p);
		return r;
	}

	static boolean isWordChar(char c) {
		return Character.isDigit(c) || Character.isAlphabetic(c) || c == '_';
	}
}
