package io.floci.aws.services.glue;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Glue Data Catalog and Glue's ETL resources are Regional, as on AWS: a resource created in one Region
 * is listed and reached only there, and its name can be used again in another Region of the account.
 */
@QuarkusTest
class GlueRegionScopingIntegrationTest {

    private static final AtomicLong ACCOUNTS = new AtomicLong(731100000000L);
    private static final String ROLE = "arn:aws:iam::000000000000:role/glue";

    /** One resource of each type: its name, how to create it, how to list its type, and where the names are. */
    private record Kind(String name, String resource, String createAction, String createBody, String listAction,
                        String listBody, String names) {}

    private static final List<Kind> KINDS = List.of(
            new Kind("database", "sales", "CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"sales\"}}",
                    "GetDatabases", "{}", "DatabaseList.Name"),
            new Kind("table", "orders", "CreateTable", "{\"DatabaseName\":\"sales\",\"TableInput\":{\"Name\":\"orders\"}}",
                    "GetTables", "{\"DatabaseName\":\"sales\"}", "TableList.Name"),
            new Kind("job", "etl", "CreateJob", "{\"Name\":\"etl\",\"Role\":\"" + ROLE
                    + "\",\"Command\":{\"Name\":\"glueetl\",\"ScriptLocation\":\"s3://b/s.py\"}}",
                    "ListJobs", "{}", "JobNames"),
            new Kind("connection", "warehouse", "CreateConnection", "{\"ConnectionInput\":{\"Name\":\"warehouse\","
                    + "\"ConnectionType\":\"JDBC\",\"ConnectionProperties\":{\"JDBC_CONNECTION_URL\":"
                    + "\"jdbc:mysql://h:3306/d\",\"USERNAME\":\"u\",\"PASSWORD\":\"p\"}}}",
                    "GetConnections", "{}", "ConnectionList.Name"),
            new Kind("crawler", "crawl", "CreateCrawler", "{\"Name\":\"crawl\",\"Role\":\"" + ROLE
                    + "\",\"Targets\":{\"S3Targets\":[{\"Path\":\"s3://b/p\"}]}}",
                    "ListCrawlers", "{}", "CrawlerNames"),
            new Kind("classifier", "csv", "CreateClassifier", "{\"CsvClassifier\":{\"Name\":\"csv\"}}",
                    "GetClassifiers", "{}", "Classifiers.CsvClassifier.Name"),
            new Kind("workflow", "nightly", "CreateWorkflow", "{\"Name\":\"nightly\"}",
                    "ListWorkflows", "{}", "Workflows"),
            new Kind("trigger", "start", "CreateTrigger", "{\"Name\":\"start\",\"Type\":\"ON_DEMAND\","
                    + "\"Actions\":[{\"JobName\":\"etl\"}]}",
                    "ListTriggers", "{}", "TriggerNames"));

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
        for (Kind kind : KINDS) {
            glue("us-east-1", kind.createAction(), kind.createBody()).statusCode(200);
        }

        for (Kind kind : KINDS) {
            if (kind.name().equals("table")) {
                glue("eu-west-1", "GetTable", "{\"DatabaseName\":\"sales\",\"Name\":\"orders\"}")
                        .statusCode(400).body("__type", equalTo("EntityNotFoundException"));
                continue;
            }
            assertEquals(0, count("eu-west-1", kind), kind.name());
        }

        for (Kind kind : KINDS) {
            glue("eu-west-1", kind.createAction(), kind.createBody()).statusCode(200);
        }

        for (Kind kind : KINDS) {
            assertEquals(1, count("us-east-1", kind), kind.name());
            assertEquals(1, count("eu-west-1", kind), kind.name());
        }
    }

    @Test
    void deletingADatabaseInOneRegionLeavesTheOthersCatalog() {
        for (String region : List.of("us-east-1", "eu-west-1")) {
            glue(region, "CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"sales\"}}").statusCode(200);
            glue(region, "CreateTable", "{\"DatabaseName\":\"sales\",\"TableInput\":{\"Name\":\"orders\"}}")
                    .statusCode(200);
        }

        glue("eu-west-1", "DeleteDatabase", "{\"Name\":\"sales\"}").statusCode(200);

        glue("eu-west-1", "GetDatabase", "{\"Name\":\"sales\"}")
                .statusCode(400).body("__type", equalTo("EntityNotFoundException"));
        glue("us-east-1", "GetTable", "{\"DatabaseName\":\"sales\",\"Name\":\"orders\"}")
                .statusCode(200).body("Table.Name", equalTo("orders"));
    }

    /** How many of the type {@code region} lists under the kind's resource name. */
    private long count(String region, Kind kind) {
        List<String> names = glue(region, kind.listAction(), kind.listBody()).statusCode(200)
                .extract().jsonPath().getList(kind.names(), String.class);
        return names == null ? 0 : names.stream().filter(kind.resource()::equals).count();
    }

    private ValidatableResponse glue(String region, String action, String body) {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWSGlue." + action)
                .header("Authorization", EnforcementFixtures.auth(account, region, "glue"))
                .body(body)
                .when().post("/")
                .then();
    }
}
