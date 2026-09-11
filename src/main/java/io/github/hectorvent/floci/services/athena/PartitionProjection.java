package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Table;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Partition projection: the table properties describing where a table's partitions live and what
 * values they take, so the partitions need not be listed from the catalog or from storage.
 */
final class PartitionProjection {

    /** Table property switching partition projection on. */
    private static final String ENABLED = "projection.enabled";

    /** Table property giving the partition path layout when it is not the default. */
    private static final String LOCATION_TEMPLATE = "storage.location.template";

    /** {@code ${column}} placeholders in a partition location template. */
    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile("\\$\\{[^}]*}");

    private PartitionProjection() {
    }

    static boolean enabled(Table table) {
        return table != null && "true".equalsIgnoreCase(parameter(table, ENABLED));
    }

    /** Case-insensitive lookup, since table properties are not normalised on the way in. */
    static String parameter(Table table, String name) {
        Map<String, String> params = table == null ? null : table.getParameters();
        if (params == null) {
            return null;
        }
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Returns the path to read for {@code table}: its partition layout when the table projects its
     * partitions, and {@code normalizedLocation} unchanged otherwise.
     *
     * <p>A projecting table's data lives only under its partition paths, and those are what Athena
     * reads - it builds them from the projection configuration rather than listing the location.
     * Reading the location wholesale instead picks up whatever else is stored beside the data, which
     * for a table rooted at a bucket means that bucket's other prefixes. A workgroup writing query
     * results under the same root is the common way this bites: the first query succeeds, its output
     * lands inside the read path, and the next query fails trying to read it as table data.
     *
     * <p>The layout comes from {@code storage.location.template} when set, and otherwise from the
     * default {@code column=value} form over the partition keys. Values stay wildcards: narrowing
     * them to the partitions a query actually needs requires that query's predicates, so the read
     * stays wide and the query itself does the filtering.
     */
    static String readPath(Table table, String normalizedLocation) {
        if (!enabled(table)) {
            return normalizedLocation;
        }

        String template = parameter(table, LOCATION_TEMPLATE);
        if (template != null && !template.isBlank()) {
            String wildcarded = TEMPLATE_PLACEHOLDER.matcher(template).replaceAll("*");
            return stripTrailingSlash(wildcarded);
        }

        List<Column> keys = table.getPartitionKeys();
        if (keys == null || keys.isEmpty()) {
            return normalizedLocation;
        }
        StringBuilder sb = new StringBuilder(normalizedLocation);
        for (Column key : keys) {
            if (key == null || key.getName() == null || key.getName().isBlank()) {
                return normalizedLocation;
            }
            sb.append('/').append(key.getName()).append("=*");
        }
        return sb.toString();
    }

    /**
     * Fails a query that names a projecting table but never mentions one of its {@code injected}
     * partition columns.
     *
     * <p>An injected column has no generatable range: its values come from the query, so Athena
     * requires an equality condition on it and rejects the query otherwise. Deciding whether a
     * condition is a *static equality* one needs the query's predicate tree, which is not available
     * here, so this checks only whether the column is mentioned at all. That is strictly narrower
     * than the real rule - a query filtering an injected column with, say, a range condition is
     * rejected by Athena and accepted here - but it never rejects a query Athena would accept, and
     * it catches the case that silently reads every partition.
     */
    static void assertInjectedColumnsFiltered(String query, List<Table> tables) {
        if (query == null || tables == null) {
            return;
        }
        for (Table table : tables) {
            if (!enabled(table) || table.getName() == null || table.getPartitionKeys() == null) {
                continue;
            }
            // A table the query never names cannot be constrained by it, and must not fail it.
            if (!mentions(query, table.getName())) {
                continue;
            }
            for (Column key : table.getPartitionKeys()) {
                if (key == null || key.getName() == null) {
                    continue;
                }
                String type = parameter(table, "projection." + key.getName() + ".type");
                if (!"injected".equalsIgnoreCase(type) || mentions(query, key.getName())) {
                    continue;
                }
                throw new AwsException("InvalidRequestException",
                        "CONSTRAINT_VIOLATION: For the injected projected partition column "
                                + key.getName() + ", the WHERE clause must contain only static equality "
                                + "conditions, and at least one such condition must be present.", 400);
            }
        }
    }

    private static boolean mentions(String query, String identifier) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(identifier) + "(?![A-Za-z0-9_])",
                Pattern.CASE_INSENSITIVE).matcher(query).find();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
