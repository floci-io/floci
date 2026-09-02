package io.github.hectorvent.floci.services.redshift.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites Redshift-specific table DDL so a plain PostgreSQL backend accepts it.
 *
 * <p>The rewrite is deliberately narrow so the "fail-open" contract stays honest:
 * we must never silently drop a column or clause from a statement we do not
 * fully understand:
 * <ul>
 *   <li>It only runs when the statement's first keyword is {@code CREATE TABLE}
 *       or {@code ALTER TABLE}. A {@code SELECT}, an {@code INSERT}, a function
 *       body or a string literal that merely contains {@code DISTKEY} is
 *       returned untouched.</li>
 *   <li>Single-quoted string literals are masked out before any pattern runs, so
 *       a {@code DEFAULT 'a,)'} or {@code CHECK (x <> 'SORTKEY')} is never
 *       mangled, including the second statement of a multi-statement {@code 'Q'}
 *       whose batch begins with a {@code CREATE TABLE}.</li>
 *   <li>Table-level {@code DISTKEY}/{@code SORTKEY} without parentheses is only
 *       stripped after the column-list's closing {@code )}, not when it looks
 *       like a column definition.</li>
 *   <li>{@code ENCODE} is only stripped when followed by a real Redshift column
 *       encoding (or {@code AUTO}), so a column literally named {@code encode} of
 *       an ordinary type survives.</li>
 * </ul>
 * String-literal masking is <em>not</em> comment-aware and does not recognise
 * dollar-quoting ({@code $$ ... $$}) or escape strings ({@code E'...'}); an
 * apostrophe inside a {@code --} or block comment can therefore make the rewrite
 * skip a real clause. Every such case fails safe: the unrewritten statement
 * reaches PostgreSQL, which returns its own syntax error. The residual ambiguity
 * (a column named {@code distkey}/{@code sortkey}/{@code encode} whose declared
 * type is itself the bare keyword that would follow such a constraint)
 * describes no valid PostgreSQL type, so in practice nothing legitimate is
 * dropped.
 */
public final class RedshiftSqlInterceptor {

    /** Leading whitespace / SQL comments, then CREATE|ALTER ... TABLE. */
    private static final Pattern TABLE_DDL_PROBE = Pattern.compile(
            "(?is)^\\s*(?:(?:--[^\\n]*\\n)|(?:/\\*.*?\\*/)|\\s)*(?:CREATE|ALTER)\\s+(?:(?:GLOBAL|LOCAL)\\s+)?"
                    + "(?:(?:TEMP|TEMPORARY|UNLOGGED)\\s+)?TABLE\\b");

    /** Single-quoted string literal, PostgreSQL-style (doubled {@code ''} is an escaped quote). */
    private static final Pattern STRING_LITERAL_PATTERN = Pattern.compile("'(?:[^']|'')*'");

    private static final Pattern DISTSTYLE_PATTERN = Pattern.compile("(?i)\\bDISTSTYLE\\s+(ALL|EVEN|KEY|AUTO)\\b");
    private static final Pattern DISTKEY_PAREN_PATTERN = Pattern.compile("(?i)\\bDISTKEY\\s*\\([^)]*\\)");
    private static final Pattern SORTKEY_PAREN_PATTERN = Pattern.compile("(?i)\\b(?:COMPOUND|INTERLEAVED)?\\s*SORTKEY\\s*\\([^)]*\\)");
    /** Table-level {@code DISTKEY col} / {@code SORTKEY col}: only after the column list closes. */
    private static final Pattern TABLE_LEVEL_KEY_IDENT_PATTERN = Pattern.compile(
            "(?i)(?<=\\))\\s*(?:COMPOUND\\s+|INTERLEAVED\\s+)?(?:DISTKEY|SORTKEY)\\s+\"?\\w+\"?");
    /**
     * Bare {@code DISTKEY} / {@code SORTKEY} as a column-level constraint: it sits
     * right after the column type and is followed by a comma, the closing paren of
     * the column list, or another column attribute ({@code ENCODE ...}). The trailing
     * lookahead is what stops this from matching a column literally <em>named</em>
     * {@code distkey} (which would be followed by its type).
     */
    private static final Pattern KEY_COLUMN_CONSTRAINT_PATTERN = Pattern.compile(
            "(?i)\\s+(?:COMPOUND\\s+|INTERLEAVED\\s+)?(?:DISTKEY|SORTKEY)\\s*(?=[,)]|\\s+ENCODE\\b)");
    /** {@code ENCODE <codec>} where {@code <codec>} is an actual Redshift column encoding or {@code AUTO}. */
    private static final Pattern ENCODE_PATTERN = Pattern.compile(
            "(?i)\\bENCODE\\s+(RAW|AZ64|BYTEDICT|DELTA32K|DELTA|LZO|MOSTLY8|MOSTLY16|MOSTLY32|RUNLENGTH|TEXT255|TEXT32K|ZSTD|AUTO)\\b");
    private static final Pattern DOUBLE_COMMA_PATTERN = Pattern.compile(",(?:\\s*,)+");
    private static final Pattern COMMA_CLOSE_PAREN_PATTERN = Pattern.compile(",\\s*\\)");

    /** Non-word, non-whitespace control char that cannot occur in a PostgreSQL statement. */
    private static final String MASK_SENTINEL = String.valueOf((char) 1);
    private static final Pattern MASK_PLACEHOLDER_PATTERN =
            Pattern.compile(Pattern.quote(MASK_SENTINEL) + "(\\d+)" + Pattern.quote(MASK_SENTINEL));

    private RedshiftSqlInterceptor() {
    }

    public static String rewrite(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        if (!TABLE_DDL_PROBE.matcher(sql).find()) {
            return sql; // Not table DDL: never touch it.
        }

        List<String> literals = new ArrayList<>();
        String masked = maskStringLiterals(sql, literals);

        String s = DISTSTYLE_PATTERN.matcher(masked).replaceAll("");
        s = DISTKEY_PAREN_PATTERN.matcher(s).replaceAll("");
        s = SORTKEY_PAREN_PATTERN.matcher(s).replaceAll("");
        s = TABLE_LEVEL_KEY_IDENT_PATTERN.matcher(s).replaceAll("");
        s = KEY_COLUMN_CONSTRAINT_PATTERN.matcher(s).replaceAll("");
        s = ENCODE_PATTERN.matcher(s).replaceAll("");

        if (s.length() == masked.length()) {
            return sql; // nothing matched: hand back the original instance
        }
        s = DOUBLE_COMMA_PATTERN.matcher(s).replaceAll(",");
        s = COMMA_CLOSE_PAREN_PATTERN.matcher(s).replaceAll(")");
        return unmaskStringLiterals(s.trim(), literals);
    }

    private static String maskStringLiterals(String sql, List<String> out) {
        Matcher m = STRING_LITERAL_PATTERN.matcher(sql);
        StringBuilder sb = new StringBuilder(sql.length());
        while (m.find()) {
            out.add(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(MASK_SENTINEL + (out.size() - 1) + MASK_SENTINEL));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Single pass so already-restored text is never re-scanned. */
    private static String unmaskStringLiterals(String masked, List<String> literals) {
        if (literals.isEmpty()) {
            return masked;
        }
        Matcher m = MASK_PLACEHOLDER_PATTERN.matcher(masked);
        StringBuilder sb = new StringBuilder(masked.length());
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            String literal = (idx >= 0 && idx < literals.size()) ? literals.get(idx) : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(literal));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
