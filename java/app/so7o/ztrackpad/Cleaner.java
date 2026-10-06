package app.so7o.ztrackpad;

/**
 * The text transform. PURE JAVA on purpose - no android.* imports - so tests/cleaner.sh
 * can compile and run it with plain javac/java from Termux, without the platform jar.
 *
 * What it removes: the junk a copy out of a terminal session (herdr over SSH) picks up -
 * ANSI escape sequences, box-drawing pane borders, control characters, zero-width and
 * bidi marks, exotic spaces, lone \r padding - then normalises whitespace without
 * destroying code indentation.
 *
 * Every rule is idempotent: clean(clean(x)) == clean(x). The service relies on that.
 */
public final class Cleaner {

    private Cleaner() {}

    /** One-arg entry: unwrap defaults off. */
    public static String clean(String raw) {
        return clean(raw, false);
    }

    /** Two-arg entry: unwrap is opt-in (it can join column-0 code lines; see unwrap).
     *  Pipeline, in order. Each step is a visible, testable rule. */
    public static String clean(String raw, boolean unwrap) {
        if (raw == null || raw.length() == 0) return "";
        String s = raw;
        s = stripAnsi(s);          // 1. escape sequences (CSI, OSC, 2-char, 7-bit leftovers)
        s = stripControl(s);       // 2. control chars except \n (incl. \r, handled in 5)
        s = stripInvisible(s);     // 3. zero-width, bidi, BOM, soft hyphen
        s = stripGutter(s);        // 4. zellij-style line gutters FIRST - the box rules
        s = stripBox(s);           //    would cut the gutter's ○│/▾●│ marks and the ≥3
                                   //    box-chars chrome rule would eat whole content
                                   //    lines, before the gutter pattern could see them
        s = normaliseSpaces(s);    // 5. exotic spaces -> plain space; \r\n and lone \r -> \n
        s = tidyLines(s);          // 6. trailing-space trim, internal space collapse,
                                   //    3+ blank lines -> 1, outer trim
        if (unwrap) s = unwrap(s); // 7. opt-in: join terminal-wrapped prose back into lines
        return s;
    }

    // ------------------------------------------------------------------ 1. ANSI

    /** CSI: ESC [ ... final byte in @-~. OSC: ESC ] ... BEL or ESC \. Plus 2-char ESC x. */
    public static String stripAnsi(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        for (int i = 0; i < n; ) {
            char c = s.charAt(i);
            if (c == 0x1b && i + 1 < n) {
                char c1 = s.charAt(i + 1);
                if (c1 == '[') {                       // CSI: params 0x30-0x3f, intermed 0x20-0x2f, final 0x40-0x7e
                    int j = i + 2;
                    while (j < n && (s.charAt(j) < 0x40 || s.charAt(j) > 0x7e)) j++;
                    i = (j < n) ? j + 1 : n;           // unterminated at end: drop the rest
                    continue;
                }
                if (c1 == ']') {                       // OSC: up to BEL or ESC \
                    int j = i + 2;
                    while (j < n && s.charAt(j) != 0x07 && !(s.charAt(j) == 0x1b && j + 1 < n && s.charAt(j + 1) == '\\')) j++;
                    i = (j < n && s.charAt(j) == 0x07) ? j + 1 : (j < n) ? j + 2 : n;
                    continue;
                }
                i += 2;                                // 2-char escape: ESC 7, ESC ( B, ESC M ...
                continue;
            }
            // 7-bit leftovers that survive some copy paths: "ESC" rendered as ^[ lost its ESC
            out.append(c);
            i++;
        }
        return out.toString();
    }

    // ------------------------------------------------------------ 2. control chars

    /** Keep \n. Drop \r here only as a stray char (pairs were normalised... they are NOT
     *  normalised yet at this point - \r\n must survive to step 5 - so this drops a \r
     *  only when it is NOT followed by \n. A lone \r is terminal cursor-return padding. */
    public static String stripControl(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\n') { out.append(c); continue; }
            if (c == '\r') {                           // \r\n survives; lone \r dies
                if (i + 1 < n && s.charAt(i + 1) == '\n') out.append(c);
                continue;
            }
            if ((c < 0x20 && c != 0x09) || c == 0x7f) continue;   // BEL, BS, VT, FF... but keep TAB
            out.append(c);                             // tabs survive: code pastes need them
        }
        return out.toString();
    }

    // ------------------------------------------------------------- 3. invisibles

    /** Zero-width joiners/separators, direction marks, BOM, word-joiner, soft hyphen. */
    public static String stripInvisible(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            boolean drop =
                (c >= 0x200b && c <= 0x200f)   // ZWSP, ZWNJ, ZWJ, LRM, RLM
                || c == 0x2060 || c == 0x2061 || c == 0x2062 || c == 0x2063  // word joiner + invisible ops
                || c == 0xfeff                 // BOM / zero-width no-break space
                || c == 0x00ad                 // soft hyphen
                || (c >= 0x202a && c <= 0x202e)   // bidi embedding/overrides
                || (c >= 0x2066 && c <= 0x2069);  // bidi isolates
            if (!drop) out.append(c);
        }
        return out.toString();
    }

    // ------------------------------------------------------------- 4. box debris

    private static boolean boxChar(char c) {
        return (c >= 0x2500 && c <= 0x257f)   // box drawing
            || (c >= 0x2580 && c <= 0x259f)   // block elements
            || (c >= 0x2596 && c <= 0x25ff);  // (covered above) geometric debris incl. shades
    }

    /**
     * True when every box char in the line sits in one run at its start - i.e. the line is a
     * rule with content after it, not a title bar with words among the box chars.
     */
    private static boolean leadingBoxRun(String line) {
        int i = 0;
        while (i < line.length() && boxChar(line.charAt(i))) i++;
        if (i == 0) return false;
        for (int j = i; j < line.length(); j++) {
            if (boxChar(line.charAt(j))) return false;
        }
        return true;
    }

    private static boolean junkOnly(String line) {
        if (line.isEmpty()) return false;   // an empty line is not box junk; stripBox
                                            // used to eat every blank line through here
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (boxChar(c)) continue;
            if (c == ' ' || c == '\t') continue;
            return false;
        }
        return true;
    }

    /** Border-only lines vanish; pane title bars (>= 3 box chars, e.g. "┌─ herdr ─ pop ─┐")
     *  vanish; content lines keep their text but lose the box chars.
     *
     *  The one exception is a line whose box chars are a single LEADING run: that is a
     *  horizontal rule with content after it ("───│ Two notes: ..."), not chrome, so the
     *  rule goes and the text stays. A title bar interleaves its box chars with words, which
     *  is what tells the two apart. */
    public static String stripBox(String s) {
        String[] lines = s.split("\n", -1);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.isEmpty() && junkOnly(line)) continue;      // a border
            int box = 0;
            for (int j = 0; j < line.length(); j++) if (boxChar(line.charAt(j))) box++;
            if (box >= 3 && !leadingBoxRun(line)) continue;       // a title bar: chrome
            if (out.length() > 0) out.append('\n');
            StringBuilder cut = new StringBuilder(line.length());
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (!boxChar(c)) cut.append(c);
            }
            // a leading box char is a border, not indentation: strip the debris it leaves
            int k = (!line.isEmpty() && boxChar(line.charAt(0))) ? 0 : -1;
            if (k >= 0) {
                while (k < cut.length() && cut.charAt(k) == ' ') k++;
                if (k >= 0) cut.delete(0, Math.min(k, cut.length()));
            }
            out.append(cut);
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- gutter

    /** Zellij and herdr draw a gutter over the leftmost columns of every display row: a
     *  scroll marker, a row counter, a fill/open circle, then the pane border. herdr counts
     *  with LETTERS as well as digits - "z✓ │", "L○ │", a letter with a check or a circle
     *  after it - which is what a copy out of one of its panes leaves for the rows its
     *  counter labels rather than numbers.
     *
     *  When the wrapped line ran under the gutter its first word is gone for good - the
     *  gutter replaced it on screen, so no rule can bring it back. Everything else
     *  survives: " 4○│ borders)" -> "borders)". */
    public static String stripGutter(String s) {
        // leading gutters first (they are anchored), then the second pane's gutter, which
        // lands mid-line when the copy spanned two panes side by side
        return GUTTER_INLINE.matcher(GUTTER.matcher(s).replaceAll("")).replaceAll(" ");
    }

    // Two leading shapes, because herdr counts with both:
    //   <marker?> <digits?> <circle?> │   - the digit gutter; the bar is REQUIRED, because a
    //                                       bare leading number is real content too often
    //   <marker?> <letters> <circle|check> - the letter gutter; the bar is OPTIONAL, because a
    //                                       letter glued to ○/●/✓ is not prose and the bar is
    //                                       the first thing a copy drops
    // Content matching neither is untouched, so code indentation can never be eaten here.
    private static final java.util.regex.Pattern GUTTER = java.util.regex.Pattern.compile(
        "^[ \\t]*(?:[\\u25be\\u25b8]\\s*)?(?:\\d+\\s*)?[\\u25cb\\u25cf]?\\s*\\u2502\\s*"
            + "|^[ \\t]*(?:[\\u25be\\u25b8]\\s*)?[A-Za-z]{1,3}\\s*"
            + "[\\u25cb\\u25cf\\u25d0\\u25d1\\u2713\\u2714]\\s*(?:\\u2502\\s*)?",
        java.util.regex.Pattern.MULTILINE);

    // A second pane's gutter lands mid-line when the copy spanned two panes side by side:
    // "... suite).            4○│ - End-to-end: ...". Two or more spaces, a circle and the
    // pane bar is unmistakable, so it goes - replaced with one space so the two halves stay
    // separate words. The circle is required, or a markdown table's "a   │ b" would be joined.
    private static final java.util.regex.Pattern GUTTER_INLINE = java.util.regex.Pattern.compile(
        "[ \\t]{2,}(?:\\d+\\s*)?[\\u25cb\\u25cf]\\s*\\u2502\\s*");

    // ---------------------------------------------------------------- unwrap

    /** Join lines a terminal wrapped mid-sentence. Guards, because a join is
     *  destructive when the text is code:
     *  - never join onto/from a blank line (paragraph break);
     *  - never join a line that starts indented (indent = code signal) or looks like a
     *    list item / heading / quote / code fence;
     *  - never join after sentence-final punctuation (. ! ? …).
     *  So prose joins; "a = b" + "c = d" at column 0 still joins - that is why this is
     *  opt-in, toggled by long-pressing the circle or op=unwrap. */
    public static String unwrap(String s) {
        String[] lines = s.split("\n", -1);
        StringBuilder out = new StringBuilder(s.length());
        String pending = null;
        boolean pendingBlank = false;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i];
            boolean blank = t.trim().isEmpty();
            boolean indented = !t.isEmpty() && (t.charAt(0) == ' ' || t.charAt(0) == '\t');
            boolean newBlock = !blank && (indented || LIST.matcher(t).find());
            if (pending != null && !blank && !newBlock && joinsAfter(pending)) {
                char last = pending.charAt(pending.length() - 1);
                pending = pending + (last == '-' || last == '/' ? "" : " ") + t;
                continue;
            }
            if (pending != null) {
                if (out.length() > 0) out.append('\n');
                out.append(pending);
            } else if (pendingBlank) {
                if (out.length() > 0) out.append('\n');   // emit the blank paragraph break
            }
            pending = blank ? null : t;
            pendingBlank = blank;
        }
        if (pending != null) {
            if (out.length() > 0) out.append('\n');
            out.append(pending);
        } else if (pendingBlank && out.length() > 0) {
            out.append('\n');
        }
        return out.toString();
    }

    private static boolean joinsAfter(String pending) {
        if (pending.isEmpty()) return false;
        char last = pending.charAt(pending.length() - 1);
        return last != '.' && last != '!' && last != '?' && last != '\u2026';
    }

    private static final java.util.regex.Pattern LIST = java.util.regex.Pattern.compile(
        "^\\s*(?:[-*+\\u2022]\\s|\\d{1,3}[.)]\\s|#{1,6}\\s|>\\s|`{3})");

    // --------------------------------------------------------- 5. spaces & newlines

    public static String normaliseSpaces(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == 0x00a0 || c == 0x2007 || c == 0x202f || c == 0x3000) c = ' ';
            if (c == '\r') {                       // the step-2 survivors are \r\n pairs:
                out.append('\n');                  // emit one \n and consume the \n
                if (i + 1 < n && s.charAt(i + 1) == '\n') i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ 6. tidy

    /** Per line: trim trailing spaces, collapse 2+ internal spaces to one, but NEVER touch
     *  leading indentation - code pastes have to keep it. Blank-run collapse: consecutive
     *  blank lines become one. Outer trim of the surrounding newlines. */
    public static String tidyLines(String s) {
        String[] lines = s.split("\n", -1);
        StringBuilder out = new StringBuilder(s.length());
        boolean prevBlank = false, first = true;
        for (int i = 0; i < lines.length; i++) {
            String t = collapseInternal(lines[i]);
            int end = t.length();
            while (end > 0 && t.charAt(end - 1) == ' ') end--;
            t = t.substring(0, end);
            boolean blank = t.length() == 0;
            if (blank && prevBlank) continue;
            if (out.length() > 0) out.append('\n');
            out.append(t);
            prevBlank = blank;
            first = false;
        }
        int start = 0, end = out.length();
        while (start < end && out.charAt(start) == '\n') start++;
        while (end > start && out.charAt(end - 1) == '\n') end--;
        return out.substring(start, end);
    }

    private static String collapseInternal(String line) {
        int first = 0;
        while (first < line.length() && line.charAt(first) == ' ') first++;   // keep indent
        StringBuilder out = new StringBuilder(line.length());
        out.append(line, 0, first);
        boolean lastSpace = false;
        for (int i = first; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                if (lastSpace) continue;
                lastSpace = true;
            } else {
                lastSpace = false;
            }
            out.append(c);
        }
        return out.toString();
    }
}
