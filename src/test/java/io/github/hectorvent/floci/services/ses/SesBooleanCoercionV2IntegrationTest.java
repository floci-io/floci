package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The SES v2 boolean members that share {@code SesV2Json.coerceBoolean}: a string coerces only
 * when it names a boolean, any other string is a SerializationException, and on the members whose
 * absence means false an empty body stores false. All of it probe-confirmed against real SES v2.
 * Runs in its own region because it toggles account-level sending.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SesBooleanCoercionV2IntegrationTest {

    private static final String REGION = "il-central-1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + REGION + "/ses/aws4_request";
    private static final String DOMAIN = "coercion.example.com";
    private static final String CS = "coercion-cs";
    private static final String ED = "coercion-ed";
    private static final String NOT_A_BOOLEAN = "STRING_VALUE can not be converted to an Boolean";

    private boolean created;

    // Not @BeforeAll: the Quarkus test port is only bound once the first test starts.
    @BeforeEach
    void createResources() {
        if (created) {
            return;
        }
        send("POST", "/v2/email/identities", "{\"EmailIdentity\":\"" + DOMAIN + "\"}").statusCode(200);
        send("POST", "/v2/email/configuration-sets", "{\"ConfigurationSetName\":\"" + CS + "\"}")
                .statusCode(200);
        send("POST", "/v2/email/configuration-sets/" + CS + "/event-destinations", """
                {"EventDestinationName": "%s",
                 "EventDestination": {"Enabled": "yes", "MatchingEventTypes": ["SEND"],
                   "SnsDestination": {"TopicArn": "arn:aws:sns:%s:000000000000:coercion"}}}
                """.formatted(ED, REGION)).statusCode(200);
        created = true;
    }

    @Test
    void signingEnabled_coercesStringsAndDefaultsToFalse() {
        String path = "/v2/email/identities/" + DOMAIN + "/dkim";
        assertStored(path, "SigningEnabled", "/v2/email/identities/" + DOMAIN, "DkimAttributes.SigningEnabled");
    }

    @Test
    void emailForwardingEnabled_coercesStringsAndDefaultsToFalse() {
        String path = "/v2/email/identities/" + DOMAIN + "/feedback";
        assertStored(path, "EmailForwardingEnabled", "/v2/email/identities/" + DOMAIN, "FeedbackForwardingStatus");
    }

    @Test
    void identityAttributes_nonObjectBody_isRejectedAndKeepsTheFlag() {
        // Absence means false, so a body that is not an object must not reach that default.
        for (String suffix : new String[] {"/dkim", "/feedback"}) {
            String member = suffix.equals("/dkim") ? "SigningEnabled" : "EmailForwardingEnabled";
            String readField = suffix.equals("/dkim") ? "DkimAttributes.SigningEnabled" : "FeedbackForwardingStatus";
            String path = "/v2/email/identities/" + DOMAIN + suffix;
            send("PUT", path, "{\"" + member + "\":true}").statusCode(200);
            send("PUT", path, "[]").statusCode(400).body("__type", equalTo("BadRequestException"));
            get("/v2/email/identities/" + DOMAIN).body(readField, equalTo(true));
        }
    }

    @Test
    void reputationMetricsEnabled_coercesStringsAndDefaultsToFalse() {
        String path = "/v2/email/configuration-sets/" + CS + "/reputation-options";
        assertStored(path, "ReputationMetricsEnabled", "/v2/email/configuration-sets/" + CS,
                "ReputationOptions.ReputationMetricsEnabled");
        send("PUT", path, "{\"ReputationMetricsEnabled\":null}").statusCode(400)
                .body("__type", equalTo("SerializationException"));
    }

    @Test
    void createConfigurationSet_reputationMetricsString_isCoerced() {
        send("POST", "/v2/email/configuration-sets", """
                {"ConfigurationSetName": "coercion-cs-create",
                 "ReputationOptions": {"ReputationMetricsEnabled": "no"}}
                """).statusCode(200);
        get("/v2/email/configuration-sets/coercion-cs-create")
                .body("ReputationOptions.ReputationMetricsEnabled", equalTo(false));
    }

    @Test
    void createConfigurationSet_emptyReputationOptions_disablesMetrics() {
        // Without ReputationOptions the metrics stay on; an empty block means the member is false.
        send("POST", "/v2/email/configuration-sets", """
                {"ConfigurationSetName": "coercion-cs-empty", "ReputationOptions": {}}
                """).statusCode(200);
        get("/v2/email/configuration-sets/coercion-cs-empty")
                .body("ReputationOptions.ReputationMetricsEnabled", equalTo(false));
        send("POST", "/v2/email/configuration-sets", """
                {"ConfigurationSetName": "coercion-cs-null", "ReputationOptions": {"ReputationMetricsEnabled": null}}
                """).statusCode(400).body("__type", equalTo("SerializationException"));
    }

    @Test
    void eventDestinationEnabled_coercesStringsAndDefaultsToFalse() {
        String path = "/v2/email/configuration-sets/" + CS + "/event-destinations/" + ED;
        String read = "/v2/email/configuration-sets/" + CS + "/event-destinations";
        // Created with "yes": a create path that ignored the string would read back the false default.
        get(read).body("EventDestinations[0].Enabled", equalTo(true));

        send("PUT", path, eventDestination("\"Enabled\": \"no\",")).statusCode(200);
        get(read).body("EventDestinations[0].Enabled", equalTo(false));
        send("PUT", path, eventDestination("\"Enabled\": \"yes\",")).statusCode(200);
        get(read).body("EventDestinations[0].Enabled", equalTo(true));
        send("PUT", path, eventDestination("")).statusCode(200);
        get(read).body("EventDestinations[0].Enabled", equalTo(false));
        send("PUT", path, eventDestination("\"Enabled\": \"abc\",")).statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo(NOT_A_BOOLEAN));
    }

    @Test
    void accountSendingEnabled_coercesStringsAndDefaultsToFalse() {
        send("PUT", "/v2/email/account/sending", "{\"SendingEnabled\":\"no\"}").statusCode(200);
        get("/v2/email/account").body("SendingEnabled", equalTo(false));
        send("PUT", "/v2/email/account/sending", "{\"SendingEnabled\":\"yes\"}").statusCode(200);
        get("/v2/email/account").body("SendingEnabled", equalTo(true));
        send("PUT", "/v2/email/account/sending", "{\"SendingEnabled\":\"abc\"}").statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo(NOT_A_BOOLEAN));
        send("PUT", "/v2/email/account/sending", "{\"SendingEnabled\":null}").statusCode(400)
                .body("__type", equalTo("SerializationException"));
        get("/v2/email/account").body("SendingEnabled", equalTo(true));
        send("PUT", "/v2/email/account/sending", "{}").statusCode(200);
        get("/v2/email/account").body("SendingEnabled", equalTo(false));
        send("PUT", "/v2/email/account/sending", "{\"SendingEnabled\":true}").statusCode(200);
    }

    @Test
    void productionAccessEnabled_acceptsABooleanString() {
        send("POST", "/v2/email/account/details", """
                {"MailType": "TRANSACTIONAL", "WebsiteURL": "https://example.com",
                 "ProductionAccessEnabled": "yes"}
                """).statusCode(200);
    }

    // "no" then "yes" tells a coerced value from a default, {} proves absence means false, and
    // "abc" is the fallback error.
    private void assertStored(String path, String member, String readPath, String readField) {
        send("PUT", path, "{\"" + member + "\":\"yes\"}").statusCode(200);
        get(readPath).body(readField, equalTo(true));
        send("PUT", path, "{\"" + member + "\":\"no\"}").statusCode(200);
        get(readPath).body(readField, equalTo(false));
        send("PUT", path, "{\"" + member + "\":\"TRUE\"}").statusCode(200);
        get(readPath).body(readField, equalTo(true));
        send("PUT", path, "{}").statusCode(200);
        get(readPath).body(readField, equalTo(false));
        send("PUT", path, "{\"" + member + "\":\"abc\"}").statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo(NOT_A_BOOLEAN));
    }

    private static String eventDestination(String enabledMember) {
        return """
                {"EventDestination": {%s "MatchingEventTypes": ["SEND"],
                  "SnsDestination": {"TopicArn": "arn:aws:sns:%s:000000000000:coercion"}}}
                """.formatted(enabledMember, REGION);
    }

    private static ValidatableResponse send(String method, String path, String body) {
        return given().contentType("application/json").header("Authorization", AUTH).body(body)
                .when().request(method, path).then();
    }

    private static ValidatableResponse get(String path) {
        return given().header("Authorization", AUTH).when().get(path).then().statusCode(200);
    }
}
