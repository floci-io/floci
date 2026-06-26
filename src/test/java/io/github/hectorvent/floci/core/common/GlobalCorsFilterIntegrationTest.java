package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestProfile(GlobalCorsFilterIntegrationTest.CorsProfile.class)
class GlobalCorsFilterIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void preflightFromExtraAllowedOriginReturnsCorsHeaders() {
        given()
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "x-custom-header, x-amz-target")
        .when()
            .options("/")
        .then()
            .statusCode(204)
            .header("Access-Control-Allow-Origin", equalTo("http://localhost:3000"))
            .header("Access-Control-Allow-Methods", containsString("POST"))
            .header("Access-Control-Allow-Headers", containsString("x-custom-header"))
            .header("Access-Control-Allow-Headers", containsString("x-added-header"))
            .header("Access-Control-Expose-Headers", containsString("x-visible-header"))
            .header("Vary", containsString("Origin"));
    }

    @Test
    void preflightRequestingPrivateNetworkAccessIsGranted() {
        given()
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Private-Network", "true")
        .when()
            .options("/")
        .then()
            .statusCode(204)
            .header("Access-Control-Allow-Origin", equalTo("http://localhost:3000"))
            .header("Access-Control-Allow-Private-Network", equalTo("true"));
    }

    @Test
    void preflightWithoutPrivateNetworkRequestOmitsAllowHeader() {
        given()
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "POST")
        .when()
            .options("/")
        .then()
            .statusCode(204)
            .header("Access-Control-Allow-Private-Network", nullValue());
    }

    @Test
    void actualRequestFromExtraAllowedOriginGetsCorsHeaders() {
        given()
            .header("Origin", "https://ui.example.test")
            .header("X-Amz-Target", "DynamoDB_20120810.ListTables")
            .contentType("application/x-amz-json-1.0")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("https://ui.example.test"))
            .header("Access-Control-Expose-Headers", containsString("x-visible-header"))
            .header("Vary", containsString("Origin"));
    }

    @Test
    void requestFromUnlistedOriginGetsNoGlobalCorsHeaders() {
        given()
            .header("Origin", "https://not-allowed.example.test")
            .header("X-Amz-Target", "DynamoDB_20120810.ListTables")
            .contentType("application/x-amz-json-1.0")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", nullValue());
    }

    public static final class CorsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.security.extra-cors-allowed-origins", "http://localhost:3000,https://ui.example.test",
                    "floci.security.extra-cors-allowed-headers", "x-added-header",
                    "floci.security.extra-cors-expose-headers", "x-visible-header",
                    "floci.security.cors-allow-private-network", "true");
        }
    }

    @QuarkusTest
    @TestProfile(PrivateNetworkDefaultOffTest.CorsPnaDefaultProfile.class)
    static class PrivateNetworkDefaultOffTest {

        @BeforeAll
        static void configureRestAssured() {
            RestAssuredJsonUtils.configureAwsContentTypes();
        }

        @Test
        void privateNetworkAccessNotGrantedByDefault() {
            given()
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Private-Network", "true")
            .when()
                .options("/")
            .then()
                .statusCode(204)
                .header("Access-Control-Allow-Origin", equalTo("http://localhost:3000"))
                .header("Access-Control-Allow-Private-Network", nullValue());
        }

        // Only origins set — cors-allow-private-network left at its (off) default.
        public static final class CorsPnaDefaultProfile implements QuarkusTestProfile {
            @Override
            public Map<String, String> getConfigOverrides() {
                return Map.of("floci.security.extra-cors-allowed-origins", "http://localhost:3000");
            }
        }
    }
}
