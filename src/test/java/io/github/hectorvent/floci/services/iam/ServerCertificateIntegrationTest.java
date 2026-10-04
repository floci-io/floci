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
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server-certificate lifecycle over the wire. Material is generated rather than hardcoded, so
 * the upload path is exercised with certificates and keys that genuinely belong together, and the
 * rejection paths with ones that genuinely do not.
 *
 * <p>IAM state is shared across the suite, so every test names its own entities uniquely. The
 * per-account quota is deliberately not exercised here: filling it would starve every other test
 * in the suite of its budget. {@code ServerCertificateStateTest} covers it against an isolated
 * store instead.
 */
@QuarkusTest
class ServerCertificateIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";
    private static final CertificateGenerator GENERATOR = new CertificateGenerator();

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static CertificateGenerator.GeneratedCertificate material() {
        return GENERATOR.generateSelfSignedCertificate("certs.test.local", List.of(), KeyAlgorithm.RSA_2048);
    }

    private static String upload(String name, CertificateGenerator.GeneratedCertificate pair) {
        return iam("UploadServerCertificate")
            .formParam("ServerCertificateName", name)
            .formParam("CertificateBody", pair.certificatePem())
            .formParam("PrivateKey", pair.privateKeyPem())
        .when().post("/").then().statusCode(200)
            .extract().path("UploadServerCertificateResponse.UploadServerCertificateResult"
                    + ".ServerCertificateMetadata.Arn");
    }

    /**
     * The reason the key is parsed rather than stored blind. Two separate calls to
     * {@code material()} produce unrelated pairs, so this is a genuine mismatch rather than
     * malformed input, and it must be distinguished from one.
     */
    @Test
    void uploadRejectsAKeyThatDoesNotMatchTheCertificate() {
        iam("UploadServerCertificate")
            .formParam("ServerCertificateName", "sc-mismatch-" + suffix())
            .formParam("CertificateBody", material().certificatePem())
            .formParam("PrivateKey", material().privateKeyPem())
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("KeyPairMismatch"));
    }

    @Test
    void uploadAcceptsAMatchingPairAndMintsAscaMetadata() {
        String name = "sc-ok-" + suffix();
        CertificateGenerator.GeneratedCertificate pair = material();

        String base = "UploadServerCertificateResponse.UploadServerCertificateResult"
                + ".ServerCertificateMetadata.";
        iam("UploadServerCertificate")
            .formParam("ServerCertificateName", name)
            .formParam("CertificateBody", pair.certificatePem())
            .formParam("PrivateKey", pair.privateKeyPem())
        .when().post("/").then()
            .statusCode(200)
            .body(base + "ServerCertificateName", equalTo(name))
            .body(base + "Arn", equalTo("arn:aws:iam::000000000000:server-certificate/" + name))
            .body(base + "Path", equalTo("/"))
            .body(base + "ServerCertificateId", containsString("ASCA"))
            // Read off the certificate's own notAfter, so it must be present.
            .body(base + "Expiration", containsString("Z"));
    }

    /** An EC pair must work too: the key-pair check uses a different signature algorithm for it. */
    @Test
    void uploadAcceptsAnEcKeyPair() {
        String name = "sc-ec-" + suffix();
        CertificateGenerator.GeneratedCertificate pair = GENERATOR.generateSelfSignedCertificate(
                "ec.certs.test.local", List.of(), KeyAlgorithm.EC_prime256v1);

        iam("UploadServerCertificate")
            .formParam("ServerCertificateName", name)
            .formParam("CertificateBody", pair.certificatePem())
            .formParam("PrivateKey", pair.privateKeyPem())
        .when().post("/").then().statusCode(200);
    }

    @Test
    void unreadableMaterialIsMalformedCertificate() {
        CertificateGenerator.GeneratedCertificate pair = material();

        iam("UploadServerCertificate")
            .formParam("ServerCertificateName", "sc-bad-body-" + suffix())
            .formParam("CertificateBody", "not a pem at all")
            .formParam("PrivateKey", pair.privateKeyPem())
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("MalformedCertificate"));

        iam("UploadServerCertificate")
            .formParam("ServerCertificateName", "sc-bad-key-" + suffix())
            .formParam("CertificateBody", pair.certificatePem())
            .formParam("PrivateKey", "not a pem at all")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("MalformedCertificate"));

        iam("UploadServerCertificate")
            .formParam("ServerCertificateName", "sc-bad-chain-" + suffix())
            .formParam("CertificateBody", pair.certificatePem())
            .formParam("PrivateKey", pair.privateKeyPem())
            .formParam("CertificateChain", "not a pem at all")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("MalformedCertificate"));
    }

    /**
     * {@code certificateBodyType} and {@code privateKeyType} are both capped at 16384, and
     * {@code certificateChainType} at 2097152. Material past a cap is a request-shape error, not a
     * malformed certificate, so it has to be distinguished from unreadable PEM.
     */
    @Test
    void materialPastItsModeledLengthIsAValidationError() {
        CertificateGenerator.GeneratedCertificate pair = material();
        String tooLong = "A".repeat(16385);

        iam("UploadServerCertificate")
            .formParam("ServerCertificateName", "sc-long-body-" + suffix())
            .formParam("CertificateBody", tooLong)
            .formParam("PrivateKey", pair.privateKeyPem())
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));

        iam("UploadServerCertificate")
            .formParam("ServerCertificateName", "sc-long-key-" + suffix())
            .formParam("CertificateBody", pair.certificatePem())
            .formParam("PrivateKey", tooLong)
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));

        // A chain of the same size is well within its own, much larger, limit, so it must be
        // judged by parseability rather than by the body's cap.
        iam("UploadServerCertificate")
            .formParam("ServerCertificateName", "sc-long-chain-" + suffix())
            .formParam("CertificateBody", pair.certificatePem())
            .formParam("PrivateKey", pair.privateKeyPem())
            .formParam("CertificateChain", tooLong)
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("MalformedCertificate"));
    }

    /**
     * A PEM reader stops at the first block, so a chain whose first certificate is valid and whose
     * second is not would otherwise be stored and only fail whenever something tried to use it.
     */
    @Test
    void aChainWithABrokenLaterCertificateIsRejected() {
        CertificateGenerator.GeneratedCertificate pair = material();
        String goodThenBad = material().certificatePem()
                + "-----BEGIN CERTIFICATE-----\nnot base64 at all\n-----END CERTIFICATE-----\n";

        iam("UploadServerCertificate")
            .formParam("ServerCertificateName", "sc-badchain2-" + suffix())
            .formParam("CertificateBody", pair.certificatePem())
            .formParam("PrivateKey", pair.privateKeyPem())
            .formParam("CertificateChain", goodThenBad)
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("MalformedCertificate"));

        // A chain of two good certificates is still accepted, so the check is per block rather
        // than a refusal of anything with more than one.
        String twoGood = material().certificatePem() + material().certificatePem();
        iam("UploadServerCertificate")
            .formParam("ServerCertificateName", "sc-goodchain2-" + suffix())
            .formParam("CertificateBody", pair.certificatePem())
            .formParam("PrivateKey", pair.privateKeyPem())
            .formParam("CertificateChain", twoGood)
        .when().post("/").then().statusCode(200);
    }

    /**
     * An omitted chain is allowed, but a present one must be a chain. {@code certificateChainType}
     * has a minimum of 1, so an empty value breaks the request shape, while whitespace satisfies
     * the length and fails as a certificate. Neither may be quietly stored as "no chain".
     */
    @Test
    void aPresentButEmptyOrBlankChainIsRejected() {
        CertificateGenerator.GeneratedCertificate pair = material();

        iam("UploadServerCertificate")
            .formParam("ServerCertificateName", "sc-emptychain-" + suffix())
            .formParam("CertificateBody", pair.certificatePem())
            .formParam("PrivateKey", pair.privateKeyPem())
            .formParam("CertificateChain", "")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));

        iam("UploadServerCertificate")
            .formParam("ServerCertificateName", "sc-blankchain-" + suffix())
            .formParam("CertificateBody", pair.certificatePem())
            .formParam("PrivateKey", pair.privateKeyPem())
            .formParam("CertificateChain", "   \n  ")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("MalformedCertificate"));

        // Omitting it entirely is still fine, and the certificate then has no chain to return.
        String name = "sc-nochain-" + suffix();
        upload(name, pair);
        String got = iam("GetServerCertificate").formParam("ServerCertificateName", name)
            .when().post("/").then().statusCode(200).extract().asString();
        assertTrue(!got.contains("CertificateChain"), "an absent chain is omitted, not empty");
    }

    /**
     * Names are compared case-insensitively, so the duplicate check has to exclude the certificate
     * being renamed or changing only the case of its own name reports a collision with itself.
     */
    @Test
    void aCaseOnlyRenameIsAllowed() {
        String name = "SCCase" + suffix();
        upload(name, material());

        iam("UpdateServerCertificate")
            .formParam("ServerCertificateName", name)
            .formParam("NewServerCertificateName", name.toLowerCase())
        .when().post("/").then().statusCode(200);

        iam("GetServerCertificate").formParam("ServerCertificateName", name.toLowerCase())
        .when().post("/").then()
            .statusCode(200)
            .body("GetServerCertificateResponse.GetServerCertificateResult.ServerCertificate"
                    + ".ServerCertificateMetadata.ServerCertificateName", equalTo(name.toLowerCase()));
    }

    @Test
    void uploadingTheSameNameTwiceIsEntityAlreadyExists() {
        String name = "sc-dup-" + suffix();
        upload(name, material());

        CertificateGenerator.GeneratedCertificate second = material();
        iam("UploadServerCertificate")
            .formParam("ServerCertificateName", name)
            .formParam("CertificateBody", second.certificatePem())
            .formParam("PrivateKey", second.privateKeyPem())
        .when().post("/").then()
            .statusCode(409)
            .body("ErrorResponse.Error.Code", equalTo("EntityAlreadyExists"));
    }

    /** The private key is stored but no reader returns it: AWS models it only on the upload. */
    @Test
    void readersNeverReturnThePrivateKey() {
        String name = "sc-nokey-" + suffix();
        CertificateGenerator.GeneratedCertificate pair = material();
        upload(name, pair);

        String get = iam("GetServerCertificate").formParam("ServerCertificateName", name)
            .when().post("/").then().statusCode(200).extract().asString();
        assertTrue(get.contains("CertificateBody"), "the certificate body is part of the shape");
        assertTrue(!get.contains("PrivateKey"), "but the private key is never echoed back");
        assertTrue(!get.contains("PRIVATE KEY"), "nor its PEM header");

        String list = iam("ListServerCertificates").when().post("/").then().statusCode(200)
            .extract().asString();
        assertTrue(!list.contains("PrivateKey"));
        assertTrue(!list.contains("CertificateBody"), "the listing is metadata only");
    }

    @Test
    void getReturnsTheChainWhenOneWasUploaded() {
        String name = "sc-chain-" + suffix();
        CertificateGenerator.GeneratedCertificate pair = material();
        CertificateGenerator.GeneratedCertificate chain = material();

        iam("UploadServerCertificate")
            .formParam("ServerCertificateName", name)
            .formParam("CertificateBody", pair.certificatePem())
            .formParam("PrivateKey", pair.privateKeyPem())
            .formParam("CertificateChain", chain.certificatePem())
        .when().post("/").then().statusCode(200);

        iam("GetServerCertificate").formParam("ServerCertificateName", name)
        .when().post("/").then()
            .statusCode(200)
            .body("GetServerCertificateResponse.GetServerCertificateResult.ServerCertificate"
                    + ".CertificateChain", containsString("BEGIN CERTIFICATE"));
    }

    @Test
    void listFiltersByPathPrefixAndPaginates() {
        String tag = suffix();
        CertificateGenerator.GeneratedCertificate a = material();
        CertificateGenerator.GeneratedCertificate b = material();
        iam("UploadServerCertificate").formParam("ServerCertificateName", "sc-pa-" + tag)
            .formParam("Path", "/team-" + tag + "/")
            .formParam("CertificateBody", a.certificatePem()).formParam("PrivateKey", a.privateKeyPem())
        .when().post("/").then().statusCode(200);
        iam("UploadServerCertificate").formParam("ServerCertificateName", "sc-pb-" + tag)
            .formParam("Path", "/team-" + tag + "/")
            .formParam("CertificateBody", b.certificatePem()).formParam("PrivateKey", b.privateKeyPem())
        .when().post("/").then().statusCode(200);

        List<String> scoped = iam("ListServerCertificates")
            .formParam("PathPrefix", "/team-" + tag + "/").formParam("MaxItems", "1000")
            .when().post("/").then().statusCode(200)
            .extract().xmlPath().getList("ListServerCertificatesResponse.ListServerCertificatesResult"
                    + ".ServerCertificateMetadataList.member.ServerCertificateName", String.class);
        assertTrue(scoped.contains("sc-pa-" + tag));
        assertTrue(scoped.contains("sc-pb-" + tag));
        assertTrue(scoped.size() == 2, "the prefix excluded everything else, got " + scoped);

        String marker = iam("ListServerCertificates")
            .formParam("PathPrefix", "/team-" + tag + "/").formParam("MaxItems", "1")
            .when().post("/").then().statusCode(200)
            .body("ListServerCertificatesResponse.ListServerCertificatesResult.IsTruncated",
                    equalTo("true"))
            .extract().path("ListServerCertificatesResponse.ListServerCertificatesResult.Marker");
        assertNotNull(marker, "a truncated page carries a Marker");
    }

    @Test
    void updateRenamesAndMovesTheCertificate() {
        String name = "sc-rename-" + suffix();
        String renamed = "sc-renamed-" + suffix();
        upload(name, material());

        iam("UpdateServerCertificate")
            .formParam("ServerCertificateName", name)
            .formParam("NewServerCertificateName", renamed)
            .formParam("NewPath", "/moved/")
        .when().post("/").then().statusCode(200);

        iam("GetServerCertificate").formParam("ServerCertificateName", renamed)
        .when().post("/").then()
            .statusCode(200)
            .body("GetServerCertificateResponse.GetServerCertificateResult.ServerCertificate"
                    + ".ServerCertificateMetadata.Path", equalTo("/moved/"))
            .body("GetServerCertificateResponse.GetServerCertificateResult.ServerCertificate"
                    + ".ServerCertificateMetadata.Arn",
                    equalTo("arn:aws:iam::000000000000:server-certificate/moved/" + renamed));

        iam("GetServerCertificate").formParam("ServerCertificateName", name)
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    void renamingOntoATakenNameIsEntityAlreadyExists() {
        String first = "sc-taken-a-" + suffix();
        String second = "sc-taken-b-" + suffix();
        upload(first, material());
        upload(second, material());

        iam("UpdateServerCertificate")
            .formParam("ServerCertificateName", first)
            .formParam("NewServerCertificateName", second)
        .when().post("/").then()
            .statusCode(409)
            .body("ErrorResponse.Error.Code", equalTo("EntityAlreadyExists"));
    }

    @Test
    void deleteRemovesItAndIsThenNoSuchEntity() {
        String name = "sc-delete-" + suffix();
        upload(name, material());

        iam("DeleteServerCertificate").formParam("ServerCertificateName", name)
        .when().post("/").then().statusCode(200);

        iam("DeleteServerCertificate").formParam("ServerCertificateName", name)
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    void unknownCertificateIsNoSuchEntity() {
        iam("GetServerCertificate").formParam("ServerCertificateName", "sc-missing-" + suffix())
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    void tagsRoundTripSortedByKeyAndPaginate() {
        String name = "sc-tags-" + suffix();
        upload(name, material());

        iam("TagServerCertificate").formParam("ServerCertificateName", name)
            .formParam("Tags.member.1.Key", "zone").formParam("Tags.member.1.Value", "b")
            .formParam("Tags.member.2.Key", "env").formParam("Tags.member.2.Value", "test")
        .when().post("/").then().statusCode(200);

        String base = "ListServerCertificateTagsResponse.ListServerCertificateTagsResult.";
        iam("ListServerCertificateTags").formParam("ServerCertificateName", name)
        .when().post("/").then()
            .statusCode(200)
            .body(base + "Tags.member[0].Key", equalTo("env"))
            .body(base + "Tags.member[1].Key", equalTo("zone"));

        iam("ListServerCertificateTags").formParam("ServerCertificateName", name)
            .formParam("MaxItems", "1")
        .when().post("/").then()
            .statusCode(200)
            .body(base + "IsTruncated", equalTo("true"))
            .body(base + "Tags.member.Key", equalTo("env"));

        iam("UntagServerCertificate").formParam("ServerCertificateName", name)
            .formParam("TagKeys.member.1", "zone")
        .when().post("/").then().statusCode(200);

        iam("ListServerCertificateTags").formParam("ServerCertificateName", name)
        .when().post("/").then()
            .statusCode(200)
            .body(base + "Tags.member.Key", equalTo("env"));
    }

    @Test
    void tagsGivenAtUploadAreKeptAndReturned() {
        String name = "sc-uploadtag-" + suffix();
        CertificateGenerator.GeneratedCertificate pair = material();

        iam("UploadServerCertificate")
            .formParam("ServerCertificateName", name)
            .formParam("CertificateBody", pair.certificatePem())
            .formParam("PrivateKey", pair.privateKeyPem())
            .formParam("Tags.member.1.Key", "owner").formParam("Tags.member.1.Value", "platform")
        .when().post("/").then()
            .statusCode(200)
            .body("UploadServerCertificateResponse.UploadServerCertificateResult.Tags.member.Key",
                    equalTo("owner"));

        iam("ListServerCertificateTags").formParam("ServerCertificateName", name)
        .when().post("/").then()
            .statusCode(200)
            .body("ListServerCertificateTagsResponse.ListServerCertificateTagsResult"
                    + ".Tags.member.Value", equalTo("platform"));
    }

    /**
     * With no Authorization header there is no SigV4 credential scope to resolve, so the
     * controller routes on the action name alone through {@code AwsQueryController.IAM_ACTIONS}.
     * An action missing from that set falls through to SQS and answers UnsupportedOperation, so
     * every new operation has to be listed there. The whole scenario stays unauthenticated to
     * keep the upload and the reads in one account context.
     */
    @Test
    void certificateActionsRouteViaTheActionFallbackWhenAuthHeaderAbsent() {
        String name = "sc-fallback-" + suffix();
        CertificateGenerator.GeneratedCertificate pair = material();

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UploadServerCertificate")
            .formParam("ServerCertificateName", name)
            .formParam("CertificateBody", pair.certificatePem())
            .formParam("PrivateKey", pair.privateKeyPem())
        .when().post("/").then()
            .statusCode(200)
            .body("UploadServerCertificateResponse.UploadServerCertificateResult"
                    + ".ServerCertificateMetadata.ServerCertificateName", equalTo(name));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListServerCertificates")
            .formParam("MaxItems", "1000")
        .when().post("/").then()
            .statusCode(200)
            .body("ListServerCertificatesResponse.ListServerCertificatesResult"
                    + ".ServerCertificateMetadataList.member.ServerCertificateName",
                    hasItem(name));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteServerCertificate")
            .formParam("ServerCertificateName", name)
        .when().post("/").then().statusCode(200);
    }

    /** The account summary counts the store rather than reporting a hardcoded zero. */
    @Test
    void theAccountSummaryCountsStoredCertificates() {
        long before = summaryValue("ServerCertificates");
        upload("sc-summary-" + suffix(), material());
        assertTrue(summaryValue("ServerCertificates") == before + 1,
                "ServerCertificates did not follow the store");
        assertTrue(summaryValue("ServerCertificatesQuota") == 20L, "the documented quota");
    }

    private static long summaryValue(String key) {
        String value = iam("GetAccountSummary").when().post("/").then().statusCode(200)
            .extract().path("GetAccountSummaryResponse.GetAccountSummaryResult.SummaryMap.entry"
                    + ".find { it.key == '" + key + "' }.value");
        assertNotNull(value, key + " missing from the summary map");
        return Long.parseLong(value);
    }
}
