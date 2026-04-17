package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventBridgePermissionIntegrationTest {

    private static final String EVENT_BRIDGE_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createBusForPermissionTests() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.CreateEventBus")
            .body("{\"Name\":\"perm-test-bus\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void putPermissionStoresPolicy() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.PutPermission")
            .body("""
                {
                    "EventBusName": "perm-test-bus",
                    "Action": "events:PutEvents",
                    "Principal": "*",
                    "StatementId": "test-stmt"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.DescribeEventBus")
            .body("{\"Name\":\"perm-test-bus\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Policy", notNullValue())
            .body("Policy", containsString("test-stmt"))
            .body("Policy", containsString("events:PutEvents"));
    }

    @Test
    @Order(3)
    void putPermissionReplacesExistingStatement() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.PutPermission")
            .body("""
                {
                    "EventBusName": "perm-test-bus",
                    "Action": "events:PutEvents",
                    "Principal": "123456789012",
                    "StatementId": "test-stmt"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.DescribeEventBus")
            .body("{\"Name\":\"perm-test-bus\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Policy", containsString("123456789012"))
            .body("Policy", not(containsString("\"*\"")));
    }

    @Test
    @Order(4)
    void removePermissionDeletesStatement() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.RemovePermission")
            .body("""
                {
                    "EventBusName": "perm-test-bus",
                    "StatementId": "test-stmt"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.DescribeEventBus")
            .body("{\"Name\":\"perm-test-bus\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Policy", nullValue());
    }

    @Test
    @Order(5)
    void removePermissionWithRemoveAllClearsPolicy() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.PutPermission")
            .body("""
                {
                    "EventBusName": "perm-test-bus",
                    "Action": "events:PutEvents",
                    "Principal": "*",
                    "StatementId": "stmt-a"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.PutPermission")
            .body("""
                {
                    "EventBusName": "perm-test-bus",
                    "Action": "events:PutEvents",
                    "Principal": "111122223333",
                    "StatementId": "stmt-b"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.RemovePermission")
            .body("""
                {
                    "EventBusName": "perm-test-bus",
                    "RemoveAllPermissions": true
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.DescribeEventBus")
            .body("{\"Name\":\"perm-test-bus\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Policy", nullValue());
    }

    @Test
    @Order(6)
    void putPermissionOnNonExistentBusReturns404() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.PutPermission")
            .body("""
                {
                    "EventBusName": "nonexistent-bus",
                    "Action": "events:PutEvents",
                    "Principal": "*",
                    "StatementId": "test"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(7)
    void putPermissionOnDefaultBus() {
        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.PutPermission")
            .body("""
                {
                    "Action": "events:PutEvents",
                    "Principal": "*",
                    "StatementId": "default-stmt"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(EVENT_BRIDGE_CONTENT_TYPE)
            .header("X-Amz-Target", "AWSEvents.DescribeEventBus")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Policy", containsString("default-stmt"));
    }
}
