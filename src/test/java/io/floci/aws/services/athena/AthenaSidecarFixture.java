package io.floci.aws.services.athena;

import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.Database;
import io.floci.aws.services.glue.model.Partition;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.s3.S3Service;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * A bucket and one Glue database of tables for the Athena sidecar tests, queried through the real floci-duck sidecar
 * with the views {@link GlueViewDdlBuilder} builds for them.
 *
 * <p>The views come from the builder over these tables alone rather than from Athena end to end: the sidecar
 * application is shared with other test classes, and Athena builds views for every Glue table it holds, so another
 * class's tables would be in every query. Each test creates its own fixture and closes it, which deletes what it put.
 */
final class AthenaSidecarFixture implements AutoCloseable {

    private final FlociDuckClient duck;
    private final S3Service s3Service;
    private final String bucket;
    private final String schema;
    private final List<String> keys = new ArrayList<>();
    private final List<Table> tables = new ArrayList<>();
    private final Map<String, List<Partition>> partitions = new HashMap<>();

    /** Creates a bucket and names a database, both unique to this fixture and starting with {@code name}. */
    AthenaSidecarFixture(FlociDuckClient duck, S3Service s3Service, String name) {
        this.duck = duck;
        this.s3Service = s3Service;
        this.bucket = "athena-" + name + "-" + System.nanoTime();
        this.schema = name + "_" + System.nanoTime();
        s3Service.createBucket(bucket, "us-east-1");
    }

    String bucket() {
        return bucket;
    }

    /** Puts {@code content} at {@code key} in the bucket. */
    void put(String key, byte[] content) {
        s3Service.putObject(bucket, key, content, "application/octet-stream", null);
        keys.add(key);
    }

    void put(String key, String content) {
        put(key, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Records an object something other than {@link #put} wrote to the bucket, so that it is deleted too. */
    void wrote(String key) {
        keys.add(key);
    }

    /** Adds {@code table} to the database. */
    void add(Table table) {
        tables.add(table);
    }

    /** Registers {@code partition} for {@code table}. */
    void register(String table, Partition partition) {
        partitions.computeIfAbsent(table, ignored -> new ArrayList<>()).add(partition);
    }

    /** A view builder over the database's tables and their registered partitions. */
    GlueViewDdlBuilder builder() {
        GlueService glue = Mockito.mock(GlueService.class);
        Database database = new Database();
        database.setName(schema);
        when(glue.getDatabases()).thenReturn(List.of(database));
        when(glue.getTables(schema)).thenReturn(tables);
        when(glue.getPartitions(eq(schema), anyString()))
                .thenAnswer(call -> partitions.getOrDefault(call.getArgument(1), List.of()));
        return new GlueViewDdlBuilder(glue, s3Service);
    }

    /** Runs {@code sql} with the views Athena builds for it, each value as text ({@code "null"} for NULL). */
    List<Map<String, String>> query(String sql) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map<String, Object> row : duck.query(sql, builder().build(null, sql))) {
            Map<String, String> values = new LinkedHashMap<>();
            row.forEach((column, value) -> values.put(column, String.valueOf(value)));
            rows.add(values);
        }
        return rows;
    }

    /** The qualified name of {@code table}'s view. */
    String view(String table) {
        return "\"" + schema + "\".\"" + table + "\"";
    }

    /** Deletes the objects put in the bucket, then the bucket. */
    @Override
    public void close() {
        for (String key : keys) {
            s3Service.deleteObject(bucket, key);
        }
        s3Service.deleteBucket(bucket);
    }

    static Column column(String name, String type) {
        Column column = new Column();
        column.setName(name);
        column.setType(type);
        return column;
    }
}
