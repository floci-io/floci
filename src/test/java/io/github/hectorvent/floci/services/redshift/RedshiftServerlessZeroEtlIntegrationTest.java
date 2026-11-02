package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessService;
import io.github.hectorvent.floci.testing.DockerAvailability;
import io.github.hectorvent.floci.testing.QueryRequests;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A DynamoDB zero-ETL integration in the form AWS takes it, a table ARN as {@code SourceArn} and a
 * Redshift Serverless namespace ARN as {@code TargetArn}, replicating into the namespace's workgroup.
 * Needs Docker.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedshiftServerlessZeroEtlIntegrationTest {

    private static final String TABLE = "zetl-serverless-orders";
    private static final String NAMESPACE = "zetl-serverless-ns";
    private static final String WORKGROUP = "zetl-serverless-wg";
    private static final String ADMIN_PASSWORD = "Secret123";
    // Not "dev", to show the landing table goes to the namespace's own database.
    private static final String DATABASE = "warehouse";
    private static final String REGION = "us-east-1";

    @Inject
    RedshiftServerlessService serverless;

    private String tableArn;
    private String namespaceArn;
    private int endpointPort;
    private boolean created;

    /** Creates the resources once, after Quarkus has pointed RestAssured at the test server. */
    @BeforeEach
    void ensureResources() throws InterruptedException {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker is required for Redshift Serverless zero-ETL tests");
        RestAssuredJsonUtils.configureAwsContentTypes();
        if (created) {
            return;
        }
        tableArn = dynamoDb("CreateTable", """
                {
                  "TableName": "%s",
                  "KeySchema": [{"AttributeName":"id","KeyType":"HASH"}],
                  "AttributeDefinitions": [{"AttributeName":"id","AttributeType":"S"}],
                  "BillingMode": "PAY_PER_REQUEST",
                  "StreamSpecification": {"StreamEnabled": true, "StreamViewType": "NEW_AND_OLD_IMAGES"}
                }
                """.formatted(TABLE)).path("TableDescription.TableArn");
        putItem("before-integration");
        namespaceArn = RestAssuredJsonUtils.awsAction("RedshiftServerless", "CreateNamespace", """
                {"namespaceName":"%s","adminUsername":"admin","adminUserPassword":"%s","dbName":"%s"}
                """.formatted(NAMESPACE, ADMIN_PASSWORD, DATABASE)).then().statusCode(200)
                .extract().path("namespace.namespaceArn");
        created = true;
        endpointPort = RestAssuredJsonUtils.awsAction("RedshiftServerless", "CreateWorkgroup", """
                {"workgroupName":"%s","namespaceName":"%s"}
                """.formatted(WORKGROUP, NAMESPACE)).then().statusCode(200)
                .extract().path("workgroup.endpoint.port");
    }

    /** Through the service: Quarkus has already pointed RestAssured away from the test server by now. */
    @AfterAll
    void deleteResources() {
        if (created) {
            serverless.deleteWorkgroup(WORKGROUP, REGION);
            serverless.deleteNamespace(NAMESPACE, REGION);
        }
    }

    @Test
    void aTableReplicatesIntoTheNamespaceWorkgroup() throws SQLException {
        String response = redshift("CreateIntegration", "IntegrationName", "zetl-serverless",
                "SourceArn", tableArn, "TargetArn", namespaceArn)
                .then().statusCode(200)
                .body(containsString("<SourceArn>" + tableArn + "</SourceArn>"))
                .body(containsString("<TargetArn>" + namespaceArn + "</TargetArn>"))
                .extract().body().asString();
        String integrationArn = response.substring(response.indexOf("<IntegrationArn>") + "<IntegrationArn>".length(),
                response.indexOf("</IntegrationArn>"));
        String landingTable = "floci_zetl_"
                + integrationArn.substring(integrationArn.lastIndexOf(':') + 1).replace('-', '_');
        try {
            awaitLanded(landingTable, "before-integration", true);
            Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> redshift("DescribeIntegrations", "IntegrationArn", integrationArn)
                            .then().statusCode(200)
                            .body(containsString("<Status>active</Status>"))
                            .body(containsString("<SourceArn>" + tableArn + "</SourceArn>"))
                            .body(containsString("<TargetArn>" + namespaceArn + "</TargetArn>")));

            putItem("after-integration");
            awaitLanded(landingTable, "after-integration", false);
        } finally {
            redshift("DeleteIntegration", "IntegrationArn", integrationArn).then().statusCode(200);
        }
    }

    @Test
    void aTableThatDoesNotExistIsIntegrationSourceNotFound() {
        redshift("CreateIntegration", "IntegrationName", "zetl-no-source",
                "SourceArn", tableArn.replace(TABLE, "no-such-table"), "TargetArn", namespaceArn)
                .then().statusCode(404)
                .body(containsString("IntegrationSourceNotFoundFault"));
    }

    @Test
    void aNamespaceThatDoesNotExistIsIntegrationTargetNotFound() {
        String otherNamespace = namespaceArn.substring(0, namespaceArn.lastIndexOf('/') + 1)
                + "00000000-0000-0000-0000-000000000000";
        redshift("CreateIntegration", "IntegrationName", "zetl-no-target",
                "SourceArn", tableArn, "TargetArn", otherNamespace)
                .then().statusCode(404)
                .body(containsString("IntegrationTargetNotFoundFault"));
    }

    private void awaitLanded(String landingTable, String itemId, boolean backfilled) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .untilAsserted(() -> assertTrue(landedEventIds(landingTable, itemId).stream()
                        .anyMatch(eventId -> eventId.startsWith("backfill#") == backfilled)));
    }

    /** Event ids of the landed records whose new image is the item with this id. */
    private List<String> landedEventIds(String landingTable, String itemId) throws SQLException {
        // 127.0.0.1 rather than the advertised address: CI sets floci.emulator.hostname to a name
        // that does not resolve from the test JVM.
        String url = "jdbc:postgresql://127.0.0.1:" + endpointPort + "/" + DATABASE;
        List<String> eventIds = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, "admin", ADMIN_PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT event_id, new_image_json FROM " + landingTable)) {
            while (rows.next()) {
                if (rows.getString(2) != null && rows.getString(2).contains("\"" + itemId + "\"")) {
                    eventIds.add(rows.getString(1));
                }
            }
        }
        return eventIds;
    }

    private static void putItem(String id) {
        dynamoDb("PutItem", """
                {"TableName": "%s", "Item": {"id": {"S": "%s"}}}
                """.formatted(TABLE, id));
    }

    private static ExtractableResponse<Response> dynamoDb(String action, String body) {
        return given()
                .header("X-Amz-Target", "DynamoDB_20120810." + action)
                .contentType("application/x-amz-json-1.0")
                .body(body)
                .when().post("/")
                .then().statusCode(200).extract();
    }

    private static Response redshift(String action, String... params) {
        return QueryRequests.request("test", "redshift", action, params)
                .formParam("Version", "2012-12-01")
                .when().post("/");
    }
}
