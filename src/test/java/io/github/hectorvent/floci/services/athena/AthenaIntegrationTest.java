package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AthenaIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    private static String queryExecutionId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void startQueryExecution() {
        String response = given()
            .header("X-Amz-Target", "AmazonAthena.StartQueryExecution")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "QueryString": "SELECT 1",
                  "WorkGroup": "primary"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("QueryExecutionId", notNullValue())
            .extract().path("QueryExecutionId");

        queryExecutionId = response;
    }

    @Test
    @Order(2)
    void getQueryExecution() {
        given()
            .header("X-Amz-Target", "AmazonAthena.GetQueryExecution")
            .contentType(CONTENT_TYPE)
            .body("{ \"QueryExecutionId\": \"" + queryExecutionId + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("QueryExecution.QueryExecutionId", equalTo(queryExecutionId))
            .body("QueryExecution.Status.State", equalTo("SUCCEEDED"));
    }

    @Test
    @Order(3)
    void getQueryResults() {
        given()
            .header("X-Amz-Target", "AmazonAthena.GetQueryResults")
            .contentType(CONTENT_TYPE)
            .body("{ \"QueryExecutionId\": \"" + queryExecutionId + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResultSet", notNullValue());
    }

    @Test
    @Order(4)
    void listQueryExecutions() {
        given()
            .header("X-Amz-Target", "AmazonAthena.ListQueryExecutions")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("QueryExecutionIds", notNullValue())
            .body("QueryExecutionIds", hasItem(queryExecutionId));
    }

    @Test
    @Order(5)
    void getQueryExecutionNotFound() {
        given()
            .header("X-Amz-Target", "AmazonAthena.GetQueryExecution")
            .contentType(CONTENT_TYPE)
            .body("{ \"QueryExecutionId\": \"nonexistent-id\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }
}
