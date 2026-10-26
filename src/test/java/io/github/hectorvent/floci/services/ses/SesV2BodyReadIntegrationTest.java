package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.AUTH;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.assertSerializationException;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * How every SES v2 operation reads its request body, as probed on AWS (us-west-2, 2026-10-24) with
 * ListTenants, CreateConfigurationSet and CreateEmailTemplate: only an empty body is read as one
 * without members, while a whitespace-only body, malformed JSON and a token after the object are a
 * SerializationException without a message. The endpoints cover each controller that reads a body.
 */
@QuarkusTest
class SesV2BodyReadIntegrationTest {

    private static final String IDENTITY = "/v2/email/identities/body-read-ghost.example.com";
    private static final String REPUTATION_ENTITY = "/v2/email/reputation/entities/RESOURCE/body-read-ghost";

    private static final List<Arguments> ENDPOINTS = List.of(
            Arguments.of("POST", "/v2/email/tenants/list"),
            Arguments.of("POST", "/v2/email/tenants"),
            Arguments.of("POST", "/v2/email/configuration-sets"),
            Arguments.of("POST", "/v2/email/templates"),
            Arguments.of("POST", "/v2/email/templates/body-read-ghost/render"),
            Arguments.of("POST", "/v2/email/contact-lists"),
            Arguments.of("POST", "/v2/email/contact-lists/body-read-ghost/contacts/list"),
            Arguments.of("POST", "/v2/email/account/details"),
            Arguments.of("PUT", IDENTITY + "/dkim/signing"),
            Arguments.of("PUT", IDENTITY + "/configuration-set"),
            Arguments.of("PUT", IDENTITY + "/mail-from"),
            Arguments.of("POST", IDENTITY + "/policies/body-read-policy"),
            Arguments.of("POST", "/v2/email/import-jobs"),
            Arguments.of("POST", "/v2/email/import-jobs/list"),
            Arguments.of("POST", "/v2/email/outbound-emails"),
            Arguments.of("POST", "/v2/email/dedicated-ip-pools"),
            Arguments.of("PUT", "/v2/email/suppression/addresses"),
            Arguments.of("POST", "/v2/email/tags"),
            Arguments.of("POST", "/v2/email/reputation/entities"),
            Arguments.of("PUT", REPUTATION_ENTITY + "/policy"),
            Arguments.of("PUT", REPUTATION_ENTITY + "/customer-managed-status"));

    static Stream<Arguments> malformedBodyCases() {
        return ENDPOINTS.stream().flatMap(endpoint -> Stream.of("{", "{\"a\":}", "{} x", "{}{}")
                .map(body -> Arguments.of(endpoint.get()[0], endpoint.get()[1], body)));
    }

    static Stream<Arguments> endpoints() {
        return ENDPOINTS.stream();
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("malformedBodyCases")
    void malformedBody_isASerializationExceptionWithoutMessage(String method, String path, String body) {
        assertSerializationException(method, path, body, null);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("endpoints")
    void whitespaceOnlyBody_isASerializationExceptionWithoutMessage(String method, String path) {
        assertSerializationException(method, path, "   ", null);
    }

    @Test
    void emptyBody_isReadAsOneWithoutMembers() {
        listTenants("");
        // Reaches the missing-name validation rather than failing to read the body.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body("")
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void whitespaceAfterTheObject_isAccepted() {
        listTenants("{} ");
    }

    private static void listTenants(String body) {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body(body)
        .when()
            .post("/v2/email/tenants/list")
        .then()
            .statusCode(200)
            .body("Tenants", notNullValue());
    }
}
