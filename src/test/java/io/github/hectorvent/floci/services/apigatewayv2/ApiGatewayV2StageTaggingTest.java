package io.github.hectorvent.floci.services.apigatewayv2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tagging a stage rather than an API. A stage ARN carries two more segments than an API ARN
 * ({@code /apis/{apiId}/stages/{stageName}}); reading only the trailing segment takes the stage
 * name for the API id, which surfaces as "Invalid API id specified" on TagResource.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2StageTaggingTest {

    private static String apiId;

    private static String stageArn() {
        return "arn:aws:apigateway:us-east-1::/apis/" + apiId + "/stages/$default";
    }

    @Test
    @Order(1)
    void createApiAndStageWithTags() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"stage-tagging-test-api\",\"protocolType\":\"HTTP\"}")
                .when().post("/v2/apis")
                .then().statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        // Tags supplied at CreateStage must survive a read, or Terraform re-tags on every plan.
        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"$default\",\"autoDeploy\":true,\"tags\":{\"Tier\":\"base\"}}")
                .when().post("/v2/apis/" + apiId + "/stages")
                .then().statusCode(201)
                .body("tags.Tier", equalTo("base"));

        given().when().get("/v2/apis/" + apiId + "/stages/$default")
                .then().statusCode(200)
                .body("tags.Tier", equalTo("base"));
    }

    @Test
    @Order(2)
    void tagResourceOnStageArn() {
        given().contentType(ContentType.JSON)
                .body("{\"tags\":{\"Feature\":\"api\"}}")
                .when().post("/v2/tags/" + stageArn())
                .then().statusCode(201);

        given().when().get("/v2/tags/" + stageArn())
                .then().statusCode(200)
                .body("tags.Feature", equalTo("api"))
                .body("tags.Tier", equalTo("base"));

        // The API itself must not have picked up the stage's tags.
        given().when().get("/v2/tags/arn:aws:apigateway:us-east-1::/apis/" + apiId)
                .then().statusCode(200)
                .body("tags.Feature", nullValue());
    }

    @Test
    @Order(3)
    void untagResourceOnStageArn() {
        given().when().delete("/v2/tags/" + stageArn() + "?tagKeys=Feature")
                .then().statusCode(anyOf204());

        given().when().get("/v2/tags/" + stageArn())
                .then().statusCode(200)
                .body("tags.Feature", nullValue())
                .body("tags.Tier", equalTo("base"));
    }

    /** UntagResource replies 204 on AWS; accept 200 too rather than pin an unrelated detail. */
    private static org.hamcrest.Matcher<Integer> anyOf204() {
        return org.hamcrest.Matchers.anyOf(equalTo(204), equalTo(200));
    }

    @Test
    @Order(4)
    void unknownStageArnIsNotFound() {
        given().contentType(ContentType.JSON)
                .body("{\"tags\":{\"a\":\"b\"}}")
                .when().post("/v2/tags/arn:aws:apigateway:us-east-1::/apis/" + apiId + "/stages/nope")
                .then().statusCode(404);
    }
}
