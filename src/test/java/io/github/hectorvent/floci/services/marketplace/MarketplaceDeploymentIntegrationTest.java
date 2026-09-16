package io.github.hectorvent.floci.services.marketplace;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class MarketplaceDeploymentIntegrationTest {
    @BeforeAll static void configure() { RestAssuredJsonUtils.configureAwsContentTypes(); }

    @Test
    void putDeploymentParameterCreatesStableResourceAndTags() {
        String body = "{\"agreementId\":\"agr-local\",\"clientToken\":\"12345678901234567890123456789012\","
                + "\"deploymentParameter\":{\"name\":\"ApiKey\",\"secretString\":\"local-secret\"},"
                + "\"tags\":{\"env\":\"test\"}}";
        var response = given().contentType("application/json").header("Authorization", auth()).body(body)
                .post("/catalogs/AWSMarketplace/products/prod-local/deployment-parameters")
                .then().statusCode(200).body("deploymentParameterId", notNullValue())
                .body("tags.env", equalTo("test")).extract().response();
        String arn = response.path("resourceArn");
        given().urlEncodingEnabled(false).header("Authorization", auth()).get("/tags/" + encode(arn)).then().statusCode(200)
                .body("tags.env", equalTo("test"));
    }

    @Test
    void putDeploymentParameterUpdatesByNameButKeepsCreateTags() {
        String first = "{\"agreementId\":\"agr-update\",\"deploymentParameter\":{\"name\":\"ExternalId\",\"secretString\":\"one\"},\"tags\":{\"owner\":\"first\"}}";
        String id = given().contentType("application/json").header("Authorization", auth()).body(first)
                .post("/catalogs/AWSMarketplace/products/prod-update/deployment-parameters")
                .then().statusCode(200).extract().path("deploymentParameterId");
        String second = "{\"agreementId\":\"agr-update\",\"deploymentParameter\":{\"name\":\"ExternalId\",\"secretString\":\"two\"},\"tags\":{\"owner\":\"ignored\"}}";
        given().contentType("application/json").header("Authorization", auth()).body(second)
                .post("/catalogs/AWSMarketplace/products/prod-update/deployment-parameters")
                .then().statusCode(200).body("deploymentParameterId", equalTo(id)).body("tags.owner", equalTo("first"));
    }

    @Test
    void putDeploymentParameterRejectsIdentifiersOutsideAwsPatterns() {
        String invalidAgreement = "{\"agreementId\":\"bad agreement\",\"deploymentParameter\":{\"name\":\"ApiKey\",\"secretString\":\"secret\"}}";
        given().contentType("application/json").header("Authorization", auth()).body(invalidAgreement)
                .post("/catalogs/AWSMarketplace/products/prod-local/deployment-parameters")
                .then().statusCode(400);

        String invalidToken = "{\"agreementId\":\"agr-local\",\"clientToken\":\"!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\","
                + "\"deploymentParameter\":{\"name\":\"ApiKey\",\"secretString\":\"secret\"}}";
        given().contentType("application/json").header("Authorization", auth()).body(invalidToken)
                .post("/catalogs/AWSMarketplace/products/prod-local/deployment-parameters")
                .then().statusCode(400);
    }

    @Test
    void putDeploymentParameterRejectsUnsupportedRegion() {
        String body = "{\"agreementId\":\"agr-local\",\"deploymentParameter\":{\"name\":\"ApiKey\",\"secretString\":\"secret\"}}";
        given().contentType("application/json").header("Authorization", auth("us-west-2")).body(body)
                .post("/catalogs/AWSMarketplace/products/prod-local/deployment-parameters")
                .then().statusCode(400);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String auth() {
        return auth("us-east-1");
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260908/" + region
                + "/aws-marketplace/aws4_request";
    }
}
