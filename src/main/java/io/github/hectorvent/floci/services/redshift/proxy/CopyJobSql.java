package io.github.hectorvent.floci.services.redshift.proxy;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the COPY JOB statements into calls on the {@code floci_internal} procedures that
 * {@code bootstrap-catalog.sql} defines, so the jobs live in the cluster database and
 * {@code sys_copy_job} reads them like any other catalog view.
 *
 * <p>The checks AWS documents for a job (an S3 source, an explicit {@code IAM_ROLE}, no manifest, no access
 * keys, no {@code MAXERROR} or {@code IGNOREALLERRORS}, a name without a hyphen, no transaction block) are made
 * here, and a statement that breaks one is rewritten to a call that raises the error. The text of the
 * errors and their SQLSTATEs are not captured from AWS.
 */
public final class CopyJobSql {

    private static final String NAME = "[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\"";

    private static final Pattern JOB_COMMAND = Pattern.compile(
            "(?is)^\\s*COPY\\s+JOB\\s+(LIST|SHOW|RUN|DROP|ALTER)\\b\\s*(.*?)\\s*;?\\s*$");
    private static final Pattern JOB_CREATE = Pattern.compile(
            "(?is)^\\s*(COPY\\s+.+?)\\s+JOB\\s+CREATE\\s+(" + NAME + ")(?:\\s+AUTO\\s+(ON|OFF))?\\s*;?\\s*$");
    private static final Pattern NAME_ONLY = Pattern.compile("(" + NAME + ")");
    private static final Pattern NAME_AUTO = Pattern.compile("(" + NAME + ")\\s+AUTO\\s+(ON|OFF)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ERROR_TOLERANCE = Pattern.compile("(?i)\\b(MAXERROR|IGNOREALLERRORS)\\b");
    private static final Pattern MANIFEST = Pattern.compile("(?i)\\bMANIFEST\\b");
    private static final Pattern ACCESS_KEYS = Pattern.compile(
            "(?i)\\b(CREDENTIALS|ACCESS_KEY_ID|SECRET_ACCESS_KEY|SESSION_TOKEN)\\b");
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

    private static final String SYS_COPY_JOB = "SELECT * FROM pg_catalog.sys_copy_job";

    private CopyJobSql() {
    }

    /** Whether the statement is one COPY JOB form, so that it is not read as an ordinary S3 COPY. */
    public static boolean matches(String sql) {
        if (sql == null) {
            return false;
        }
        return JOB_COMMAND.matcher(sql).matches() || JOB_CREATE.matcher(sql).matches();
    }

    /** The statement to send to PostgreSQL in place of a COPY JOB statement, or the statement itself otherwise. */
    public static String rewrite(String sql, boolean inTransaction) {
        if (sql == null) {
            return null;
        }
        Matcher create = JOB_CREATE.matcher(sql);
        if (create.matches()) {
            return create(create.group(1), create.group(2), create.group(3), inTransaction);
        }
        Matcher command = JOB_COMMAND.matcher(sql);
        if (command.matches()) {
            return command(command.group(1).toUpperCase(Locale.ROOT), command.group(2), inTransaction);
        }
        return sql;
    }

    private static String command(String verb, String arguments, boolean inTransaction) {
        Matcher name = NAME_ONLY.matcher(arguments);
        Matcher alter = NAME_AUTO.matcher(arguments);
        return switch (verb) {
            case "LIST" -> arguments.isEmpty() ? SYS_COPY_JOB + " ORDER BY job_id"
                    : reject("42601", "COPY JOB LIST takes no arguments");
            case "SHOW" -> name.matches()
                    ? SYS_COPY_JOB + " WHERE job_name = " + literal(jobName(name.group(1)))
                    : reject("42601", "COPY JOB SHOW needs a job name");
            case "DROP" -> {
                if (!name.matches()) {
                    yield reject("42601", "COPY JOB DROP needs a job name");
                }
                yield inTransaction ? reject("25001", "COPY JOB DROP cannot run inside a transaction block")
                        : "CALL floci_internal.drop_copy_job(" + literal(jobName(name.group(1))) + ")";
            }
            case "ALTER" -> alter.matches()
                    ? "CALL floci_internal.alter_copy_job(" + literal(jobName(alter.group(1))) + ", "
                            + isAuto(alter.group(2)) + ")"
                    : reject("42601", "COPY JOB ALTER needs a job name and AUTO ON or AUTO OFF");
            default -> reject("0A000", "COPY JOB RUN is not supported");
        };
    }

    private static String create(String copy, String quotedName, String auto, boolean inTransaction) {
        if (inTransaction) {
            return reject("25001", "COPY JOB CREATE cannot run inside a transaction block");
        }
        String name = jobName(quotedName);
        if (name.indexOf('-') >= 0) {
            return reject("42602", "A copy job name cannot contain a hyphen");
        }
        // the parser declines some of these options, so they are named before it is asked to read the COPY
        String withoutLiterals = STRING_LITERAL.matcher(copy).replaceAll("''");
        if (ERROR_TOLERANCE.matcher(withoutLiterals).find()) {
            return reject("0A000", "A copy job cannot use MAXERROR or IGNOREALLERRORS");
        }
        if (MANIFEST.matcher(withoutLiterals).find()) {
            return reject("0A000", "A copy job cannot use a manifest");
        }
        if (ACCESS_KEYS.matcher(withoutLiterals).find()) {
            return reject("0A000", "A copy job authorizes with IAM_ROLE, not access keys");
        }
        CopyStatementParser.S3Statement statement = CopyStatementParser.parse(copy);
        if (!(statement instanceof CopyStatementParser.S3CopyFrom from)) {
            return reject("0A000", "A copy job needs a COPY from an Amazon S3 location");
        }
        if (from.iamRoleArn() == null || from.iamRoleArn().isBlank() || from.defaultIamRoleRequested()) {
            return reject("0A000", "A copy job needs an IAM_ROLE in its COPY command");
        }
        String prefix = from.keyOrPrefix() == null ? "" : from.keyOrPrefix();
        return "CALL floci_internal.create_copy_job(" + literal(name) + ", " + literal(from.iamRoleArn()) + ", "
                + literal(copy) + ", " + isAuto(auto) + ", " + literal(from.targetTable()) + ", "
                + literal(from.bucket()) + ", " + literal(prefix) + ")";
    }

    /** AUTO ON is the default. */
    private static String isAuto(String auto) {
        return auto == null || "ON".equalsIgnoreCase(auto) ? "true" : "false";
    }

    /** An unquoted name folds to lower case as PostgreSQL does, a quoted one is taken as written. */
    private static String jobName(String name) {
        return name.startsWith("\"") ? name.substring(1, name.length() - 1) : name.toLowerCase(Locale.ROOT);
    }

    private static String reject(String sqlState, String message) {
        return "CALL floci_internal.reject_copy_job(" + literal(sqlState) + ", " + literal(message) + ")";
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
