package io.github.hectorvent.floci.services.emr;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * A default release label the catalogue does not describe is still a known release: it is listed
 * (newest first here) and described with no applications, so Floci never calls its own default
 * unknown.
 */
@QuarkusTest
@TestProfile(EmrReleaseCatalogCustomDefaultIntegrationTest.FutureDefault.class)
class EmrReleaseCatalogCustomDefaultIntegrationTest {

    public static class FutureDefault implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.emr.default-release-label", "emr-7.9.0");
        }
    }

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void theConfiguredDefaultIsListedAndDescribed() {
        given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "ElasticMapReduce.ListReleaseLabels")
                .body("{}").when().post("/").then().statusCode(200)
                .body("ReleaseLabels[0]", equalTo("emr-7.9.0"));
        given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "ElasticMapReduce.DescribeReleaseLabel")
                .body("{\"ReleaseLabel\":\"emr-7.9.0\"}").when().post("/").then().statusCode(200)
                .body("ReleaseLabel", equalTo("emr-7.9.0"))
                .body("Applications", hasSize(0));
    }
}
