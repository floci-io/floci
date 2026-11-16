package io.floci.aws.services.dsql.dataplane;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Renders the DSQL bootstrap script for the database, owner role and admin role of one cluster.
 * The script's placeholders: {@code {{database}}}, {@code {{owner}}} and {@code {{admin}}} as
 * identifiers, always quoted; {@code {{owner_literal}}} and {@code {{admin_literal}}}
 * as SQL string literals.
 */
final class DsqlBootstrap {

    private static final String RESOURCE = "/dsql/bootstrap.sql";

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

    /** Always quoted, so no name needs checking against PostgreSQL's reserved words. */
    private static String identifier(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
