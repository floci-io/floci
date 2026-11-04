package io.github.hectorvent.floci.services.redshift.spectrum;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tokenizes SQL for Spectrum analysis (reference scanning and partition pruning).
 *
 * <p>Handles comments, quoted identifiers, string literals, and dollar-quoted blocks.
 */
final class SpectrumSqlLexer {

    enum Kind {
        WORD,
        QUOTED_WORD,
        STRING,
        NUMBER,
        DOLLAR_QUOTED,
        LPAREN,
        RPAREN,
        COMMA,
        SEMICOLON,
        OP,
        OTHER
    }

    record Token(Kind kind, String text, boolean quoted) {
        Token(Kind kind, String text) {
            this(kind, text, kind == Kind.QUOTED_WORD || kind == Kind.STRING);
        }

        boolean isWord(String word) {
            return kind == Kind.WORD && text.equals(word);
        }
    }

    record Result(List<Token> tokens, boolean unsafe) {
    }

    private SpectrumSqlLexer() {
    }

    static Result tokenize(String sql) {
        if (sql == null || sql.isEmpty()) {
            return new Result(List.of(), false);
        }
        List<Token> tokens = new ArrayList<>();
        boolean unsafe = false;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int newline = sql.indexOf('\n', i + 2);
                i = newline < 0 ? sql.length() : newline + 1;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int depth = 1;
                i += 2;
                while (i < sql.length() && depth > 0) {
                    if (sql.startsWith("/*", i)) {
                        depth++;
                        i += 2;
                    } else if (sql.startsWith("*/", i)) {
                        depth--;
                        i += 2;
                    } else {
                        i++;
                    }
                }
                if (depth > 0) {
                    unsafe = true;
                }
            } else if (c == '$' && dollarTagEnd(sql, i) > 0) {
                int start = i;
                int bodyStart = dollarTagEnd(sql, i);
                String tag = sql.substring(start, bodyStart);
                int close = sql.indexOf(tag, bodyStart);
                if (close < 0) {
                    unsafe = true;
                    i = sql.length();
                } else {
                    int end = close + tag.length();
                    tokens.add(new Token(Kind.DOLLAR_QUOTED, sql.substring(start, end)));
                    unsafe = true;
                    i = end;
                }
            } else if (c == '"') {
                StringBuilder value = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < sql.length()) {
                    char ch = sql.charAt(i);
                    if (ch == '"') {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == '"') {
                            value.append('"');
                            i += 2;
                            continue;
                        }
                        i++;
                        closed = true;
                        break;
                    }
                    value.append(ch);
                    i++;
                }
                if (!closed) {
                    unsafe = true;
                }
                tokens.add(new Token(Kind.QUOTED_WORD, value.toString()));
            } else if (c == '\'') {
                boolean backslashEscapes = isEscapeStringPrefix(sql, i);
                StringBuilder value = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < sql.length()) {
                    char ch = sql.charAt(i);
                    if (backslashEscapes && ch == '\\' && i + 1 < sql.length()) {
                        value.append(sql.charAt(i + 1));
                        i += 2;
                    } else if (ch == '\'') {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                            value.append('\'');
                            i += 2;
                            continue;
                        }
                        i++;
                        closed = true;
                        break;
                    } else {
                        value.append(ch);
                        i++;
                    }
                }
                if (!closed) {
                    unsafe = true;
                }
                tokens.add(new Token(Kind.STRING, value.toString()));
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_'
                        || sql.charAt(i) == '$')) {
                    i++;
                }
                String word = sql.substring(start, i);
                if (i < sql.length() && (sql.charAt(i) == '\'' || sql.charAt(i) == '&' && i + 1 < sql.length()
                        && sql.charAt(i + 1) == '\'')) {
                    unsafe = true;
                }
                tokens.add(new Token(Kind.WORD, word.toLowerCase(Locale.ROOT)));
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < sql.length() && Character.isDigit(sql.charAt(i))) {
                    i++;
                }
                if (i + 1 < sql.length() && sql.charAt(i) == '.' && Character.isDigit(sql.charAt(i + 1))) {
                    i++;
                    while (i < sql.length() && Character.isDigit(sql.charAt(i))) {
                        i++;
                    }
                }
                tokens.add(new Token(Kind.NUMBER, sql.substring(start, i)));
            } else if (c == '$') {
                int start = i++;
                while (i < sql.length() && Character.isDigit(sql.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(Kind.OTHER, sql.substring(start, i)));
            } else if (c == '(') {
                tokens.add(new Token(Kind.LPAREN, "("));
                i++;
            } else if (c == ')') {
                tokens.add(new Token(Kind.RPAREN, ")"));
                i++;
            } else if (c == ',') {
                tokens.add(new Token(Kind.COMMA, ","));
                i++;
            } else if (c == ';') {
                tokens.add(new Token(Kind.SEMICOLON, ";"));
                i++;
            } else {
                String two = i + 1 < sql.length() ? sql.substring(i, i + 2) : "";
                if (two.equals("<=") || two.equals(">=") || two.equals("<>") || two.equals("!=") || two.equals("::")) {
                    tokens.add(new Token(two.equals("::") ? Kind.OTHER : Kind.OP, two));
                    i += 2;
                } else if ("=<>.+-".indexOf(c) >= 0) {
                    tokens.add(new Token(Kind.OP, String.valueOf(c)));
                    i++;
                } else {
                    tokens.add(new Token(Kind.OTHER, String.valueOf(c)));
                    i++;
                }
            }
        }
        return new Result(tokens, unsafe);
    }

    private static int dollarTagEnd(String sql, int start) {
        int i = start + 1;
        if (i < sql.length() && (Character.isLetter(sql.charAt(i)) || sql.charAt(i) == '_')) {
            while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
                i++;
            }
        }
        return i < sql.length() && sql.charAt(i) == '$' ? i + 1 : -1;
    }

    private static boolean isEscapeStringPrefix(String sql, int quote) {
        if (quote == 0 || Character.toLowerCase(sql.charAt(quote - 1)) != 'e') {
            return false;
        }
        if (quote == 1) {
            return true;
        }
        char before = sql.charAt(quote - 2);
        return !(Character.isLetterOrDigit(before) || before == '_' || before == '$');
    }
}
