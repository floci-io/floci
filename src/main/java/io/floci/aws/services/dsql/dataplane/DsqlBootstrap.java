package io.floci.aws.services.dsql.dataplane;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Renders the DSQL bootstrap script for the database, owner role and admin role of one cluster.
 * The script's placeholders: {@code {{database}}}, {@code {{owner}}} and {@code {{admin}}} as
 * identifiers, always quoted; {@code {{owner_literal}}}, {@code {{admin_literal}}} and
 * {@code {{role_prefix}}} as SQL string literals ({@code 'dabc_'}, or {@code ''} for no prefix);
 * {@code {{external}}} as the SQL boolean {@code true} or {@code false}.
 */
final class DsqlBootstrap {

    private static final String RESOURCE = "/dsql/bootstrap.sql";

    private DsqlBootstrap() {
    }

    /**
     * The script for one cluster's database. {@code rolePrefix} is what the cluster's backend role
     * names start with (empty in Docker mode), and {@code external} says whether the database is one
     * of many on a shared server, whose roles are not all the cluster's.
     */
    static String render(String database, String owner, String admin, String rolePrefix, boolean external) {
        return template()
                .replace("{{database}}", identifier(database))
                .replace("{{owner_literal}}", literal(owner))
                .replace("{{owner}}", identifier(owner))
                .replace("{{admin_literal}}", literal(admin))
                .replace("{{admin}}", identifier(admin))
                .replace("{{role_prefix}}", literal(rolePrefix))
                .replace("{{external}}", Boolean.toString(external));
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

    /** Always quoted, so no name needs checking against PostgreSQL's reserved words. */
    private static String identifier(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
