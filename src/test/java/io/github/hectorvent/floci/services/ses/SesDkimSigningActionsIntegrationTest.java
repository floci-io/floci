package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Integration tests for the DKIM actions: v1 {@code VerifyDomainDkim} / {@code SetIdentityDkimEnabled}
 * and v2 {@code PutEmailIdentityDkimSigningAttributes}, plus the email-inherits-parent-domain DKIM
 * behavior. Verified against real AWS (tokens stable on VerifyDomainDkim; email DKIM mirrors the
 * parent domain; DKIM status tracks DNS detection, not the enabled flag).
 */
@QuarkusTest
class SesDkimSigningActionsIntegrationTest {

    private static final String V1_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    private static final String V2_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    private io.restassured.specification.RequestSpecification v1(String action) {
        return given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", V1_AUTH).formParam("Action", action);
    }

    @Test
    void verifyDomainDkim_returnsThreeStableTokens_andRegistersDomain() {
        String domain = "dkim-verify.floci.test";
        List<String> first = XmlParser.extractAll(v1("VerifyDomainDkim").formParam("Domain", domain)
        .when().post("/").then().statusCode(200).extract().asString(), "member");
        assertEquals(3, first.size());

        // Stable across calls (AWS does not regenerate).
        List<String> second = XmlParser.extractAll(v1("VerifyDomainDkim").formParam("Domain", domain)
        .when().post("/").then().statusCode(200).extract().asString(), "member");
        assertEquals(first, second);

        // The domain is now a DKIM identity, visible via GetIdentityDkimAttributes.
        v1("GetIdentityDkimAttributes").formParam("Identities.member.1", domain)
        .when().post("/").then().statusCode(200)
                .body(containsString("<key>" + domain + "</key>"))
                .body(containsString("<DkimEnabled>true</DkimEnabled>"));
    }

    @Test
    void setIdentityDkimEnabled_togglesFlag_andUnknownIdentityErrors() {
        String domain = "dkim-toggle.floci.test";
        v1("VerifyDomainIdentity").formParam("Domain", domain).when().post("/").then().statusCode(200);

        v1("SetIdentityDkimEnabled").formParam("Identity", domain).formParam("DkimEnabled", "false")
        .when().post("/").then().statusCode(200)
                .body(containsString("SetIdentityDkimEnabledResponse"));

        v1("GetIdentityDkimAttributes").formParam("Identities.member.1", domain)
        .when().post("/").then().statusCode(200)
                .body(containsString("<DkimEnabled>false</DkimEnabled>"));

        // Unknown identity -> v1-native InvalidParameterValue.
        v1("SetIdentityDkimEnabled").formParam("Identity", "unknown-dkim.floci.test")
                .formParam("DkimEnabled", "true")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("is not verified for DKIM signing."));
    }

    @Test
    void putDkimSigningAttributes_awsSes_regeneratesTokensOnKeyLengthChange() {
        String domain = "dkim-signing.floci.test";
        List<String> before = given().contentType("application/json").header("Authorization", V2_AUTH)
                .body("{\"EmailIdentity\":\"" + domain + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200)
                .extract().path("DkimAttributes.Tokens");

        // Change the key length (default is RSA_2048_BIT) -> tokens regenerate.
        Response resp = given().contentType("application/json").header("Authorization", V2_AUTH)
                .body("{\"SigningAttributesOrigin\":\"AWS_SES\","
                        + "\"SigningAttributes\":{\"NextSigningKeyLength\":\"RSA_1024_BIT\"}}")
        .when().put("/v2/email/identities/" + domain + "/dkim/signing").then().statusCode(200)
                .body("DkimTokens", hasSize(3))
                // Regenerated tokens reset the status: the new CNAMEs are not yet detected in DNS.
                .body("DkimStatus", equalTo("PENDING")).extract().response();
        List<String> after = resp.path("DkimTokens");
        assertNotEquals(before, after, "changing the key length must regenerate the tokens");

        given().header("Authorization", V2_AUTH)
        .when().get("/v2/email/identities/" + domain).then().statusCode(200)
                .body("DkimAttributes.CurrentSigningKeyLength", equalTo("RSA_1024_BIT"))
                .body("DkimAttributes.Tokens", equalTo(after));
    }

    @Test
    void putDkimSigningAttributes_emailIdentity_returns400() {
        // DKIM signing attributes are domain-level; an email-shaped identity is rejected.
        given().contentType("application/json").header("Authorization", V2_AUTH)
                .body("{\"SigningAttributesOrigin\":\"AWS_SES\"}")
        .when().put("/v2/email/identities/bob@dkim-emailsigning.floci.test/dkim/signing")
        .then().statusCode(400).body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("The EmailIdentity value must be a valid domain."));
    }

    @Test
    void putDkimSigningAttributes_unknownIdentity_returns404() {
        given().contentType("application/json").header("Authorization", V2_AUTH)
                .body("{\"SigningAttributesOrigin\":\"AWS_SES\"}")
        .when().put("/v2/email/identities/ghost-signing.floci.test/dkim/signing")
        .then().statusCode(404).body("__type", equalTo("NotFoundException"));
    }

    @Test
    void putDkimSigningAttributes_externalWithBlankPrivateKey_returns400() {
        String domain = "dkim-external.floci.test";
        given().contentType("application/json").header("Authorization", V2_AUTH)
                .body("{\"EmailIdentity\":\"" + domain + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);

        given().contentType("application/json").header("Authorization", V2_AUTH)
                .body("{\"SigningAttributesOrigin\":\"EXTERNAL\",\"SigningAttributes\":"
                        + "{\"DomainSigningSelector\":\"sel\",\"DomainSigningPrivateKey\":\"\"}}")
        .when().put("/v2/email/identities/" + domain + "/dkim/signing")
        .then().statusCode(400).body("__type", equalTo("BadRequestException"));
    }

    @Test
    void setIdentityDkimEnabled_missingIdentity_returns400() {
        v1("SetIdentityDkimEnabled").formParam("DkimEnabled", "true")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("Identity is required."));
    }

    @Test
    void verifyDomainDkim_whitespaceDomain_returns400() {
        v1("VerifyDomainDkim").formParam("Domain", " dkim-ws.floci.test")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("whitespace"));
    }

    @Test
    void verifyDomainDkim_emailShapedInput_returns400() {
        v1("VerifyDomainDkim").formParam("Domain", "bob@dkim-bad.floci.test")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    void putDkimSigningAttributes_invalidKeyLength_returns400() {
        String domain = "dkim-badkeylen.floci.test";
        given().contentType("application/json").header("Authorization", V2_AUTH)
                .body("{\"EmailIdentity\":\"" + domain + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);

        given().contentType("application/json").header("Authorization", V2_AUTH)
                .body("{\"SigningAttributesOrigin\":\"AWS_SES\","
                        + "\"SigningAttributes\":{\"NextSigningKeyLength\":\"RSA_4096_BIT\"}}")
        .when().put("/v2/email/identities/" + domain + "/dkim/signing")
        .then().statusCode(400).body("__type", equalTo("BadRequestException"));
    }

    @Test
    void putDkimSigningAttributes_external_clearsTokensAndResetsStatusToPending() {
        String domain = "dkim-byodkim.floci.test";
        given().contentType("application/json").header("Authorization", V2_AUTH)
                .body("{\"EmailIdentity\":\"" + domain + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);

        given().contentType("application/json").header("Authorization", V2_AUTH)
                .body("{\"SigningAttributesOrigin\":\"EXTERNAL\",\"SigningAttributes\":"
                        + "{\"DomainSigningSelector\":\"sel\",\"DomainSigningPrivateKey\":\"key\"}}")
        .when().put("/v2/email/identities/" + domain + "/dkim/signing").then().statusCode(200)
                .body("DkimStatus", equalTo("PENDING"))
                .body("DkimTokens", hasSize(0));

        given().header("Authorization", V2_AUTH)
        .when().get("/v2/email/identities/" + domain).then().statusCode(200)
                .body("DkimAttributes.Status", equalTo("PENDING"))
                .body("DkimAttributes.SigningAttributesOrigin", equalTo("EXTERNAL"))
                .body("DkimAttributes.Tokens", hasSize(0));
    }

    @Test
    void putDkimSigningAttributes_nonObjectBody_returns400() {
        given().contentType("application/json").header("Authorization", V2_AUTH).body("[]")
        .when().put("/v2/email/identities/dkim-nonobj.floci.test/dkim/signing")
        .then().statusCode(400).body("__type", equalTo("BadRequestException"));
    }

    @Test
    void putEmailIdentityDkimAttributes_existingEmailWithRegisteredDomain_isNoOp() {
        String domain = "dkim-emailtoggle.floci.test";
        given().contentType("application/json").header("Authorization", V2_AUTH)
                .body("{\"EmailIdentity\":\"" + domain + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);
        given().contentType("application/json").header("Authorization", V2_AUTH)
                .body("{\"EmailIdentity\":\"bob@" + domain + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);

        // Toggling DKIM on the email is a no-op (DKIM is domain-controlled) — 200, nothing changes.
        given().contentType("application/json").header("Authorization", V2_AUTH)
                .body("{\"SigningEnabled\": false}")
        .when().put("/v2/email/identities/bob@" + domain + "/dkim").then().statusCode(200);

        // The email still reports the domain's DKIM (enabled), and the domain is untouched.
        given().header("Authorization", V2_AUTH)
        .when().get("/v2/email/identities/bob@" + domain).then().statusCode(200)
                .body("DkimAttributes.SigningEnabled", equalTo(true));
        given().header("Authorization", V2_AUTH)
        .when().get("/v2/email/identities/" + domain).then().statusCode(200)
                .body("DkimAttributes.SigningEnabled", equalTo(true));
    }

    @Test
    void emailIdentity_inheritsParentDomainDkim() {
        String domain = "dkim-inherit.floci.test";
        List<String> domainTokens = given().contentType("application/json").header("Authorization", V2_AUTH)
                .body("{\"EmailIdentity\":\"" + domain + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200)
                .extract().path("DkimAttributes.Tokens");

        // An email under that domain inherits the domain's DKIM (tokens, enabled, pending status).
        given().contentType("application/json").header("Authorization", V2_AUTH)
                .body("{\"EmailIdentity\":\"bob@" + domain + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);

        given().header("Authorization", V2_AUTH)
        .when().get("/v2/email/identities/bob@" + domain).then().statusCode(200)
                .body("DkimAttributes.SigningEnabled", equalTo(true))
                .body("DkimAttributes.Status", equalTo("PENDING"))
                .body("DkimAttributes.Tokens", equalTo(domainTokens))
                .body("DkimAttributes.Tokens", not(hasSize(0)));
    }
}
