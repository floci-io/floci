package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/**
 * UpdateConfigurationSet ({@code POST /v2/email/update-configuration-sets}) and the
 * {@code MessageSecurityOptions} member of Create/GetConfigurationSet. The signing-scheme
 * semantics and the error messages were measured against SES in us-west-2 on 2026-10-04; the
 * missing-name and type-mismatch cases follow the other configuration-set operations, and the
 * order of body validation before the existence check is unmeasured.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesConfigurationSetMessageSecurityV2IntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String CS = "cs-message-security";
    private static final String SMIME = "{\"SigningScheme\":{\"SmimeScheme\":{\"SignatureFormat\":\"DETACHED\"}}}";
    private static final String DEFAULT = "{\"SigningScheme\":{\"DefaultScheme\":{}}}";
    private static final String BAD_FORMAT_MESSAGE = "1 validation error detected: Value at "
            + "'messageSecurityOptions.signingScheme.smimeScheme.signatureFormat' failed to satisfy "
            + "constraint: Member must satisfy enum value set: [DETACHED]";

    @Test
    @Order(1)
    void createWithoutOptions_getOmitsMessageSecurityOptions() {
        create("{\"ConfigurationSetName\":\"" + CS + "\"}").statusCode(200);

        get(CS).body("$", not(hasKey("MessageSecurityOptions")));
    }

    @Test
    @Order(2)
    void updateWithoutOptions_keepsTheSetUnchanged() {
        update("{\"ConfigurationSetName\":\"" + CS + "\"}").statusCode(200);

        get(CS).body("$", not(hasKey("MessageSecurityOptions")));
    }

    @Test
    @Order(3)
    void updateSmime_isReturnedByGet() {
        update(withOptions(SMIME)).statusCode(200);

        get(CS).body("MessageSecurityOptions.SigningScheme.SmimeScheme.SignatureFormat", equalTo("DETACHED"))
                .body("MessageSecurityOptions.SigningScheme", not(hasKey("DefaultScheme")));
    }

    @Test
    @Order(4)
    void updateWithEmptyOptionsBlock_keepsTheStoredScheme() {
        update(withOptions("{}")).statusCode(200);

        get(CS).body("MessageSecurityOptions.SigningScheme.SmimeScheme.SignatureFormat", equalTo("DETACHED"));
    }

    @Test
    @Order(5)
    void updateWithEmptySigningScheme_resetsToDefaultScheme() {
        update(withOptions("{\"SigningScheme\":{}}")).statusCode(200);

        get(CS).body("MessageSecurityOptions.SigningScheme.DefaultScheme", anEmptyMap())
                .body("MessageSecurityOptions.SigningScheme", not(hasKey("SmimeScheme")));
    }

    @Test
    @Order(6)
    void updateWithBothUnionMembers_storesSmime() {
        update(withOptions("{\"SigningScheme\":{\"DefaultScheme\":{},"
                + "\"SmimeScheme\":{\"SignatureFormat\":\"DETACHED\"}}}")).statusCode(200);

        get(CS).body("MessageSecurityOptions.SigningScheme.SmimeScheme.SignatureFormat", equalTo("DETACHED"))
                .body("MessageSecurityOptions.SigningScheme", not(hasKey("DefaultScheme")));
    }

    @Test
    @Order(7)
    void updateDefault_replacesSmime() {
        update(withOptions(DEFAULT)).statusCode(200);

        get(CS).body("MessageSecurityOptions.SigningScheme.DefaultScheme", anEmptyMap());
    }

    @Test
    @Order(8)
    void updateSmimeWithoutFormat_storesDetached() {
        update(withOptions("{\"SigningScheme\":{\"SmimeScheme\":{}}}")).statusCode(200);

        get(CS).body("MessageSecurityOptions.SigningScheme.SmimeScheme.SignatureFormat", equalTo("DETACHED"));
    }

    @Test
    @Order(9)
    void updateWithUnknownFormat_isRejectedAndKeepsTheStoredScheme() {
        update(withOptions(DEFAULT)).statusCode(200);

        update(withOptions("{\"SigningScheme\":{\"SmimeScheme\":{\"SignatureFormat\":\"ATTACHED\"}}}"))
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo(BAD_FORMAT_MESSAGE));
        update(withOptions("{\"SigningScheme\":{\"SmimeScheme\":{\"SignatureFormat\":\"detached\"}}}"))
                .statusCode(400)
                .body("message", equalTo(BAD_FORMAT_MESSAGE));

        get(CS).body("MessageSecurityOptions.SigningScheme.DefaultScheme", anEmptyMap());
    }

    @Test
    @Order(10)
    void updateMissingSet_isNotFound() {
        update("{\"ConfigurationSetName\":\"cs-message-security-missing\",\"MessageSecurityOptions\":"
                + SMIME + "}")
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("Configuration set <cs-message-security-missing> does not exist."));
    }

    @Test
    @Order(11)
    void updateWithoutName_isBadRequest() {
        update("{\"MessageSecurityOptions\":" + SMIME + "}")
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(12)
    void createWithSmime_isReturnedByGet() {
        create("{\"ConfigurationSetName\":\"cs-message-security-created\",\"MessageSecurityOptions\":"
                + SMIME + "}").statusCode(200);

        get("cs-message-security-created")
                .body("MessageSecurityOptions.SigningScheme.SmimeScheme.SignatureFormat", equalTo("DETACHED"));
    }

    @Test
    @Order(13)
    void createWithUnknownFormat_isRejectedAndNotCreated() {
        create("{\"ConfigurationSetName\":\"cs-message-security-bad\",\"MessageSecurityOptions\":"
                + "{\"SigningScheme\":{\"SmimeScheme\":{\"SignatureFormat\":\"ATTACHED\"}}}}")
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo(BAD_FORMAT_MESSAGE));

        given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/cs-message-security-bad")
        .then().statusCode(404);
    }

    @Test
    @Order(14)
    void updateWithNonObjectOptions_isSerializationError() {
        update("{\"ConfigurationSetName\":\"" + CS + "\",\"MessageSecurityOptions\":\"SMIME\"}")
                .statusCode(400)
                .body("__type", equalTo("SerializationException"));
    }

    @Test
    @Order(15)
    void updateWithNonObjectDefaultScheme_isSerializationError() {
        update(withOptions("{\"SigningScheme\":{\"DefaultScheme\":\"x\"}}"))
                .statusCode(400)
                .body("__type", equalTo("SerializationException"));
    }

    @Test
    @Order(16)
    void updateWithNonStringSignatureFormat_isSerializationError() {
        update(withOptions("{\"SigningScheme\":{\"SmimeScheme\":{\"SignatureFormat\":5}}}"))
                .statusCode(400)
                .body("__type", equalTo("SerializationException"));
    }

    private static String withOptions(String options) {
        return "{\"ConfigurationSetName\":\"" + CS + "\",\"MessageSecurityOptions\":" + options + "}";
    }

    private static ValidatableResponse create(String body) {
        return given().contentType("application/json").header("Authorization", AUTH_HEADER).body(body)
        .when().post("/v2/email/configuration-sets")
        .then();
    }

    private static ValidatableResponse update(String body) {
        return given().contentType("application/json").header("Authorization", AUTH_HEADER).body(body)
        .when().post("/v2/email/update-configuration-sets")
        .then();
    }

    private static ValidatableResponse get(String name) {
        return given().header("Authorization", AUTH_HEADER)
        .when().get("/v2/email/configuration-sets/" + name)
        .then().statusCode(200);
    }
}
