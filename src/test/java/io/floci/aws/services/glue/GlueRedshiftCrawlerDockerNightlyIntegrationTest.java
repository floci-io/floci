package io.floci.aws.services.glue;

import io.floci.aws.services.redshift.RedshiftConnections;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.GlueRequests;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * End to end: a Glue crawler with a JDBC target reads a Floci Redshift cluster through a Glue connection
 * and writes the tables it finds to the Data Catalog. Needs a Docker daemon, like every Redshift
 * integration test.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
class GlueRedshiftCrawlerDockerNightlyIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String CLUSTER_ID = "glue-crawl-rs";
    private static final String URL = "jdbc:redshift://" + CLUSTER_ID
            + ".abc123.us-east-1.redshift.amazonaws.com:5439/dev";

    @Inject
    RedshiftService redshiftService;

    @BeforeAll
    void createClusterWithTables() throws Exception {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(),
                "Docker daemon must be available for Glue Redshift crawler integration tests");
        RestAssuredJsonUtils.configureAwsContentTypes();
        Cluster cluster = redshiftService.createCluster(CLUSTER_ID, "dc2.large", "admin", "Secret123");
        try (Connection connection = RedshiftConnections.waitForConnection(cluster);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA sales");
            statement.execute("CREATE TABLE public.orders (id integer, amount numeric(10,2), note varchar(64))");
            statement.execute("CREATE TABLE public.orders_tmp (id integer)");
            statement.execute("CREATE TABLE sales.invoices (id bigint, issued_on date)");
            statement.execute("CREATE VIEW public.recent_orders AS SELECT id FROM public.orders");
        }
    }

    @AfterAll
    void deleteCluster() throws InterruptedException {
        if (DockerAvailability.isAvailable()) {
            redshiftService.deleteCluster(CLUSTER_ID);
        }
    }

    private static ValidatableResponse call(String action, String body) {
        return GlueRequests.call(action, ACCOUNT_ID, REGION, body);
    }

    private static void createConnection(String name, String password) {
        call("CreateConnection", """
                {"ConnectionInput": {"Name": "%s", "ConnectionType": "JDBC",
                 "ConnectionProperties": {"JDBC_CONNECTION_URL": "%s", "USERNAME": "admin", "PASSWORD": "%s"}}}
                """.formatted(name, URL, password)).statusCode(200);
    }

    /**
     * Creates the crawler's Data Catalog database here rather than in {@code @BeforeAll}: RestAssured has no
     * port for the Quarkus application until a test runs.
     */
    private static void crawl(String crawler, String database, String connection, String path,
                              String exclusions) {
        call("CreateDatabase", "{\"DatabaseInput\": {\"Name\": \"" + database + "\"}}").statusCode(200);
        call("CreateCrawler", """
                {"Name": "%s", "Role": "arn:aws:iam::000000000000:role/glue", "DatabaseName": "%s",
                 "Targets": {"JdbcTargets": [{"ConnectionName": "%s", "Path": "%s", "Exclusions": [%s]}]}}
                """.formatted(crawler, database, connection, path, exclusions)).statusCode(200);
        call("StartCrawler", "{\"Name\": \"" + crawler + "\"}").statusCode(200);
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> call("GetCrawler", "{\"Name\": \"" + crawler + "\"}")
                        .body("Crawler.State", equalTo("READY")));
    }

    @Test
    void crawlsTheTablesAndViewsOfTheIncludePathIntoTheCatalog() {
        createConnection("rs-good", "Secret123");

        crawl("rs-crawler", "rs_lake", "rs-good", "dev/%", "\"**_tmp\"");

        call("GetCrawler", "{\"Name\": \"rs-crawler\"}")
                .body("Crawler.LastCrawl.Status", equalTo("SUCCEEDED"));
        call("GetTables", "{\"DatabaseName\": \"rs_lake\"}")
                .statusCode(200)
                .body("TableList.Name", containsInAnyOrder("dev_public_orders", "dev_public_recent_orders",
                        "dev_sales_invoices"));
        call("GetTable", "{\"DatabaseName\": \"rs_lake\", \"Name\": \"dev_public_orders\"}")
                .statusCode(200)
                .body("Table.StorageDescriptor.Columns.Type", contains("int",
                        "decimal(10,2)", "string"))
                .body("Table.StorageDescriptor.Location", equalTo("dev.public.orders"))
                .body("Table.Parameters.classification", equalTo("redshift"));
        call("GetTable", "{\"DatabaseName\": \"rs_lake\", \"Name\": \"dev_public_recent_orders\"}")
                .body("Table.Parameters.typeOfData", equalTo("view"));
        call("GetCrawlerMetrics", "{\"CrawlerNameList\": [\"rs-crawler\"]}")
                .body("CrawlerMetricsList[0].TablesCreated", equalTo(3));
    }

    @Test
    void aWrongPasswordFailsTheCrawlWithTheSignInError() {
        createConnection("rs-bad", "not-the-password");

        crawl("rs-bad-crawler", "rs_lake_bad", "rs-bad", "dev/public/%", "");

        call("GetCrawler", "{\"Name\": \"rs-bad-crawler\"}")
                .body("Crawler.LastCrawl.Status", equalTo("FAILED"))
                .body("Crawler.LastCrawl.ErrorMessage", containsString("rs-bad"));
    }
}
