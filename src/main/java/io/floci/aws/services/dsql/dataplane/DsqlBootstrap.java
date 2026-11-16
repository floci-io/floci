package io.floci.aws.services.dsql.dataplane;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders the DSQL bootstrap script for the database, owner role and admin role of one cluster.
 * The script's placeholders: {@code {{database}}}, {@code {{owner}}} and {@code {{admin}}} as
 * identifiers, quoted when they need it; {@code {{owner_literal}}} and {@code {{admin_literal}}}
 * as SQL string literals.
 */
final class DsqlBootstrap {

    private static final String RESOURCE = "/dsql/bootstrap.sql";
    private static final Pattern PLAIN = Pattern.compile("[a-z_][a-z0-9_]*");
    // A partial list of PostgreSQL's reserved words: enough for the names Floci gives, which are
    // never reserved words; any other name that is one fails loudly when the script runs.
    private static final Set<String> RESERVED = Set.of(
            "user", "table", "select", "group", "order", "all", "analyse", "analyze", "and", "any",
            "as", "asc", "both", "case", "check", "column", "constraint", "create", "current_user",
            "default", "desc", "distinct", "do", "else", "end", "false", "for", "from", "grant",
            "having", "in", "into", "limit", "not", "null", "on", "only", "or", "primary",
            "references", "session_user", "some", "then", "to", "true", "union", "unique", "using",
            "when", "where", "with");

    private DsqlBootstrap() {
    }

    /** The script for one cluster's database. */
    static String render(String database, String owner, String admin) {
        return template()
                .replace("{{database}}", identifier(database))
                .replace("{{owner_literal}}", literal(owner))
                .replace("{{owner}}", identifier(owner))
                .replace("{{admin_literal}}", literal(admin))
                .replace("{{admin}}", identifier(admin));
    }

    private static String template() {
        try (InputStream in = DsqlBootstrap.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("dsql/bootstrap.sql is missing from the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String identifier(String name) {
        if (PLAIN.matcher(name).matches() && !RESERVED.contains(name)) {
            return name;
        }
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
