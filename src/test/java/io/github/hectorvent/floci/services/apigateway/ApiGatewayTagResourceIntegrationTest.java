package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class ApiGatewayTagResourceIntegrationTest {

    private static final String CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/11111111-2222-3333-4444-555555555555";

    @Test
    void testTagResource() {
        String apiId = given()
                .contentType("application/json")
                .body("{\"name\":\"taggable-api\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract().path("id");

        String resourceArn = "arn:aws:apigateway:us-east-1::/restapis/" + apiId;

        given()
                .pathParam("resourceArn", resourceArn)
                .contentType("application/json")
                .body("{\"tags\":{\"environment\":\"test\"}}")
                .when()
                .put("/tags/{resourceArn}")
                .then()
                .statusCode(204);

        given()
                .pathParam("apiId", apiId)
                .when()
                .get("/restapis/{apiId}")
                .then()
                .statusCode(200)
                .body("tags.environment", equalTo("test"));
    }

    @Test
    void arnWithAnAccountSegmentIsRejectedForEveryTagOperation() {
        String apiId = given()
                .contentType("application/json")
                .body("{\"name\":\"account-arn-api\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract().path("id");
        String canonicalArn = "arn:aws:apigateway:us-east-1::/restapis/" + apiId;
        String accountArn = "arn:aws:apigateway:us-east-1:111111111111:/restapis/" + apiId;
        String expectedMessage = "Expected ARN " + canonicalArn + ", not " + accountArn;

        given().pathParam("arn", canonicalArn).contentType(ContentType.JSON)
                .body("{\"tags\":{\"keep\":\"yes\"}}")
                .when().put("/tags/{arn}").then().statusCode(204);

        given().pathParam("arn", accountArn).contentType(ContentType.JSON)
                .body("{\"tags\":{\"environment\":\"test\"}}")
                .when().put("/tags/{arn}").then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo(expectedMessage));

        given().pathParam("arn", accountArn)
                .when().get("/tags/{arn}").then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo(expectedMessage));

        given().pathParam("arn", accountArn).queryParam("tagKeys", "keep")
                .when().delete("/tags/{arn}").then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo(expectedMessage));

        given().pathParam("arn", canonicalArn)
                .when().get("/tags/{arn}").then()
                .statusCode(200)
                .body("tags.environment", nullValue())
                .body("tags.keep", equalTo("yes"));
    }

    @Test
    void arnNamingAnotherRegionIsNotFoundAndLeavesTheCallersDomainAlone() {
        String domain = "other-region-arn.apigw-tags-it.example.com";
        given().contentType(ContentType.JSON)
                .body("""
                        {"domainName":"%s","regionalCertificateArn":"%s","tags":{"env":"prod"}}
                        """.formatted(domain, CERTIFICATE_ARN))
                .when().post("/domainnames").then().statusCode(201);
        String otherRegionArn = "arn:aws:apigateway:eu-west-1::/domainnames/" + domain;

        given().pathParam("arn", otherRegionArn).contentType(ContentType.JSON)
                .body("{\"tags\":{\"team\":\"api\"}}")
                .when().put("/tags/{arn}").then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("Invalid resource identifier specified"));

        given().pathParam("arn", otherRegionArn)
                .when().get("/tags/{arn}").then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("Invalid resource identifier specified"));

        given().pathParam("arn", otherRegionArn).queryParam("tagKeys", "env")
                .when().delete("/tags/{arn}").then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("Invalid resource identifier specified"));

        given().when().get("/domainnames/" + domain).then()
                .statusCode(200)
                .body("tags.env", equalTo("prod"))
                .body("tags.team", nullValue());

        given().when().delete("/domainnames/" + domain).then().statusCode(202);
    }
}
