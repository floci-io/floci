package io.github.hectorvent.floci.services.accessanalyzer;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class AccessAnalyzerIntegrationTest {
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/access-analyzer/aws4_request";

    @BeforeAll
    static void configureRestAssured() { RestAssuredJsonUtils.configureAwsContentTypes(); }

    @Test
    void analyzerLifecycle() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"analyzerName\":\"org-analyzer\",\"type\":\"ORGANIZATION\"}")
                .put("/analyzer").then().statusCode(200).body("arn", notNullValue());
        given().header("Authorization", AUTH).get("/analyzer")
                .then().statusCode(200).body("analyzers", hasSize(1)).body("analyzers[0].name", equalTo("org-analyzer"));
        given().header("Authorization", AUTH).delete("/analyzer/org-analyzer").then().statusCode(200);
        given().header("Authorization", AUTH).get("/analyzer")
                .then().statusCode(200).body("analyzers", hasSize(0));
    }

    @Test
    void analyzerQuotasAreIndependentByExactType() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"analyzerName\":\"account-external\",\"type\":\"ACCOUNT\"}")
                .put("/analyzer").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"analyzerName\":\"account-unused\",\"type\":\"ACCOUNT_UNUSED_ACCESS\"}")
                .put("/analyzer").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"analyzerName\":\"account-external-2\",\"type\":\"ACCOUNT\"}")
                .put("/analyzer").then().statusCode(402).body("__type", equalTo("ServiceQuotaExceededException"));

        given().header("Authorization", AUTH).delete("/analyzer/account-unused").then().statusCode(200);
        given().header("Authorization", AUTH).delete("/analyzer/account-external").then().statusCode(200);
    }

    @Test
    void organizationInternalAccessAnalyzerLimitIsOne() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"analyzerName\":\"org-internal-1\",\"type\":\"ORGANIZATION_INTERNAL_ACCESS\"}")
                .put("/analyzer").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"analyzerName\":\"org-internal-2\",\"type\":\"ORGANIZATION_INTERNAL_ACCESS\"}")
                .put("/analyzer").then().statusCode(402).body("__type", equalTo("ServiceQuotaExceededException"));
        given().header("Authorization", AUTH).delete("/analyzer/org-internal-1").then().statusCode(200);
    }

    @Test
    void trailingJsonReturnsSerializationException() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"analyzerName\":\"trailing-json\",\"type\":\"ACCOUNT\"} {}")
                .put("/analyzer").then().statusCode(400).body("__type", equalTo("SerializationException"));
    }

    @Test
    void invalidAnalyzerTypeReturnsValidationError() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"analyzerName\":\"bad-analyzer\",\"type\":\"INVALID\"}")
                .put("/analyzer").then().statusCode(400).body("__type", equalTo("ValidationException"));
    }
}
