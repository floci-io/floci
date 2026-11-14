package io.floci.aws.services.athena;

import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.GlueTableResolver;
import io.floci.aws.services.glue.PartitionProjection;
import io.floci.aws.services.glue.model.Database;
import io.floci.aws.services.glue.model.Table;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class GlueViewDdlBuilder {

    private static final Logger LOG = Logger.getLogger(GlueViewDdlBuilder.class);

    /**
     * Iceberg tables are read through the {@code iceberg} extension rather than one of the
     * Hive-format-sniffed {@code read_*} functions, so it is installed and loaded once, up
     * front, only when the batch actually contains an Iceberg table.
     */
    private static final String ICEBERG_EXTENSION_SETUP = "INSTALL iceberg; LOAD iceberg;\n";

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
                // Athena fails every query on such a table, and only those (AthenaService checks
                // for it). A view can't bind the key the template omits, and one view failing to
                // bind fails the whole batch, so the table gets none.
                if (PartitionProjection.hasIncompleteLocationTemplate(t)) {
                    LOG.debugv("skip Glue table {0}.{1}: storage.location.template omits a partition key",
                            schemaOrNull, t.getName());
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
                if (GlueTableResolver.isIcebergTable(t) && GlueTableResolver.icebergMetadataLocation(t) != null
                        && !GlueTableResolver.icebergMetadataLocation(t).isBlank()) {
                    fromClause = GlueTableResolver.icebergReadExpression(GlueTableResolver.icebergMetadataLocation(t));
                    usesIceberg = true;
                } else {
                    String readFn = GlueTableResolver.inferReadFunction(t);
                    String readPath = GlueTableResolver.readPath(t, normalizedLocation);
                    fromClause = GlueTableResolver.readExpression(t, readFn, readPath);
                }
                sb.append("CREATE OR REPLACE VIEW ")
                  .append(target)
                  .append(" AS SELECT ")
                  .append(GlueTableResolver.buildProjection(t))
                  .append(" FROM ")
                  .append(fromClause);
                String pathFilter = GlueTableResolver.pathFilter(t);
                if (pathFilter != null) {
                    sb.append(" WHERE ").append(pathFilter);
                }
                sb.append(";\n");
            } catch (Exception e) {
                LOG.debugv("skip Glue table {0}.{1}: {2}", schemaOrNull, t != null ? t.getName() : "unknown", e.getMessage());
            }
        }
        return usesIceberg;
    }

    static String quote(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }
}
