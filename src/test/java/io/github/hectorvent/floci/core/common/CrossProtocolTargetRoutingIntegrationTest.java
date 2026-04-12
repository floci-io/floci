package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Regression coverage for the descriptor-backed target matcher. catalog.matchTarget
 * is protocol-agnostic: it will return a descriptor for a JSON 1.1 target even when
 * the request arrived at the JSON 1.0 controller (and vice versa). Each controller
 * must map such mismatches to UnknownOperationException rather than dropping the
 * request on a null switch branch.
 */
@QuarkusTest
class CrossProtocolTargetRoutingIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void json11ControllerRejectsJson10TargetAsUnknownOperation() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "DynamoDB_20120810.ListTables")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void json10ControllerRejectsJson11TargetAsUnknownOperation() {
        given()
            .contentType("application/x-amz-json-1.0")
            .header("X-Amz-Target", "AmazonSSM.ListDocuments")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("UnknownOperationException"));
    }
}
