package io.floci.aws.services.athena;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.Locale;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Athena runs a DDL statement against the Glue Data Catalog with the caller's identity, so under IAM enforcement the
 * statement needs the Glue permissions Athena's user guide gives it. A caller without them still starts the query,
 * which fails with AWS's reason, "Insufficient permissions to execute the query." and the denial of the first Glue
 * request refused. The policies granted here are the guide's examples for each statement, with this test's names.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class AthenaGluePermissionsIntegrationTest {

    private static final String ADMIN = "test";
    private static final String GLUE = "arn:aws:glue:us-east-1:000000000000:";
    private static final String ATHENA = "{\"Effect\":\"Allow\",\"Action\":\"athena:*\",\"Resource\":\"*\"}";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createDatabaseWithoutGluePermissionsFailsTheQueryAndCreatesNothing() {
        String database = "perm_db_" + suffix();
        User user = user("athena-perm-db");
        user.policy(ATHENA);

        JsonPath failed = user.run("CREATE DATABASE " + database);
        assertEquals("FAILED", failed.getString("QueryExecution.Status.State"));
        assertEquals("Insufficient permissions to execute the query. User: arn:aws:iam::000000000000:user/"
                + user.name() + " is not authorized to perform: glue:GetDatabase on resource: " + GLUE
                + "catalog because no identity-based policy allows the glue:GetDatabase action ",
                failed.getString("QueryExecution.Status.StateChangeReason"));
        glue("GetDatabase", "{\"Name\":\"" + database + "\"}").statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));

        user.policy(ATHENA + "," + allow(new String[]{"glue:GetDatabase", "glue:CreateDatabase"},
                GLUE + "catalog", GLUE + "database/" + database));
        cleanup.register(() -> glue("DeleteDatabase", "{\"Name\":\"" + database + "\"}"));
        assertEquals("SUCCEEDED", user.run("CREATE DATABASE " + database).getString("QueryExecution.Status.State"));
        glue("GetDatabase", "{\"Name\":\"" + database + "\"}").statusCode(200);
    }

    @Test
    void createTableNeedsEveryActionOfTheGuidesPolicyOnTheCatalogDatabaseAndTable() {
        String database = database();
        String table = "orders";
        String create = "CREATE EXTERNAL TABLE " + database + "." + table
                + " (id string) LOCATION 's3://athena-perm-" + suffix() + "/orders/'";
        String databaseStatement = allow(new String[]{"glue:GetDatabase", "glue:GetDatabases"}, GLUE + "catalog",
                GLUE + "database/" + database);
        String[] tableResources = {GLUE + "catalog", GLUE + "database/" + database,
                GLUE + "table/" + database + "/" + table};
        User user = user("athena-perm-table");
        user.policy(ATHENA + "," + databaseStatement + ","
                + allow(new String[]{"glue:GetTables", "glue:GetTable", "glue:CreateTable"}, tableResources));

        JsonPath failed = user.run(create);
        assertEquals("FAILED", failed.getString("QueryExecution.Status.State"));
        assertEquals("Insufficient permissions to execute the query. User: arn:aws:iam::000000000000:user/"
                + user.name() + " is not authorized to perform: glue:GetPartitions on resource: " + GLUE
                + "catalog because no identity-based policy allows the glue:GetPartitions action ",
                failed.getString("QueryExecution.Status.StateChangeReason"));
        glue("GetTable", "{\"DatabaseName\":\"" + database + "\",\"Name\":\"" + table + "\"}").statusCode(400);

        user.policy(ATHENA + "," + databaseStatement + "," + allow(new String[]{"glue:GetTables", "glue:GetTable",
                "glue:GetPartitions", "glue:CreateTable"}, tableResources));
        assertEquals("SUCCEEDED", user.run(create).getString("QueryExecution.Status.State"));
        glue("GetTable", "{\"DatabaseName\":\"" + database + "\",\"Name\":\"" + table + "\"}").statusCode(200);
    }

    /**
     * The guide's DROP TABLE policy is for a partitioned table, "If your table does not have partitions, do not
     * include partition actions", so a table without them drops without those; a deny on the table holds.
     */
    @Test
    void dropTableOfATableWithoutPartitionsNeedsNoPartitionActionAndADenyOnTheTableHolds() {
        String database = database();
        String table = "events";
        glue("CreateTable", "{\"DatabaseName\":\"" + database + "\",\"TableInput\":{\"Name\":\"" + table + "\"}}")
                .statusCode(200);
        String tableArn = GLUE + "table/" + database + "/" + table;
        String withoutPartitions = allow(new String[]{"glue:GetDatabase", "glue:GetTable", "glue:DeleteTable"},
                GLUE + "catalog", GLUE + "database/" + database, tableArn);
        User user = user("athena-perm-drop");
        user.policy(ATHENA + "," + withoutPartitions + ",{\"Effect\":\"Deny\",\"Action\":\"glue:DeleteTable\","
                + "\"Resource\":\"" + tableArn + "\"}");

        JsonPath denied = user.run("DROP TABLE " + database + "." + table);
        assertEquals("FAILED", denied.getString("QueryExecution.Status.State"));
        assertEquals("Insufficient permissions to execute the query. User: arn:aws:iam::000000000000:user/"
                + user.name() + " is not authorized to perform: glue:DeleteTable on resource: " + tableArn
                + " with an explicit deny in an identity-based policy ",
                denied.getString("QueryExecution.Status.StateChangeReason"));

        user.policy(ATHENA + "," + withoutPartitions);
        assertEquals("SUCCEEDED", user.run("DROP TABLE " + database + "." + table)
                .getString("QueryExecution.Status.State"));
        glue("GetTable", "{\"DatabaseName\":\"" + database + "\",\"Name\":\"" + table + "\"}").statusCode(400);
    }

    /**
     * Glue names a database and a table in an ARN lowercase, as it stores them, so a deny on that ARN holds however
     * the statement or the query's context database spells the name.
     */
    @Test
    void aDenyOnALowercaseNameHoldsWhateverCaseTheQuerySpellsItIn() {
        String database = "perm_db_" + suffix();
        User user = user("athena-perm-case");
        String allowGlue = "{\"Effect\":\"Allow\",\"Action\":\"glue:*\",\"Resource\":\"*\"}";
        user.policy(ATHENA + "," + allowGlue + ",{\"Effect\":\"Deny\",\"Action\":\"glue:CreateDatabase\",\"Resource\":\""
                + GLUE + "database/" + database + "\"}");
        JsonPath created = user.run("CREATE DATABASE " + database.toUpperCase(Locale.ROOT));
        assertEquals("Insufficient permissions to execute the query. User: arn:aws:iam::000000000000:user/"
                + user.name() + " is not authorized to perform: glue:CreateDatabase on resource: " + GLUE + "database/"
                + database + " with an explicit deny in an identity-based policy ",
                created.getString("QueryExecution.Status.StateChangeReason"));
        glue("GetDatabase", "{\"Name\":\"" + database + "\"}").statusCode(400);

        String existing = database();
        user.policy(ATHENA + "," + allowGlue + ",{\"Effect\":\"Deny\",\"Action\":\"glue:CreateTable\",\"Resource\":\""
                + GLUE + "table/" + existing + "/*\"}");
        JsonPath table = user.run("CREATE EXTERNAL TABLE Orders (id string) LOCATION 's3://athena-perm-" + suffix()
                + "/orders/'", existing.toUpperCase(Locale.ROOT));
        assertEquals("Insufficient permissions to execute the query. User: arn:aws:iam::000000000000:user/"
                + user.name() + " is not authorized to perform: glue:CreateTable on resource: " + GLUE + "table/"
                + existing + "/orders with an explicit deny in an identity-based policy ",
                table.getString("QueryExecution.Status.StateChangeReason"));
        glue("GetTable", "{\"DatabaseName\":\"" + existing + "\",\"Name\":\"orders\"}").statusCode(400);
    }

    /** A Glue database the admin creates for one test, deleted afterwards. */
    private String database() {
        String database = "perm_db_" + suffix();
        glue("CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"" + database + "\"}}").statusCode(200);
        cleanup.register(() -> glue("DeleteDatabase", "{\"Name\":\"" + database + "\"}"));
        return database;
    }

    private User user(String name) {
        String userName = name + "-" + suffix();
        return new User(userName, EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ADMIN, "iam"),
                userName).accessKeyId());
    }

    private static String allow(String[] actions, String... resources) {
        return "{\"Effect\":\"Allow\",\"Action\":[\"" + String.join("\",\"", actions) + "\"],\"Resource\":[\""
                + String.join("\",\"", resources) + "\"]}";
    }

    private static ValidatableResponse glue(String operation, String body) {
        return given().header("Authorization", auth(ADMIN, "glue")).header("X-Amz-Target", "AWSGlue." + operation)
                .contentType("application/x-amz-json-1.1").body(body).when().post("/").then();
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    /** An IAM user whose one inline policy each step replaces. */
    private record User(String name, String accessKeyId) {

        void policy(String statements) {
            EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ADMIN, "iam"), name, "p",
                    "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
        }

        /** Starts the statement, which the request itself never refuses, and returns its execution once it ends. */
        JsonPath run(String query) {
            return run(query, null);
        }

        /** As {@link #run(String)}, with {@code database} as the query's context database when given. */
        JsonPath run(String query, String database) {
            String context = database == null ? "" : ",\"QueryExecutionContext\":{\"Database\":\"" + database + "\"}";
            String id = athena("StartQueryExecution", "{\"QueryString\":\"" + query.replace("\"", "\\\"") + "\""
                    + context + "}").statusCode(200).extract().path("QueryExecutionId");
            return athena("GetQueryExecution", "{\"QueryExecutionId\":\"" + id + "\"}").statusCode(200).extract()
                    .jsonPath();
        }

        private ValidatableResponse athena(String operation, String body) {
            return given().header("Authorization", auth(accessKeyId, "athena"))
                    .header("X-Amz-Target", "AmazonAthena." + operation).contentType("application/x-amz-json-1.1")
                    .body(body).when().post("/").then();
        }
    }
}
