package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

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

        Assertions.assertEquals("{}", responseBody);
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
    void listWorkGroupsIncludesCreatedWorkGroupAlongsidePrimary() {
        given()
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "Name": "analytics-list",
                  "Description": "analytics workgroup for reporting queries",
                  "Configuration": {
                    "ResultConfiguration": {
                      "OutputLocation": "s3://test-bucket/athena-results/"
                    }
                  }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonAthena.ListWorkGroups")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("WorkGroups.size()", equalTo(2))
            .body("WorkGroups.Name", contains("primary", "analytics-list"))
            .body("WorkGroups.State", contains("ENABLED", "ENABLED"));
    }

    @Test
    void listWorkGroupsPaginatesWithNextToken() {
        String authorization = "AWS4-HMAC-SHA256 Credential=test/20261025/ap-south-2/athena/aws4_request";
        for (String name : new String[] {"pagination-one", "pagination-two"}) {
            given()
                .header("Authorization", authorization)
                .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
                .contentType(CONTENT_TYPE)
                .body("{\"Name\":\"" + name + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }

        String token = given()
            .header("Authorization", authorization)
            .header("X-Amz-Target", "AmazonAthena.ListWorkGroups")
            .contentType(CONTENT_TYPE)
            .body("{\"MaxResults\":1}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("WorkGroups.Name", contains("primary"))
            .body("NextToken", notNullValue())
            .extract().path("NextToken");

        token = given()
            .header("Authorization", authorization)
            .header("X-Amz-Target", "AmazonAthena.ListWorkGroups")
            .contentType(CONTENT_TYPE)
            .body("{\"MaxResults\":1,\"NextToken\":\"" + token + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("WorkGroups.Name", contains("pagination-one"))
            .body("NextToken", notNullValue())
            .extract().path("NextToken");

        given()
            .header("Authorization", authorization)
            .header("X-Amz-Target", "AmazonAthena.ListWorkGroups")
            .contentType(CONTENT_TYPE)
            .body("{\"MaxResults\":1,\"NextToken\":\"" + token + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("WorkGroups.Name", contains("pagination-two"))
            .body("NextToken", nullValue());
    }

    @Test
    void listWorkGroupsDoesNotRepeatEntriesInsertedBeforeCursor() {
        String authorization = "AWS4-HMAC-SHA256 Credential=test/20261025/eu-central-2/athena/aws4_request";
        for (String name : new String[] {"cursor-b", "cursor-c"}) {
            given()
                .header("Authorization", authorization)
                .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
                .contentType(CONTENT_TYPE)
                .body("{\"Name\":\"" + name + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }

        String token = given()
            .header("Authorization", authorization)
            .header("X-Amz-Target", "AmazonAthena.ListWorkGroups")
            .contentType(CONTENT_TYPE)
            .body("{\"MaxResults\":2}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("WorkGroups.Name", contains("primary", "cursor-b"))
            .extract().path("NextToken");

        given()
            .header("Authorization", authorization)
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{\"Name\":\"cursor-a\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", authorization)
            .header("X-Amz-Target", "AmazonAthena.ListWorkGroups")
            .contentType(CONTENT_TYPE)
            .body("{\"MaxResults\":2,\"NextToken\":\"" + token + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("WorkGroups.Name", contains("cursor-c"))
            .body("NextToken", nullValue());
    }

    @Test
    void listWorkGroupsDoesNotSkipEntriesAfterDeletion() {
        String authorization = "AWS4-HMAC-SHA256 Credential=test/20261025/sa-east-1/athena/aws4_request";
        for (String name : new String[] {"deleted-a", "deleted-b"}) {
            given()
                .header("Authorization", authorization)
                .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
                .contentType(CONTENT_TYPE)
                .body("{\"Name\":\"" + name + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }

        String token = given()
            .header("Authorization", authorization)
            .header("X-Amz-Target", "AmazonAthena.ListWorkGroups")
            .contentType(CONTENT_TYPE)
            .body("{\"MaxResults\":2}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("WorkGroups.Name", contains("primary", "deleted-a"))
            .extract().path("NextToken");

        given()
            .header("Authorization", authorization)
            .header("X-Amz-Target", "AmazonAthena.DeleteWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{\"WorkGroup\":\"deleted-a\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", authorization)
            .header("X-Amz-Target", "AmazonAthena.ListWorkGroups")
            .contentType(CONTENT_TYPE)
            .body("{\"MaxResults\":2,\"NextToken\":\"" + token + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("WorkGroups.Name", contains("deleted-b"))
            .body("NextToken", nullValue());
    }

    @Test
    void listWorkGroupsRejectsInvalidPageSizes() {
        for (String maxResults : new String[] {"0", "-1", "51", "1.5", "2147483648", "\"1\""}) {
            given()
                .header("X-Amz-Target", "AmazonAthena.ListWorkGroups")
                .contentType(CONTENT_TYPE)
                .body("{\"MaxResults\":" + maxResults + "}")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        }
    }

    @Test
    void listWorkGroupsRejectsMalformedAndCrossRegionTokens() {
        String authorization = "AWS4-HMAC-SHA256 Credential=test/20261025/eu-west-3/athena/aws4_request";
        given()
            .header("Authorization", authorization)
            .header("X-Amz-Target", "AmazonAthena.CreateWorkGroup")
            .contentType(CONTENT_TYPE)
            .body("{\"Name\":\"token-source\"}")
        .when().post("/").then().statusCode(200);

        String token = given()
            .header("Authorization", authorization)
            .header("X-Amz-Target", "AmazonAthena.ListWorkGroups")
            .contentType(CONTENT_TYPE)
            .body("{\"MaxResults\":1}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("NextToken", notNullValue())
            .extract().path("NextToken");
        for (String invalidToken : new String[] {"", "not-a-token!", token}) {
            given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261025/ap-northeast-3/athena/aws4_request")
                .header("X-Amz-Target", "AmazonAthena.ListWorkGroups")
                .contentType(CONTENT_TYPE)
                .body("{\"MaxResults\":1,\"NextToken\":\"" + invalidToken + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        }
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
