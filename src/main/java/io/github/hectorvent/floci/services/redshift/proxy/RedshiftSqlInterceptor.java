package io.github.hectorvent.floci.services.redshift.proxy;

import java.util.regex.Pattern;

public final class RedshiftSqlInterceptor {

    private static final Pattern DISTSTYLE_PATTERN = Pattern.compile("(?i)\\bDISTSTYLE\\s+(ALL|EVEN|KEY|AUTO)\\b");
    private static final Pattern DISTKEY_PAREN_PATTERN = Pattern.compile("(?i)\\bDISTKEY\\s*\\([^)]*\\)");
    private static final Pattern DISTKEY_IDENT_PATTERN = Pattern.compile("(?i)\\bDISTKEY\\s+\"?\\w+\"?");
    private static final Pattern DISTKEY_BARE_PATTERN = Pattern.compile("(?i)\\bDISTKEY\\b");
    private static final Pattern SORTKEY_PAREN_PATTERN = Pattern.compile("(?i)\\b(?:COMPOUND|INTERLEAVED)?\\s*SORTKEY\\s*\\([^)]*\\)");
    private static final Pattern SORTKEY_IDENT_PATTERN = Pattern.compile("(?i)\\b(?:COMPOUND|INTERLEAVED)?\\s*SORTKEY\\s+\"?\\w+\"?");
    private static final Pattern SORTKEY_BARE_PATTERN = Pattern.compile("(?i)\\b(?:COMPOUND|INTERLEAVED)?\\s*SORTKEY\\b");
    private static final Pattern ENCODE_PATTERN = Pattern.compile("(?i)\\bENCODE\\s+\\w+");
    private static final Pattern DOUBLE_COMMA_PATTERN = Pattern.compile(",(?:\\s*,)+");
    private static final Pattern COMMA_CLOSE_PAREN_PATTERN = Pattern.compile(",\\s*\\)");

    private RedshiftSqlInterceptor() {
    }

    public static String rewrite(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        String s = DISTSTYLE_PATTERN.matcher(sql).replaceAll("");
        s = DISTKEY_PAREN_PATTERN.matcher(s).replaceAll("");
        s = DISTKEY_IDENT_PATTERN.matcher(s).replaceAll("");
        s = DISTKEY_BARE_PATTERN.matcher(s).replaceAll("");
        s = SORTKEY_PAREN_PATTERN.matcher(s).replaceAll("");
        s = SORTKEY_IDENT_PATTERN.matcher(s).replaceAll("");
        s = SORTKEY_BARE_PATTERN.matcher(s).replaceAll("");
        s = ENCODE_PATTERN.matcher(s).replaceAll("");

        if (s.length() != sql.length()) {
            s = DOUBLE_COMMA_PATTERN.matcher(s).replaceAll(",");
            s = COMMA_CLOSE_PAREN_PATTERN.matcher(s).replaceAll(")");
            return s.trim();
        }
        return sql;
    }
}
