package com.testing.aproxy.deps;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * An AutoSys starting condition ({@code condition:} of a job), e.g.
 * {@code s(LOAD,03.00) & (f(FEED) | e(CHECK) > 1) & v(MODE) = "FULL"}.
 * <p>
 * Atoms: {@code s/success}, {@code f/failure}, {@code d/done}, {@code t/terminated}, {@code n/notrunning},
 * {@code e/exitcode} (with a comparison) and {@code v/value} (a global variable, with a comparison). A job may be
 * {@code JOB^INS} (another AutoSys instance) and have a look-back ({@code ,HH.MM}). Operators {@code & | and or},
 * with parentheses; AND binds tighter than OR.
 */
public sealed interface StartCondition {

    enum Type { SUCCESS, FAILURE, DONE, TERMINATED, NOTRUNNING, EXITCODE, VALUE }

    /**
     * @param name     job (or, for {@link Type#VALUE}, global variable) name
     * @param instance the other AutoSys instance of {@code JOB^INS}, else null
     * @param lookBack look-back as written ({@code 03.00}, {@code 0}), else null
     * @param operator comparison of {@code e()} / {@code v()} ({@code = != < > <= >=}), else null
     * @param text     the atom as written
     */
    record Atom(Type type, String name, @Nullable String instance, @Nullable String lookBack,
            @Nullable String operator, @Nullable String value, String text) implements StartCondition {
    }

    record All(List<StartCondition> parts) implements StartCondition {
    }

    record Any(List<StartCondition> parts) implements StartCondition {
    }

    /** @throws IllegalArgumentException when the text is not a condition this parser understands */
    static StartCondition parse(String text) {
        Parser parser = new Parser(text);
        StartCondition condition = parser.or();
        parser.skipSpaces();
        if (!parser.atEnd()) {
            throw parser.error("unexpected '" + parser.rest() + "'");
        }
        return condition;
    }

    final class Parser {

        private static final Map<String, Type> TYPES = Map.ofEntries(Map.entry("s", Type.SUCCESS),
                Map.entry("success", Type.SUCCESS), Map.entry("f", Type.FAILURE), Map.entry("failure", Type.FAILURE),
                Map.entry("d", Type.DONE), Map.entry("done", Type.DONE), Map.entry("t", Type.TERMINATED),
                Map.entry("terminated", Type.TERMINATED), Map.entry("n", Type.NOTRUNNING),
                Map.entry("notrunning", Type.NOTRUNNING), Map.entry("e", Type.EXITCODE),
                Map.entry("exitcode", Type.EXITCODE), Map.entry("v", Type.VALUE), Map.entry("value", Type.VALUE));

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        StartCondition or() {
            List<StartCondition> parts = new ArrayList<>(List.of(and()));
            while (operator('|', "or")) {
                parts.add(and());
            }
            return parts.size() == 1 ? parts.getFirst() : new Any(List.copyOf(parts));
        }

        private StartCondition and() {
            List<StartCondition> parts = new ArrayList<>(List.of(primary()));
            while (operator('&', "and")) {
                parts.add(primary());
            }
            return parts.size() == 1 ? parts.getFirst() : new All(List.copyOf(parts));
        }

        private StartCondition primary() {
            skipSpaces();
            if (peek() == '(') {
                pos++;
                StartCondition inner = or();
                skipSpaces();
                expect(')');
                return inner;
            }
            int start = pos;
            String word = word();
            Type type = TYPES.get(word.toLowerCase(Locale.ROOT));
            skipSpaces();
            if (type == null || peek() != '(') {
                throw error("expected s(), f(), d(), t(), n(), e() or v() at '" + text.substring(start) + "'");
            }
            pos++;
            int close = text.indexOf(')', pos);
            if (close < 0) {
                throw error("missing ')'");
            }
            String[] args = text.substring(pos, close).split(",", 2);
            pos = close + 1;
            String name = args[0].strip();
            String instance = null;
            int caret = name.indexOf('^');
            if (caret >= 0) {
                instance = name.substring(caret + 1).strip();
                name = name.substring(0, caret).strip();
            }
            if (name.isEmpty()) {
                throw error("no job name in " + text.substring(start, pos));
            }
            String lookBack = args.length > 1 && !args[1].isBlank() ? args[1].strip() : null;
            String operator = null;
            String value = null;
            if (type == Type.EXITCODE || type == Type.VALUE) {
                skipSpaces();
                operator = comparison();
                if (operator == null) {
                    throw error("expected a comparison after " + text.substring(start, pos));
                }
                skipSpaces();
                value = value();
            }
            return new Atom(type, name, instance, lookBack, operator, value, text.substring(start, pos).strip());
        }

        private boolean operator(char symbol, String keyword) {
            skipSpaces();
            if (peek() == symbol) {
                pos++;
                return true;
            }
            int end = pos + keyword.length();
            if (text.regionMatches(true, pos, keyword, 0, keyword.length())
                    && (end >= text.length() || !Character.isLetterOrDigit(text.charAt(end)))) {
                pos = end;
                return true;
            }
            return false;
        }

        private @Nullable String comparison() {
            for (String op : List.of("!=", "<>", "<=", ">=", "==", "=", "<", ">")) {
                if (text.startsWith(op, pos)) {
                    pos += op.length();
                    return op.equals("<>") ? "!=" : op.equals("==") ? "=" : op;
                }
            }
            return null;
        }

        private String value() {
            if (peek() == '"' || peek() == '\'') {
                char quote = text.charAt(pos++);
                int end = text.indexOf(quote, pos);
                if (end < 0) {
                    throw error("missing closing quote");
                }
                String value = text.substring(pos, end);
                pos = end + 1;
                return value;
            }
            int start = pos;
            while (!atEnd() && !Character.isWhitespace(peek()) && peek() != ')' && peek() != '&' && peek() != '|') {
                pos++;
            }
            if (start == pos) {
                throw error("expected a value");
            }
            return text.substring(start, pos);
        }

        private String word() {
            int start = pos;
            while (!atEnd() && Character.isLetter(peek())) {
                pos++;
            }
            return text.substring(start, pos);
        }

        private void expect(char c) {
            if (peek() != c) {
                throw error("expected '" + c + "'");
            }
            pos++;
        }

        void skipSpaces() {
            while (!atEnd() && Character.isWhitespace(peek())) {
                pos++;
            }
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        String rest() {
            return text.substring(pos);
        }

        private char peek() {
            return atEnd() ? '\0' : text.charAt(pos);
        }

        IllegalArgumentException error(String problem) {
            return new IllegalArgumentException("Cannot read the condition \"" + text + "\": " + problem);
        }
    }
}
