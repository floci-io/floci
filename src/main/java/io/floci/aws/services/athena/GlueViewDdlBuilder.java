package io.floci.aws.services.athena;

import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.GlueTableResolver;
import io.floci.aws.services.glue.PartitionProjection;
import io.floci.aws.services.glue.model.Database;
import io.floci.aws.services.glue.model.Partition;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;

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
    private final S3Service s3Service;

    @Inject
    public GlueViewDdlBuilder(GlueService glueService, S3Service s3Service) {
        this.glueService = glueService;
        this.s3Service = s3Service;
    }

    public String build(String contextDatabase) {
        return build(contextDatabase, null);
    }

    /**
     * Views for the Glue tables {@code query} names, or for every table when {@code query} is null,
     * reads {@code information_schema} or is a {@code SHOW} statement, all of which list tables.
     * DuckDB binds each view of the batch before the query runs, so a table whose view cannot bind
     * (files that do not match its declared schema) fails only the queries that read it rather than
     * every query.
     */
    public String build(String contextDatabase, String query) {
        ViewFilter wanted = viewFilter(query);
        // A table read through its registered partitions is built once for its qualified and unqualified views.
        Map<String, String> partitionReads = new HashMap<>();
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
                    usesIceberg |= appendViews(sb, schema, tables, true, wanted, partitionReads);
                    if (schema.equals(contextDatabase)) {
                        usesIceberg |= appendViews(sb, schema, tables, false, wanted, partitionReads);
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
                usesIceberg |= appendViews(sb, contextDatabase, tables, false, wanted, partitionReads);
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
    private boolean appendViews(StringBuilder sb, String schemaOrNull, List<Table> tables, boolean qualified,
                                ViewFilter wanted, Map<String, String> partitionReads) {
        if (tables == null) {
            return false;
        }
        boolean usesIceberg = false;
        for (Table t : tables) {
            try {
                if (t == null) {
                    continue;
                }
                if (t.getName() == null || t.getName().isBlank()
                        || !wanted.wants(schemaOrNull, t.getName(), qualified)) {
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
                } else if (GlueTableResolver.readsRegisteredPartitions(t)) {
                    sb.append("CREATE OR REPLACE VIEW ").append(target).append(" AS ")
                            .append(registeredPartitionsSelect(schemaOrNull, t, partitionReads)).append(";\n");
                    continue;
                } else {
                    String readFn = GlueTableResolver.inferReadFunction(t);
                    String readPath = GlueTableResolver.readPath(t, normalizedLocation);
                    // Reading a location that holds no data fails to bind, where Athena answers no rows.
                    String empty = holdsNoData(readPath) ? GlueTableResolver.emptyProjection(t) : null;
                    if (empty != null) {
                        sb.append("CREATE OR REPLACE VIEW ").append(target).append(" AS SELECT ").append(empty)
                                .append(";\n");
                        continue;
                    }
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

    /**
     * The rows of a table Athena reads through its registered partitions, built once per batch for the table's
     * qualified and unqualified views: the partitions holding data, each at its own location (its storage
     * descriptor's, or {@code <table location>/key=value/...} for one that gives none) with its values as the
     * partition keys, read together ({@link GlueTableResolver#registeredPartitionsSelect}); no rows when none holds
     * data, as a table whose partitions are not loaded answers on Athena. A partition that does not give one value for
     * each key, which Glue refuses on AWS, is skipped rather than hiding the table.
     */
    private String registeredPartitionsSelect(String database, Table table, Map<String, String> built) {
        String builtKey = database + "/" + table.getName();
        String select = built.get(builtKey);
        if (select != null) {
            return select;
        }
        String tableLocation = normalized(table.getStorageDescriptor().getLocation());
        TableLocation root = TableLocation.of(tableLocation);
        NavigableSet<String> rootKeys = null;
        boolean rootListed = false;
        List<GlueTableResolver.RegisteredPartition> holdingData = new ArrayList<>();
        List<Partition> partitions = glueService.getPartitions(database, table.getName());
        for (Partition partition : partitions == null ? List.<Partition>of() : partitions) {
            if (!GlueTableResolver.valuesEachKey(table, partition.getValues())) {
                LOG.debugv("skip a partition of Glue table {0}.{1}: values {2} do not match its keys",
                        database, table.getName(), partition.getValues());
                continue;
            }
            String location = normalized(partition.getStorageDescriptor() != null
                    && partition.getStorageDescriptor().getLocation() != null
                    && !partition.getStorageDescriptor().getLocation().isBlank()
                    ? partition.getStorageDescriptor().getLocation()
                    : AthenaHiveDdlExecutor.defaultLocation(table, partition.getValues()));
            TableLocation at = TableLocation.of(location);
            boolean holds;
            if (at == null) {
                holds = true;
            } else if (root != null && root.covers(at)) {
                // One listing of the table's location answers for every partition under it.
                if (!rootListed) {
                    rootKeys = root.keysHoldingData(s3Service);
                    rootListed = true;
                }
                String next = rootKeys == null ? null : rootKeys.ceiling(at.prefix());
                holds = rootKeys == null || next != null && next.startsWith(at.prefix());
            } else {
                holds = !at.holdsNoData(s3Service);
            }
            if (holds) {
                holdingData.add(new GlueTableResolver.RegisteredPartition(location, partition.getValues()));
            }
        }
        select = holdingData.isEmpty() ? "SELECT " + GlueTableResolver.emptyProjection(table)
                : GlueTableResolver.registeredPartitionsSelect(table, holdingData);
        built.put(builtKey, select);
        return select;
    }

    private static String normalized(String location) {
        return location.endsWith("/") ? location.substring(0, location.length() - 1) : location;
    }

    /** Whether the S3 location a table is read from holds no data; false for one that is not in S3. */
    private boolean holdsNoData(String readPath) {
        TableLocation location = TableLocation.of(readPath);
        return location != null && location.holdsNoData(s3Service);
    }

    /** Which views a query needs: a qualified view of a database's table, or an unqualified one of the context's. */
    @FunctionalInterface
    interface ViewFilter {
        boolean wants(String schema, String table, boolean qualified);
    }

    /**
     * The views {@code query} needs, from the names it references (see {@link #references}): a qualified view
     * {@code "db"."t"} for a reference whose parts include {@code db} then {@code t} ({@code db.t},
     * {@code awsdatacatalog.db.t}, a column {@code db.t.c}), and an unqualified one for a reference starting with
     * {@code t}, which resolves in the context database. Every view when {@code query} is null, starts with
     * {@code SHOW} or references {@code information_schema}, all of which list tables.
     */
    static ViewFilter viewFilter(String query) {
        if (query == null) {
            return (schema, table, qualified) -> true;
        }
        List<List<String>> references = references(query);
        boolean lists = references.stream().anyMatch(name -> name.contains("information_schema"))
                || (!references.isEmpty() && references.getFirst().getFirst().equals("show"));
        if (lists) {
            return (schema, table, qualified) -> true;
        }
        return (schema, table, qualified) -> {
            String db = schema == null ? null : schema.toLowerCase(Locale.ROOT);
            String name = table.toLowerCase(Locale.ROOT);
            for (List<String> reference : references) {
                if (!qualified && names(reference.getFirst(), name)) {
                    return true;
                }
                for (int i = 0; qualified && i + 1 < reference.size(); i++) {
                    if (reference.get(i).equals(db) && names(reference.get(i + 1), name)) {
                        return true;
                    }
                }
            }
            return false;
        };
    }

    /** Whether a referenced name names the table: itself, or one of its Iceberg metadata tables ({@code "t$files"}). */
    private static boolean names(String referenced, String table) {
        return referenced.equals(table) || referenced.startsWith(table + "$");
    }

    /**
     * The dotted names {@code query} references, each part lowercased, a quoted part decoded ({@code "a""b"} is
     * {@code a"b}), in the order they appear: keywords too, since a table may share a keyword's name. String
     * literals and comments are skipped, so a name only they hold is no reference.
     */
    static List<List<String>> references(String query) {
        List<List<String>> names = new ArrayList<>();
        List<String> current = null;
        boolean dotted = false;
        int i = 0;
        int n = query.length();
        while (i < n) {
            char c = query.charAt(i);
            if (c == '-' && i + 1 < n && query.charAt(i + 1) == '-') {
                int end = query.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
                current = null;
            } else if (c == '/' && i + 1 < n && query.charAt(i + 1) == '*') {
                int end = query.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                current = null;
            } else if (c == '\'') {
                i = skipQuoted(query, i, '\'');
                current = null;
            } else if (c == '"' || Character.isLetter(c) || c == '_') {
                String part;
                if (c == '"') {
                    int end = skipQuoted(query, i, '"');
                    part = query.substring(i + 1, Math.max(i + 1, end - 1)).replace("\"\"", "\"");
                    i = end;
                } else {
                    int start = i;
                    while (i < n && isIdentifierChar(query.charAt(i))) {
                        i++;
                    }
                    part = query.substring(start, i);
                }
                part = part.toLowerCase(Locale.ROOT);
                if (dotted && current != null) {
                    current.add(part);
                } else {
                    current = new ArrayList<>(List.of(part));
                    names.add(current);
                }
                dotted = false;
                continue;
            } else if (c == '.' && current != null) {
                dotted = true;
                i++;
                continue;
            } else if (Character.isWhitespace(c)) {
                // A name may span whitespace around its dots (db . t); anything else between parts ends it.
                i++;
                continue;
            } else {
                current = null;
                i++;
            }
            dotted = false;
        }
        return names;
    }

    /** The index just past the quoted text starting at {@code start}, a doubled {@code quote} inside it kept. */
    private static int skipQuoted(String query, int start, char quote) {
        int i = start + 1;
        while (i < query.length()) {
            if (query.charAt(i) == quote) {
                if (i + 1 < query.length() && query.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return query.length();
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    static String quote(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }
}
