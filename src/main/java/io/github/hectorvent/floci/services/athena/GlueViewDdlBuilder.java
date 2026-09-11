package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.Table;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@ApplicationScoped
public class GlueViewDdlBuilder {

    private static final Logger LOG = Logger.getLogger(GlueViewDdlBuilder.class);

    private final GlueService glueService;

    @Inject
    public GlueViewDdlBuilder(GlueService glueService) {
        this.glueService = glueService;
    }

    public String build(String contextDatabase) {
        StringBuilder sb = new StringBuilder();
        boolean contextDbHandled = false;
        List<Database> databases = glueService.getDatabases();
        if (databases != null) {
            for (Database db : databases) {
                if (db == null) {
                    continue;
                }
                String schema = db.getName();
                if (schema == null || schema.isBlank()) {
                    continue;
                }
                sb.append("CREATE SCHEMA IF NOT EXISTS ").append(quote(schema)).append(";\n");
                try {
                    List<Table> tables = glueService.getTables(schema);
                    appendViews(sb, schema, tables, true);
                    if (schema.equals(contextDatabase)) {
                        appendViews(sb, schema, tables, false);
                        contextDbHandled = true;
                    }
                } catch (Exception e) {
                    LOG.debugv("Could not fetch tables for Glue database {0}: {1}", schema, e.getMessage());
                }
            }
        }

        if (!contextDbHandled && contextDatabase != null && !contextDatabase.isBlank()) {
            try {
                List<Table> tables = glueService.getTables(contextDatabase);
                appendViews(sb, contextDatabase, tables, false);
            } catch (Exception e) {
                LOG.debugv("Could not fetch tables for context database {0}: {1}", contextDatabase, e.getMessage());
            }
        }

        return sb.toString();
    }

    private void appendViews(StringBuilder sb, String schemaOrNull, List<Table> tables, boolean qualified) {
        if (tables == null) {
            return;
        }
        for (Table t : tables) {
            try {
                if (t == null) {
                    continue;
                }
                if (t.getName() == null || t.getName().isBlank()) {
                    continue;
                }
                if (t.getStorageDescriptor() == null
                        || t.getStorageDescriptor().getLocation() == null
                        || t.getStorageDescriptor().getLocation().isBlank()) {
                    continue;
                }
                String location = t.getStorageDescriptor().getLocation();
                String normalizedLocation = location.endsWith("/")
                        ? location.substring(0, location.length() - 1)
                        : location;
                String readFn = inferReadFunction(t);
                String target = qualified
                        ? quote(schemaOrNull) + "." + quote(t.getName())
                        : quote(t.getName());
                sb.append("CREATE OR REPLACE VIEW ")
                  .append(target)
                  .append(" AS SELECT ")
                  .append(buildProjection(t))
                  .append(" FROM ")
                  .append(readExpression(readFn, normalizedLocation))
                  .append(";\n");
            } catch (Exception e) {
                LOG.debugv("skip Glue table {0}.{1}: {2}", schemaOrNull, t != null ? t.getName() : "unknown", e.getMessage());
            }
        }
    }

    /**
     * Projects the columns the catalog declares, rather than whatever the underlying files spell.
     *
     * <p>Athena reports a table's declared column names: a JSON body written with {@code tenantId}
     * surfaces as {@code tenantid} when the table declares it that way, because the catalog owns the
     * schema and the SerDe maps onto it. Inference sees only the data, so without this the view
     * exposes the file's spelling and a client reading the declared name finds nothing. Identifier
     * resolution is case-insensitive here, so a declared name still binds to an inferred one that
     * differs from it only by case.
     *
     * <p>Partition keys are part of the table's schema and are what partition predicates filter on,
     * so they are projected too. A table that declares no columns keeps {@code *}, leaving inference
     * in charge.
     */
    static String buildProjection(Table table) {
        List<Column> declared = new ArrayList<>();
        if (table != null) {
            if (table.getStorageDescriptor() != null && table.getStorageDescriptor().getColumns() != null) {
                declared.addAll(table.getStorageDescriptor().getColumns());
            }
            if (table.getPartitionKeys() != null) {
                declared.addAll(table.getPartitionKeys());
            }
        }

        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Column c : declared) {
            if (c == null || c.getName() == null || c.getName().isBlank()) {
                continue;
            }
            String name = c.getName();
            // A partition key repeating a data column would bind twice and make the view ambiguous.
            if (seen.add(name.toLowerCase(Locale.ROOT))) {
                names.add(name);
            }
        }

        if (names.isEmpty()) {
            return "*";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(quote(names.get(i))).append(" AS ").append(quote(names.get(i)));
        }
        return sb.toString();
    }

    static String inferReadFunction(Table table) {
        if (table == null || table.getStorageDescriptor() == null) {
            return "read_csv_auto";
        }
        String format = table.getStorageDescriptor().getInputFormat();
        String serde = table.getStorageDescriptor().getSerdeInfo() != null
                ? table.getStorageDescriptor().getSerdeInfo().getSerializationLibrary()
                : null;
        if (containsIgnoreCase(format, "parquet") || containsIgnoreCase(serde, "parquet")) {
            return "read_parquet";
        }
        if (containsIgnoreCase(format, "json") || containsIgnoreCase(serde, "json")
                || containsIgnoreCase(format, "hive")) {
            return "read_json_auto";
        }
        return "read_csv_auto";
    }

    static String readExpression(String readFn, String normalizedLocation) {
        String escapedLocation = normalizedLocation.replace("'", "''");
        String glob = escapedLocation + "/**";
        if ("read_parquet".equals(readFn)) {
            return "read_parquet('" + glob + "', union_by_name = true)";
        }
        return readFn + "('" + glob + "')";
    }

    static boolean containsIgnoreCase(String str, String sub) {
        return str != null && str.toLowerCase(Locale.ROOT).contains(sub);
    }

    static String quote(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }
}
