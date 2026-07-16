package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(GlobalCorsFilterAliasIntegrationTest.AliasProfile.class)
public class GlobalCorsFilterAliasIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void preflightWithAliasEnvVarReturnsOk() {
        given()
            .header("Origin", "http://localhost:4000")
            .header("Access-Control-Request-Method", "PUT")
        .when()
            .options("/my-bucket/some-key")
        .then()
            .statusCode(204)
            .header("Access-Control-Allow-Origin", equalTo("http://localhost:4000"));
    }

    public static final class AliasProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("EXTRA_CORS_ALLOWED_ORIGINS", "http://localhost:4000");
        }
    }
}
