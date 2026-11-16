package io.floci.aws.services.athena;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.GlueTableResolver;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.Partition;
import io.floci.aws.services.glue.model.StorageDescriptor;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.s3.S3Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs an {@link AthenaHiveDdl} statement against the Glue Data Catalog, the way Athena runs it:
 * {@code DROP TABLE} removes only a Hive table's metadata, and an Iceberg table's data with it, as
 * Athena treats Iceberg tables as managed; {@code MSCK REPAIR TABLE}
 * adds the Hive-style {@code key=value} partitions it finds under the table's location and never
 * removes any; {@code IF [NOT] EXISTS} turns a duplicate or missing table or partition from an
 * error into a no-op. An Iceberg {@code CREATE TABLE} goes to {@link AthenaIcebergStatements}.
 */
final class AthenaHiveDdlExecutor {

    private static final String HIVE_ESCAPED_CHARACTERS = "\"#%'*/:=?\\{[]^";

    private final GlueService glueService;
    private final S3Service s3Service;
    private final AthenaIcebergStatements icebergStatements;

    AthenaHiveDdlExecutor(GlueService glueService, S3Service s3Service, AthenaIcebergStatements icebergStatements) {
        this.glueService = glueService;
        this.s3Service = s3Service;
        this.icebergStatements = icebergStatements;
    }

    /**
     * Runs {@code statement}, a table it names without a database being in {@code contextDatabase}.
     * Returns the result rows of {@code SHOW PARTITIONS}, one {@code key=value[/key=value...]} string
     * each, and no rows for every other statement.
     */
    List<String> execute(AthenaHiveDdl.Statement statement, String contextDatabase) {
        return execute(statement, AthenaService.DEFAULT_CATALOG, contextDatabase);
    }

    /**
     * Runs {@code statement} in the context catalog {@code contextCatalog}. In an S3 Tables catalog
     * ({@code s3tablescatalog/<bucket>}), which holds Iceberg tables only, a {@code CREATE TABLE} creates one in the
     * table bucket and {@code DROP TABLE} deletes it; Hive DDL, {@code OPTIMIZE} and {@code VACUUM} are refused, as on
     * Athena.
     */
    List<String> execute(AthenaHiveDdl.Statement statement, String contextCatalog, String contextDatabase) {
        String database = statement.ref().database() != null ? statement.ref().database() : contextDatabase;
        String table = statement.ref().table();
        String bucket = AthenaIcebergStatements.s3TablesBucket(contextCatalog);
        if (bucket != null) {
            return switch (statement) {
                case AthenaHiveDdl.CreateIcebergTable create -> {
                    icebergStatements.createTable(AthenaIcebergStatements.restCatalog(bucket), database, create);
                    yield List.of();
                }
                case AthenaHiveDdl.DropTable drop -> {
                    icebergStatements.dropS3Table(bucket, database, table, drop.ifExists());
                    yield List.of();
                }
                case AthenaHiveDdl.Vacuum vacuum -> throw maintainedByS3();
                case AthenaHiveDdl.Optimize optimize -> throw maintainedByS3();
                default -> throw new AwsException("InvalidRequestException", "Catalog " + contextCatalog
                        + " holds Iceberg tables only; this statement is not supported in it", 400);
            };
        }
        return switch (statement) {
            case AthenaHiveDdl.CreateTable create -> {
                createTable(database, create);
                yield List.of();
            }
            case AthenaHiveDdl.DropTable drop -> {
                dropTable(database, table, drop.ifExists());
                yield List.of();
            }
            case AthenaHiveDdl.AddPartitions add -> {
                addPartitions(database, existingTable(database, table), add);
                yield List.of();
            }
            case AthenaHiveDdl.DropPartitions drop -> {
                dropPartitions(database, existingTable(database, table), drop);
                yield List.of();
            }
            case AthenaHiveDdl.RepairTable repair -> {
                repair(database, existingTable(database, table));
                yield List.of();
            }
            case AthenaHiveDdl.ShowPartitions show -> partitionNames(database, existingTable(database, table));
            case AthenaHiveDdl.CreateIcebergTable create -> {
                icebergStatements.createTable(database, create);
                yield List.of();
            }
            case AthenaHiveDdl.Vacuum vacuum ->
                    throw new IllegalStateException("VACUUM runs on the query engine, not against the catalog alone");
            case AthenaHiveDdl.Optimize optimize ->
                    throw new AwsException("InvalidRequestException", "OPTIMIZE is not supported yet", 400);
        };
    }

    /**
     * Athena's refusal of {@code OPTIMIZE} and {@code VACUUM} on S3 Tables, whose compaction and snapshots S3
     * manages.
     */
    private static AwsException maintainedByS3() {
        return new AwsException("InvalidRequestException",
                "OPTIMIZE and VACUUM are not supported on S3 Tables: S3 manages their compaction and snapshots", 400);
    }

    private void createTable(String database, AthenaHiveDdl.CreateTable create) {
        create.table().setDatabaseName(database);
        try {
            glueService.createTable(database, create.table());
        } catch (AwsException e) {
            if (!create.ifNotExists() || !"AlreadyExistsException".equals(e.getErrorCode())) {
                throw e;
            }
        }
    }

    private void dropTable(String database, String table, boolean ifExists) {
        Table found = findTable(database, table);
        if (found == null) {
            if (ifExists) {
                return;
            }
            throw tableNotFound(database, table);
        }
        try {
            glueService.deleteTable(database, table);
        } catch (AwsException e) {
            // Another drop can remove the table between the lookup and the delete.
            if (!ifExists || !"EntityNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            return;
        }
        if (GlueTableResolver.isIcebergTable(found)) {
            // Athena treats an Iceberg table as managed: dropping it removes its data too.
            icebergStatements.purge(found);
        }
    }

    private void addPartitions(String database, Table table, AthenaHiveDdl.AddPartitions add) {
        for (AthenaHiveDdl.PartitionSpec spec : add.partitions()) {
            List<String> values = orderedValues(table, spec);
            createPartition(database, table, values,
                    spec.location() != null ? spec.location() : defaultLocation(table, values), add.ifNotExists());
        }
    }

    private void dropPartitions(String database, Table table, AthenaHiveDdl.DropPartitions drop) {
        for (AthenaHiveDdl.PartitionSpec spec : drop.partitions()) {
            List<String> values = orderedValues(table, spec);
            try {
                glueService.deletePartition(database, table.getName(), values);
            } catch (AwsException e) {
                if (!drop.ifExists() || !"EntityNotFoundException".equals(e.getErrorCode())) {
                    throw e;
                }
            }
        }
    }

    /** Adds every complete {@code key=value/...} folder path under the table's location that has no partition yet. */
    private void repair(String database, Table table) {
        List<Column> keys = partitionKeys(table);
        TableLocation root = TableLocation.of(table.getStorageDescriptor() != null
                ? table.getStorageDescriptor().getLocation() : null);
        if (keys.isEmpty() || root == null) {
            return;
        }
        Set<List<String>> existing = glueService.getPartitions(database, table.getName()).stream()
                .map(Partition::getValues)
                .collect(Collectors.toCollection(HashSet::new));
        // Each new partition's values and the folder they were found in, kept as spelled in S3.
        Map<List<String>, String> found = new LinkedHashMap<>();
        root.forEachObject(s3Service, object -> {
            String relative = object.getKey().substring(root.prefix().length());
            List<String> values = hivePartitionValues(relative, keys);
            if (values != null && !existing.contains(values)) {
                found.putIfAbsent(values, "s3://" + root.bucket() + "/" + root.prefix()
                        + String.join("/", Arrays.asList(relative.split("/", -1)).subList(0, keys.size())) + "/");
            }
        });
        found.forEach((values, location) -> createPartition(database, table, values, location, true));
    }

    private void createPartition(String database, Table table, List<String> values, String location,
                                 boolean tolerateExisting) {
        Partition partition = new Partition();
        partition.setValues(values);
        partition.setStorageDescriptor(partitionDescriptor(table, location));
        try {
            glueService.createPartition(database, table.getName(), partition);
        } catch (AwsException e) {
            if (!tolerateExisting || !"AlreadyExistsException".equals(e.getErrorCode())) {
                throw e;
            }
        }
    }

    /**
     * The partition values the leading folders of {@code relativeKey} name, one {@code key=value}
     * folder per partition key in order, or null when they do not. A key is matched without regard to
     * case and a value is unescaped as Hive escapes folder names ({@code %2F} for {@code /}).
     */
    static List<String> hivePartitionValues(String relativeKey, List<Column> keys) {
        String[] segments = relativeKey.split("/", -1);
        if (segments.length <= keys.size()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            String segment = segments[i];
            int equals = segment.indexOf('=');
            if (equals <= 0 || !segment.substring(0, equals).equalsIgnoreCase(keys.get(i).getName())) {
                return null;
            }
            try {
                values.add(URLDecoder.decode(segment.substring(equals + 1).replace("+", "%2B"), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException malformedEscape) {
                // A folder name Hive could not have written is not a partition folder.
                return null;
            }
        }
        return values;
    }

    private List<String> partitionNames(String database, Table table) {
        List<Column> keys = partitionKeys(table);
        List<String> names = new ArrayList<>();
        for (Partition partition : glueService.getPartitions(database, table.getName())) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < keys.size() && i < partition.getValues().size(); i++) {
                parts.add(keys.get(i).getName() + "=" + escapePathName(partition.getValues().get(i)));
            }
            names.add(String.join("/", parts));
        }
        return names;
    }

    /** The spec's values in the table's partition-key order; the spec has to name every key once. */
    private static List<String> orderedValues(Table table, AthenaHiveDdl.PartitionSpec spec) {
        List<Column> keys = partitionKeys(table);
        Map<String, String> values = spec.values();
        Set<String> named = values.keySet().stream().map(k -> k.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Set<String> declared = keys.stream().map(k -> k.getName().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        if (keys.isEmpty() || !named.equals(declared) || named.size() != values.size()) {
            throw new AwsException("InvalidRequestException", "The partition specification " + values.keySet()
                    + " does not match the partition keys of table " + table.getName() + ": " + declared, 400);
        }
        List<String> ordered = new ArrayList<>();
        for (Column key : keys) {
            values.forEach((name, value) -> {
                if (name.equalsIgnoreCase(key.getName())) {
                    ordered.add(value);
                }
            });
        }
        return ordered;
    }

    /** Athena's location for a partition added without one: {@code <table location>/key=value/...}. */
    static String defaultLocation(Table table, List<String> values) {
        String location = table.getStorageDescriptor() != null && table.getStorageDescriptor().getLocation() != null
                ? table.getStorageDescriptor().getLocation() : "";
        StringBuilder path = new StringBuilder(location.endsWith("/") ? location : location + "/");
        List<Column> keys = partitionKeys(table);
        for (int i = 0; i < keys.size(); i++) {
            path.append(keys.get(i).getName()).append('=').append(escapePathName(values.get(i))).append('/');
        }
        return path.toString();
    }

    /**
     * {@code value} as Hive writes it in a partition folder name and in {@code SHOW PARTITIONS}: the
     * characters Hive's {@code FileUtils.escapePathName} escapes become {@code %XX}.
     */
    static String escapePathName(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c < 0x20 || c == 0x7F || HIVE_ESCAPED_CHARACTERS.indexOf(c) >= 0) {
                escaped.append('%').append(String.format(Locale.ROOT, "%02X", (int) c));
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /** The table's storage descriptor with the partition's own location. */
    private static StorageDescriptor partitionDescriptor(Table table, String location) {
        StorageDescriptor source = table.getStorageDescriptor();
        StorageDescriptor descriptor = new StorageDescriptor();
        if (source != null) {
            descriptor.setColumns(source.getColumns());
            descriptor.setInputFormat(source.getInputFormat());
            descriptor.setOutputFormat(source.getOutputFormat());
            descriptor.setCompressed(source.getCompressed());
            descriptor.setNumberOfBuckets(source.getNumberOfBuckets());
            descriptor.setSerdeInfo(source.getSerdeInfo());
            descriptor.setBucketColumns(source.getBucketColumns());
            descriptor.setSortColumns(source.getSortColumns());
            descriptor.setParameters(source.getParameters());
            descriptor.setSkewedInfo(source.getSkewedInfo());
            descriptor.setStoredAsSubDirectories(source.getStoredAsSubDirectories());
            descriptor.setSchemaReference(source.getSchemaReference());
            descriptor.setAdditionalLocations(source.getAdditionalLocations());
        }
        descriptor.setLocation(location);
        return descriptor;
    }

    private static List<Column> partitionKeys(Table table) {
        return table.getPartitionKeys() == null ? List.of() : table.getPartitionKeys();
    }

    private Table existingTable(String database, String table) {
        Table found = findTable(database, table);
        if (found == null) {
            throw tableNotFound(database, table);
        }
        return found;
    }

    private Table findTable(String database, String table) {
        try {
            return glueService.getTable(database, table);
        } catch (AwsException e) {
            if ("EntityNotFoundException".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    private static AwsException tableNotFound(String database, String table) {
        return new AwsException("InvalidRequestException", "Table not found: " + database + "." + table, 400);
    }
}
