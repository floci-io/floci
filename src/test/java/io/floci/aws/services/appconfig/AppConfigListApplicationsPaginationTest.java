package io.floci.aws.services.appconfig;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AppConfigListApplicationsPaginationTest {
    private static final String SCOPE =
            "AWS4-HMAC-SHA256 Credential=123456789012/20261109/us-east-1/appconfig/aws4_request";

    @Test
    void listApplicationsRespectsMaxResultsAndReturnsNextToken() {
        String prefix = "pagination-" + UUID.randomUUID() + "-";
        List<String> ids = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                String id = given()
                        .header("Authorization", SCOPE)
                        .contentType(ContentType.JSON)
                        .body("{\"Name\":\"" + prefix + i + "\"}")
                        .when().post("/applications")
                        .then().statusCode(201)
                        .extract().path("Id");
                ids.add(id);
            }

            Response firstPage = given()
                    .header("Authorization", SCOPE)
                    .queryParam("max_results", 1)
                    .when().get("/applications")
                    .then().statusCode(200)
                    .extract().response();
            assertEquals(1, firstPage.jsonPath().getList("Items").size());
            String nextToken = firstPage.jsonPath().getString("NextToken");
            assertNotNull(nextToken);

            Response secondPage = given()
                    .header("Authorization", SCOPE)
                    .queryParam("max_results", 1)
                    .queryParam("next_token", nextToken)
                    .when().get("/applications")
                    .then().statusCode(200)
                    .extract().response();
            assertEquals(1, secondPage.jsonPath().getList("Items").size());
            assertNull(secondPage.jsonPath().getString("NextToken"));
            assertTrue(ids.contains(firstPage.jsonPath().getString("Items[0].Id")));
            assertTrue(ids.contains(secondPage.jsonPath().getString("Items[0].Id")));
            assertNotEquals(firstPage.jsonPath().getString("Items[0].Id"),
                    secondPage.jsonPath().getString("Items[0].Id"));

            given()
                    .header("Authorization", SCOPE)
                    .queryParam("max_results", 0)
                    .when().get("/applications")
                    .then().statusCode(400);
            given()
                    .header("Authorization", SCOPE)
                    .queryParam("max_results", 51)
                    .when().get("/applications")
                    .then().statusCode(400);
            given()
                    .header("Authorization", SCOPE)
                    .queryParam("next_token", "not-a-token")
                    .when().get("/applications")
                    .then().statusCode(400);
            given()
                    .header("Authorization", SCOPE)
                    .when().get("/applications?next_token=")
                    .then().statusCode(400);
            given()
                    .header("Authorization", SCOPE)
                    .queryParam("next_token", "x".repeat(2049))
                    .when().get("/applications")
                    .then().statusCode(400);
            given()
                    .header("Authorization",
                            "AWS4-HMAC-SHA256 Credential=210987654321/20261109/us-east-1/appconfig/aws4_request")
                    .queryParam("next_token", nextToken)
                    .when().get("/applications")
                    .then().statusCode(400);
        } finally {
            for (String id : ids) {
                given().header("Authorization", SCOPE)
                        .when().delete("/applications/" + id).then().statusCode(204);
            }
        }
    }
}
