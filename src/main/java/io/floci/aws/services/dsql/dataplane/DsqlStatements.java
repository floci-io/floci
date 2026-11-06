package io.floci.aws.services.dsql.dataplane;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The statements of a query as Aurora DSQL reads them. DSQL's own syntax ({@code CREATE INDEX
 * ASYNC}, {@code AWS IAM GRANT} and {@code REVOKE}) becomes the calls into the cluster's
 * {@code sys} schema that implement it, and a statement DSQL refuses becomes a call to
 * {@code sys._reject} with the SQLSTATE and message DSQL answers (measured on AWS, eu-west-1,
 * 2026-10-16), so the database raises the error and the transaction fails as DSQL's does. What
 * DSQL refuses that the catalog can tell (types, temporary and partitioned tables, PL/pgSQL
 * functions, triggers) is refused inside the database instead.
 */
public final class DsqlStatements {

    private static final Set<String> ADVISORY_LOCKS = Set.of(
            "pg_advisory_lock", "pg_advisory_lock_shared", "pg_try_advisory_lock", "pg_try_advisory_lock_shared",
            "pg_advisory_xact_lock", "pg_advisory_xact_lock_shared", "pg_try_advisory_xact_lock",
            "pg_try_advisory_xact_lock_shared", "pg_advisory_unlock", "pg_advisory_unlock_shared",
            "pg_advisory_unlock_all");
    private static final Set<String> COLUMN_CONSTRAINTS = Set.of(
            "DEFAULT", "NOT", "NULL", "CHECK", "UNIQUE", "PRIMARY", "REFERENCES", "GENERATED", "CONSTRAINT",
            "COLLATE");
    private static final Set<String> TABLE_CONSTRAINTS = Set.of(
            "CONSTRAINT", "PRIMARY", "UNIQUE", "CHECK", "FOREIGN", "EXCLUDE");
    private static final String RESERVED_PREFIX = "dsql.";
    private static final Set<String> ISOLATION_PARAMETERS = Set.of("default_transaction_isolation", "transaction_isolation");
    private static final Set<String> ROLE_KINDS = Set.of("ROLE", "USER", "GROUP");
    private static final Set<String> ROLE_SPECIFIERS = Set.of("CURRENT_ROLE", "CURRENT_USER", "SESSION_USER", "PUBLIC",
            "NONE");

    private DsqlStatements() {
    }

    /** One statement of a query, and the command tag a client should see for it when it differs. */
    public record Statement(String sql, String commandTag) {
    }

    /** The statements of {@code query}, each rewritten as DSQL reads it, in order. */
    public static List<Statement> rewrite(String query) {
        List<Statement> statements = new ArrayList<>();
        for (List<Token> tokens : split(tokenize(query))) {
            if (tokens.isEmpty()) {
                continue;
            }
            String text = query.substring(tokens.get(0).start(), tokens.get(tokens.size() - 1).end());
            statements.add(rewrite(text, tokens));
        }
        return statements;
    }

    /**
     * A query as the database runs it, and the command tag each of its statements shows, null where
     * the database's own is right.
     */
    public record Query(String sql, List<String> commandTags) {

        /** Whether any statement shows a command tag other than the database's. */
        public boolean overridesTags() {
            return commandTags.stream().anyMatch(tag -> tag != null);
        }
    }

    /**
     * The whole query as the database runs it: the statements rewritten and joined, or, when none
     * needed rewriting, the query exactly as the client sent it.
     */
    public static Query rewriteQuery(String query) {
        List<String> parts = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        boolean changed = false;
        for (List<Token> tokens : split(tokenize(query))) {
            if (tokens.isEmpty()) {
                continue;
            }
            String text = query.substring(tokens.get(0).start(), tokens.get(tokens.size() - 1).end());
            Statement statement = rewrite(text, tokens);
            changed |= statement.commandTag() != null || !statement.sql().equals(text);
            parts.add(statement.sql());
            tags.add(statement.commandTag());
        }
        return new Query(changed ? String.join(";\n", parts) : query, tags);
    }

    /** {@code sys._reject} raising {@code state} with {@code message}, as a statement. */
    public static String reject(String state, String message, String detail) {
        return "SELECT sys._reject(" + literal(state) + ", " + literal(message)
                + (detail == null ? "" : ", " + literal(detail)) + ")";
    }

    private static Statement rewrite(String text, List<Token> tokens) {
        String first = word(tokens, 0);
        String second = word(tokens, 1);
        String third = word(tokens, 2);

        if ("AWS".equals(first) && "IAM".equals(second) && ("GRANT".equals(third) || "REVOKE".equals(third))) {
            Statement iam = iamGrant(tokens, "GRANT".equals(third));
            if (iam != null) {
                return iam;
            }
        }
        if ("DROP".equals(first) && ("ROLE".equals(second) || "USER".equals(second) || "GROUP".equals(second))) {
            Statement drop = dropRoles(tokens);
            if (drop != null) {
                return drop;
            }
        }
        if ("CREATE".equals(first)) {
            int at = 1;
            if ("UNIQUE".equals(word(tokens, at))) {
                at++;
            }
            if ("INDEX".equals(word(tokens, at)) && "ASYNC".equals(word(tokens, at + 1))) {
                return createIndexAsync(text, tokens, at + 1);
            }
            if ("DATABASE".equals(second)) {
                return rejected("0A000", "CREATE DATABASE not supported");
            }
            if ("TABLESPACE".equals(second)) {
                return rejected("0A000", "unsupported statement: CreateTableSpace");
            }
            if ("EXTENSION".equals(second)) {
                return rejected("0A000", "unsupported statement: CreateExtension");
            }
            int sequence = indexOfWord(tokens, "SEQUENCE");
            if (sequence > 0 && sequence <= 3 && onlyWords(tokens, 1, sequence, Set.of("TEMP", "TEMPORARY", "UNLOGGED"))) {
                Statement refused = sequenceCache(tokens);
                if (refused != null) {
                    return refused;
                }
            }
        }
        if ("TRUNCATE".equals(first)) {
            return rejected("0A000", "unsupported statement: Truncate");
        }
        if ("LISTEN".equals(first)) {
            return rejected("0A000", "unsupported statement: Listen");
        }
        if ("NOTIFY".equals(first)) {
            return rejected("0A000", "unsupported statement: Notify");
        }
        if ("UNLISTEN".equals(first)) {
            return rejected("0A000", "unsupported statement: Unlisten");
        }
        if ("VACUUM".equals(first)) {
            return rejected("0A000", "unsupported VACUUM statement");
        }
        if ("DO".equals(first)) {
            return rejected("0A000", "DO with language plpgsql not supported");
        }
        if ("GRANT".equals(first) || "REVOKE".equals(first)) {
            int on = indexOfWord(tokens, "ON");
            if (on > 0 && "DATABASE".equals(word(tokens, on + 1))) {
                return rejected("0A000", "unsupported object type in " + first);
            }
        }
        if ("ALTER".equals(first) && "DATABASE".equals(second)
                && (indexOfWord(tokens, "SET") > 0 || indexOfWord(tokens, "RESET") > 0)) {
            return rejected("0A000", "unsupported statement: AlterDatabaseSet");
        }
        if ("ALTER".equals(first) && "TABLE".equals(second)) {
            Statement refused = alterTableAdd(tokens);
            if (refused != null) {
                return refused;
            }
        }
        Statement setting = setting(tokens, first);
        if (setting != null) {
            return setting;
        }
        Statement isolation = isolation(tokens, first, second);
        if (isolation != null) {
            return isolation;
        }
        Statement role = roleDdl(text, tokens, first, second);
        if (role != null) {
            return role;
        }
        Statement locking = lockingClause(tokens);
        if (locking != null) {
            return locking;
        }
        for (int i = 0; i + 1 < tokens.size(); i++) {
            Token token = tokens.get(i);
            boolean name = token.type() == Type.WORD || token.type() == Type.QUOTED_IDENTIFIER;
            if (name && ADVISORY_LOCKS.contains(identifier(token)) && "(".equals(tokens.get(i + 1).text())) {
                return rejected("0A000", "function " + identifier(token) + " not supported");
            }
        }
        return new Statement(text, null);
    }

    private static Statement iamGrant(List<Token> tokens, boolean grant) {
        if (tokens.size() != 6 || tokens.get(5).type() != Type.STRING) {
            return null;
        }
        String preposition = word(tokens, 4);
        if (!(grant ? "TO" : "FROM").equals(preposition)) {
            return null;
        }
        Token role = tokens.get(3);
        String roleName = switch (role.type()) {
            case WORD -> role.lower();
            case QUOTED_IDENTIFIER -> role.value();
            default -> null;
        };
        if (roleName == null) {
            return null;
        }
        String call = "CALL sys.aws_iam_" + (grant ? "grant" : "revoke") + "(" + literal(roleName) + ", "
                + literal(tokens.get(5).value()) + ")";
        return new Statement(call, grant ? "AWS IAM GRANT" : "AWS IAM REVOKE");
    }

    /**
     * {@code CREATE [UNIQUE] INDEX ASYNC [IF NOT EXISTS] [name] ON [ONLY] table ...}: the
     * statement without {@code ASYNC}, the index's name and its table, for
     * {@code sys.create_index_async}, which answers the job id DSQL answers.
     */
    private static Statement createIndexAsync(String text, List<Token> tokens, int asyncAt) {
        Token async = tokens.get(asyncAt);
        int base = tokens.get(0).start();
        String statement = text.substring(0, async.start() - base) + text.substring(async.end() - base);
        int at = asyncAt + 1;
        if ("IF".equals(word(tokens, at)) && "NOT".equals(word(tokens, at + 1)) && "EXISTS".equals(word(tokens, at + 2))) {
            at += 3;
        }
        String indexName = null;
        if (!"ON".equals(word(tokens, at)) && at < tokens.size()) {
            indexName = identifier(tokens.get(at));
            at++;
        }
        if (!"ON".equals(word(tokens, at))) {
            return new Statement(statement, null);
        }
        at++;
        if ("ONLY".equals(word(tokens, at))) {
            at++;
        }
        int tableStart = at;
        while (at < tokens.size() && !"(".equals(tokens.get(at).text()) && !"USING".equals(word(tokens, at))) {
            at++;
        }
        if (tableStart >= at) {
            return new Statement(statement, null);
        }
        String table = text.substring(tokens.get(tableStart).start() - base, tokens.get(at - 1).end() - base);
        String call = "SELECT job_id FROM sys.create_index_async(" + dollarQuoted(statement) + ", "
                + (indexName == null ? "NULL" : literal(indexName)) + ", " + literal(table) + ")";
        return new Statement(call, null);
    }

    /** The sequence's {@code CACHE}, read from the statement's tokens so comments and names do not count. */
    private static Statement sequenceCache(List<Token> tokens) {
        String cache = null;
        for (int i = 0; i + 1 < tokens.size() && cache == null; i++) {
            if ("CACHE".equals(word(tokens, i))) {
                boolean negative = "-".equals(tokens.get(i + 1).text());
                int at = negative ? i + 2 : i + 1;
                if (at < tokens.size() && tokens.get(at).type() == Type.NUMBER) {
                    cache = (negative ? "-" : "") + tokens.get(at).text();
                }
            }
        }
        if (cache == null) {
            return rejected("0A000", "CREATE SEQUENCE is not supported without an explicit cache size. please"
                    + " define CACHE greater than or equal to 65536 or equal to 1");
        }
        long size;
        try {
            size = Long.parseLong(cache);
        } catch (NumberFormatException e) {
            // Past a bigint, or not an integer: the database's own error is what DSQL answers too (measured).
            return null;
        }
        if (size != 1 && size < 65536) {
            return rejected("22023", "CACHE (" + size + ") must be greater than or equal to 65536 or equal to 1");
        }
        return null;
    }

    /** {@code ALTER TABLE ... ADD [COLUMN] name type <constraint>} and {@code ADD CONSTRAINT}. */
    private static Statement alterTableAdd(List<Token> tokens) {
        for (int i = 2; i < tokens.size(); i++) {
            if (!"ADD".equals(word(tokens, i))) {
                continue;
            }
            int at = i + 1;
            if (TABLE_CONSTRAINTS.contains(word(tokens, at))) {
                return rejected("0A000", "unsupported ALTER TABLE ADD CONSTRAINT statement");
            }
            if ("COLUMN".equals(word(tokens, at))) {
                at++;
            }
            if ("IF".equals(word(tokens, at)) && "NOT".equals(word(tokens, at + 1))) {
                at += 3;
            }
            int depth = 0;
            for (int j = at + 1; j < tokens.size(); j++) {
                String text = tokens.get(j).text();
                if ("(".equals(text)) {
                    depth++;
                } else if (")".equals(text)) {
                    depth--;
                } else if (depth == 0 && ",".equals(text)) {
                    break;
                } else if (depth == 0 && tokens.get(j).type() == Type.WORD
                        && COLUMN_CONSTRAINTS.contains(tokens.get(j).upper())) {
                    return rejected("0A000", "ALTER TABLE ADD COLUMN with constraint not supported");
                }
            }
        }
        return null;
    }

    /**
     * DSQL reserves the {@code dsql} prefix as PostgreSQL reserves an extension's (measured,
     * eu-west-1, 2026-10-20): setting or resetting a {@code dsql.} parameter, with {@code SET} or
     * {@code RESET} in any statement or with {@code set_config}, is {@code 42602}, and showing one
     * {@code 42704}, as for a parameter that does not exist. {@code set_config} of any other dotted
     * parameter named in the statement is not supported, though {@code SET} of one is.
     */
    private static Statement setting(List<Token> tokens, String first) {
        if ("SHOW".equals(first)) {
            String name = parameterName(tokens, 1);
            if (name.startsWith(RESERVED_PREFIX)) {
                return rejected("42704", "unrecognized configuration parameter \"" + name + "\"");
            }
        }
        for (int i = 0; i + 1 < tokens.size(); i++) {
            String word = word(tokens, i);
            if ("SET".equals(word) || "RESET".equals(word)) {
                int at = "SESSION".equals(word(tokens, i + 1)) || "LOCAL".equals(word(tokens, i + 1)) ? i + 2 : i + 1;
                String name = parameterName(tokens, at);
                if (name.startsWith(RESERVED_PREFIX)) {
                    return reservedPrefix(name);
                }
                if ("SET".equals(word) && ISOLATION_PARAMETERS.contains(name)) {
                    return notSupported(name);
                }
            }
            boolean setConfig = "SET_CONFIG".equals(word) || tokens.get(i).type() == Type.QUOTED_IDENTIFIER
                    && "set_config".equals(tokens.get(i).value());
            if (setConfig && i + 2 < tokens.size() && "(".equals(tokens.get(i + 1).text())
                    && tokens.get(i + 2).type() == Type.STRING) {
                String name = tokens.get(i + 2).value();
                if (name.startsWith(RESERVED_PREFIX)) {
                    return reservedPrefix(name);
                }
                if (name.contains(".") || ISOLATION_PARAMETERS.contains(name)) {
                    return notSupported(name);
                }
            }
        }
        return null;
    }

    /**
     * {@code DROP ROLE|USER|GROUP [IF EXISTS] name, ...}: the call that refuses a role an IAM role or
     * user is mapped to, as DSQL does (measured), and otherwise drops them as the caller.
     */
    private static Statement dropRoles(List<Token> tokens) {
        int at = 2;
        boolean ifExists = "IF".equals(word(tokens, at)) && "EXISTS".equals(word(tokens, at + 1));
        if (ifExists) {
            at += 2;
        }
        List<String> names = new ArrayList<>();
        for (int i = at; i < tokens.size(); i += 2) {
            Token name = tokens.get(i);
            // A role specifier PostgreSQL refuses here is left for PostgreSQL to refuse.
            boolean specifier = name.type() == Type.WORD && ROLE_SPECIFIERS.contains(name.upper());
            if (specifier || name.type() != Type.WORD && name.type() != Type.QUOTED_IDENTIFIER) {
                return null;
            }
            names.add(literal(identifier(name)));
            if (i + 1 < tokens.size() && !",".equals(tokens.get(i + 1).text())) {
                return null;
            }
        }
        // Names and commas alternate, so a list that ends in a comma is left for PostgreSQL to refuse.
        if (names.isEmpty() || (tokens.size() - at) % 2 == 0) {
            return null;
        }
        return new Statement("CALL sys.drop_roles(ARRAY[" + String.join(", ", names) + "]::text[], " + ifExists + ")",
                "DROP ROLE");
    }

    private static Statement notSupported(String parameter) {
        return rejected("0A000", "setting configuration parameter \"" + parameter + "\" not supported");
    }

    /**
     * A statement on roles, which DSQL counts as DDL (measured) though PostgreSQL fires no event
     * trigger for one: {@code CREATE} or {@code ALTER ROLE|USER|GROUP}, and {@code GRANT} or
     * {@code REVOKE} of a role, which names no object with {@code ON}. {@code sys.run_role_ddl}
     * counts it, then runs it as the caller, under PostgreSQL's command tag.
     */
    private static Statement roleDdl(String text, List<Token> tokens, String first, String second) {
        boolean createOrAlter = ("CREATE".equals(first) || "ALTER".equals(first)) && ROLE_KINDS.contains(second)
                && !"MAPPING".equals(word(tokens, 2));
        boolean grantOrRevoke = ("GRANT".equals(first) || "REVOKE".equals(first)) && indexOfWord(tokens, "ON") < 0;
        if (!createOrAlter && !grantOrRevoke) {
            return null;
        }
        return new Statement("CALL sys.run_role_ddl(" + dollarQuoted(text) + ")", first + " ROLE");
    }

    private static Statement reservedPrefix(String name) {
        return new Statement(reject("42602", "invalid configuration parameter name \"" + name + "\"",
                "\"dsql\" is a reserved prefix."), null);
    }

    /** The parameter named from {@code at} on, its parts joined by dots, as PostgreSQL folds its case. */
    private static String parameterName(List<Token> tokens, int at) {
        StringBuilder name = new StringBuilder();
        for (int i = at; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type() == Type.WORD || token.type() == Type.QUOTED_IDENTIFIER) {
                name.append(identifier(token));
            } else if (".".equals(token.text()) && token.type() == Type.SYMBOL && name.length() > 0) {
                name.append('.');
                continue;
            } else {
                break;
            }
            if (i + 1 >= tokens.size() || !".".equals(tokens.get(i + 1).text())) {
                break;
            }
        }
        return name.toString();
    }

    private static Statement isolation(List<Token> tokens, String first, String second) {
        boolean setsCharacteristics = "SET".equals(first) && ("TRANSACTION".equals(second)
                || ("SESSION".equals(second) && "CHARACTERISTICS".equals(word(tokens, 2))));
        boolean begins = "BEGIN".equals(first) || ("START".equals(first) && "TRANSACTION".equals(second));
        if (setsCharacteristics || begins) {
            int level = indexOfWord(tokens, "LEVEL");
            if (level > 0 && "ISOLATION".equals(word(tokens, level - 1))) {
                String chosen = word(tokens, level + 1) + " " + word(tokens, level + 2);
                String name = switch (chosen) {
                    case "REPEATABLE READ" -> null;
                    case "READ COMMITTED", "READ UNCOMMITTED" -> chosen;
                    default -> word(tokens, level + 1);
                };
                if (name != null) {
                    return rejected("0A000", "Unsupported isolation level: " + name);
                }
            }
        }
        return null;
    }

    private static Statement lockingClause(List<Token> tokens) {
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (!"FOR".equals(word(tokens, i))) {
                continue;
            }
            String next = word(tokens, i + 1);
            if ("SHARE".equals(next) || ("NO".equals(next) && "KEY".equals(word(tokens, i + 2))
                    && "UPDATE".equals(word(tokens, i + 3)))) {
                return rejected("0A000", "locking clauses other than FOR UPDATE/FOR KEY SHARE are not supported");
            }
        }
        return null;
    }

    private static Statement rejected(String state, String message) {
        return new Statement(reject(state, message, null), null);
    }

    // ── Tokens ────────────────────────────────────────────────────────────────

    enum Type { WORD, QUOTED_IDENTIFIER, STRING, NUMBER, SYMBOL }

    record Token(Type type, String text, String value, int start, int end) {
        String upper() {
            return text.toUpperCase(Locale.ROOT);
        }

        String lower() {
            return text.toLowerCase(Locale.ROOT);
        }
    }

    static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                // PostgreSQL ends the comment at either line break.
                while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int depth = 1;
                i += 2;
                while (i < n && depth > 0) {
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
            } else if (c == '\'' || ((c == 'E' || c == 'e') && i + 1 < n && sql.charAt(i + 1) == '\'')) {
                boolean escapes = c != '\'';
                int start = i;
                i += escapes ? 2 : 1;
                StringBuilder value = new StringBuilder();
                while (i < n) {
                    char d = sql.charAt(i);
                    if (escapes && d == '\\' && i + 1 < n) {
                        value.append(sql.charAt(i + 1));
                        i += 2;
                    } else if (d == '\'' && i + 1 < n && sql.charAt(i + 1) == '\'') {
                        value.append('\'');
                        i += 2;
                    } else if (d == '\'') {
                        i++;
                        break;
                    } else {
                        value.append(d);
                        i++;
                    }
                }
                tokens.add(new Token(Type.STRING, sql.substring(start, i), value.toString(), start, i));
            } else if (c == '"') {
                int start = i++;
                StringBuilder value = new StringBuilder();
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '"' && i + 1 < n && sql.charAt(i + 1) == '"') {
                        value.append('"');
                        i += 2;
                    } else if (d == '"') {
                        i++;
                        break;
                    } else {
                        value.append(d);
                        i++;
                    }
                }
                tokens.add(new Token(Type.QUOTED_IDENTIFIER, sql.substring(start, i), value.toString(), start, i));
            } else if (c == '$' && dollarTagEnd(sql, i) > 0) {
                int tagEnd = dollarTagEnd(sql, i);
                String tag = sql.substring(i, tagEnd);
                int close = sql.indexOf(tag, tagEnd);
                int end = close < 0 ? n : close + tag.length();
                String value = sql.substring(tagEnd, close < 0 ? n : close);
                tokens.add(new Token(Type.STRING, sql.substring(i, end), value, i, end));
                i = end;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) {
                    i++;
                }
                tokens.add(new Token(Type.WORD, sql.substring(start, i), null, start, i));
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(Type.NUMBER, sql.substring(start, i), null, start, i));
            } else {
                tokens.add(new Token(Type.SYMBOL, String.valueOf(c), null, i, i + 1));
                i++;
            }
        }
        return tokens;
    }

    private static int dollarTagEnd(String sql, int at) {
        int i = at + 1;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
            if (i == at + 1 && Character.isDigit(sql.charAt(i))) {
                return -1;
            }
            i++;
        }
        return i < sql.length() && sql.charAt(i) == '$' ? i + 1 : -1;
    }

    static List<List<Token>> split(List<Token> tokens) {
        List<List<Token>> statements = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int depth = 0;
        for (Token token : tokens) {
            if ("(".equals(token.text()) && token.type() == Type.SYMBOL) {
                depth++;
            } else if (")".equals(token.text()) && token.type() == Type.SYMBOL) {
                depth = Math.max(0, depth - 1);
            }
            if (";".equals(token.text()) && token.type() == Type.SYMBOL && depth == 0) {
                statements.add(current);
                current = new ArrayList<>();
            } else {
                current.add(token);
            }
        }
        statements.add(current);
        return statements;
    }

    private static String word(List<Token> tokens, int index) {
        if (index < 0 || index >= tokens.size() || tokens.get(index).type() != Type.WORD) {
            return "";
        }
        return tokens.get(index).upper();
    }

    private static int indexOfWord(List<Token> tokens, String word) {
        for (int i = 0; i < tokens.size(); i++) {
            if (word.equals(word(tokens, i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean onlyWords(List<Token> tokens, int from, int to, Set<String> allowed) {
        for (int i = from; i < to; i++) {
            if (!allowed.contains(word(tokens, i))) {
                return false;
            }
        }
        return true;
    }

    private static String identifier(Token token) {
        return switch (token.type()) {
            case QUOTED_IDENTIFIER -> token.value();
            case WORD -> token.lower();
            default -> token.text();
        };
    }

    static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String dollarQuoted(String value) {
        String tag = "$dsql$";
        int n = 0;
        while (value.contains(tag)) {
            tag = "$dsql" + (++n) + "$";
        }
        return tag + value + tag;
    }
}
