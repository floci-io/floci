package io.floci.aws.services.rdsdata;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class RdsDataControllerIntegrationTest {

    @Test
    void executeRejectsMalformedSqlParametersWithJsonDataApiError() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "resourceArn": "arn:aws:rds:us-east-1:000000000000:cluster:missing",
                  "secretArn": "arn:aws:secretsmanager:us-east-1:000000000000:secret:missing",
                  "database": "app",
                  "sql": "select 1",
                  "parameters": {"name": "id", "value": {"longValue": 1}}
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

    // Catches: the model's 64-character limit on database checked only after the resource lookup;
    // AWS checks the request's shape first, so a missing resource does not hide it.
    @Test
    void executeRefusesADatabaseNameLongerThanTheModelAllows() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "resourceArn": "arn:aws:rds:us-east-1:000000000000:cluster:missing",
                  "secretArn": "arn:aws:secretsmanager:us-east-1:000000000000:secret:missing",
                  "database": "%s",
                  "sql": "select 1"
                }
                """.formatted("d".repeat(65)))
        .when()
            .post("/Execute")
        .then()
            .statusCode(400)
            .contentType("application/json")
            .header("X-Amzn-Errortype", "BadRequestException")
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("database must be at most 64 characters."));
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
    void batchExecuteRejectsMissingSqlWithJsonDataApiError() {
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
    void batchExecuteRejectsMalformedParameterSetsWithJsonDataApiError() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "resourceArn": "arn:aws:rds:us-east-1:000000000000:cluster:missing",
                  "secretArn": "arn:aws:secretsmanager:us-east-1:000000000000:secret:missing",
                  "database": "app",
                  "sql": "insert into items(id) values (:id)",
                  "parameterSets": {"name": "id"}
                }
                """)
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
    void batchExecuteUnknownResourceReturnsJsonDataApiError() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "resourceArn": "arn:aws:rds:us-east-1:000000000000:cluster:missing",
                  "secretArn": "arn:aws:secretsmanager:us-east-1:000000000000:secret:missing",
                  "database": "app",
                  "sql": "insert into items(id) values (:id)",
                  "parameterSets": [[{"name": "id", "value": {"longValue": 1}}]]
                }
                """)
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
