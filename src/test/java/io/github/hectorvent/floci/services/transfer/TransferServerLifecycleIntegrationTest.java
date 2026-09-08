package io.github.hectorvent.floci.services.transfer;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * Server lifecycle conformance (#2802): AWS's DeleteServer has no state
 * precondition (terraform destroy deletes running servers directly, without
 * calling StopServer first), and a newly created server ends up ONLINE
 * (terraform's post-create waiter expects STARTING -> ONLINE, never OFFLINE).
 */
@QuarkusTest
class TransferServerLifecycleIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private String createServer() {
        return given()
            .header("X-Amz-Target", "TransferService.CreateServer")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ServerId", startsWith("s-"))
            .extract().jsonPath().getString("ServerId");
    }

    @Test
    void createServerComesUpOnline() {
        String serverId = createServer();

        given()
            .header("X-Amz-Target", "TransferService.DescribeServer")
            .contentType(CONTENT_TYPE)
            .body("{\"ServerId\": \"" + serverId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Server.ServerId", equalTo(serverId))
            .body("Server.State", equalTo("ONLINE"));
    }

    // terraform-provider-aws reads Domain back from DescribeServer into a
    // ForceNew attribute defaulting to S3; a missing field re-plans as a
    // replacement on every run.
    @Test
    void describeServerReportsDomain() {
        String defaulted = createServer();

        given()
            .header("X-Amz-Target", "TransferService.DescribeServer")
            .contentType(CONTENT_TYPE)
            .body("{\"ServerId\": \"" + defaulted + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Server.Domain", equalTo("S3"));

        String efs = given()
            .header("X-Amz-Target", "TransferService.CreateServer")
            .contentType(CONTENT_TYPE)
            .body("{\"Domain\": \"EFS\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("ServerId");

        given()
            .header("X-Amz-Target", "TransferService.DescribeServer")
            .contentType(CONTENT_TYPE)
            .body("{\"ServerId\": \"" + efs + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Server.Domain", equalTo("EFS"));
    }

    @Test
    void deleteServerWhileOnline() {
        String serverId = createServer();

        // Never stopped, so still ONLINE - deletion must succeed anyway.
        given()
            .header("X-Amz-Target", "TransferService.DeleteServer")
            .contentType(CONTENT_TYPE)
            .body("{\"ServerId\": \"" + serverId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "TransferService.DescribeServer")
            .contentType(CONTENT_TYPE)
            .body("{\"ServerId\": \"" + serverId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteServerWhileOffline() {
        String serverId = createServer();

        given()
            .header("X-Amz-Target", "TransferService.StopServer")
            .contentType(CONTENT_TYPE)
            .body("{\"ServerId\": \"" + serverId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "TransferService.DeleteServer")
            .contentType(CONTENT_TYPE)
            .body("{\"ServerId\": \"" + serverId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void deleteServerCascadesToUsers() {
        String serverId = createServer();

        given()
            .header("X-Amz-Target", "TransferService.CreateUser")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ServerId": "%s",
                    "UserName": "alice",
                    "Role": "arn:aws:iam::000000000000:role/transfer-role"
                }
                """.formatted(serverId))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Users no longer block deletion of a running server.
        given()
            .header("X-Amz-Target", "TransferService.DeleteServer")
            .contentType(CONTENT_TYPE)
            .body("{\"ServerId\": \"" + serverId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void deleteMissingServerReturnsNotFound() {
        given()
            .header("X-Amz-Target", "TransferService.DeleteServer")
            .contentType(CONTENT_TYPE)
            .body("{\"ServerId\": \"s-00000000000000000\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
