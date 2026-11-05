package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.services.redshift.proxy.RedshiftRoleAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Rules for the {@code DataTableName}, {@code DataTableColumns} and {@code CopyOptions} members of a Redshift
 * destination's {@code CopyCommand}. AWS pastes these members into the {@code COPY} statement as given, so Floci
 * does the same and lets Redshift's own identifier folding apply: an unquoted name is lowercased, a quoted one is
 * kept. Instead of quoting, it refuses names that are not identifiers and any {@code ;}, {@code $} or comment marker
 * outside a quoted literal, which is what keeps the text from carrying a second statement. The separator scan follows PostgreSQL's own
 * quoting rules rather than a simplified one, because the statement is later run in simple query mode, where
 * PostgreSQL executes every {@code ;}-separated statement it receives.
 *
 * <p>{@link #build} adds an {@code IAM_ROLE} never: the stream's role is not necessarily associated with the cluster.
 * AWS hands the cluster temporary credentials of that role, so a caller that has minted them passes them in and they
 * go in as a {@code CREDENTIALS} clause, which the wire proxy reads S3 with. Without them the proxy reads the staged
 * object as an unsigned request.
 */
final class RedshiftCopySql {

    private static final String NAME = "(?:[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\")";
    private static final Pattern QUALIFIED_NAME = Pattern.compile(NAME + "(?:\\." + NAME + ")?");
    private static final Pattern SIMPLE_NAME = Pattern.compile(NAME);
    private static final Pattern BUCKET = Pattern.compile("[^/'\"\\s]+");
    private static final Pattern OBJECT_KEY = Pattern.compile("[^'\"\\s]*");
    private static final Pattern CREDENTIAL_VALUE = Pattern.compile("[A-Za-z0-9+/=_.-]+");

    private RedshiftCopySql() {}

    static String build(String table, String columns, String bucket, String key, String copyOptions) {
        return build(table, columns, bucket, key, copyOptions, null);
    }

    /** @param credentials temporary credentials the COPY reads S3 with, or null to send none */
    static String build(String table, String columns, String bucket, String key, String copyOptions,
                        RedshiftRoleAccess.RoleSession credentials) {
        if (!isValidTableName(table)) {
            throw new IllegalArgumentException("DataTableName is not a table identifier: " + table);
        }
        if (!isValidColumnList(columns)) {
            throw new IllegalArgumentException("DataTableColumns is not a list of column identifiers: " + columns);
        }
        if (!BUCKET.matcher(bucket).matches() || !OBJECT_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("The staged object s3://" + bucket + "/" + key
                    + " has a name the COPY emulation cannot read: keys must not contain whitespace or quotes.");
        }
        if (copyOptions != null && hasStatementSeparator(copyOptions)) {
            throw new IllegalArgumentException("CopyOptions must not contain a statement separator.");
        }
        StringBuilder sql = new StringBuilder("COPY ").append(table);
        if (columns != null && !columns.isBlank()) {
            List<String> trimmed = new ArrayList<>();
            for (String column : columns.split(",")) {
                trimmed.add(column.trim());
            }
            sql.append(" (").append(String.join(", ", trimmed)).append(')');
        }
        sql.append(" FROM 's3://").append(bucket).append('/').append(key).append('\'');
        if (credentials != null) {
            sql.append(" CREDENTIALS '").append(credentialsClause(credentials)).append('\'');
        }
        // The closing terminator is dropped: the statement is sent on its own, and a second one cannot follow it.
        String options = copyOptions == null ? "" : withoutTrailingTerminator(copyOptions).trim();
        if (!options.isEmpty()) {
            sql.append(' ').append(options);
        }
        return sql.toString();
    }

    /** The message never echoes a value: a credential that cannot be quoted safely must not reach a log either. */
    private static String credentialsClause(RedshiftRoleAccess.RoleSession credentials) {
        for (String value : List.of(credentials.accessKeyId(), credentials.secretAccessKey(),
                credentials.sessionToken())) {
            if (!CREDENTIAL_VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("The temporary credentials for the COPY contain a character "
                        + "that cannot be placed in a CREDENTIALS clause.");
            }
        }
        return "aws_access_key_id=" + credentials.accessKeyId()
                + ";aws_secret_access_key=" + credentials.secretAccessKey()
                + ";token=" + credentials.sessionToken();
    }

    /** Replaces the secret and token of {@code credentials} in {@code text}, for anything that leaves the process. */
    static String redact(String text, RedshiftRoleAccess.RoleSession credentials) {
        if (text == null || credentials == null) {
            return text;
        }
        return text.replace(credentials.secretAccessKey(), "***").replace(credentials.sessionToken(), "***");
    }

    static boolean isValidTableName(String table) {
        return table != null && QUALIFIED_NAME.matcher(table).matches();
    }

    /** An absent or blank list is valid: the proxy then discovers the table columns itself. */
    static boolean isValidColumnList(String columns) {
        if (columns == null || columns.isBlank()) {
            return true;
        }
        for (String column : columns.split(",", -1)) {
            if (!SIMPLE_NAME.matcher(column.trim()).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The text without one closing {@code ;} and the whitespace around it. A terminator at the very end carries no
     * second statement, and the Firehose API reference's own CopyOptions example ends with one:
     * {@code delimiter '\t' lzop;}.
     */
    static String withoutTrailingTerminator(String text) {
        String trimmed = text.stripTrailing();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1).stripTrailing() : text;
    }

    /**
     * True when the text could carry a second statement: a {@code ;} outside a quoted literal other than a single
     * closing one, an unterminated literal, any {@code $} or a comment. A {@code $} outside a literal is refused
     * outright rather than parsed as a dollar-quote tag: CopyOptions never need dollar quoting, and a tag can hold any
     * non-ASCII character, so a tag pattern here could always fall behind PostgreSQL's lexer. Single-quoted strings honour
     * backslash escapes only in the {@code E'...'} form, double-quoted identifiers are skipped as a whole, and a
     * doubled quote stays inside its literal.
     */
    static boolean hasStatementSeparator(String text) {
        String body = withoutTrailingTerminator(text);
        int length = body.length();
        int i = 0;
        while (i < length) {
            char c = body.charAt(i);
            if (c == '\'') {
                boolean escapeString = i > 0 && (body.charAt(i - 1) == 'e' || body.charAt(i - 1) == 'E')
                        && (i == 1 || !isIdentifierChar(body.charAt(i - 2)));
                i = endOfQuoted(body, i + 1, '\'', escapeString);
            } else if (c == '"') {
                i = endOfQuoted(body, i + 1, '"', false);
            } else if (c == ';' || c == '$' || startsComment(body, i)) {
                return true;
            } else {
                i++;
            }
            if (i < 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsComment(String text, int i) {
        if (i + 1 >= text.length()) {
            return false;
        }
        char c = text.charAt(i);
        char next = text.charAt(i + 1);
        return (c == '-' && next == '-') || (c == '/' && next == '*');
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Index just past the closing quote, or -1 when the literal is never closed. */
    private static int endOfQuoted(String text, int from, char quote, boolean backslashEscapes) {
        int j = from;
        while (j < text.length()) {
            char ch = text.charAt(j);
            if (backslashEscapes && ch == '\\') {
                j += 2;
            } else if (ch == quote) {
                if (j + 1 < text.length() && text.charAt(j + 1) == quote) {
                    j += 2;
                } else {
                    return j + 1;
                }
            } else {
                j++;
            }
        }
        return -1;
    }
}
