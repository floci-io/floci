package io.floci.aws.services.glue;

import io.floci.aws.testing.GlueRequests;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Glue Data Catalog and Glue's ETL resources are Regional, as on AWS: a resource created in one Region
 * is listed and reached only there, and its name can be used again in another Region of the account. Each
 * test works in an account of its own, so a listing holds only what that test created.
 */
@QuarkusTest
class GlueRegionScopingIntegrationTest {

    private static final AtomicLong ACCOUNTS = new AtomicLong(731100000000L);
    private static final String ROLE = "arn:aws:iam::000000000000:role/glue";

    /**
     * One resource of each type: its name; how to create, get and delete it; how to list its type, and where
     * the listing puts the names.
     */
    private record Kind(String resource, String createAction, String createBody, String getAction,
                        String getBody, String deleteAction, String deleteBody, String listAction,
                        String listBody, String names) {}

    private static final List<Kind> KINDS = List.of(
            new Kind("sales", "CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"sales\"}}",
                    "GetDatabase", "{\"Name\":\"sales\"}", "DeleteDatabase", "{\"Name\":\"sales\"}",
                    "GetDatabases", "{}", "DatabaseList.Name"),
            new Kind("orders", "CreateTable", "{\"DatabaseName\":\"sales\",\"TableInput\":{\"Name\":\"orders\"}}",
                    "GetTable", "{\"DatabaseName\":\"sales\",\"Name\":\"orders\"}",
                    "DeleteTable", "{\"DatabaseName\":\"sales\",\"Name\":\"orders\"}",
                    "GetTables", "{\"DatabaseName\":\"sales\"}", "TableList.Name"),
            new Kind("etl", "CreateJob", "{\"Name\":\"etl\",\"Role\":\"" + ROLE
                    + "\",\"Command\":{\"Name\":\"glueetl\",\"ScriptLocation\":\"s3://b/s.py\"}}",
                    "GetJob", "{\"JobName\":\"etl\"}", "DeleteJob", "{\"JobName\":\"etl\"}",
                    "ListJobs", "{}", "JobNames"),
            new Kind("warehouse", "CreateConnection", "{\"ConnectionInput\":{\"Name\":\"warehouse\","
                    + "\"ConnectionType\":\"JDBC\",\"ConnectionProperties\":{\"JDBC_CONNECTION_URL\":"
                    + "\"jdbc:mysql://h:3306/d\",\"USERNAME\":\"u\",\"PASSWORD\":\"p\"}}}",
                    "GetConnection", "{\"Name\":\"warehouse\"}",
                    "DeleteConnection", "{\"ConnectionName\":\"warehouse\"}",
                    "GetConnections", "{}", "ConnectionList.Name"),
            new Kind("crawl", "CreateCrawler", "{\"Name\":\"crawl\",\"Role\":\"" + ROLE
                    + "\",\"Targets\":{\"S3Targets\":[{\"Path\":\"s3://b/p\"}]}}",
                    "GetCrawler", "{\"Name\":\"crawl\"}", "DeleteCrawler", "{\"Name\":\"crawl\"}",
                    "ListCrawlers", "{}", "CrawlerNames"),
            new Kind("csv", "CreateClassifier", "{\"CsvClassifier\":{\"Name\":\"csv\"}}",
                    "GetClassifier", "{\"Name\":\"csv\"}", "DeleteClassifier", "{\"Name\":\"csv\"}",
                    "GetClassifiers", "{}", "Classifiers.CsvClassifier.Name"),
            new Kind("nightly", "CreateWorkflow", "{\"Name\":\"nightly\"}",
                    "GetWorkflow", "{\"Name\":\"nightly\"}", "DeleteWorkflow", "{\"Name\":\"nightly\"}",
                    "ListWorkflows", "{}", "Workflows"),
            new Kind("start", "CreateTrigger", "{\"Name\":\"start\",\"Type\":\"ON_DEMAND\","
                    + "\"Actions\":[{\"JobName\":\"etl\"}]}",
                    "GetTrigger", "{\"Name\":\"start\"}", "DeleteTrigger", "{\"Name\":\"start\"}",
                    "ListTriggers", "{}", "TriggerNames"));

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private String account;

    @BeforeAll
    static void configureAwsJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void accountOfItsOwn() {
        account = Long.toString(ACCOUNTS.incrementAndGet());
    }

    @Test
    void eachResourceIsSeenOnlyInTheRegionItWasCreatedIn() {
        createAll("us-east-1");

        for (Kind kind : KINDS) {
            assertEquals(List.of(), names("eu-west-1", kind), kind.listAction());
            glue("eu-west-1", kind.getAction(), kind.getBody())
                    .statusCode(400).body("__type", equalTo("EntityNotFoundException"));
        }

        createAll("eu-west-1");

        for (Kind kind : KINDS) {
            for (String region : List.of("us-east-1", "eu-west-1")) {
                assertEquals(List.of(kind.resource()), names(region, kind), region + " " + kind.listAction());
                glue(region, kind.getAction(), kind.getBody()).statusCode(200);
            }
        }
    }

    @Test
    void deletingADatabaseInOneRegionLeavesTheOthersCatalog() {
        for (String region : List.of("us-east-1", "eu-west-1")) {
            create(region, KINDS.get(0));
            create(region, KINDS.get(1));
        }

        glue("eu-west-1", "DeleteDatabase", "{\"Name\":\"sales\"}").statusCode(200);

        glue("eu-west-1", "GetDatabase", "{\"Name\":\"sales\"}")
                .statusCode(400).body("__type", equalTo("EntityNotFoundException"));
        glue("us-east-1", "GetTable", "{\"DatabaseName\":\"sales\",\"Name\":\"orders\"}")
                .statusCode(200).body("Table.Name", equalTo("orders"));
    }

    /** Creates one resource of each kind in {@code region}, each deleted after the test. */
    private void createAll(String region) {
        KINDS.forEach(kind -> create(region, kind));
    }

    /** Creates {@code kind}'s resource in {@code region}; the cleanup deletes it, unless the test already did. */
    private void create(String region, Kind kind) {
        glue(region, kind.createAction(), kind.createBody()).statusCode(200);
        cleanup.register(() -> glue(region, kind.deleteAction(), kind.deleteBody()));
    }

    /** The names {@code region} lists for the kind's type. */
    private List<String> names(String region, Kind kind) {
        return glue(region, kind.listAction(), kind.listBody()).statusCode(200)
                .extract().jsonPath().getList(kind.names(), String.class);
    }

    private ValidatableResponse glue(String region, String action, String body) {
        return GlueRequests.call(action, account, region, body);
    }
}
