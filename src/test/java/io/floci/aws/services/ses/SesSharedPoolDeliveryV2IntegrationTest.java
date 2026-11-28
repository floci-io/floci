package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Map;

import static io.floci.aws.services.ses.SesV2Requests.v2;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/**
 * Global shared delivery: {@code PutAccountDeliveryAttributes} with the {@code GetAccount}
 * {@code DeliveryAttributes} round-trip, and the configuration set's
 * {@code DeliveryOptions.SharedPoolDeliveryMode}.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesSharedPoolDeliveryV2IntegrationTest {

    private static final String AUTH = SesV2Requests.authFor("eu-south-2");
    private static final String OTHER_REGION_AUTH = SesV2Requests.authFor("me-central-1");

    @Test
    @Order(0)
    void getAccount_unsetRegionReportsGlobal() {
        // AWS reports DeliveryAttributes before it is ever set; an account new since the 2026-11-19
        // launch starts on GLOBAL.
        given().header("Authorization", AUTH)
        .when().get("/v2/email/account").then().statusCode(200)
                .body("DeliveryAttributes.SharedPoolDeliveryMode", equalTo("GLOBAL"));
    }

    @Test
    @Order(1)
    void putAccountDeliveryAttributes_roundTripsPerRegion() {
        v2(AUTH)
                .body("{\"SharedPoolDeliveryMode\":\"REGIONAL\"}")
        .when().post("/v2/email/account/delivery").then().statusCode(200)
                .body("$", equalTo(Map.of()));

        given().header("Authorization", AUTH)
        .when().get("/v2/email/account").then().statusCode(200)
                .body("DeliveryAttributes.SharedPoolDeliveryMode", equalTo("REGIONAL"));
        given().header("Authorization", OTHER_REGION_AUTH)
        .when().get("/v2/email/account").then().statusCode(200)
                .body("DeliveryAttributes.SharedPoolDeliveryMode", equalTo("GLOBAL"));
    }

    @Test
    @Order(2)
    void putAccountDeliveryAttributes_missingMode_returns400() {
        v2(AUTH)
                .body("{}")
        .when().post("/v2/email/account/delivery").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("1 validation error detected: Value at 'sharedPoolDeliveryMode' "
                        + "failed to satisfy constraint: Member must not be null"));
    }

    @Test
    @Order(3)
    void putAccountDeliveryAttributes_accountValueIsConfigurationSetOnly() {
        v2(AUTH)
                .body("{\"SharedPoolDeliveryMode\":\"ACCOUNT\"}")
        .when().post("/v2/email/account/delivery").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("1 validation error detected: Value at 'sharedPoolDeliveryMode' "
                        + "failed to satisfy constraint: Member must satisfy enum value set: [GLOBAL, REGIONAL]"));
    }

    @Test
    @Order(4)
    void putAccountDeliveryAttributes_nonStringMode_returnsSerializationException() {
        v2(AUTH)
                .body("{\"SharedPoolDeliveryMode\":1}")
        .when().post("/v2/email/account/delivery").then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
    }

    @Test
    @Order(10)
    void createConfigurationSet_storesMode() {
        v2(AUTH)
                .body("{\"ConfigurationSetName\":\"spd-create\","
                        + "\"DeliveryOptions\":{\"SharedPoolDeliveryMode\":\"ACCOUNT\"}}")
        .when().post("/v2/email/configuration-sets").then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get("/v2/email/configuration-sets/spd-create").then().statusCode(200)
                .body("DeliveryOptions.SharedPoolDeliveryMode", equalTo("ACCOUNT"));
    }

    @Test
    @Order(11)
    void createConfigurationSet_withoutMode_omitsIt() {
        v2(AUTH)
                .body("{\"ConfigurationSetName\":\"spd-none\",\"DeliveryOptions\":{\"TlsPolicy\":\"REQUIRE\"}}")
        .when().post("/v2/email/configuration-sets").then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get("/v2/email/configuration-sets/spd-none").then().statusCode(200)
                .body("DeliveryOptions", not(hasKey("SharedPoolDeliveryMode")));
    }

    @Test
    @Order(12)
    void putDeliveryOptions_omittedModeKeepsStoredValue() {
        v2(AUTH)
                .body("{\"SharedPoolDeliveryMode\":\"REGIONAL\",\"TlsPolicy\":\"REQUIRE\"}")
        .when().put("/v2/email/configuration-sets/spd-create/delivery-options").then().statusCode(200);
        v2(AUTH)
                .body("{\"TlsPolicy\":\"OPTIONAL\"}")
        .when().put("/v2/email/configuration-sets/spd-create/delivery-options").then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get("/v2/email/configuration-sets/spd-create").then().statusCode(200)
                .body("DeliveryOptions.TlsPolicy", equalTo("OPTIONAL"))
                .body("DeliveryOptions.SharedPoolDeliveryMode", equalTo("REGIONAL"));
    }

    @Test
    @Order(13)
    void putDeliveryOptions_emptyBodyKeepsOnlyTheMode() {
        v2(AUTH)
                .body("{}")
        .when().put("/v2/email/configuration-sets/spd-create/delivery-options").then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get("/v2/email/configuration-sets/spd-create").then().statusCode(200)
                .body("DeliveryOptions", not(hasKey("TlsPolicy")))
                .body("DeliveryOptions.SharedPoolDeliveryMode", equalTo("REGIONAL"));
    }

    @Test
    @Order(14)
    void putDeliveryOptions_v1WriteKeepsTheMode() {
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH)
                .formParam("Action", "PutConfigurationSetDeliveryOptions")
                .formParam("ConfigurationSetName", "spd-create")
                .formParam("DeliveryOptions.TlsPolicy", "Require")
        .when().post("/").then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get("/v2/email/configuration-sets/spd-create").then().statusCode(200)
                .body("DeliveryOptions.TlsPolicy", equalTo("REQUIRE"))
                .body("DeliveryOptions.SharedPoolDeliveryMode", equalTo("REGIONAL"));
    }

    @Test
    @Order(16)
    void putDeliveryOptions_nonStringMode_returnsSerializationException() {
        v2(AUTH)
                .body("{\"SharedPoolDeliveryMode\":1}")
        .when().put("/v2/email/configuration-sets/spd-create/delivery-options").then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
    }

    @Test
    @Order(15)
    void putDeliveryOptions_invalidMode_returns400() {
        v2(AUTH)
                .body("{\"SharedPoolDeliveryMode\":\"EVERYWHERE\"}")
        .when().put("/v2/email/configuration-sets/spd-create/delivery-options").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("1 validation error detected: Value at 'sharedPoolDeliveryMode' "
                        + "failed to satisfy constraint: "
                        + "Member must satisfy enum value set: [GLOBAL, REGIONAL, ACCOUNT]"));
    }
}
