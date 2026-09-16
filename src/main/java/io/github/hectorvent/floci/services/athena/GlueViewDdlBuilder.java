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

    /**
     * Iceberg tables are read through the {@code iceberg} extension rather than one of the
     * Hive-format-sniffed {@code read_*} functions, so it is installed and loaded once, up
     * front, only when the batch actually contains an Iceberg table.
     */
    private static final String ICEBERG_EXTENSION_SETUP = "INSTALL iceberg; LOAD iceberg;\n";

    /** Glue table property AWS/pyiceberg set to mark a table as Iceberg-format. */
    private static final String PARAM_TABLE_TYPE = "table_type";

    /** Glue table property holding the path to the table's current Iceberg metadata JSON. */
    private static final String PARAM_METADATA_LOCATION = "metadata_location";

    private static final String ICEBERG_TABLE_TYPE = "ICEBERG";

    private final GlueService glueService;

    @Inject
    public GlueViewDdlBuilder(GlueService glueService) {
        this.glueService = glueService;
    }

    public String build(String contextDatabase) {
        StringBuilder sb = new StringBuilder();
        boolean usesIceberg = false;
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
                    usesIceberg |= appendViews(sb, schema, tables, true);
                    if (schema.equals(contextDatabase)) {
                        usesIceberg |= appendViews(sb, schema, tables, false);
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
                usesIceberg |= appendViews(sb, contextDatabase, tables, false);
            } catch (Exception e) {
                LOG.debugv("Could not fetch tables for context database {0}: {1}", contextDatabase, e.getMessage());
            }
        }

        if (usesIceberg) {
            sb.insert(0, ICEBERG_EXTENSION_SETUP);
        }

        return sb.toString();
    }

    /** @return true if at least one of the appended views reads an Iceberg table. */
    private boolean appendViews(StringBuilder sb, String schemaOrNull, List<Table> tables, boolean qualified) {
        if (tables == null) {
            return false;
        }
        boolean usesIceberg = false;
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
                String target = qualified
                        ? quote(schemaOrNull) + "." + quote(t.getName())
                        : quote(t.getName());
                String fromClause;
                if (isIcebergTable(t) && icebergMetadataLocation(t) != null && !icebergMetadataLocation(t).isBlank()) {
                    fromClause = icebergReadExpression(icebergMetadataLocation(t));
                    usesIceberg = true;
                } else {
                    String readFn = inferReadFunction(t);
                    String readPath = PartitionProjection.readPath(t, normalizedLocation);
                    fromClause = readExpression(readFn, readPath);
                }
                sb.append("CREATE OR REPLACE VIEW ")
                  .append(target)
                  .append(" AS SELECT ")
                  .append(buildProjection(t))
                  .append(" FROM ")
                  .append(fromClause)
                  .append(";\n");
            } catch (Exception e) {
                LOG.debugv("skip Glue table {0}.{1}: {2}", schemaOrNull, t != null ? t.getName() : "unknown", e.getMessage());
            }
        }
        return usesIceberg;
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

    /**
     * Iceberg tables are not read via a Hive {@code InputFormat}/{@code SerializationLibrary}
     * pair at all — pyiceberg and the AWS Glue-Iceberg integration leave those unset — so
     * {@link #inferReadFunction} would otherwise fall through to {@code read_csv_auto} and fail
     * on the table's binary Parquet data files, or a forced {@code read_parquet} would silently
     * glob every data file ever written under the table's location, including ones no longer
     * referenced by the current snapshot. Following the catalog's own {@code metadata_location}
     * into {@code iceberg_scan} instead resolves the table through its real manifest list.
     */
    static boolean isIcebergTable(Table table) {
        return table != null
                && table.getParameters() != null
                && ICEBERG_TABLE_TYPE.equalsIgnoreCase(table.getParameters().get(PARAM_TABLE_TYPE));
    }

    static String icebergMetadataLocation(Table table) {
        return table.getParameters().get(PARAM_METADATA_LOCATION);
    }

    static String icebergReadExpression(String metadataLocation) {
        return "iceberg_scan('" + metadataLocation.replace("'", "''") + "')";
    }

    static boolean containsIgnoreCase(String str, String sub) {
        return str != null && str.toLowerCase(Locale.ROOT).contains(sub);
    }

    static String quote(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }
}
