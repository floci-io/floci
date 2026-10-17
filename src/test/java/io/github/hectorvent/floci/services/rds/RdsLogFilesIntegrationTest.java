package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@code DescribeDBLogFiles} and {@code DownloadDBLogFilePortion} over the Query protocol in mock
 * RDS mode, where an instance has no container and so no log files, and the errors the API
 * reference lists. {@code RdsLogFilesDockerIntegrationTest} reads a real container's log.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RdsLogFilesIntegrationTest {

    private static final String INSTANCE = "log-files-db";

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261015/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    private static RequestSpecification query(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    @Test
    @Order(1)
    void anInstanceWithoutAContainerHasNoLogFiles() {
        query("CreateDBInstance")
                .formParam("DBInstanceIdentifier", INSTANCE)
                .formParam("Engine", "postgres")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("AllocatedStorage", "20")
        .when().post("/").then().statusCode(200);

        query("DescribeDBLogFiles").formParam("DBInstanceIdentifier", INSTANCE)
        .when().post("/").then().statusCode(200)
                .body(containsString("<DescribeDBLogFilesResult><DescribeDBLogFiles></DescribeDBLogFiles>"));
        query("DownloadDBLogFilePortion").formParam("DBInstanceIdentifier", INSTANCE)
                .formParam("LogFileName", "error/postgresql.log.2026-10-15-10")
        .when().post("/").then().statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("DBLogFileNotFoundFault"));
    }

    @Test
    @Order(2)
    void theApiReferencesErrors() {
        query("DescribeDBLogFiles").formParam("DBInstanceIdentifier", "no-such-db")
        .when().post("/").then().statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("DBInstanceNotFound"));
        query("DescribeDBLogFiles")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("MissingParameter"));
        query("DownloadDBLogFilePortion").formParam("DBInstanceIdentifier", INSTANCE)
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("MissingParameter"));
        query("DescribeDBLogFiles").formParam("DBInstanceIdentifier", INSTANCE).formParam("MaxRecords", "5")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"));
        query("DescribeDBLogFiles").formParam("DBInstanceIdentifier", INSTANCE).formParam("FileSize", "big")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"));
        for (String[] bad : new String[][] {{"FileSize", "-1"}, {"FileLastWritten", "-1"}, {"Marker", "-1"}}) {
            query("DescribeDBLogFiles").formParam("DBInstanceIdentifier", INSTANCE).formParam(bad[0], bad[1])
            .when().post("/").then().statusCode(400)
                    .body("ErrorResponse.Error.Code", equalTo("InvalidParameterValue"));
        }

        query("StopDBInstance").formParam("DBInstanceIdentifier", INSTANCE)
        .when().post("/").then().statusCode(200);
        query("DescribeDBLogFiles").formParam("DBInstanceIdentifier", INSTANCE)
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("DBInstanceNotReady"));
        query("StartDBInstance").formParam("DBInstanceIdentifier", INSTANCE)
        .when().post("/").then().statusCode(200);
    }
}
