package io.floci.aws.services.glue.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.StorageDescriptor;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.s3.S3Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The Glue table AWS Glue shows for an Iceberg table, built from the table's metadata file. */
public final class IcebergGlueTables {

    static final String PARAM_TABLE_TYPE = "table_type";
    static final String PARAM_METADATA_LOCATION = "metadata_location";
    static final String ICEBERG = "ICEBERG";
    private static final Pattern S3_LOCATION = Pattern.compile("^s3[an]?://([^/]+)/?(.*)$");

    private IcebergGlueTables() {
    }

    /**
     * The Glue table holding an Iceberg table: {@code table_type} and {@code metadata_location} in its parameters,
     * its location and its current schema's columns, with their Hive types and Iceberg field ids, in its storage
     * descriptor, as AWS Glue keeps one.
     */
    public static Table glueTable(String name, JsonNode metadata, String metadataLocation) {
        Table table = new Table();
        table.setName(name);
        table.setTableType("EXTERNAL_TABLE");
        Map<String, String> parameters = new HashMap<>();
        parameters.put(PARAM_TABLE_TYPE, ICEBERG);
        parameters.put(PARAM_METADATA_LOCATION, metadataLocation);
        table.setParameters(parameters);
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setLocation(metadata.path("location").asText());
        List<Column> columns = new ArrayList<>();
        for (JsonNode field : IcebergTableMetadata.currentSchema(metadata).path("fields")) {
            Column column = new Column(field.path("name").asText(), IcebergTypes.hiveType(field.path("type")));
            if (field.hasNonNull("doc")) {
                column.setComment(field.get("doc").asText());
            }
            column.setParameters(Map.of(
                    "iceberg.field.id", field.path("id").asText(),
                    "iceberg.field.optional", String.valueOf(!field.path("required").asBoolean(false)),
                    "iceberg.field.current", "true"));
            columns.add(column);
        }
        descriptor.setColumns(columns);
        table.setStorageDescriptor(descriptor);
        return table;
    }

    /**
     * Reads the metadata file at {@code metadataLocation}, an {@code s3://} object.
     *
     * @throws IllegalArgumentException when the location is not an S3 object
     */
    public static JsonNode readMetadata(S3Service s3, ObjectMapper mapper, String metadataLocation) throws IOException {
        S3Location location = s3Location(metadataLocation);
        try (InputStream stream = s3.openObjectStream(location.bucket(), location.key(), null)) {
            return mapper.readTree(stream);
        }
    }

    /** {@code location} without a trailing slash. */
    static String stripTrailingSlash(String location) {
        return location != null && location.endsWith("/") ? location.substring(0, location.length() - 1) : location;
    }

    /** The bucket and key an {@code s3://} (or {@code s3a://}, {@code s3n://}) object location names. */
    static S3Location s3Location(String location) {
        Matcher matcher = S3_LOCATION.matcher(location == null ? "" : location);
        if (!matcher.matches() || matcher.group(2).isEmpty()) {
            throw new IllegalArgumentException("Not an S3 object location: " + location);
        }
        return new S3Location(matcher.group(1), matcher.group(2));
    }

    record S3Location(String bucket, String key) {
    }
}
