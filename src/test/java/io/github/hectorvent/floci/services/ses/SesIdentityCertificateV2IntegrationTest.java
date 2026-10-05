package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SesIdentityCertificateV2IntegrationTest {

    private static final String SES_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String ACM_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/acm/aws4_request";
    private static final String MISSING_CERT_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/00000000-0000-0000-0000-000000000000";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void matchingCertificateOnAVerifiedIdentityIsActive() {
        String identity = "smime-active@floci.test";
        createIdentity(identity);
        String certificateArn = importCertificate(identity);
        associate(identity, null, certificateArn).then().statusCode(200).body(equalTo("{}"));

        Response list = list("{\"EmailIdentity\":\"" + identity + "\"}");
        list.then().statusCode(200)
                .body("Certificates.FromAddress", equalTo(List.of(identity)))
                .body("Certificates[0].Status", equalTo("ACTIVE"))
                .body("Certificates[0].CertificateArn", equalTo(certificateArn))
                .body("NextToken", nullValue());
        double expiry = list.jsonPath().getDouble("Certificates[0].CertificateExpiryTime");
        assertTrue(expiry > 0, "CertificateExpiryTime is epoch seconds");
    }

    @Test
    void rsa1024CertificateImportedThroughAcmIsFailed() {
        String identity = "smime-rsa1024@floci.test";
        createIdentity(identity);
        String certificateArn = importCertificate(SmimeTestCertificates.rsa(identity, 1024));
        associate(identity, null, certificateArn).then().statusCode(200);

        list("{\"EmailIdentity\":\"" + identity + "\"}").then().statusCode(200)
                .body("Certificates.Status", equalTo(List.of("FAILED")))
                .body("Certificates[0]", not(hasKey("CertificateExpiryTime")));
    }

    @Test
    void unverifiedDomainListsEveryAssociationAsFailedAndPages() {
        String domain = "smime-list.floci.test";
        createIdentity(domain);
        String certificateArn = importCertificate("alice@" + domain);

        associate(domain, "bob@" + domain, MISSING_CERT_ARN).then().statusCode(200);
        associate(domain, "alice@" + domain, certificateArn).then().statusCode(200);

        list("{\"EmailIdentity\":\"" + domain + "\"}").then().statusCode(200)
                .body("Certificates.FromAddress", equalTo(List.of("alice@" + domain, "bob@" + domain)))
                .body("Certificates.Status", equalTo(List.of("FAILED", "FAILED")))
                .body("Certificates[0]", not(hasKey("CertificateExpiryTime")))
                .body("NextToken", nullValue());

        Response firstPage = list("{\"EmailIdentity\":\"" + domain + "\",\"PageSize\":1}");
        String token = firstPage.jsonPath().getString("NextToken");
        assertEquals(List.of("alice@" + domain), firstPage.jsonPath().getList("Certificates.FromAddress"));
        list("{\"EmailIdentity\":\"" + domain + "\",\"PageSize\":1,\"NextToken\":\"" + token + "\"}")
                .then().statusCode(200)
                .body("Certificates.FromAddress", equalTo(List.of("bob@" + domain)));
    }

    @Test
    void requestErrorsFollowSes() {
        String domain = "smime-errors.floci.test";
        createIdentity(domain);

        associate(domain, null, MISSING_CERT_ARN).then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("FromAddress is required when EmailIdentity is a domain."));
        associate("nosuch.floci.test", "alice@nosuch.floci.test", MISSING_CERT_ARN).then().statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("Email identity <nosuch.floci.test> does not exist."));
        associate(domain, "alice@" + domain, MISSING_CERT_ARN).then().statusCode(200);
        associate(domain, "ALICE@" + domain, MISSING_CERT_ARN).then().statusCode(400)
                .body("__type", equalTo("AlreadyExistsException"))
                .body("message", equalTo("A certificate is already associated with sender <alice@" + domain
                        + "> on identity <" + domain + ">."));
        list("{\"EmailIdentity\":\"" + domain + "\",\"PageSize\":0}").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value '0' at 'pageSize' failed to "
                        + "satisfy constraint: Member must have value greater than or equal to 1"));
        list("{\"EmailIdentity\":\"" + domain + "\",\"NextToken\":\"bogus\"}").then().statusCode(400)
                .body("message", equalTo("Invalid NextToken."));
        list("{\"EmailIdentity\":\" padded.floci.test\"}").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));
        list("{\"EmailIdentity\":123}").then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
    }

    @Test
    void disassociateIsIdempotent() {
        String domain = "smime-disassociate.floci.test";
        createIdentity(domain);
        associate(domain, "alice@" + domain, MISSING_CERT_ARN).then().statusCode(200);

        disassociate("{\"EmailIdentity\":\"" + domain + "\",\"FromAddress\":\"alice@" + domain + "\"}")
                .then().statusCode(200).body(equalTo("{}"));
        disassociate("{\"EmailIdentity\":\"" + domain + "\",\"FromAddress\":\"alice@" + domain + "\"}")
                .then().statusCode(200);

        list("{\"EmailIdentity\":\"" + domain + "\"}").then().statusCode(200)
                .body("Certificates", equalTo(List.of()));
    }

    @Test
    void deleteEmailIdentityIsRefusedWhileACertificateIsAssociated() {
        String identity = "smime-delete@floci.test";
        createIdentity(identity);
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"EmailIdentity\":\"" + identity + "\",\"CertificateArn\":\"" + MISSING_CERT_ARN + "\"}")
        .when().post("/v2/email/identity/certificates").then().statusCode(200);

        given().header("Authorization", SES_AUTH)
        .when().delete("/v2/email/identities/" + identity).then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Cannot delete <arn:aws:ses:us-east-1:000000000000:identity/" + identity
                        + "> because it has certificates associated with it. "
                        + "Disassociate all certificates and try again."));

        disassociate("{\"EmailIdentity\":\"" + identity + "\"}").then().statusCode(200);
        given().header("Authorization", SES_AUTH)
        .when().delete("/v2/email/identities/" + identity).then().statusCode(200);
    }

    @Test
    void v1DeletesShareTheGuard() {
        String identity = "smime-v1-delete@floci.test";
        createIdentity(identity);
        associate(identity, null, MISSING_CERT_ARN).then().statusCode(200);

        v1Delete("DeleteIdentity", "Identity", identity).then().statusCode(400)
                .body(containsString("<Code>BadRequestException</Code>"))
                .body(containsString("because it has certificates associated with it."));
        v1Delete("DeleteVerifiedEmailAddress", "EmailAddress", identity).then().statusCode(400)
                .body(containsString("<Code>BadRequestException</Code>"))
                .body(containsString("because it has certificates associated with it."));

        disassociate("{\"EmailIdentity\":\"" + identity + "\"}").then().statusCode(200);
        v1Delete("DeleteVerifiedEmailAddress", "EmailAddress", identity).then().statusCode(200);
    }

    private static Response v1Delete(String action, String member, String identity) {
        return given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request")
                .formParam("Action", action)
                .formParam(member, identity)
                .when().post("/");
    }

    private static void createIdentity(String identity) {
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"EmailIdentity\":\"" + identity + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);
    }

    private static Response associate(String identity, String fromAddress, String certificateArn) {
        String from = fromAddress == null ? "" : ",\"FromAddress\":\"" + fromAddress + "\"";
        return given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"EmailIdentity\":\"" + identity + "\"" + from
                        + ",\"CertificateArn\":\"" + certificateArn + "\"}")
                .when().post("/v2/email/identity/certificates");
    }

    private static Response disassociate(String body) {
        return given().contentType("application/json").header("Authorization", SES_AUTH)
                .body(body).when().post("/v2/email/identity/certificates/delete");
    }

    private static Response list(String body) {
        return given().contentType("application/json").header("Authorization", SES_AUTH)
                .body(body).when().post("/v2/email/identity/certificates/list");
    }

    private static String importCertificate(String email) {
        return importCertificate(SmimeTestCertificates.rsa(email, 2048));
    }

    private static String importCertificate(SmimeTestCertificates.Pem pem) {
        String certJson = pem.certificate().replace("\r\n", "\n").replace("\n", "\\n");
        String keyJson = pem.privateKey().replace("\r\n", "\n").replace("\n", "\\n");
        return given().header("X-Amz-Target", "CertificateManager.ImportCertificate")
                .header("Authorization", ACM_AUTH)
                .contentType("application/x-amz-json-1.1")
                .body("{\"Certificate\":\"" + certJson + "\",\"PrivateKey\":\"" + keyJson + "\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("CertificateArn");
    }
}
