package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Where the tenant association 403 sits among the other errors of a tenant send. It is the last
 * check: AWS reports an unverified sender or return path, a missing configuration set and a missing
 * stored template first (probe-confirmed with an ENABLED tenant, on SendEmail Simple, Template and
 * Raw, and on SendBulkEmail).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesTenantSendOrderV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String TENANT = "floci-order-tenant";
    private static final String DOMAIN = "floci-order.example.com";
    private static final String FROM = "sender@" + DOMAIN;
    private static final String UNVERIFIED = "sender@floci-order-unverified.example.com";
    private static final String CONFIG_SET = "floci-order-cs";
    private static final String TEMPLATE = "floci-order-tpl";
    private static final String ARN_PREFIX = "arn:aws:ses:us-east-1:000000000000:";
    private static final String NOT_VERIFIED =
            "Email address is not verified. The following identities failed the check in region US-EAST-1: ";
    private static final String DESTINATION =
            "\"Destination\":{\"ToAddresses\":[\"success@simulator.amazonses.com\"]},";
    private static final String SIMPLE =
            "\"Content\":{\"Simple\":{\"Subject\":{\"Data\":\"s\"},\"Body\":{\"Text\":{\"Data\":\"t\"}}}}";

    private static RequestSpecification v2() {
        return given().contentType("application/json").header("Authorization", AUTH);
    }

    private static String member(String name, String value) {
        return "\"" + name + "\":\"" + value + "\",";
    }

    private static ValidatableResponse send(String members, String content) {
        return v2().body("{" + member("TenantName", TENANT) + DESTINATION + members + content + "}")
                .when().post("/v2/email/outbound-emails").then();
    }

    private static ValidatableResponse bulk(String members) {
        return v2().body("{" + member("TenantName", TENANT) + members
                        + "\"DefaultContent\":{\"Template\":{\"TemplateContent\":{\"Subject\":\"s\",\"Text\":\"t\"}}},"
                        + "\"BulkEmailEntries\":[{\"Destination\":{\"ToAddresses\":"
                        + "[\"success@simulator.amazonses.com\"]}}]}")
                .when().post("/v2/email/outbound-bulk-emails").then();
    }

    private static String raw(String from) {
        return raw(from, "");
    }

    private static String raw(String from, String extraHeaders) {
        String mime = "From: " + from + "\r\nTo: success@simulator.amazonses.com\r\n" + extraHeaders
                + "Subject: s\r\n\r\nt";
        return "\"Content\":{\"Raw\":{\"Data\":\""
                + Base64.getEncoder().encodeToString(mime.getBytes(StandardCharsets.UTF_8)) + "\"}}";
    }

    private static String storedTemplate(String name) {
        return "\"Content\":{\"Template\":{\"TemplateName\":\"" + name + "\",\"TemplateData\":\"{}\"}}";
    }

    private static void assertNotVerified(ValidatableResponse response, String address) {
        response.statusCode(400)
                .body("__type", equalTo("MessageRejected"))
                .body("message", equalTo(NOT_VERIFIED + address));
    }

    private static void assertNotAssociated(ValidatableResponse response, String resource) {
        response.statusCode(403)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", equalTo("Tenant not associated with resources [" + ARN_PREFIX + resource + "]."));
    }

    @Test
    @Order(1)
    void setup_tenantAndResources() {
        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants").then().statusCode(200);
        SesDomainIdentityTestHelper.createVerified(DOMAIN, AUTH);
        v2().body("{\"ConfigurationSetName\":\"" + CONFIG_SET + "\"}")
                .when().post("/v2/email/configuration-sets").then().statusCode(200);
        v2().body("{\"TemplateName\":\"" + TEMPLATE + "\",\"TemplateContent\":{\"Subject\":\"s\",\"Text\":\"t\"}}")
                .when().post("/v2/email/templates").then().statusCode(200);
    }

    @Test
    @Order(2)
    void unverifiedSender_isReportedBeforeTheUnassociatedIdentity() {
        assertNotVerified(send(member("FromEmailAddress", UNVERIFIED), SIMPLE), UNVERIFIED);
        assertNotVerified(send(member("FromEmailAddress", UNVERIFIED), raw(UNVERIFIED)), UNVERIFIED);
        assertNotVerified(send("", raw(UNVERIFIED)), UNVERIFIED);
        assertNotVerified(bulk(member("FromEmailAddress", UNVERIFIED)), UNVERIFIED);
    }

    @Test
    @Order(3)
    void unverifiedReturnPath_isReportedBeforeTheUnassociatedIdentity() {
        String members = member("FromEmailAddress", FROM) + member("FeedbackForwardingEmailAddress", UNVERIFIED);

        assertNotVerified(send(members, SIMPLE), UNVERIFIED);
        assertNotVerified(bulk(members), UNVERIFIED);
    }

    @Test
    @Order(4)
    void missingConfigurationSet_isReportedBeforeTheAssociationAndTheSender() {
        String ghost = member("ConfigurationSetName", "floci-order-ghost-cs");

        send(member("FromEmailAddress", FROM) + ghost, SIMPLE).statusCode(404)
                .body("message", equalTo("Configuration set <floci-order-ghost-cs> does not exist."));
        send(member("FromEmailAddress", UNVERIFIED) + ghost, SIMPLE).statusCode(404)
                .body("message", equalTo("Configuration set <floci-order-ghost-cs> does not exist."));
        bulk(member("FromEmailAddress", FROM) + ghost).statusCode(404)
                .body("message", equalTo("Configuration set <floci-order-ghost-cs> does not exist."));
    }

    @Test
    @Order(5)
    void missingStoredTemplate_isReportedBeforeTheAssociation() {
        send(member("FromEmailAddress", FROM), storedTemplate("floci-order-ghost-tpl")).statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(6)
    void verifiedSender_unassociatedIdentity_is403() {
        assertNotAssociated(send(member("FromEmailAddress", FROM), SIMPLE), "identity/" + DOMAIN);
        assertNotAssociated(send(member("FromEmailAddress", FROM), raw(FROM)), "identity/" + DOMAIN);
        assertNotAssociated(bulk(member("FromEmailAddress", FROM)), "identity/" + DOMAIN);
    }

    @Test
    @Order(7)
    void associatedIdentity_unassociatedConfigurationSetOrTemplate_is403UnlessTheReturnPathIsUnverified() {
        v2().body("{\"TenantName\":\"" + TENANT + "\",\"ResourceArn\":\"" + ARN_PREFIX + "identity/" + DOMAIN + "\"}")
                .when().post("/v2/email/tenants/resources").then().statusCode(200);
        String withConfigSet = member("FromEmailAddress", FROM) + member("ConfigurationSetName", CONFIG_SET);

        assertNotAssociated(send(withConfigSet, SIMPLE), "configuration-set/" + CONFIG_SET);
        assertNotAssociated(bulk(withConfigSet), "configuration-set/" + CONFIG_SET);
        assertNotAssociated(send(member("FromEmailAddress", FROM), storedTemplate(TEMPLATE)), "template/" + TEMPLATE);
        send(member("FromEmailAddress", FROM), storedTemplate("floci-order-ghost-tpl")).statusCode(404);

        String unverifiedReturnPath = withConfigSet + member("FeedbackForwardingEmailAddress", UNVERIFIED);
        assertNotVerified(send(unverifiedReturnPath, SIMPLE), UNVERIFIED);
        assertNotVerified(bulk(unverifiedReturnPath), UNVERIFIED);
    }

    // The configuration set a raw message names only in its X-SES-CONFIGURATION-SET header is the one
    // the send uses, so it must be associated too.
    @Test
    @Order(8)
    void associatedIdentity_rawHeaderConfigurationSet_is403() {
        assertNotAssociated(send(member("FromEmailAddress", FROM),
                raw(FROM, "X-SES-CONFIGURATION-SET: " + CONFIG_SET + "\r\n")), "configuration-set/" + CONFIG_SET);
    }

    @Test
    @Order(9)
    void cleanup() {
        v2().body("{\"TenantName\":\"" + TENANT + "\"}")
                .when().post("/v2/email/tenants/delete").then().statusCode(200);
        v2().when().delete("/v2/email/templates/" + TEMPLATE).then().statusCode(200);
        v2().when().delete("/v2/email/configuration-sets/" + CONFIG_SET).then().statusCode(200);
        v2().when().delete("/v2/email/identities/" + DOMAIN).then().statusCode(200);
    }
}
