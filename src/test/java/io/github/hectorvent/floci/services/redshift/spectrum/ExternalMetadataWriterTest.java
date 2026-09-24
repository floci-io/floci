package io.github.hectorvent.floci.services.redshift.spectrum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

class ExternalMetadataWriterTest {
    private static final ExternalSchemaBinding BINDING = new ExternalSchemaBinding("000000000000",
            "000000000000:c", "dev", "analytics", "lake", "arn:aws:iam::000000000000:role/R");
    private final ExternalMetadataWriter writer = new ExternalMetadataWriter(mock(GlueService.class), new ObjectMapper());

    @Test
    void refreshSqlWritesGlueTablesColumnsAndPartitionMetadata() {
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

        assertThat(sql, containsString("INSERT INTO floci_internal.external_schemas"));
        assertThat(sql, containsString("{\"IAM_ROLE\":\"arn:aws:iam::000000000000:role/R\"}"));
        assertThat(sql, containsString("'s3://bucket/o''brien/'"));
        assertThat(sql, containsString("'id', 'int', 1, 0"));
        assertThat(sql, containsString("'dt', 'string', 2, 1"));
        assertThat(sql, containsString("[\"2024-01-01\"]"));
    }

    @Test
    void purgeDeletesRowsFromEveryMetadataTable() {
        String sql = writer.purgeSql("analytics");
        assertThat(sql, containsString("external_schemas WHERE schemaname = 'analytics'"));
        assertThat(sql, containsString("external_tables WHERE schemaname = 'analytics'"));
        assertThat(sql, containsString("external_columns WHERE schemaname = 'analytics'"));
        assertThat(sql, containsString("external_partitions WHERE schemaname = 'analytics'"));
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
}
