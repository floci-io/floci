package io.floci.aws.services.glue.iceberg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.model.Database;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The catalog's commit paths when Glue refuses the write. */
class GlueIcebergCatalogTest {

    private static final String CREATE_COMMIT = """
            {"requirements":[{"type":"assert-create"}],"updates":[
             {"action":"add-schema","schema":{"type":"struct","schema-id":0,"fields":[
               {"id":1,"name":"id","required":false,"type":"long"}]}},
             {"action":"set-current-schema","schema-id":-1},
             {"action":"add-spec","spec":{"spec-id":0,"fields":[]}},{"action":"set-default-spec","spec-id":-1},
             {"action":"add-sort-order","sort-order":{"order-id":0,"fields":[]}},
             {"action":"set-default-sort-order","sort-order-id":-1},
             {"action":"set-location","location":"s3://lake-bucket/lake/events"}]}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private GlueService glue;
    private S3Service s3;
    private GlueIcebergCatalog catalog;

    @BeforeEach
    void setUp() {
        glue = Mockito.mock(GlueService.class);
        s3 = Mockito.mock(S3Service.class);
        catalog = new GlueIcebergCatalog(glue, s3, new RegionResolver("us-east-1", "000000000000"), mapper,
                null, null, Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneOffset.UTC));
        Database database = new Database("lake");
        database.setLocationUri("s3://lake-bucket/lake");
        when(glue.getDatabase("lake")).thenReturn(database);
    }

    private void tableMissing() {
        when(glue.getTable("lake", "events")).thenThrow(new AwsException("EntityNotFoundException",
                "Table not found: lake.events", 400));
    }

    private void tableExists() {
        ObjectNode metadata = IcebergTableMetadata.empty(1_000L);
        try {
            IcebergTableMetadata.apply(metadata, mapper.readTree(CREATE_COMMIT).path("updates"), 2_000L);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String location = "s3://lake-bucket/lake/events/metadata/00000-a.metadata.json";
        Table table = new Table();
        table.setName("events");
        table.setVersionId("1");
        Map<String, String> parameters = new HashMap<>();
        parameters.put("table_type", "ICEBERG");
        parameters.put("metadata_location", location);
        table.setParameters(parameters);
        when(glue.getTable("lake", "events")).thenReturn(table);
        when(s3.openObjectStream("lake-bucket", "lake/events/metadata/00000-a.metadata.json", null))
                .thenAnswer(invocation -> new ByteArrayInputStream(
                        mapper.writeValueAsString(metadata).getBytes(StandardCharsets.UTF_8)));
    }

    private String writtenMetadataKey() {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(s3).putObject(eq("lake-bucket"), key.capture(), any(), eq("application/json"), isNull());
        return key.getValue();
    }

    @Test
    void aCommitGlueRefusesAsConcurrentIsACommitFailureAndItsMetadataFileIsRemoved() throws Exception {
        tableExists();
        doThrow(new AwsException("ConcurrentModificationException", "concurrent", 400))
                .when(glue).updateTable(eq("lake"), any(), eq("1"), eq(false));

        IcebergRestException e = assertThrows(IcebergRestException.class, () -> catalog.commitTable(":", "lake",
                "events", mapper.readTree(IcebergCommits.snapshotCommit(7, null, 1))));

        assertEquals("CommitFailedException", e.type());
        String written = writtenMetadataKey();
        verify(s3).deleteObject("lake-bucket", written);
    }

    @Test
    void aStagedCreateIsAConflictOnlyWhenTheTableAppearedMeanwhile() throws Exception {
        tableMissing();
        doThrow(new AwsException("AlreadyExistsException", "Table already exists", 400))
                .when(glue).createTable(eq("lake"), any());

        IcebergRestException raced = assertThrows(IcebergRestException.class,
                () -> catalog.commitTable(":", "lake", "events", mapper.readTree(CREATE_COMMIT)));

        assertEquals("CommitFailedException", raced.type());
        String written = writtenMetadataKey();
        verify(s3).deleteObject("lake-bucket", written);
    }

    @Test
    void aStagedCreateIntoANamespaceThatWentAwayAnswersThatInsteadOfAConflict() throws Exception {
        tableMissing();
        doThrow(new AwsException("EntityNotFoundException", "Database not found: lake", 400))
                .when(glue).createTable(eq("lake"), any());

        IcebergRestException gone = assertThrows(IcebergRestException.class,
                () -> catalog.commitTable(":", "lake", "events", mapper.readTree(CREATE_COMMIT)));

        assertEquals("NoSuchNamespaceException", gone.type());
        String written = writtenMetadataKey();
        verify(s3).deleteObject("lake-bucket", written);
    }
}
