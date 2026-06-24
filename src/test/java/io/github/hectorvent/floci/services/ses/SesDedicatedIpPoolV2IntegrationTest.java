package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * Integration tests for SES V2 dedicated IP pool endpoints under
 * /v2/email/dedicated-ip-pools.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesDedicatedIpPoolV2IntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    @Test
    @Order(1)
    void createDedicatedIpPool_defaultScalingMode() {
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"PoolName\": \"v2-pool-alpha\"}")
        .when().post("/v2/email/dedicated-ip-pools")
        .then().statusCode(200);

        // Default ScalingMode is STANDARD.
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/dedicated-ip-pools/v2-pool-alpha")
        .then().statusCode(200)
            .body("DedicatedIpPool.PoolName", equalTo("v2-pool-alpha"))
            .body("DedicatedIpPool.ScalingMode", equalTo("STANDARD"));
    }

    @Test
    @Order(2)
    void createDedicatedIpPool_managedScalingMode() {
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"PoolName\": \"v2-pool-managed\", \"ScalingMode\": \"MANAGED\"}")
        .when().post("/v2/email/dedicated-ip-pools")
        .then().statusCode(200);

        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/dedicated-ip-pools/v2-pool-managed")
        .then().statusCode(200)
            .body("DedicatedIpPool.ScalingMode", equalTo("MANAGED"));
    }

    @Test
    @Order(3)
    void createDedicatedIpPool_invalidScalingMode_returns400() {
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"PoolName\": \"v2-pool-bad\", \"ScalingMode\": \"NONSENSE\"}")
        .when().post("/v2/email/dedicated-ip-pools")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("The ScalingMode parameter is invalid."));
    }

    @Test
    @Order(4)
    void createDedicatedIpPool_duplicate_returns400() {
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"PoolName\": \"v2-pool-alpha\"}")
        .when().post("/v2/email/dedicated-ip-pools")
        .then().statusCode(400)
            .body("__type", equalTo("AlreadyExistsException"))
            .body("message", equalTo("The pool <v2-pool-alpha> already exists."));
    }

    @Test
    @Order(5)
    void getDedicatedIpPool_unknown_returns404() {
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/dedicated-ip-pools/v2-pool-ghost")
        .then().statusCode(404)
            .body("__type", equalTo("NotFoundException"))
            .body("message", equalTo("The requested pool <v2-pool-ghost> does not exist."));
    }

    @Test
    @Order(6)
    void listDedicatedIpPools() {
        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/dedicated-ip-pools")
        .then().statusCode(200)
            .body("DedicatedIpPools", hasItem("v2-pool-alpha"))
            .body("DedicatedIpPools", hasItem("v2-pool-managed"));
    }

    @Test
    @Order(7)
    void deleteDedicatedIpPool() {
        given().header("Authorization", AUTH_HEADER)
        .when().delete("/v2/email/dedicated-ip-pools/v2-pool-alpha")
        .then().statusCode(200);

        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/dedicated-ip-pools/v2-pool-alpha")
        .then().statusCode(404);
    }

    @Test
    @Order(8)
    void deleteDedicatedIpPool_unknown_returns404() {
        given().header("Authorization", AUTH_HEADER)
        .when().delete("/v2/email/dedicated-ip-pools/v2-pool-ghost")
        .then().statusCode(404)
            .body("__type", equalTo("NotFoundException"))
            .body("message", equalTo("The requested pool <v2-pool-ghost> does not exist."));
    }

    @Test
    @Order(9)
    void createDedicatedIpPool_emptyBody_returns400() {
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("")
        .when().post("/v2/email/dedicated-ip-pools")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Request body is required."));
    }

    @Test
    @Order(10)
    void createDedicatedIpPool_missingPoolName_returns400() {
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{}")
        .when().post("/v2/email/dedicated-ip-pools")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("PoolName is required."));
    }

    @Test
    @Order(11)
    void createDedicatedIpPool_nullPoolName_returns400() {
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"PoolName\": null}")
        .when().post("/v2/email/dedicated-ip-pools")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("PoolName is required."));
    }

    @Test
    @Order(12)
    void createDedicatedIpPool_nonTextualScalingMode_returns400() {
        // A present-but-non-string ScalingMode must be rejected, not coerced to
        // "" and silently defaulted to STANDARD.
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"PoolName\": \"v2-pool-objsm\", \"ScalingMode\": {}}")
        .when().post("/v2/email/dedicated-ip-pools")
        .then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("The ScalingMode parameter is invalid."));
    }
}
