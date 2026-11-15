package io.floci.aws.services.glue.iceberg;

import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AWS Glue's Iceberg REST endpoint as a REST catalog client (Spark, PyIceberg) uses it, signed for {@code glue}: a
 * staged create committed with {@code assert-create}, as DuckDB and Spark create tables, then snapshot commits that
 * move the Glue table's {@code metadata_location}, one based on a stale snapshot refused.
 */
@QuarkusTest
class GlueIcebergRestIntegrationTest {

    private static final String PREFIX = "/iceberg/v1/catalogs/000000000000";
    private static final String SCHEMA = """
            {"type":"struct","schema-id":0,"fields":[{"id":1,"name":"id","required":false,"type":"long"},
             {"id":2,"name":"name","required":false,"type":"string"}]}""";

    private final AwsRequestSigner glue = AwsRequestSigner.signedAs("test", "test", "glue");

    @TestHTTPResource("/")
    URI endpoint;

    @Inject
    S3Service s3Service;

    @Inject
    GlueService glueService;

    private String bucket;
    private String namespace;

    @BeforeEach
    void createBucket() {
        long suffix = System.nanoTime();
        bucket = "iceberg-rest-" + suffix;
        namespace = "lake_" + suffix;
        s3Service.createBucket(bucket, "us-east-1");
    }

    @AfterEach
    void dropNamespace() {
        send(glue, "DELETE", PREFIX + "/namespaces/" + namespace + "/tables/events", null);
        send(glue, "DELETE", PREFIX + "/namespaces/" + namespace, null);
    }

    private String tablePath() {
        return PREFIX + "/namespaces/" + namespace + "/tables/events";
    }

    private void createNamespace() {
        send(glue, "POST", PREFIX + "/namespaces", "{\"namespace\":[\"" + namespace + "\"],\"properties\":"
                + "{\"location\":\"s3://" + bucket + "/lake\",\"owner\":\"data\"}}").then().statusCode(200)
                .body("namespace[0]", equalTo(namespace));
    }

    private String createTable() {
        send(glue, "POST", PREFIX + "/namespaces/" + namespace + "/tables", "{\"stage-create\":true,\"name\":\"events\","
                + "\"schema\":" + SCHEMA + ",\"properties\":{\"format-version\":\"2\"}}").then().statusCode(200)
                .body("metadata.location", equalTo("s3://" + bucket + "/lake/events"))
                .body("metadata-location", nullValue());
        return send(glue, "POST", tablePath(), IcebergCommits.createCommit(SCHEMA, "s3://" + bucket + "/lake/events")).then().statusCode(200)
                .body("metadata-location", endsWith(".metadata.json"))
                .extract().path("metadata-location");
    }

    private static String snapshotCommit(long snapshotId, Long parent) {
        return IcebergCommits.snapshotCommit(snapshotId, parent, snapshotId);
    }

    @Test
    void configNamesTheCatalogsPrefixAndTheRoutesItServes() {
        send(glue, "GET", "/iceberg/v1/config?warehouse=000000000000", null).then().statusCode(200)
                .body("overrides.prefix", equalTo("catalogs/000000000000"))
                .body("endpoints", hasItem("POST /v1/{prefix}/namespaces/{namespace}/tables/{table}"));
        send(glue, "GET", "/iceberg/v1/config", null).then().statusCode(200)
                .body("overrides.prefix", equalTo("catalogs/:"));
        send(glue, "GET", "/iceberg/v1/config?warehouse=111122223333", null).then().statusCode(400)
                .body("error.type", equalTo("BadRequestException"));
    }

    @Test
    void aStagedCreateIsCommittedAsAGlueIcebergTableWhoseMetadataLocationEachCommitMoves() throws Exception {
        createNamespace();
        send(glue, "GET", PREFIX + "/namespaces/" + namespace, null).then().statusCode(200)
                .body("properties.location", equalTo("s3://" + bucket + "/lake"))
                .body("properties.owner", equalTo("data"));

        String created = createTable();
        Table table = glueService.getTable(namespace, "events");
        assertEquals("ICEBERG", table.getParameters().get("table_type"));
        assertEquals(created, table.getParameters().get("metadata_location"));
        assertEquals(List.of("id:bigint", "name:string"), table.getStorageDescriptor().getColumns().stream()
                .map(column -> column.getName() + ":" + column.getType()).toList());
        assertTrue(created.contains("/lake/events/metadata/00000-"), created);
        String stored = new String(s3Service.openObjectStream(bucket,
                created.substring(("s3://" + bucket + "/").length()), null).readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(stored.contains("11111111-2222-3333-4444-555555555555"), stored);

        String committed = send(glue, "POST", tablePath(), snapshotCommit(7, null)).then().statusCode(200)
                .body("metadata.current-snapshot-id", equalTo(7))
                .body("metadata.metadata-log[0].metadata-file", equalTo(created))
                .extract().path("metadata-location");

        assertTrue(committed.contains("/metadata/00001-"), committed);
        Table updated = glueService.getTable(namespace, "events");
        assertEquals(committed, updated.getParameters().get("metadata_location"));
        assertEquals(created, updated.getParameters().get("previous_metadata_location"));
        send(glue, "GET", tablePath(), null).then().statusCode(200)
                .body("metadata-location", equalTo(committed))
                .body("metadata.refs.main.snapshot-id", equalTo(7));
        send(glue, "HEAD", tablePath(), null).then().statusCode(204);
        send(glue, "GET", PREFIX + "/namespaces/" + namespace + "/tables", null).then().statusCode(200)
                .body("identifiers.name", hasItem("events"));
    }

    @Test
    void aCommitBasedOnAStaleSnapshotIsRefusedAndLeavesTheTableAsItWas() {
        createNamespace();
        createTable();
        String first = send(glue, "POST", tablePath(), snapshotCommit(7, null)).then().statusCode(200)
                .extract().path("metadata-location");

        send(glue, "POST", tablePath(), snapshotCommit(8, null)).then().statusCode(409)
                .body("error.type", equalTo("CommitFailedException"));

        assertEquals(first, glueService.getTable(namespace, "events").getParameters().get("metadata_location"));
        send(glue, "POST", tablePath(), snapshotCommit(8, 7L)).then().statusCode(200)
                .body("metadata.current-snapshot-id", equalTo(8));
    }

    @Test
    void missingTablesAndNamespacesAnswerTheIcebergErrors() {
        send(glue, "GET", PREFIX + "/namespaces/" + namespace + "/tables/events", null).then().statusCode(404)
                .body("error.type", equalTo("NoSuchNamespaceException"));
        createNamespace();
        send(glue, "GET", tablePath(), null).then().statusCode(404)
                .body("error.type", equalTo("NoSuchTableException"));
        send(glue, "POST", tablePath(), snapshotCommit(7, null)).then().statusCode(404)
                .body("error.type", equalTo("NoSuchTableException"));
        createTable();
        send(glue, "DELETE", PREFIX + "/namespaces/" + namespace, null).then().statusCode(409)
                .body("error.type", equalTo("NamespaceNotEmptyException"));
        send(glue, "DELETE", tablePath(), null).then().statusCode(204);
        send(glue, "GET", tablePath(), null).then().statusCode(404);
    }

    @Test
    void namespacePropertiesAreUpdatedAndRemovedAsGlueDatabaseFields() {
        createNamespace();

        send(glue, "POST", PREFIX + "/namespaces/" + namespace + "/properties",
                "{\"removals\":[\"owner\",\"missing-key\"],\"updates\":{\"comment\":\"raw events\",\"team\":\"x\"}}")
                .then().statusCode(200)
                .body("removed", equalTo(List.of("owner")))
                .body("missing", equalTo(List.of("missing-key")))
                .body("updated", hasItem("comment"));

        assertEquals("raw events", glueService.getDatabase(namespace).getDescription());
        assertEquals(Map.of("team", "x"), glueService.getDatabase(namespace).getParameters());
    }

    @Test
    void onlyARequestSignedForGlueReachesTheEndpoint() {
        AwsRequestSigner s3 = AwsRequestSigner.signedAs("test", "test", "s3");

        send(s3, "GET", "/iceberg/v1/config", null).then().statusCode(not(equalTo(200)))
                .body(not(containsString("overrides")));
    }

    private Response send(AwsRequestSigner signer, String method, String path, String body) {
        return IcebergCommits.send(endpoint, signer, method, path, body);
    }
}
