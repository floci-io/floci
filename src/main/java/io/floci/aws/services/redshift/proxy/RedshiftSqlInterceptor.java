package io.floci.aws.services.redshift.proxy;

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
 * String-literal masking recognizes both single-quoted literals and
 * dollar-quoted literals ({@code $$ ... $$}, {@code $tag$ ... $tag$}). It is
 * <em>not</em> comment-aware and does not recognise escape strings ({@code E'...'}); an
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

    /** Single-quoted string literal (doubled '' is escaped quote) and PostgreSQL dollar-quoted string literal ($tag$...$tag$). */
    private static final Pattern STRING_LITERAL_PATTERN = Pattern.compile(
            "'(?:[^']|'')*'|(?s)\\$([^$\\s]*)\\$.*?\\$\\1\\$");

    private static final Pattern DISTSTYLE_PATTERN = Pattern.compile("(?i)\\bDISTSTYLE\\s+(ALL|EVEN|KEY|AUTO)\\b");
    private static final Pattern DISTKEY_PAREN_PATTERN = Pattern.compile("(?i)\\bDISTKEY\\s*\\([^)]*\\)");
    private static final Pattern SORTKEY_PAREN_PATTERN = Pattern.compile("(?i)\\b(?:COMPOUND|INTERLEAVED)?\\s*SORTKEY\\s*\\([^)]*\\)");
    /** Table-level {@code DISTKEY col} / {@code SORTKEY col}: only after the column list closes. */
    private static final Pattern TABLE_LEVEL_KEY_IDENT_PATTERN = Pattern.compile(
            "(?i)(?<=\\))\\s*(?:COMPOUND\\s+|INTERLEAVED\\s+)?(?:DISTKEY|SORTKEY)\\s+\"?[a-zA-Z_]\\w*\"?(?=\\s*(?:[;]|\\b(?:DISTSTYLE|DISTKEY|SORTKEY|ENCODE)\\b|$))");
    /**
     * Bare {@code DISTKEY} / {@code SORTKEY} as a column-level attribute: it sits
     * right after the column type and is followed by a comma, the closing paren of
     * the column list, another column attribute ({@code ENCODE ...}), or a column
     * constraint ({@code NOT NULL}, {@code PRIMARY KEY}, etc.). The trailing
     * lookahead is what stops this from matching a column literally <em>named</em>
     * {@code distkey} (which would be followed by its type).
     */
    private static final Pattern KEY_COLUMN_CONSTRAINT_PATTERN = Pattern.compile(
            "(?i)\\s+(?:COMPOUND\\s+|INTERLEAVED\\s+)?(?:DISTKEY|SORTKEY)\\s*(?=[,)]|\\s+(?:ENCODE|DISTKEY|SORTKEY|NOT\\s+NULL|NULL|UNIQUE|PRIMARY\\s+KEY|REFERENCES|DEFAULT|COLLATE|CHECK|CONSTRAINT|IDENTITY|GENERATED)\\b)");
    /** {@code ENCODE <codec>} where {@code <codec>} is an actual Redshift column encoding or {@code AUTO}. */
    private static final Pattern ENCODE_PATTERN = Pattern.compile(
            "(?i)\\bENCODE\\s+(RAW|AZ64|BYTEDICT|DELTA32K|DELTA|LZO|MOSTLY8|MOSTLY16|MOSTLY32|RUNLENGTH|TEXT255|TEXT32K|ZSTD|AUTO)\\b");
    /** Whitespace and SQL comments, which may sit between the parts of a column definition. */
    private static final String GAP = "(?:\\s|/\\*.*?\\*/|--[^\\n]*(?:\\n|$))";
    /**
     * {@code VARBYTE(n)}, {@code VARBINARY(n)} and {@code BINARY VARYING(n)}, and the two aliases without a length, as
     * the type of a column: the {@code varbyte} domain takes no length, and PostgreSQL has no type of the other names.
     * A type is only the word after a column's name, and a column's definition only starts after the {@code (} or
     * {@code ,} of a column list or after {@code ADD [COLUMN] [IF NOT EXISTS]}. A column called {@code varbinary}, a
     * {@code RENAME COLUMN a TO varbinary} and the like are never in that position. Group 1 is what leads up to the
     * type, kept as it is.
     */
    private static final Pattern VARBYTE_TYPE_PATTERN = Pattern.compile(
            "(?is)((?:[(,]|\\bADD\\s+(?:COLUMN\\s+)?+(?:IF\\s+NOT\\s+EXISTS\\s+)?+)" + GAP + "*(?:\"[^\"]+\"|\\w+)" + GAP + "+)"
                    + "(?:VARBYTE|VARBINARY|BINARY\\s+VARYING)(?:\\s*\\(\\s*\\d+\\s*\\))?(?=[\\s,)]|/\\*|--|$)");
    /** A table made from a query has no column types to rewrite, only select lists whose aliases could look like one. */
    private static final Pattern TABLE_FROM_QUERY_PATTERN = Pattern.compile("(?is)\\bAS\\s*\\(?\\s*(?:SELECT|WITH)\\b");
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

        List<String> literals = new ArrayList<>();
        String masked = maskStringLiterals(sql, literals);

        String[] statements = masked.split(";", -1);
        boolean anyRewritten = false;
        for (int i = 0; i < statements.length; i++) {
            if (TABLE_DDL_PROBE.matcher(statements[i]).find()) {
                String rewritten = rewriteTableDdl(statements[i]);
                if (!rewritten.equals(statements[i])) {
                    statements[i] = rewritten;
                    anyRewritten = true;
                }
            }
        }

        if (!anyRewritten) {
            return sql; // nothing matched: hand back the original instance
        }
        String joined = String.join(";", statements);
        return unmaskStringLiterals(joined.trim(), literals);
    }

    private static String rewriteTableDdl(String maskedDdl) {
        String s = DISTSTYLE_PATTERN.matcher(maskedDdl).replaceAll("");
        s = DISTKEY_PAREN_PATTERN.matcher(s).replaceAll("");
        s = SORTKEY_PAREN_PATTERN.matcher(s).replaceAll("");
        s = KEY_COLUMN_CONSTRAINT_PATTERN.matcher(s).replaceAll("");
        s = TABLE_LEVEL_KEY_IDENT_PATTERN.matcher(s).replaceAll("");
        s = ENCODE_PATTERN.matcher(s).replaceAll("");
        if (!TABLE_FROM_QUERY_PATTERN.matcher(s).find()) {
            s = VARBYTE_TYPE_PATTERN.matcher(s).replaceAll("$1varbyte");
        }
        s = DOUBLE_COMMA_PATTERN.matcher(s).replaceAll(",");
        s = COMMA_CLOSE_PAREN_PATTERN.matcher(s).replaceAll(")");
        return s;
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
