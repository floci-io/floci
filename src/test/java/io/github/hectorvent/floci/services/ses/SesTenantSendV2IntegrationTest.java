package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests for SES v2 tenant-scoped sending (Phase 4): the {@code TenantName} member on
 * {@code SendEmail} and {@code SendBulkEmail}. A send whose resources are not associated with the
 * tenant is refused with a 403 {@code AccessDeniedException} listing every missing ARN — probed for
 * the From identity (exact-address precedence included) and the configuration set; the
 * stored-template gate is inferred from the association model, not observed. The send-path
 * tenant-not-found wording differs from the management operations (no angle brackets, account id
 * included, probe-confirmed). The tenant's SendingStatus is not checked — nothing can move it off
 * ENABLED in the emulator.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesTenantSendV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String TENANT = "floci-send-tenant";
    private static final String DOMAIN = "floci-send.example.com";
    private static final String FROM = "no-reply@" + DOMAIN;
    private static final String CONFIG_SET = "floci-send-cs";
    private static final String TEMPLATE = "floci-send-tpl";
    private static final String ARN_PREFIX = "arn:aws:ses:us-east-1:000000000000:";

    private static io.restassured.specification.RequestSpecification v2() {
        return given().contentType("application/json").header("Authorization", AUTH);
    }

    private static String simpleSend(String tenantName, String configSetLine) {
        return "{\"FromEmailAddress\":\"" + FROM + "\","
                + "\"Destination\":{\"ToAddresses\":[\"success@simulator.amazonses.com\"]},"
                + (tenantName == null ? "" : "\"TenantName\":\"" + tenantName + "\",")
                + configSetLine
                + "\"Content\":{\"Simple\":{\"Subject\":{\"Data\":\"s\"},"
                + "\"Body\":{\"Text\":{\"Data\":\"t\"}}}}}";
    }

    private static void associate(String arn) {
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"ResourceArn\":\"" + arn + "\"}")
                .when().post("/v2/email/tenants/resources").then().statusCode(200);
    }

    @Test
    @Order(1)
    void setup_tenantAndResources() {
        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants").then().statusCode(200);
        v2().body("{\"EmailIdentity\":\"" + DOMAIN + "\"}")
                .when().post("/v2/email/identities").then().statusCode(200);
        v2().body("{\"ConfigurationSetName\":\"" + CONFIG_SET + "\"}")
                .when().post("/v2/email/configuration-sets").then().statusCode(200);
        v2().body("{\"TemplateName\":\"" + TEMPLATE + "\",\"TemplateContent\":{\"Subject\":\"s\",\"Text\":\"t\"}}")
                .when().post("/v2/email/templates").then().statusCode(200);
    }

    @Test
    @Order(2)
    void send_ghostTenant_sendFlavoredNotFound() {
        v2().body(simpleSend("ghost-tenant", ""))
                .when().post("/v2/email/outbound-emails").then().statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo(
                        "Tenant ghost-tenant for AwsAccountId 000000000000 not found."));
    }

    @Test
    @Order(3)
    void send_validationOrderMatchesAws() {
        // Probe-confirmed split: request-shape errors win over the ghost tenant...
        v2().body("{\"FromEmailAddress\":\"" + FROM + "\",\"TenantName\":\"ghost-tenant\","
                        + "\"Destination\":{\"ToAddresses\":[\"success@simulator.amazonses.com\"]}}")
                .when().post("/v2/email/outbound-emails").then().statusCode(400);
        v2().body("{\"FromEmailAddress\":\"" + FROM + "\",\"TenantName\":\"ghost-tenant\","
                        + "\"Destination\":{\"ToAddresses\":[\"success@simulator.amazonses.com\"]},"
                        + "\"Content\":{\"Template\":{\"TemplateContent\":{},\"TemplateData\":\"{}\"}}}")
                .when().post("/v2/email/outbound-emails").then().statusCode(400);

        // ...while the recipient check and the raw sender derivation lose to the tenant 404.
        v2().body("{\"FromEmailAddress\":\"" + FROM + "\",\"TenantName\":\"ghost-tenant\","
                        + "\"Destination\":{\"ToAddresses\":[]},"
                        + "\"Content\":{\"Simple\":{\"Subject\":{\"Data\":\"s\"},"
                        + "\"Body\":{\"Text\":{\"Data\":\"t\"}}}}}")
                .when().post("/v2/email/outbound-emails").then().statusCode(404)
                .body("message", equalTo(
                        "Tenant ghost-tenant for AwsAccountId 000000000000 not found."));
    }

    @Test
    @Order(4)
    void send_unassociatedIdentity_is403() {
        // The domain identity backs the From address, so its ARN is the one in the message.
        v2().body(simpleSend(TENANT, ""))
                .when().post("/v2/email/outbound-emails").then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", equalTo("Tenant not associated with resources ["
                        + ARN_PREFIX + "identity/" + DOMAIN + "]."));
    }

    @Test
    @Order(5)
    void send_associatedIdentity_succeeds_unassociatedConfigSetStill403() {
        associate(ARN_PREFIX + "identity/" + DOMAIN);
        v2().body(simpleSend(TENANT, ""))
                .when().post("/v2/email/outbound-emails").then().statusCode(200)
                .body("MessageId", notNullValue());

        v2().body(simpleSend(TENANT, "\"ConfigurationSetName\":\"" + CONFIG_SET + "\","))
                .when().post("/v2/email/outbound-emails").then().statusCode(403)
                .body("message", equalTo("Tenant not associated with resources ["
                        + ARN_PREFIX + "configuration-set/" + CONFIG_SET + "]."));

        associate(ARN_PREFIX + "configuration-set/" + CONFIG_SET);
        v2().body(simpleSend(TENANT, "\"ConfigurationSetName\":\"" + CONFIG_SET + "\","))
                .when().post("/v2/email/outbound-emails").then().statusCode(200)
                .body("MessageId", notNullValue());
    }

    @Test
    @Order(6)
    void send_templated_requiresTemplateAssociation() {
        String templatedSend = "{\"FromEmailAddress\":\"" + FROM + "\","
                + "\"Destination\":{\"ToAddresses\":[\"success@simulator.amazonses.com\"]},"
                + "\"TenantName\":\"" + TENANT + "\","
                + "\"Content\":{\"Template\":{\"TemplateName\":\"" + TEMPLATE + "\","
                + "\"TemplateData\":\"{}\"}}}";
        v2().body(templatedSend)
                .when().post("/v2/email/outbound-emails").then().statusCode(403)
                .body("message", equalTo("Tenant not associated with resources ["
                        + ARN_PREFIX + "template/" + TEMPLATE + "]."));

        associate(ARN_PREFIX + "template/" + TEMPLATE);
        v2().body(templatedSend)
                .when().post("/v2/email/outbound-emails").then().statusCode(200)
                .body("MessageId", notNullValue());
    }

    @Test
    @Order(7)
    void send_nonStringTenantName_isSerializationError_displayNameFromResolves() {
        v2().body("{\"FromEmailAddress\":\"" + FROM + "\",\"TenantName\":123,"
                        + "\"Destination\":{\"ToAddresses\":[\"success@simulator.amazonses.com\"]},"
                        + "\"Content\":{\"Simple\":{\"Subject\":{\"Data\":\"s\"},"
                        + "\"Body\":{\"Text\":{\"Data\":\"t\"}}}}}")
                .when().post("/v2/email/outbound-emails").then().statusCode(400)
                .body("__type", equalTo("SerializationException"));

        // Display-name syntax must not break the identity resolution behind the gate.
        v2().body("{\"FromEmailAddress\":\"Floci <" + FROM + ">\","
                        + "\"Destination\":{\"ToAddresses\":[\"success@simulator.amazonses.com\"]},"
                        + "\"TenantName\":\"" + TENANT + "\","
                        + "\"Content\":{\"Simple\":{\"Subject\":{\"Data\":\"s\"},"
                        + "\"Body\":{\"Text\":{\"Data\":\"t\"}}}}}")
                .when().post("/v2/email/outbound-emails").then().statusCode(200)
                .body("MessageId", notNullValue());

        // An exact-address identity takes precedence over the (associated) domain identity — the
        // probed AWS 403 named the address identity's ARN in exactly this setup.
        v2().body("{\"EmailIdentity\":\"" + FROM + "\"}")
                .when().post("/v2/email/identities").then().statusCode(200);
        v2().body(simpleSend(TENANT, ""))
                .when().post("/v2/email/outbound-emails").then().statusCode(403)
                .body("message", equalTo("Tenant not associated with resources ["
                        + ARN_PREFIX + "identity/" + FROM + "]."));
        associate(ARN_PREFIX + "identity/" + FROM);
        v2().body(simpleSend(TENANT, ""))
                .when().post("/v2/email/outbound-emails").then().statusCode(200)
                .body("MessageId", notNullValue());
    }

    @Test
    @Order(8)
    void rawSend_gatesTheMimeDerivedSender() {
        v2().body("{\"EmailIdentity\":\"floci-raw.example.com\"}")
                .when().post("/v2/email/identities").then().statusCode(200);
        String mime = "From: probe@floci-raw.example.com\r\nTo: success@simulator.amazonses.com\r\n"
                + "Subject: s\r\n\r\nbody";
        String data = java.util.Base64.getEncoder().encodeToString(mime.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // FromEmailAddress omitted: the sender comes from the MIME From header, and that identity
        // is not associated with the tenant.
        v2().body("{\"TenantName\":\"" + TENANT + "\","
                        + "\"Destination\":{\"ToAddresses\":[\"success@simulator.amazonses.com\"]},"
                        + "\"Content\":{\"Raw\":{\"Data\":\"" + data + "\"}}}")
                .when().post("/v2/email/outbound-emails").then().statusCode(403)
                .body("message", equalTo("Tenant not associated with resources ["
                        + ARN_PREFIX + "identity/floci-raw.example.com]."));
    }

    @Test
    @Order(9)
    void send_gatesTheIdentityDefaultConfigurationSet() {
        v2().body("{\"ConfigurationSetName\":\"floci-send-default-cs\"}")
                .when().post("/v2/email/configuration-sets").then().statusCode(200);
        v2().body("{\"ConfigurationSetName\":\"floci-send-default-cs\"}")
                .when().put("/v2/email/identities/" + DOMAIN + "/configuration-set")
                .then().statusCode(200);

        // No explicit ConfigurationSetName — the identity's default is the effective one, and it
        // needs the association just the same.
        v2().body(simpleSend(TENANT, ""))
                .when().post("/v2/email/outbound-emails").then().statusCode(403)
                .body("message", equalTo("Tenant not associated with resources ["
                        + ARN_PREFIX + "configuration-set/floci-send-default-cs]."));

        associate(ARN_PREFIX + "configuration-set/floci-send-default-cs");
        v2().body(simpleSend(TENANT, ""))
                .when().post("/v2/email/outbound-emails").then().statusCode(200)
                .body("MessageId", notNullValue());
    }

    @Test
    @Order(10)
    void bulkSend_contentValidationPrecedesTenantExistence() {
        // Probe-confirmed: a bulk send without template content fails on the content before the
        // ghost tenant is even looked at. The same holds for the rest of the request shape.
        v2().body("{\"FromEmailAddress\":\"" + FROM + "\",\"TenantName\":\"ghost-tenant\","
                        + "\"DefaultContent\":{},"
                        + "\"BulkEmailEntries\":[{\"Destination\":{\"ToAddresses\":"
                        + "[\"success@simulator.amazonses.com\"]}}]}")
                .when().post("/v2/email/outbound-bulk-emails").then().statusCode(400);
        v2().body("{\"FromEmailAddress\":\"" + FROM + "\",\"TenantName\":\"ghost-tenant\","
                        + "\"DefaultContent\":{\"Template\":{\"TemplateName\":\"" + TEMPLATE + "\","
                        + "\"TemplateData\":\"{}\"}},\"BulkEmailEntries\":[]}")
                .when().post("/v2/email/outbound-bulk-emails").then().statusCode(400);
        // An all-blank inline template is a shape error too — 400 before the ghost tenant's 404.
        v2().body("{\"FromEmailAddress\":\"" + FROM + "\",\"TenantName\":\"ghost-tenant\","
                        + "\"DefaultContent\":{\"Template\":{\"TemplateContent\":{},"
                        + "\"TemplateData\":\"{}\"}},"
                        + "\"BulkEmailEntries\":[{\"Destination\":{\"ToAddresses\":"
                        + "[\"success@simulator.amazonses.com\"]}}]}")
                .when().post("/v2/email/outbound-bulk-emails").then().statusCode(400);

        v2().body("{\"FromEmailAddress\":\"" + FROM + "\",\"TenantName\":\"ghost-tenant\","
                        + "\"DefaultContent\":{\"Template\":{\"TemplateName\":\"" + TEMPLATE + "\","
                        + "\"TemplateData\":\"{}\"}},"
                        + "\"BulkEmailEntries\":[{\"Destination\":{\"ToAddresses\":"
                        + "[\"success@simulator.amazonses.com\"]}}]}")
                .when().post("/v2/email/outbound-bulk-emails").then().statusCode(404)
                .body("message", equalTo(
                        "Tenant ghost-tenant for AwsAccountId 000000000000 not found."));
    }

    @Test
    @Order(11)
    void bulkSend_withAssociatedResources_succeeds() {
        v2().body("{\"FromEmailAddress\":\"" + FROM + "\",\"TenantName\":\"" + TENANT + "\","
                        + "\"DefaultContent\":{\"Template\":{\"TemplateName\":\"" + TEMPLATE + "\","
                        + "\"TemplateData\":\"{}\"}},"
                        + "\"BulkEmailEntries\":[{\"Destination\":{\"ToAddresses\":"
                        + "[\"success@simulator.amazonses.com\"]}}]}")
                .when().post("/v2/email/outbound-bulk-emails").then().statusCode(200)
                .body("BulkEmailEntryResults[0].Status", equalTo("SUCCESS"));
    }

    @Test
    @Order(12)
    void cleanup_cascadeAndResources() {
        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants/delete").then().statusCode(200);
        v2().when().delete("/v2/email/templates/" + TEMPLATE).then().statusCode(200);
        v2().when().delete("/v2/email/configuration-sets/" + CONFIG_SET).then().statusCode(200);
        v2().when().delete("/v2/email/configuration-sets/floci-send-default-cs").then().statusCode(200);
        v2().when().delete("/v2/email/identities/" + FROM).then().statusCode(200);
        v2().when().delete("/v2/email/identities/" + DOMAIN).then().statusCode(200);
        v2().when().delete("/v2/email/identities/floci-raw.example.com").then().statusCode(200);
    }
}
