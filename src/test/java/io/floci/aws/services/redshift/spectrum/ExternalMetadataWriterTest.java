package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.Partition;
import io.floci.aws.services.glue.model.StorageDescriptor;
import io.floci.aws.services.glue.model.Table;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ExternalMetadataWriterTest {
    private static final ExternalSchemaBinding BINDING = new ExternalSchemaBinding("000000000000",
            "000000000000:c", "dev", "analytics", "lake", "arn:aws:iam::000000000000:role/R");
    private final ExternalMetadataWriter writer = new ExternalMetadataWriter(mock(GlueService.class), new ObjectMapper());

    @Test
    void refreshSqlCallsPrivilegedWriterWithGlueMetadataPayload() {
        Column id = new Column();
        id.setName("id");
        id.setType("int");
        Column dt = new Column();
        dt.setName("dt");
        dt.setType("string");
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setLocation("s3://bucket/o'brien/");
        descriptor.setColumns(List.of(id));
        Table table = new Table();
        table.setName("events");
        table.setStorageDescriptor(descriptor);
        table.setPartitionKeys(List.of(dt));
        table.setParameters(Map.of("EXTERNAL", "TRUE"));
        Partition partition = new Partition();
        partition.setValues(List.of("2024-01-01"));

        String sql = writer.refreshSql(BINDING, List.of(table), Map.of("events", List.of(partition)));

        assertThat(sql, containsString("SELECT floci_internal.refresh_external_catalog(E'analytics'"));
        assertThat(sql, containsString("IAM_ROLE"));
        assertThat(sql, containsString("\"location\":\"s3://bucket/o''brien/\""));
        assertThat(sql, containsString("\"columnname\":\"id\",\"external_type\":\"int\""));
        assertThat(sql, containsString("\"columnname\":\"dt\",\"external_type\":\"string\""));
        assertThat(sql, containsString("2024-01-01"));
        assertThat(sql.contains("INSERT INTO floci_internal."), equalTo(false));
    }

    @Test
    void kinesisSchemaListsAsKindNineWithItsRoleAndNoTables() {
        KinesisSchemaBinding kinesis = new KinesisSchemaBinding("000000000000", "000000000000:c", "dev", "kds",
                "us-west-2", "arn:aws:iam::000000000000:role/R");

        String sql = writer.refreshKinesisSql(kinesis);

        assertThat(sql, containsString("SELECT floci_internal.refresh_external_catalog(E'kds'"));
        assertThat(sql, containsString("\"eskind\":9"));
        assertThat(sql, containsString("\"databasename\":\"\""));
        assertThat(sql, containsString("IAM_ROLE"));
        assertThat(sql, containsString("\"tables\":[]"));
    }

    @Test
    void mskSchemaListsAsKindTenWithItsOptionsAndNoTables() throws Exception {
        String clusterArn = "arn:aws:kafka:us-east-1:000000000000:cluster/orders/abc-123";
        MskSchemaBinding msk = new MskSchemaBinding("000000000000", "000000000000:c", "dev", "msk",
                "aws", "arn:aws:iam::000000000000:role/R", "IAM", clusterArn);

        String sql = writer.refreshMskSql(msk);
        JsonNode payload = refreshPayload("msk", sql);
        JsonNode options = new ObjectMapper().readTree(payload.path("esoptions").asText());

        assertEquals(10, payload.path("eskind").asInt());
        assertEquals("", payload.path("databasename").asText());
        assertEquals("arn:aws:iam::000000000000:role/R", options.path("IAM_ROLE").asText());
        assertEquals("IAM", options.path("AUTHENTICATION").asText());
        assertEquals(clusterArn, options.path("CLUSTER_ARN").asText());
        assertEquals("aws", options.path("PARTITION").asText());
        assertEquals(0, payload.path("tables").size());
        assertEquals(0, payload.path("columns").size());
        assertEquals(0, payload.path("partitions").size());
    }

    @Test
    void mskSchemaWithoutPartitionOmitsPartitionFromEsoptions() throws Exception {
        MskSchemaBinding msk = new MskSchemaBinding("000000000000", "000000000000:c", "dev", "msk",
                null, "arn:aws:iam::000000000000:role/R", "NONE",
                "arn:aws:kafka:us-east-1:000000000000:cluster/orders/abc-123");

        String sql = writer.refreshMskSql(msk);
        JsonNode payload = refreshPayload("msk", sql);
        JsonNode options = new ObjectMapper().readTree(payload.path("esoptions").asText());

        assertEquals(10, payload.path("eskind").asInt());
        assertEquals("NONE", options.path("AUTHENTICATION").asText());
        assertThat(options.has("PARTITION"), equalTo(false));
    }

    @Test
    void glueSchemaPayloadLeavesTheKindToTheViewDefault() {
        String sql = writer.refreshSql(BINDING, List.of(), Map.of());

        assertThat(sql.contains("eskind"), equalTo(false));
    }

    @Test
    void refreshSqlUsesPartitionStorageSettings() throws Exception {
        StorageDescriptor tableDescriptor = new StorageDescriptor();
        tableDescriptor.setSerdeInfo(serdeInfo("table-serde", Map.of("source", "table")));
        tableDescriptor.setCompressed(false);
        Table table = new Table();
        table.setName("events");
        table.setStorageDescriptor(tableDescriptor);

        StorageDescriptor partitionDescriptor = new StorageDescriptor();
        partitionDescriptor.setSerdeInfo(serdeInfo("partition-serde", Map.of("source", "partition")));
        partitionDescriptor.setCompressed(true);
        Partition partition = new Partition();
        partition.setValues(List.of("2024-01-01"));
        partition.setStorageDescriptor(partitionDescriptor);

        String sql = writer.refreshSql(BINDING, List.of(table), Map.of("events", List.of(partition)));
        JsonNode partitionRow = refreshPayload(sql).path("partitions").get(0);

        assertEquals("partition-serde", partitionRow.path("serialization_lib").asText());
        assertEquals("{\"source\":\"partition\"}", partitionRow.path("serde_parameters").asText());
        assertEquals(1, partitionRow.path("compressed").asInt());
    }

    @Test
    void refreshSqlFallsBackToTableSettingsOmittedFromPartitionDescriptor() throws Exception {
        StorageDescriptor tableDescriptor = new StorageDescriptor();
        tableDescriptor.setSerdeInfo(serdeInfo("table-serde", Map.of("source", "table")));
        tableDescriptor.setCompressed(true);
        Table table = new Table();
        table.setName("events");
        table.setStorageDescriptor(tableDescriptor);

        StorageDescriptor partitionDescriptor = new StorageDescriptor();
        partitionDescriptor.setLocation("s3://bucket/events/date=2024-01-01/");
        Partition partition = new Partition();
        partition.setValues(List.of("2024-01-01"));
        partition.setStorageDescriptor(partitionDescriptor);

        String sql = writer.refreshSql(BINDING, List.of(table), Map.of("events", List.of(partition)));
        JsonNode partitionRow = refreshPayload(sql).path("partitions").get(0);

        assertEquals("table-serde", partitionRow.path("serialization_lib").asText());
        assertEquals("{\"source\":\"table\"}", partitionRow.path("serde_parameters").asText());
        assertEquals(1, partitionRow.path("compressed").asInt());
    }

    @Test
    void refreshSqlPreservesExplicitlyEmptyPartitionSerdeParameters() throws Exception {
        StorageDescriptor tableDescriptor = new StorageDescriptor();
        tableDescriptor.setSerdeInfo(serdeInfo("table-serde", Map.of("field.delim", "|")));
        Table table = new Table();
        table.setName("events");
        table.setStorageDescriptor(tableDescriptor);

        StorageDescriptor partitionDescriptor = new StorageDescriptor();
        partitionDescriptor.setSerdeInfo(serdeInfo("partition-serde", Map.of()));
        Partition partition = new Partition();
        partition.setValues(List.of("2024-01-01"));
        partition.setStorageDescriptor(partitionDescriptor);

        String sql = writer.refreshSql(BINDING, List.of(table), Map.of("events", List.of(partition)));
        JsonNode partitionRow = refreshPayload(sql).path("partitions").get(0);

        assertEquals("{}", partitionRow.path("serde_parameters").asText());
    }

    @Test
    void refreshPreservesBackslashesAndApostrophesInJsonPayload() throws Exception {
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setLocation("s3://bucket/o'brien\\new/");
        Table table = new Table();
        table.setName("events");
        table.setStorageDescriptor(descriptor);

        String sql = writer.refreshSql(BINDING, List.of(table), Map.of());

        assertEquals(descriptor.getLocation(), refreshPayload(sql).path("tables").get(0).path("location").asText());
    }

    @Test
    void purgeEscapesApostrophesAndBackslashes() {
        assertEquals("SELECT floci_internal.purge_external_schema(E'o''brien\\\\lake')",
                writer.purgeSql("o'brien\\lake"));
    }

    @Test
    void purgePreservesNullAsAnEmptySchemaName() {
        assertEquals("SELECT floci_internal.purge_external_schema(E'')", writer.purgeSql(null));
    }

    @Test
    void purgeCallsPrivilegedWriterForOneSchema() {
        String sql = writer.purgeSql("analytics");
        assertThat(sql, containsString("SELECT floci_internal.purge_external_schema(E'analytics')"));
        assertThat(sql.contains("DELETE FROM floci_internal."), equalTo(false));
    }

    @Test
    void purgeToleratesMissingBootstrapTables() {
        BackendSql missingTable = new BackendSql() {
            @Override
            public void execute(String sql) {
                throw new SpectrumReadException("42P01", "metadata table missing");
            }

            @Override
            public long copyIn(String copySql, InputStream data) {
                return 0;
            }
        };
        assertDoesNotThrow(() -> writer.purge(missingTable, "analytics"));
    }

    @Test
    void refreshPropagatesMissingBootstrapTables() {
        BackendSql missingTable = new BackendSql() {
            @Override
            public void execute(String sql) {
                throw new SpectrumReadException("42P01", "metadata table missing");
            }

            @Override
            public long copyIn(String copySql, InputStream data) {
                return 0;
            }
        };

        SpectrumReadException exception = assertThrows(SpectrumReadException.class,
                () -> writer.refresh(missingTable, BINDING.accountId(), BINDING));

        assertEquals("42P01", exception.sqlState());
    }

    private static StorageDescriptor.SerDeInfo serdeInfo(String library, Map<String, String> parameters) {
        StorageDescriptor.SerDeInfo serdeInfo = new StorageDescriptor.SerDeInfo();
        serdeInfo.setSerializationLibrary(library);
        serdeInfo.setParameters(parameters);
        return serdeInfo;
    }

    private JsonNode refreshPayload(String sql) throws Exception {
        return refreshPayload("analytics", sql);
    }

    private JsonNode refreshPayload(String schemaName, String sql) throws Exception {
        String prefix = "SELECT floci_internal.refresh_external_catalog(E'" + schemaName + "', E'";
        String payloadEnd = "'::jsonb)";
        String escapedJson = sql.substring(prefix.length(), sql.lastIndexOf(payloadEnd));
        return new ObjectMapper().readTree(escapedJson.replace("''", "'").replace("\\\\", "\\"));
    }
}
