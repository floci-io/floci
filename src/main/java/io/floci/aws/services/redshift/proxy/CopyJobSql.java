package io.floci.aws.services.redshift.proxy;

import io.floci.aws.core.common.PostgresWire;
import io.floci.aws.services.redshift.RedshiftAuditSql;
import io.floci.aws.services.redshift.RedshiftAuditSql.Kind;
import io.floci.aws.services.redshift.RedshiftAuditSql.Token;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Rewrites COPY JOB SQL to the cluster catalog, without executing or validating the stored COPY options. */
public final class CopyJobSql {
    private static final String SYS_COPY_JOB = "SELECT * FROM pg_catalog.sys_copy_job";

    private CopyJobSql() {
    }

    public static String runName(String sql) {
        if (sql == null || RedshiftAuditSql.statements(sql).size() != 1) {
            return null;
        }
        List<Token> tokens = RedshiftAuditSql.tokens(RedshiftAuditSql.statements(sql).getFirst());
        return tokens.size() == 4 && tokens.get(0).keyword("COPY") && tokens.get(1).keyword("JOB")
                && tokens.get(2).keyword("RUN") && isName(tokens.get(3)) ? tokens.get(3).identifier() : null;
    }

    /** Identifies job clauses outside strings, quoted identifiers and comments. */
    public static boolean matches(String sql) {
        return sql != null && RedshiftAuditSql.statements(sql).stream()
                .anyMatch(statement -> jobClause(RedshiftAuditSql.tokens(statement)) >= 0);
    }

    /** CREATE and DROP need a standalone, auto-committed execution, including on the Data API path. */
    public static boolean requiresAutoCommit(String sql) {
        if (sql == null) {
            return false;
        }
        for (String statement : RedshiftAuditSql.statements(sql)) {
            List<Token> tokens = RedshiftAuditSql.tokens(statement);
            int job = jobClause(tokens);
            if (job >= 0 && job + 1 < tokens.size()
                    && (tokens.get(job + 1).keyword("CREATE") || tokens.get(job + 1).keyword("DROP"))) {
                return true;
            }
        }
        return false;
    }

    /** The backend also checks transaction restrictions at execution, since a prepared statement may be reused. */
    public static String rewrite(String sql, boolean inTransaction) {
        if (sql == null) {
            return null;
        }
        List<String> statements = RedshiftAuditSql.statements(sql);
        List<String> rewritten = new ArrayList<>();
        boolean changed = false;
        for (String statement : statements) {
            List<Token> tokens = RedshiftAuditSql.tokens(statement);
            int job = jobClause(tokens);
            String replacement = job < 0 ? statement
                    : rewriteStatement(statement, tokens, job, inTransaction || statements.size() > 1);
            rewritten.add(replacement);
            changed |= !replacement.equals(statement);
        }
        return changed ? String.join("; ", rewritten.stream().map(String::stripLeading).toList()) : sql;
    }

    private static int jobClause(List<Token> tokens) {
        if (tokens.size() < 3 || !tokens.getFirst().keyword("COPY")) {
            return -1;
        }
        if (tokens.get(1).keyword("JOB") && (tokens.get(2).keyword("LIST") || tokens.get(2).keyword("SHOW")
                || tokens.get(2).keyword("DROP") || tokens.get(2).keyword("ALTER") || tokens.get(2).keyword("RUN"))) {
            return 1;
        }
        for (int i = 2; i + 1 < tokens.size(); i++) {
            if (tokens.get(i).keyword("JOB") && tokens.get(i + 1).keyword("CREATE")) {
                return i;
            }
        }
        return -1;
    }

    private static String rewriteStatement(String sql, List<Token> tokens, int job, boolean inTransaction) {
        if (tokens.stream().anyMatch(token -> token.kind() == Kind.INVALID)) {
            return reject("42601", "Unterminated comment in COPY JOB statement");
        }
        if (job > 1) {
            return create(sql, tokens, job, inTransaction);
        }
        String verb = tokens.get(2).text().toUpperCase(Locale.ROOT);
        List<Token> arguments = tokens.subList(3, tokens.size());
        boolean hasName = !arguments.isEmpty() && isName(arguments.getFirst());
        return switch (verb) {
            case "LIST" -> arguments.isEmpty() ? SYS_COPY_JOB + " ORDER BY job_id"
                    : reject("42601", "COPY JOB LIST takes no arguments");
            case "SHOW" -> hasName && arguments.size() == 1
                    ? SYS_COPY_JOB + " WHERE job_name = " + PostgresWire.quoteLiteral(arguments.getFirst().identifier())
                    : reject("42601", "COPY JOB SHOW needs a job name");
            case "DROP" -> {
                if (!hasName || arguments.size() != 1) {
                    yield reject("42601", "COPY JOB DROP needs a job name");
                }
                yield inTransaction ? reject("25001", "COPY JOB DROP cannot run inside a transaction block")
                        : "CALL floci_internal.drop_copy_job("
                                + PostgresWire.quoteLiteral(arguments.getFirst().identifier()) + ")";
            }
            case "ALTER" -> hasName && arguments.size() == 3 && arguments.get(1).keyword("AUTO")
                    && isAutoValue(arguments.get(2))
                    ? "CALL floci_internal.alter_copy_job(" + PostgresWire.quoteLiteral(arguments.getFirst().identifier())
                            + ", " + arguments.get(2).keyword("ON") + ")"
                    : reject("42601", "COPY JOB ALTER needs a job name and AUTO ON or AUTO OFF");
            case "RUN" -> hasName && arguments.size() == 1 ? sql
                    : reject("42601", "COPY JOB RUN needs a job name");
            default -> reject("42601", "Unknown COPY JOB command");
        };
    }

    private static String create(String sql, List<Token> tokens, int job, boolean inTransaction) {
        if (inTransaction) {
            return reject("25001", "COPY JOB CREATE cannot run inside a transaction block");
        }
        List<Token> arguments = tokens.subList(job + 2, tokens.size());
        if (arguments.isEmpty() || !isName(arguments.getFirst())
                || !(arguments.size() == 1 || arguments.size() == 3 && arguments.get(1).keyword("AUTO")
                && isAutoValue(arguments.get(2)))) {
            return reject("42601", "COPY JOB CREATE needs a job name and optional AUTO ON or AUTO OFF");
        }
        String name = arguments.getFirst().identifier();
        if (name.indexOf('-') >= 0) {
            return reject("42602", "A copy job name cannot contain a hyphen");
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > 127) {
            return reject("42602", "A copy job name cannot exceed 127 bytes");
        }
        int from = 2;
        while (from < job && !tokens.get(from).keyword("FROM")) {
            from++;
        }
        if (from + 1 >= job || tokens.get(from + 1).kind() != Kind.STRING) {
            return reject("0A000", "A copy job needs a COPY from an Amazon S3 location");
        }
        String table = targetTable(sql, tokens, from);
        if (table == null) {
            return reject("42601", "Invalid COPY target table or column list");
        }
        String source = stringValue(tokens.get(from + 1));
        if (source == null || !source.startsWith("s3://") || source.length() <= 5) {
            return reject("0A000", "A copy job needs a COPY from an Amazon S3 location");
        }
        String role = null;
        for (int i = from + 2; i < job; i++) {
            Token token = tokens.get(i);
            if (token.keyword("MAXERROR") || token.keyword("IGNOREALLERRORS")) {
                return reject("0A000", "A copy job cannot use MAXERROR or IGNOREALLERRORS");
            }
            if (token.keyword("MANIFEST")) {
                return reject("0A000", "A copy job cannot use a manifest");
            }
            if (token.keyword("CREDENTIALS") || token.keyword("ACCESS_KEY_ID")
                    || token.keyword("SECRET_ACCESS_KEY") || token.keyword("SESSION_TOKEN")) {
                return reject("0A000", "A copy job authorizes with IAM_ROLE, not access keys");
            }
            if (token.keyword("IAM_ROLE")) {
                int value = i + 1;
                if (value < job && tokens.get(value).keyword("AS")) {
                    value++;
                }
                role = value < job ? stringValue(tokens.get(value)) : null;
            }
        }
        if (role == null || role.isBlank() || "default".equalsIgnoreCase(role)) {
            return reject("0A000", "A copy job needs an IAM_ROLE in its COPY command");
        }
        int slash = source.indexOf('/', 5);
        String bucket = slash < 0 ? source.substring(5) : source.substring(5, slash);
        String prefix = slash < 0 ? "" : source.substring(slash + 1);
        if (bucket.isEmpty()) {
            return reject("0A000", "A copy job needs a COPY from an Amazon S3 location");
        }
        String copy = sql.substring(tokens.getFirst().start(), tokens.get(job).start()).trim();
        boolean auto = arguments.size() == 1 || arguments.get(2).keyword("ON");
        return "CALL floci_internal.create_copy_job(" + PostgresWire.quoteLiteral(name) + ", "
                + PostgresWire.quoteLiteral(role) + ", " + PostgresWire.quoteLiteral(copy) + ", " + auto + ", "
                + PostgresWire.quoteLiteral(table) + ", " + PostgresWire.quoteLiteral(bucket) + ", "
                + PostgresWire.quoteLiteral(prefix) + ")";
    }

    private static String targetTable(String sql, List<Token> tokens, int from) {
        int index = 1;
        if (!isName(tokens.get(index++))) {
            return null;
        }
        while (index < from && ".".equals(tokens.get(index).text())) {
            index++;
            if (index >= from || !isName(tokens.get(index++))) {
                return null;
            }
        }
        int end = index;
        if (index < from && "(".equals(tokens.get(index).text())) {
            index++;
            while (true) {
                if (index >= from || !isName(tokens.get(index++))) {
                    return null;
                }
                if (index >= from || !",".equals(tokens.get(index).text())) {
                    break;
                }
                index++;
            }
            if (index >= from || !")".equals(tokens.get(index++).text())) {
                return null;
            }
        }
        return index == from ? sql.substring(tokens.get(1).start(), tokens.get(end - 1).end()) : null;
    }

    private static boolean isName(Token token) {
        return token.kind() == Kind.IDENTIFIER && token.text().length() > 2
                && token.text().matches("\"(?:[^\"]|\"\")*+\"")
                || token.kind() == Kind.WORD && token.text().matches("[\\p{L}_][\\p{L}\\p{N}_$]*");
    }

    private static boolean isAutoValue(Token token) {
        return token.keyword("ON") || token.keyword("OFF");
    }

    private static String stringValue(Token token) {
        String text = token.text();
        return token.kind() == Kind.STRING && text.length() >= 2 && text.startsWith("'") && text.endsWith("'")
                ? text.substring(1, text.length() - 1).replace("''", "'") : null;
    }

    private static String reject(String sqlState, String message) {
        return "CALL floci_internal.reject_copy_job(" + PostgresWire.quoteLiteral(sqlState) + ", "
                + PostgresWire.quoteLiteral(message) + ")";
    }
}
