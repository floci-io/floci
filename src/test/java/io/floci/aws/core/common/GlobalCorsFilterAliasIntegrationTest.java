package io.floci.aws.core.common;

import io.floci.aws.testing.DynamoDbPartitionedWalStorageProfile;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * {@code EXTRA_CORS_ALLOWED_ORIGINS}, the environment-style alias of the extra CORS origins setting,
 * set to {@code http://localhost:4000} by {@link DynamoDbPartitionedWalStorageProfile}.
 */
@QuarkusTest
@TestProfile(DynamoDbPartitionedWalStorageProfile.class)
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

    @Test
    void preflightFromUnlistedOriginGetsNoGlobalCorsHeaders() {
        given()
            .header("Origin", "http://localhost:5000")
            .header("Access-Control-Request-Method", "PUT")
        .when()
            .options("/my-bucket/some-key")
        .then()
            .statusCode(403)
            .header("Access-Control-Allow-Origin", nullValue());
    }
}
