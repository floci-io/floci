package io.github.hectorvent.floci.services.rdsdata;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class RdsDataControllerIntegrationTest {

    @Test
    void executeRejectsSqlParametersWithJsonDataApiError() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "resourceArn": "arn:aws:rds:us-east-1:000000000000:cluster:missing",
                  "secretArn": "arn:aws:secretsmanager:us-east-1:000000000000:secret:missing",
                  "database": "app",
                  "sql": "select 1",
                  "parameters": [{"name": "id", "value": {"longValue": 1}}]
                }
                """)
        .when()
            .post("/Execute")
        .then()
            .statusCode(400)
            .contentType("application/json")
            .header("X-Amzn-Errortype", "BadRequestException")
            .header("x-amzn-query-error", "BadRequestException;Sender")
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void executeRejectsFormattedRecordsWithJsonDataApiError() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "resourceArn": "arn:aws:rds:us-east-1:000000000000:cluster:missing",
                  "secretArn": "arn:aws:secretsmanager:us-east-1:000000000000:secret:missing",
                  "database": "app",
                  "sql": "select 1",
                  "formatRecordsAs": "JSON"
                }
                """)
        .when()
            .post("/Execute")
        .then()
            .statusCode(400)
            .contentType("application/json")
            .header("X-Amzn-Errortype", "BadRequestException")
            .header("x-amzn-query-error", "BadRequestException;Sender")
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void malformedJsonReturnsBadRequestJsonDataApiError() {
        given()
            .contentType("application/json")
            .body("{")
        .when()
            .post("/Execute")
        .then()
            .statusCode(400)
            .contentType("application/json")
            .header("X-Amzn-Errortype", "BadRequestException")
            .header("x-amzn-query-error", "BadRequestException;Sender")
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void executeUnknownResourceReturnsJsonDataApiError() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "resourceArn": "arn:aws:rds:us-east-1:000000000000:cluster:missing",
                  "secretArn": "arn:aws:secretsmanager:us-east-1:000000000000:secret:missing",
                  "database": "app",
                  "sql": "select 1"
                }
                """)
        .when()
            .post("/Execute")
        .then()
            .statusCode(400)
            .contentType("application/json")
            .header("X-Amzn-Errortype", "BadRequestException")
            .header("x-amzn-query-error", "BadRequestException;Sender")
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void commitUnknownTransactionReturnsTypedJsonError() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "resourceArn": "arn:aws:rds:us-east-1:000000000000:cluster:missing",
                  "secretArn": "arn:aws:secretsmanager:us-east-1:000000000000:secret:missing",
                  "transactionId": "missing-tx"
                }
                """)
        .when()
            .post("/CommitTransaction")
        .then()
            .statusCode(404)
            .contentType("application/json")
            .header("X-Amzn-Errortype", "TransactionNotFoundException")
            .header("x-amzn-query-error", "TransactionNotFoundException;Sender")
            .body("__type", equalTo("TransactionNotFoundException"));
    }

    @Test
    void batchExecuteReturnsJsonUnsupportedError() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/BatchExecute")
        .then()
            .statusCode(400)
            .contentType("application/json")
            .header("X-Amzn-Errortype", "BadRequestException")
            .header("x-amzn-query-error", "BadRequestException;Sender")
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void executeSqlReturnsJsonUnsupportedError() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/ExecuteSql")
        .then()
            .statusCode(400)
            .contentType("application/json")
            .header("X-Amzn-Errortype", "BadRequestException")
            .header("x-amzn-query-error", "BadRequestException;Sender")
            .body("__type", equalTo("BadRequestException"));
    }
}
