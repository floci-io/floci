package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The signing-certificate lifecycle over the wire. Material is generated rather than hardcoded, so
 * the upload path reads real X.509 and the rejection paths reject things that genuinely are not.
 *
 * <p>IAM state is shared across the suite, so every test makes its own user. The per-user quota is
 * only two certificates, which is small enough to exercise here without starving anything else:
 * each test owns its user, so it owns that user's whole budget.
 */
@QuarkusTest
class SigningCertificateIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";
    private static final CertificateGenerator GENERATOR = new CertificateGenerator();

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    /** EC rather than RSA: these tests generate a lot of material and never check the algorithm. */
    private static String certificatePem() {
        return GENERATOR.generateSelfSignedCertificate(
                "signing.test.local", List.of(), KeyAlgorithm.EC_prime256v1).certificatePem();
    }

    private static String user() {
        String name = "signing-user-" + suffix();
        iam("CreateUser").formParam("UserName", name)
            .when().post("/").then().statusCode(200);
        return name;
    }

    private static String upload(String userName, String body) {
        return iam("UploadSigningCertificate")
            .formParam("UserName", userName)
            .formParam("CertificateBody", body)
        .when().post("/").then().statusCode(200)
            .extract().path("UploadSigningCertificateResponse.UploadSigningCertificateResult"
                    + ".Certificate.CertificateId");
    }

    @Test
    void uploadReturnsTheCertificateWithAnActiveStatusAndAModelShapedId() {
        String userName = user();
        String body = certificatePem();

        String id = iam("UploadSigningCertificate")
            .formParam("UserName", userName)
            .formParam("CertificateBody", body)
        .when().post("/").then().statusCode(200)
            .body("UploadSigningCertificateResponse.UploadSigningCertificateResult.Certificate"
                    + ".UserName", equalTo(userName))
            .body("UploadSigningCertificateResponse.UploadSigningCertificateResult.Certificate"
                    + ".Status", equalTo("Active"))
            .body("UploadSigningCertificateResponse.UploadSigningCertificateResult.Certificate"
                    + ".CertificateBody", containsString("BEGIN CERTIFICATE"))
            .extract().path("UploadSigningCertificateResponse.UploadSigningCertificateResult"
                    + ".Certificate.CertificateId");

        assertNotNull(id);
        // certificateIdType: 24 to 128 characters of [\w]+.
        assertTrue(id.length() >= 24 && id.length() <= 128, "id length: " + id.length());
        assertTrue(id.matches("\\w+"), "id must be word characters only: " + id);
    }

    @Test
    void theUploadDateIsReturned() {
        String userName = user();
        upload(userName, certificatePem());

        iam("ListSigningCertificates").formParam("UserName", userName)
        .when().post("/").then().statusCode(200)
            .body("ListSigningCertificatesResponse.ListSigningCertificatesResult.Certificates"
                    + ".member.UploadDate", containsString("T"));
    }

    @Test
    void listReturnsTheUsersCertificatesAndNoOneElses() {
        String mine = user();
        String theirs = user();
        String myId = upload(mine, certificatePem());
        String theirId = upload(theirs, certificatePem());

        iam("ListSigningCertificates").formParam("UserName", mine)
        .when().post("/").then().statusCode(200)
            .body(containsString(myId))
            .body(not(containsString(theirId)));
    }

    @Test
    void theStatusCanBeSetToEveryDocumentedValue() {
        String userName = user();
        String id = upload(userName, certificatePem());

        // The API Reference gives Active, Inactive and Expired as the valid values, even though
        // the prose only explains the first two.
        for (String status : List.of("Inactive", "Expired", "Active")) {
            iam("UpdateSigningCertificate")
                .formParam("UserName", userName)
                .formParam("CertificateId", id)
                .formParam("Status", status)
            .when().post("/").then().statusCode(200);

            iam("ListSigningCertificates").formParam("UserName", userName)
            .when().post("/").then().statusCode(200)
                .body("ListSigningCertificatesResponse.ListSigningCertificatesResult.Certificates"
                        + ".member.Status", equalTo(status));
        }
    }

    @Test
    void anUnknownStatusIsRejected() {
        String userName = user();
        String id = upload(userName, certificatePem());

        iam("UpdateSigningCertificate")
            .formParam("UserName", userName)
            .formParam("CertificateId", id)
            .formParam("Status", "Revoked")
        .when().post("/").then().statusCode(400)
            .body(containsString("ValidationError"));
    }

    @Test
    void deleteRemovesIt() {
        String userName = user();
        String id = upload(userName, certificatePem());

        iam("DeleteSigningCertificate")
            .formParam("UserName", userName)
            .formParam("CertificateId", id)
        .when().post("/").then().statusCode(200);

        iam("ListSigningCertificates").formParam("UserName", userName)
        .when().post("/").then().statusCode(200)
            .body(not(containsString(id)));
    }

    @Test
    void malformedMaterialIsRejectedAsMalformedCertificate() {
        String userName = user();

        iam("UploadSigningCertificate")
            .formParam("UserName", userName)
            .formParam("CertificateBody", "-----BEGIN CERTIFICATE-----\nnot base64\n"
                    + "-----END CERTIFICATE-----\n")
        .when().post("/").then().statusCode(400)
            .body(containsString("MalformedCertificate"));
    }

    @Test
    void anAbsentBodyIsRejected() {
        String userName = user();

        iam("UploadSigningCertificate").formParam("UserName", userName)
        .when().post("/").then().statusCode(400)
            .body(containsString("ValidationError"));
    }

    /** certificateBodyType caps the body at 16384, which is a request-shape error, not bad PEM. */
    @Test
    void anOverLongBodyIsRejectedBeforeItIsParsed() {
        String userName = user();

        iam("UploadSigningCertificate")
            .formParam("UserName", userName)
            .formParam("CertificateBody", "A".repeat(16385))
        .when().post("/").then().statusCode(400)
            .body(containsString("ValidationError"));
    }

    /** "Users can have up to two X.509 signing certificates", per the User Guide. */
    @Test
    void aThirdCertificateExceedsThePerUserQuota() {
        String userName = user();
        upload(userName, certificatePem());
        upload(userName, certificatePem());

        iam("UploadSigningCertificate")
            .formParam("UserName", userName)
            .formParam("CertificateBody", certificatePem())
        .when().post("/").then().statusCode(409)
            .body(containsString("LimitExceeded"))
            .body(containsString("SigningCertificatesPerUser"));
    }

    /**
     * DuplicateCertificate is account-wide: "the same certificate is associated with an IAM user in
     * the account", so a second user uploading the same material is refused too.
     */
    @Test
    void theSameCertificateCannotBeUploadedTwiceInTheAccount() {
        String first = user();
        String second = user();
        String body = certificatePem();
        upload(first, body);

        iam("UploadSigningCertificate")
            .formParam("UserName", second)
            .formParam("CertificateBody", body)
        .when().post("/").then().statusCode(409)
            .body(containsString("DuplicateCertificate"));
    }

    /**
     * The duplicate check compares the encoded certificate rather than the PEM text, so the same
     * certificate re-wrapped is still the same certificate.
     */
    @Test
    void aReindentedCopyOfTheSameCertificateIsStillADuplicate() {
        String userName = user();
        String body = certificatePem();
        upload(userName, body);

        // Same DER, different surrounding whitespace.
        String reindented = body.replace("\n", "\r\n") + "\n";
        iam("UploadSigningCertificate")
            .formParam("UserName", userName)
            .formParam("CertificateBody", reindented)
        .when().post("/").then().statusCode(409)
            .body(containsString("DuplicateCertificate"));
    }

    @Test
    void aCertificateOfAnotherUserCannotBeUpdatedOrDeleted() {
        String owner = user();
        String other = user();
        String id = upload(owner, certificatePem());

        iam("UpdateSigningCertificate")
            .formParam("UserName", other)
            .formParam("CertificateId", id)
            .formParam("Status", "Inactive")
        .when().post("/").then().statusCode(404)
            .body(containsString("NoSuchEntity"));

        iam("DeleteSigningCertificate")
            .formParam("UserName", other)
            .formParam("CertificateId", id)
        .when().post("/").then().statusCode(404)
            .body(containsString("NoSuchEntity"));
    }

    @Test
    void anUnknownUserIsNoSuchEntity() {
        iam("ListSigningCertificates").formParam("UserName", "absent-" + suffix())
        .when().post("/").then().statusCode(404)
            .body(containsString("NoSuchEntity"));
    }

    /**
     * CertificateId is required on both operations. Omitted, it must be a validation error: the
     * store is a map that rejects a null key, so passing it through would surface as an unhandled
     * failure rather than as the error the model defines.
     */
    @Test
    void anAbsentCertificateIdIsAValidationError() {
        String userName = user();

        iam("DeleteSigningCertificate").formParam("UserName", userName)
        .when().post("/").then().statusCode(400)
            .body(containsString("ValidationError"))
            .body(containsString("certificateId"));

        // Update validates the status first, so this only reaches the lookup with a valid one.
        iam("UpdateSigningCertificate")
            .formParam("UserName", userName)
            .formParam("Status", "Inactive")
        .when().post("/").then().statusCode(400)
            .body(containsString("ValidationError"))
            .body(containsString("certificateId"));
    }

    @Test
    void anUnknownCertificateIdIsNoSuchEntity() {
        String userName = user();

        iam("DeleteSigningCertificate")
            .formParam("UserName", userName)
            .formParam("CertificateId", "ABSENTCERTIFICATEID0000000000")
        .when().post("/").then().statusCode(404)
            .body(containsString("NoSuchEntity"));
    }

    /**
     * UserName is optional on every one of these operations: the model determines it from the
     * access key that signed the request. Signing as the user's own key must reach the user's own
     * certificates without naming them.
     */
    @Test
    void theUserNameIsImpliedFromTheSigningAccessKey() {
        String userName = user();
        String id = upload(userName, certificatePem());
        String akid = iam("CreateAccessKey").formParam("UserName", userName)
            .when().post("/").then().statusCode(200)
            .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");

        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + akid
                        + "/20260227/us-east-1/iam/aws4_request")
                .formParam("Action", "ListSigningCertificates")
        .when().post("/").then().statusCode(200)
            .body(containsString(id))
            .body("ListSigningCertificatesResponse.ListSigningCertificatesResult.Certificates"
                    + ".member.UserName", equalTo(userName));
    }

    @Test
    void listHonoursMaxItemsAndMarker() {
        String userName = user();
        upload(userName, certificatePem());
        upload(userName, certificatePem());

        String marker = iam("ListSigningCertificates")
            .formParam("UserName", userName)
            .formParam("MaxItems", "1")
        .when().post("/").then().statusCode(200)
            .body("ListSigningCertificatesResponse.ListSigningCertificatesResult.IsTruncated",
                    equalTo("true"))
            .extract().path("ListSigningCertificatesResponse.ListSigningCertificatesResult.Marker");

        assertNotNull(marker, "a truncated page must carry a marker");
        iam("ListSigningCertificates")
            .formParam("UserName", userName)
            .formParam("Marker", marker)
        .when().post("/").then().statusCode(200)
            .body("ListSigningCertificatesResponse.ListSigningCertificatesResult.IsTruncated",
                    equalTo("false"));
    }

    /**
     * Without an Authorization header the Query controller infers the service from the action name
     * alone, so these have to be in its IAM set or they fall through to SQS and come back
     * UnsupportedOperation. The IAM namespace on the error is what proves the routing.
     */
    @Test
    void certificateActionsRouteViaTheActionFallbackWhenAuthHeaderAbsent() {
        for (String action : List.of("UploadSigningCertificate", "ListSigningCertificates",
                "UpdateSigningCertificate", "DeleteSigningCertificate")) {
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("Action", action)
                    .formParam("Version", "2010-05-08")
            .when().post("/").then()
                .body(containsString("iam.amazonaws.com/doc/2010-05-08"));
        }
    }
}
