package app.so7o.ztrackpad;

/**
 * Plain asserts, no junit. Exit non-zero on the first failure, print PASS at the end.
 */
public final class CleanerTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        eq("plain text untouched", "hello world", Cleaner.clean("hello world"));
        eq("idempotent", Cleaner.clean("a  b\n\nc\r\nd\u200be"), Cleaner.clean(Cleaner.clean("a  b\n\nc\r\nd\u200be")));

        // ANSI
        eq("csi colour", "hello", Cleaner.clean("\u001b[32mhello\u001b[0m"));
        eq("csi erase", "a b", Cleaner.clean("a\u001b[0K b"));
        eq("osc title", "text", Cleaner.clean("\u001b]0;my title\u0007text"));
        eq("osc st", "text", Cleaner.clean("\u001b]0;my title\u001b\\text"));
        eq("two-char esc", "ab", Cleaner.clean("a\u001b7b"));   // ESC 7 = save-cursor, both chars gone
        eq("unterminated csi", "abc", Cleaner.clean("abc\u001b[32"));

        // control chars
        // \r\n -> \n; a lone \r (terminal redraw padding) is dropped, so b and c join:
        eq("crlf -> lf, lone cr dropped", "a\nbc", Cleaner.clean("a\r\nb\rc"));
        eq("lone cr dropped", "a\nb", Cleaner.clean("a\nb"));
        eq("bel dropped", "ab", Cleaner.clean("a\u0007b"));
        eq("tab kept", "a\tb", Cleaner.clean("a\tb"));

        // invisibles
        eq("zero width", "hello", Cleaner.clean("he\u200bll\uFEFFo"));
        eq("bidi marks", "a b", Cleaner.clean("a\u202E b"));
        eq("soft hyphen", "abstract", Cleaner.clean("ab\u00ADstract"));

        // box debris (the herdr pane borders)
        eq("border-only line removed", "x\ny", Cleaner.clean("x\n┌────┐\ny"));
        eq("box chars cut in line", "foo bar", Cleaner.clean("│ foo │ bar"));
        eq("braille/blocks line removed", "keep", Cleaner.clean("▓▓▒▒\nkeep"));

        // spaces
        eq("nbsp", "a b", Cleaner.clean("a\u00A0b"));
        eq("ideographic space", "a b", Cleaner.clean("a\u3000b"));
        eq("internal collapse", "a b c", Cleaner.clean("a    b   c"));
        eq("indent kept", "    code()", Cleaner.clean("    code()"));
        eq("indent kept, trailing trimmed", "  a\n  b", Cleaner.clean("  a  \n  b  "));
        eq("trailing trimmed", "a b", Cleaner.clean("a b   "));

        // blank runs
        eq("blank collapse", "a\n\nb", Cleaner.clean("a\n\n\n\nb"));
        eq("outer trim", "  a", Cleaner.clean("\n\n  a \n\n"));   // leading spaces are indent: kept

        // the whole thing: a herdr-ish pane paste
        String herdr = "\u001b[2J\u001b[H"
            + "┌─ herdr ─ pop ─────────────────┐\n"
            + "│ $ ls -la  \u001b[0m\r                 │\n"
            + "│ total 0\u001b[0K                     │\n"
            + "└───────────────────────────────┘\n";
        eq("herdr pane paste", "$ ls -la\ntotal 0", Cleaner.clean(herdr));

        // the herdr paste specimen the user supplied live (zellij-style gutters + hard wrap)
        String specimen = "Verified on device just now: junk clip in (145 chars of escapes +\n"
            + " 4\u25cb\u2502 borders) \u2192 op clean \u2192 clipboard held exactly $ adb logcat -s\n"
            + "\u25be3\u25cf\u2502 ClipClean\\ntotal 0; screenshot in docs/bubble-first-test.png. Commits\n"
            + " 1\u25cb\u2502 ff1489b, 6655abf.";
        eq("gutter only (unwrap off)",
           "Verified on device just now: junk clip in (145 chars of escapes +\n"
           + "borders) \u2192 op clean \u2192 clipboard held exactly $ adb logcat -s\n"
           + "ClipClean\\ntotal 0; screenshot in docs/bubble-first-test.png. Commits\n"
           + "ff1489b, 6655abf.",
           Cleaner.clean(specimen));
        eq("gutter + unwrap", "Verified on device just now: junk clip in (145 chars of escapes + borders) "
           + "\u2192 op clean \u2192 clipboard held exactly $ adb logcat -s ClipClean\\ntotal 0; "
           + "screenshot in docs/bubble-first-test.png. Commits ff1489b, 6655abf.",
           Cleaner.clean(specimen, true));

        // BARE-DIGIT gutter variant (same pane, gutter columns cut: " 4 borders") is
        // deliberately NOT stripped - a bare leading number is real content too often.
        // Documented limitation, pinned here so a future change is a decision:
        eq("bare-digit gutter stays", "escapes +\n 4 borders", Cleaner.clean("escapes +\n 4 borders"));

        // herdr's LETTER counters: "z✓ │" / "L○ │" - a letter with a check or a circle after
        // it. The bar is optional there, because the letter+marker is the discriminator and
        // the bar is the first thing a copy drops. The digit rule cannot see these at all.
        eq("letter+check gutter", "it's the only way",
           Cleaner.clean("z\u2713 \u2502 it's the only way"));
        eq("letter+circle gutter", "op=clip takes effect",
           Cleaner.clean("L\u25cb \u2502 op=clip takes effect"));
        eq("letter gutter without the bar", "startup.", Cleaner.clean("L\u25cb startup."));
        eq("letter gutter after a scroll marker", "text", Cleaner.clean("\u25bez\u2713\u2502 text"));
        // 4+ letters is a word, not a counter: the rule is capped at three. (A check is
        // used, not a circle, because a circle is box debris and stripBox removes it anyway.)
        eq("a word before a check stays", "This\u2713 stays", Cleaner.clean("This\u2713 stays"));

        // A second pane's gutter lands mid-line when the copy spanned two panes side by
        // side. The real copy had a 47-space run before the counter; two is the threshold.
        eq("second pane's gutter mid-line", "a) - b",
           Cleaner.clean("a)          4\u25cb\u2502 - b"));
        // A lone inline bar (no counter) is ordinary box debris: cut, and the gap collapses.
        eq("an inline bar with no counter is debris", "a) b",
           Cleaner.clean("a)          \u2502 b"));

        // A line whose box chars are one leading run is a rule with content after it, not
        // chrome - the content survives. A title bar interleaves box chars with words, so
        // it is still dropped.
        eq("leading rule keeps its content", "Two notes: keep me",
           Cleaner.clean("\u2500\u2500\u2500\u2502 Two notes: keep me"));
        eq("title bar still goes", "keep",
           Cleaner.clean("\u250c\u2500 herdr \u2500 pop \u2500\u2500\u2510\nkeep"));

        // unwrap guards
        eq("unwrap keeps lists", "- a\n- b", Cleaner.clean("- a\n- b", true));
        eq("unwrap keeps indented code", "foo(arg1,\n    arg2);", Cleaner.clean("foo(arg1,\n    arg2);", true));
        eq("unwrap keeps sentences", "End. New", Cleaner.clean("End. New", true));
        eq("unwrap no-space after hyphen", "state-machine", Cleaner.clean("state-\nmachine", true));

        System.out.println((failed == 0 ? "PASS " : "FAIL ") + passed + " checks, " + failed + " failures");
        if (failed > 0) System.exit(1);
    }

    private static void eq(String name, String want, String got) {
        if (want.equals(got)) { passed++; return; }
        failed++;
        System.out.println("FAIL: " + name);
        System.out.println("  want: " + show(want));
        System.out.println("  got:  " + show(got));
    }

    private static String show(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
            .replace("\r", "\\r").replace("\u001b", "\\e") + "\"";
    }
}
