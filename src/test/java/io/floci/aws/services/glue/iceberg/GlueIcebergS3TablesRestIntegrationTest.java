package io.floci.aws.services.glue.iceberg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.glue.GlueCatalogService;
import io.floci.aws.services.s3tables.S3TablesService;
import io.floci.aws.services.s3tables.model.Namespace;
import io.floci.aws.services.s3tables.model.S3Table;
import io.floci.aws.services.s3tables.model.TableBucket;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AWS Glue's Iceberg REST endpoint serving a table bucket federated from Amazon S3 Tables: the warehouse
 * {@code <account>:s3tablescatalog/<bucket>} configures the prefix {@code catalogs/<account>:s3tablescatalog:<bucket>},
 * under which namespaces and tables are the bucket's own, S3 Tables placing each table and each commit moving its
 * metadata location.
 */
@QuarkusTest
class GlueIcebergS3TablesRestIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String SCHEMA = """
            {"type":"struct","schema-id":0,"fields":[{"id":1,"name":"id","required":false,"type":"long"},
             {"id":2,"name":"name","required":false,"type":"string"}]}""";

    private final AwsRequestSigner glue = AwsRequestSigner.signedAs("test", "test", "glue");

    @TestHTTPResource("/")
    URI endpoint;

    @Inject
    S3TablesService s3Tables;

    @Inject
    GlueCatalogService catalogs;

    private TableBucket bucket;
    private String prefix;

    @BeforeEach
    void federateATableBucket() throws Exception {
        String name = "rest-" + System.nanoTime();
        ObjectNode input = (ObjectNode) new ObjectMapper().readTree("""
                {"FederatedCatalog":{"Identifier":"arn:aws:s3tables:us-east-1:000000000000:bucket/%s",
                 "ConnectionName":"aws:s3tables"}}""".formatted(name));
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            bucket = s3Tables.createTableBucket(name, null, null, Map.of(), REGION);
            catalogs.createCatalog("s3tablescatalog", input, Map.of(), REGION);
        });
        prefix = "/iceberg/v1/catalogs/" + ACCOUNT + ":s3tablescatalog:" + bucket.getName();
    }

    @AfterEach
    void removeTheBucket() {
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            catalogs.deleteCatalog("s3tablescatalog", REGION);
            for (Namespace namespace : s3Tables.listNamespaces(bucket.getArn(), null, REGION)) {
                for (S3Table table : s3Tables.listTables(bucket.getArn(), namespace.getName(), null, REGION)) {
                    s3Tables.deleteTable(bucket.getArn(), namespace.getName(), table.getName(), REGION);
                }
                s3Tables.deleteNamespace(bucket.getArn(), namespace.getName(), REGION);
            }
            s3Tables.deleteTableBucket(bucket.getArn(), REGION);
        });
    }

    private Response send(String method, String path, String body) {
        return IcebergCommits.send(endpoint, glue, method, path, body);
    }

    private S3Table s3Table(String name) {
        return RequestScopes.callAs(ACCOUNT, REGION, () -> s3Tables.getTable(bucket.getArn(), "sales", name, REGION));
    }

    @Test
    void theWarehouseOfATableBucketConfiguresItsCatalogsPrefix() {
        send("GET", "/iceberg/v1/config?warehouse=" + ACCOUNT + "%3As3tablescatalog%2F" + bucket.getName(), null)
                .then().statusCode(200)
                .body("overrides.prefix", equalTo("catalogs/" + ACCOUNT + ":s3tablescatalog:" + bucket.getName()));
        send("GET", "/iceberg/v1/config?warehouse=" + ACCOUNT + "%3As3tablescatalog%2Fno-such-bucket", null)
                .then().statusCode(400).body("error.type", equalTo("BadRequestException"));
    }

    @Test
    void aStagedCreateAndItsCommitMakeAnS3TableWhoseMetadataLocationEachCommitMoves() {
        send("POST", prefix + "/namespaces", "{\"namespace\":[\"sales\"]}").then().statusCode(200);
        send("GET", "/iceberg/v1/catalogs/s3tablescatalog:" + bucket.getName() + "/namespaces", null)
                .then().statusCode(200).body("namespaces[0][0]", equalTo("sales"));

        String location = send("POST", prefix + "/namespaces/sales/tables",
                "{\"stage-create\":true,\"name\":\"orders\",\"schema\":" + SCHEMA + "}")
                .then().statusCode(200).extract().path("metadata.location");
        S3Table reserved = s3Table("orders");
        assertEquals(reserved.getWarehouseLocation().replaceAll("/$", ""), location);
        assertNull(reserved.getMetadataLocation(), "a staged create commits nothing yet");
        send("GET", prefix + "/namespaces/sales/tables", null).then().statusCode(200)
                .body("identifiers", hasSize(0));

        String created = send("POST", prefix + "/namespaces/sales/tables/orders",
                IcebergCommits.createCommit(SCHEMA, location)).then().statusCode(200)
                .body("metadata-location", endsWith(".metadata.json"))
                .extract().path("metadata-location");
        assertEquals(created, s3Table("orders").getMetadataLocation());
        assertTrue(created.startsWith(location + "/metadata/00000-"), created);

        String committed = send("POST", prefix + "/namespaces/sales/tables/orders",
                IcebergCommits.snapshotCommit(7, null, 1)).then().statusCode(200)
                .body("metadata.current-snapshot-id", equalTo(7))
                .extract().path("metadata-location");
        assertEquals(committed, s3Table("orders").getMetadataLocation());
        send("POST", prefix + "/namespaces/sales/tables/orders", IcebergCommits.snapshotCommit(8, null, 2))
                .then().statusCode(409).body("error.type", equalTo("CommitFailedException"));
        send("POST", prefix + "/namespaces/sales/tables/orders", "{\"requirements\":[],\"updates\":["
                + "{\"action\":\"set-location\",\"location\":\"s3://elsewhere/orders\"}]}")
                .then().statusCode(400).body("error.type", equalTo("BadRequestException"));
        assertEquals(committed, s3Table("orders").getMetadataLocation(), "a refused move leaves the table where it was");

        send("GET", prefix + "/namespaces/sales/tables/orders", null).then().statusCode(200)
                .body("metadata-location", equalTo(committed));
        send("GET", prefix + "/namespaces/sales/tables", null).then().statusCode(200)
                .body("identifiers[0].name", equalTo("orders"));
        send("DELETE", prefix + "/namespaces/sales/tables/orders", null).then().statusCode(204);
        assertTrue(RequestScopes.callAs(ACCOUNT, REGION,
                () -> s3Tables.listTables(bucket.getArn(), "sales", null, REGION)).isEmpty());
    }

    @Test
    void aCreateNamingALocationOrAPropertyIsRefusedSinceS3TablesDecidesThem() {
        send("POST", prefix + "/namespaces", "{\"namespace\":[\"sales\"],\"properties\":{\"comment\":\"x\"}}")
                .then().statusCode(400).body("error.type", equalTo("BadRequestException"));
        // Spark adds its user as owner to every CREATE NAMESPACE; S3 Tables cannot keep it, so it is let go.
        send("POST", prefix + "/namespaces", "{\"namespace\":[\"sales\"],\"properties\":{\"owner\":\"spark\"}}")
                .then().statusCode(200);
        send("POST", prefix + "/namespaces/sales/tables/direct", IcebergCommits.createCommit(SCHEMA,
                "s3://elsewhere/direct")).then().statusCode(400).body("error.type", equalTo("BadRequestException"));
        assertTrue(RequestScopes.callAs(ACCOUNT, REGION,
                () -> s3Tables.listTables(bucket.getArn(), "sales", null, REGION)).isEmpty(),
                "a refused create leaves no table behind");
        send("POST", prefix + "/namespaces/sales/tables", "{\"name\":\"orders\",\"schema\":" + SCHEMA
                + ",\"location\":\"s3://elsewhere/orders\"}")
                .then().statusCode(400).body("error.type", equalTo("BadRequestException"));
    }

    @Test
    void aCommitOnAStaleVersionTokenIsACommitFailure() throws Exception {
        send("POST", prefix + "/namespaces", "{\"namespace\":[\"sales\"]}").then().statusCode(200);
        send("POST", prefix + "/namespaces/sales/tables", "{\"name\":\"orders\",\"schema\":" + SCHEMA + "}")
                .then().statusCode(200);
        S3TablesIcebergStore store = new S3TablesIcebergStore(s3Tables, bucket.getArn(), REGION);
        IcebergCatalogStore.StoredTable base = RequestScopes.callAs(ACCOUNT, REGION,
                () -> store.table("sales", "orders").orElseThrow());
        RequestScopes.runAs(ACCOUNT, REGION, () -> s3Tables.updateTableMetadataLocation(bucket.getArn(), "sales",
                "orders", base.metadataLocation(), base.token(), REGION));

        IcebergRestException stale = assertThrows(IcebergRestException.class, () -> RequestScopes.runAs(ACCOUNT,
                REGION, () -> store.commit("sales", "orders", base, null, base.metadataLocation() + ".next")));

        assertEquals("CommitFailedException", stale.type());
        assertEquals(List.of("orders"), RequestScopes.callAs(ACCOUNT, REGION, () -> store.tables("sales")));
    }
}
