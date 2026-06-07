package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class AthenaCreateWorkGroupIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createWorkGroupReturnsHttp200WithEmptyJsonObjectBody() {
        String responseBody = given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "analytics-empty-body"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        org.junit.jupiter.api.Assertions.assertEquals("{}", responseBody);
    }

    @Test
    void createDuplicateWorkGroupReturnsInvalidRequestException() {
        String request = """
            {
              "Name": "analytics-duplicate"
            }
            """;

        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body(request)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body(request)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", equalTo("WorkGroup already exists"));
    }

    @Test
    void createWorkGroupRejectsPrimaryWorkGroup() {
        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "primary"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void createWorkGroupRejectsBlankName() {
        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": ""
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void createWorkGroupRejectsInvalidCharactersInName() {
        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "bad name with spaces"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void createWorkGroupRejectsTooLongName() {
        String tooLongName = "a".repeat(129);

        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "%s"
                }
                """.formatted(tooLongName))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }
}
