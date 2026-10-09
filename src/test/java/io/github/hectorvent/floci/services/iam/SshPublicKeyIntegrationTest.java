package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.SshPublicKeys;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SSH public key lifecycle over the wire. Keys are generated rather than hardcoded, so the
 * accept paths read real material and the reject paths reject things that genuinely are not keys.
 *
 * <p>Each test makes its own user, which also gives it that user's whole quota of five.
 */
@QuarkusTest
class SshPublicKeyIntegrationTest {

    /** A real 2048-bit RSA blob with its type field rewritten to ssh-ed25519. */
    private static final String RSA_BLOB_LABELLED_ED25519 =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAAwEAAQAAAQEAsNx6pYV5SGmCmxitRthv"
            + "DL+KOosyVaBCBnOMd3BGZQUfVxtpfdObaZ9KnRIspbCY+Ny/dSCxzoCG4YdXJhUUaM2y"
            + "iCDrNIQnBm3cGPZXtOLztrn8OzlwyXDu082rSav14HgK0N/VmyUzvBQgMucKLn1cBhoa"
            + "l/BueVTc0Eu4xOPzfwrkxY26OptLxUO4AkQ8GJsLJa65O9QvwnwSQnqjS3gbPuZqKo6W"
            + "SwhJ4SyR3/4R3Uk1e8fg3blqwTj3o8vNj1H6jwWOH4tkE/yXvzbHjKhG9JQs938QWSvb"
            + "wud3R01B3JO5KFiFFPT+fihU3qWbCaXaQR0ESPhHBILaBkYGFw==";

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static RSAPublicKey generated() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return (RSAPublicKey) generator.generateKeyPair().getPublic();
        } catch (Exception e) {
            throw new IllegalStateException("could not generate a key", e);
        }
    }

    private static String user() {
        String name = "ssh-user-" + suffix();
        iam("CreateUser").formParam("UserName", name).when().post("/").then().statusCode(200);
        return name;
    }

    private static String upload(String userName, String body) {
        return iam("UploadSSHPublicKey")
            .formParam("UserName", userName)
            .formParam("SSHPublicKeyBody", body)
        .when().post("/").then().statusCode(200)
            .extract().path("UploadSSHPublicKeyResponse.UploadSSHPublicKeyResult"
                    + ".SSHPublicKey.SSHPublicKeyId");
    }

    @Test
    void uploadReturnsTheKeyWithAnApkaIdAndTheOpenSshFingerprint() {
        String userName = user();
        RSAPublicKey key = generated();
        String expectedFingerprint =
                SshPublicKeys.openSshFingerprint(SshPublicKeys.openSshBlob(key));

        String id = iam("UploadSSHPublicKey")
            .formParam("UserName", userName)
            .formParam("SSHPublicKeyBody", SshPublicKeys.toOpenSsh(key))
        .when().post("/").then().statusCode(200)
            .body("UploadSSHPublicKeyResponse.UploadSSHPublicKeyResult.SSHPublicKey.UserName",
                    equalTo(userName))
            .body("UploadSSHPublicKeyResponse.UploadSSHPublicKeyResult.SSHPublicKey.Status",
                    equalTo("Active"))
            // The fingerprint AWS reports is the MD5 of the OpenSSH blob, not of the DER.
            .body("UploadSSHPublicKeyResponse.UploadSSHPublicKeyResult.SSHPublicKey.Fingerprint",
                    equalTo(expectedFingerprint))
            .extract().path("UploadSSHPublicKeyResponse.UploadSSHPublicKeyResult"
                    + ".SSHPublicKey.SSHPublicKeyId");

        assertNotNull(id);
        assertTrue(id.startsWith("APKA"), "AWS's own examples use the APKA prefix: " + id);
        // publicKeyIdType is 20 to 128 characters of word characters.
        assertTrue(id.length() >= 20 && id.length() <= 128, "id length: " + id.length());
        assertTrue(id.matches("\\w+"), id);
    }

    /** "The public key must be encoded in ssh-rsa format or PEM format." Both are accepted. */
    @Test
    void bothAcceptedEncodingsCanBeUploaded() {
        String userName = user();
        upload(userName, SshPublicKeys.toOpenSsh(generated()));
        upload(userName, SshPublicKeys.toPem(generated()));

        iam("ListSSHPublicKeys").formParam("UserName", userName)
        .when().post("/").then().statusCode(200)
            .body("ListSSHPublicKeysResponse.ListSSHPublicKeysResult.SSHPublicKeys.member.size()",
                    equalTo(2));
    }

    /**
     * Encoding is required and decides the form of the body in the response, so a key uploaded as
     * ssh-rsa comes back as PEM when PEM is asked for. The conversion is the point of the
     * parameter.
     */
    @Test
    void theRequestedEncodingIsWhatComesBack() {
        String userName = user();
        RSAPublicKey key = generated();
        String id = upload(userName, SshPublicKeys.toOpenSsh(key));

        String asPem = iam("GetSSHPublicKey")
            .formParam("UserName", userName)
            .formParam("SSHPublicKeyId", id)
            .formParam("Encoding", "PEM")
        .when().post("/").then().statusCode(200)
            .extract().path("GetSSHPublicKeyResponse.GetSSHPublicKeyResult.SSHPublicKey"
                    + ".SSHPublicKeyBody");
        assertTrue(asPem.contains("BEGIN PUBLIC KEY"), asPem);
        assertEquals(key.getModulus(), SshPublicKeys.fromPem(asPem).getModulus(),
                "the converted key must be the same key");

        String asSsh = iam("GetSSHPublicKey")
            .formParam("UserName", userName)
            .formParam("SSHPublicKeyId", id)
            .formParam("Encoding", "SSH")
        .when().post("/").then().statusCode(200)
            .extract().path("GetSSHPublicKeyResponse.GetSSHPublicKeyResult.SSHPublicKey"
                    + ".SSHPublicKeyBody");
        assertTrue(asSsh.startsWith("ssh-rsa "), asSsh);
    }

    /** And the same in the other direction: a PEM upload read back as ssh-rsa. */
    @Test
    void aPemUploadConvertsToTheOpenSshForm() {
        String userName = user();
        RSAPublicKey key = generated();
        String id = upload(userName, SshPublicKeys.toPem(key));

        String asSsh = iam("GetSSHPublicKey")
            .formParam("UserName", userName)
            .formParam("SSHPublicKeyId", id)
            .formParam("Encoding", "SSH")
        .when().post("/").then().statusCode(200)
            .extract().path("GetSSHPublicKeyResponse.GetSSHPublicKeyResult.SSHPublicKey"
                    + ".SSHPublicKeyBody");

        assertEquals(key.getModulus(),
                SshPublicKeys.rsaKeyOf(SshPublicKeys.decodeBlob(asSsh)).getModulus());
    }

    @Test
    void anUnknownEncodingIsRejected() {
        String userName = user();
        String id = upload(userName, SshPublicKeys.toOpenSsh(generated()));

        iam("GetSSHPublicKey")
            .formParam("UserName", userName)
            .formParam("SSHPublicKeyId", id)
            .formParam("Encoding", "DER")
        .when().post("/").then().statusCode(400)
            .body(containsString("UnrecognizedPublicKeyEncoding"));
    }

    /**
     * The list carries metadata only: AWS documents SSHPublicKeyMetadata as describing a key
     * "without the key's body or fingerprint".
     */
    @Test
    void theListOmitsTheBodyAndTheFingerprint() {
        String userName = user();
        String id = upload(userName, SshPublicKeys.toOpenSsh(generated()));

        String body = iam("ListSSHPublicKeys").formParam("UserName", userName)
        .when().post("/").then().statusCode(200)
            .body(containsString(id))
            .body("ListSSHPublicKeysResponse.ListSSHPublicKeysResult.SSHPublicKeys.member.Status",
                    equalTo("Active"))
            .body("ListSSHPublicKeysResponse.ListSSHPublicKeysResult.SSHPublicKeys.member"
                    + ".UploadDate", containsString("T"))
        .extract().body().asString();

        assertTrue(!body.contains("SSHPublicKeyBody"), "the list must not carry the body");
        assertTrue(!body.contains("Fingerprint"), "the list must not carry the fingerprint");
    }

    @Test
    void theStatusCanBeSetToEveryDocumentedValue() {
        String userName = user();
        String id = upload(userName, SshPublicKeys.toOpenSsh(generated()));

        for (String status : List.of("Inactive", "Expired", "Active")) {
            iam("UpdateSSHPublicKey")
                .formParam("UserName", userName)
                .formParam("SSHPublicKeyId", id)
                .formParam("Status", status)
            .when().post("/").then().statusCode(200);

            iam("ListSSHPublicKeys").formParam("UserName", userName)
            .when().post("/").then().statusCode(200)
                .body("ListSSHPublicKeysResponse.ListSSHPublicKeysResult.SSHPublicKeys"
                        + ".member.Status", equalTo(status));
        }
    }

    /**
     * And nothing outside the enum. {@code statusType} is one shape across the three operations
     * that take it, so this is the same answer AWS gives on UpdateServiceSpecificCredential, where
     * it was recorded: the offending value and the permitted set are both absent.
     */
    @Test
    void theStatusTakesNothingOutsideTheEnum() {
        String userName = user();
        String id = upload(userName, SshPublicKeys.toOpenSsh(generated()));

        for (String status : List.of("inactive", "Revoked", "")) {
            iam("UpdateSSHPublicKey")
                .formParam("UserName", userName)
                .formParam("SSHPublicKeyId", id)
                .formParam("Status", status)
            .when().post("/").then().statusCode(400)
                .body(containsString("ValidationError"))
                .body(containsString("1 validation error detected"))
                .body(containsString("Member must satisfy enum value set"))
                .body(not(containsString("enum value set:")))
                .body(not(containsString("Revoked")));
        }
    }

    @Test
    void deleteRemovesIt() {
        String userName = user();
        String id = upload(userName, SshPublicKeys.toOpenSsh(generated()));

        iam("DeleteSSHPublicKey")
            .formParam("UserName", userName)
            .formParam("SSHPublicKeyId", id)
        .when().post("/").then().statusCode(200);

        iam("ListSSHPublicKeys").formParam("UserName", userName)
        .when().post("/").then().statusCode(200).body(not(containsString(id)));
    }

    /** Neither accepted encoding, which the model separates from material that will not parse. */
    @Test
    void somethingThatIsNeitherEncodingIsUnrecognised() {
        String userName = user();

        iam("UploadSSHPublicKey")
            .formParam("UserName", userName)
            .formParam("SSHPublicKeyBody", "this is not a key at all")
        .when().post("/").then().statusCode(400)
            .body(containsString("UnrecognizedPublicKeyEncoding"));
    }

    /** A recognised encoding carrying material that will not parse is the other error. */
    @Test
    void aRecognisedEncodingWithBadMaterialIsInvalid() {
        String userName = user();

        iam("UploadSSHPublicKey")
            .formParam("UserName", userName)
            .formParam("SSHPublicKeyBody",
                    "-----BEGIN PUBLIC KEY-----\nbm90IGEga2V5\n-----END PUBLIC KEY-----\n")
        .when().post("/").then().statusCode(400)
            .body(containsString("InvalidPublicKey"));
    }

    /** "The minimum bit-length of the public key is 2048 bits." */
    @Test
    void aKeyShorterThanTheMinimumBitLengthIsRejected() throws Exception {
        String userName = user();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        RSAPublicKey small = (RSAPublicKey) generator.generateKeyPair().getPublic();

        iam("UploadSSHPublicKey")
            .formParam("UserName", userName)
            .formParam("SSHPublicKeyBody", SshPublicKeys.toOpenSsh(small))
        .when().post("/").then().statusCode(400)
            .body(containsString("InvalidPublicKey"));
    }

    /** "SSH Public keys per user: 5", from the IAM service quotas. */
    @Test
    void aSixthKeyExceedsThePerUserQuota() {
        String userName = user();
        for (int i = 0; i < 5; i++) {
            upload(userName, SshPublicKeys.toOpenSsh(generated()));
        }

        iam("UploadSSHPublicKey")
            .formParam("UserName", userName)
            .formParam("SSHPublicKeyBody", SshPublicKeys.toOpenSsh(generated()))
        .when().post("/").then().statusCode(409)
            .body(containsString("LimitExceeded"))
            .body(containsString("SSHPublicKeysPerUser"));
    }

    /**
     * DuplicateSSHPublicKey is per user: "already associated with the specified IAM user". The
     * comparison is on the key rather than the text, so the same key in the other encoding counts.
     *
     * <p>The status is 400, which the model gives this error, and not the 409 that
     * DuplicateCertificate carries: the two read alike and differ.
     */
    @Test
    void theSameKeyCannotBeUploadedTwiceForOneUser() {
        String userName = user();
        RSAPublicKey key = generated();
        upload(userName, SshPublicKeys.toOpenSsh(key));

        iam("UploadSSHPublicKey")
            .formParam("UserName", userName)
            .formParam("SSHPublicKeyBody", SshPublicKeys.toPem(key))
        .when().post("/").then().statusCode(400)
            .body(containsString("DuplicateSSHPublicKey"));
    }

    /** And another user may hold the same key, because the duplicate rule is per user. */
    @Test
    void anotherUserMayHoldTheSameKey() {
        String first = user();
        String second = user();
        RSAPublicKey key = generated();
        upload(first, SshPublicKeys.toOpenSsh(key));
        upload(second, SshPublicKeys.toOpenSsh(key));
    }

    @Test
    void aKeyOfAnotherUserCannotBeReadOrDeleted() {
        String owner = user();
        String other = user();
        String id = upload(owner, SshPublicKeys.toOpenSsh(generated()));

        iam("GetSSHPublicKey")
            .formParam("UserName", other)
            .formParam("SSHPublicKeyId", id)
            .formParam("Encoding", "SSH")
        .when().post("/").then().statusCode(404).body(containsString("NoSuchEntity"));

        iam("DeleteSSHPublicKey")
            .formParam("UserName", other)
            .formParam("SSHPublicKeyId", id)
        .when().post("/").then().statusCode(404).body(containsString("NoSuchEntity"));
    }

    /**
     * UserName is required on every operation but the list, where the model marks it optional and
     * it resolves from the signing access key.
     */
    @Test
    void userNameIsRequiredExceptOnTheList() {
        iam("UploadSSHPublicKey")
            .formParam("SSHPublicKeyBody", SshPublicKeys.toOpenSsh(generated()))
        .when().post("/").then().statusCode(400)
            .body(containsString("ValidationError")).body(containsString("userName"));

        iam("GetSSHPublicKey")
            .formParam("SSHPublicKeyId", "APKAEXAMPLEEXAMPLEEX")
            .formParam("Encoding", "SSH")
        .when().post("/").then().statusCode(400)
            .body(containsString("ValidationError"));

        iam("DeleteSSHPublicKey")
            .formParam("SSHPublicKeyId", "APKAEXAMPLEEXAMPLEEX")
        .when().post("/").then().statusCode(400)
            .body(containsString("ValidationError"));
    }

    @Test
    void anAbsentKeyIdIsNoSuchEntity() {
        String userName = user();

        iam("DeleteSSHPublicKey")
            .formParam("UserName", userName)
            .formParam("SSHPublicKeyId", "APKAABSENTABSENTABSE")
        .when().post("/").then().statusCode(404).body(containsString("NoSuchEntity"));
    }

    /**
     * Without an Authorization header the Query controller infers the service from the action name
     * alone, so these have to be in its IAM set or they fall through to SQS. The IAM namespace on
     * the error is what proves the routing.
     */
    @Test
    void sshKeyActionsRouteViaTheActionFallbackWhenAuthHeaderAbsent() {
        for (String action : List.of("UploadSSHPublicKey", "GetSSHPublicKey", "ListSSHPublicKeys",
                "UpdateSSHPublicKey", "DeleteSSHPublicKey")) {
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("Action", action)
                    .formParam("Version", "2010-05-08")
            .when().post("/").then()
                .body(containsString("iam.amazonaws.com/doc/2010-05-08"));
        }
    }

    /**
     * A real RSA blob relabelled as ssh-ed25519, which is the discriminating case: the type field
     * is the only thing changed, so the exponent and modulus still parse. Skipping the declared
     * type would read this as an RSA key and store it under a type that was never supported.
     *
     * <p>The earlier version of this test used material that was simply malformed, so it passed
     * without the type ever being checked.
     */
    @Test
    void anRsaBlobRelabelledAsAnotherTypeIsInvalid() {
        String userName = user();

        iam("UploadSSHPublicKey")
            .formParam("UserName", userName)
            .formParam("SSHPublicKeyBody", RSA_BLOB_LABELLED_ED25519)
        .when().post("/").then().statusCode(400)
            .body(containsString("InvalidPublicKey"));
    }

    /**
     * A PEM body whose closing marker is missing. The base64 that survives is still valid base64,
     * so treating the marker as optional decodes it and stores a truncated body.
     */
    @Test
    void aPemBodyWithNoClosingMarkerIsRejected() {
        String userName = user();
        String complete = SshPublicKeys.toPem(generated());
        String truncated = complete.replace("-----END PUBLIC KEY-----\n", "");

        iam("UploadSSHPublicKey")
            .formParam("UserName", userName)
            .formParam("SSHPublicKeyBody", truncated)
        .when().post("/").then().statusCode(400)
            .body(containsString("UnrecognizedPublicKeyEncoding"));
    }

    /**
     * Reading back in the encoding a key was uploaded in must return what was uploaded. A trailing
     * comment on an OpenSSH line is the visible case: re-encoding drops it.
     */
    @Test
    void readingBackInTheSameEncodingReturnsTheUploadedBody() {
        String userName = user();
        String withComment = SshPublicKeys.toOpenSsh(generated()) + " someone@example.com";
        String id = upload(userName, withComment);

        String readBack = iam("GetSSHPublicKey")
            .formParam("UserName", userName)
            .formParam("SSHPublicKeyId", id)
            .formParam("Encoding", "SSH")
        .when().post("/").then().statusCode(200)
            .extract().path("GetSSHPublicKeyResponse.GetSSHPublicKeyResult.SSHPublicKey"
                    + ".SSHPublicKeyBody");

        assertEquals(withComment, readBack,
                "the uploaded body must come back unchanged, comment included");
    }

    /** And the same for PEM, whose line wrapping a re-encode would normalise. */
    @Test
    void aPemUploadReadBackAsPemIsUnchanged() {
        String userName = user();
        String pem = SshPublicKeys.toPem(generated());
        String id = upload(userName, pem);

        String readBack = iam("GetSSHPublicKey")
            .formParam("UserName", userName)
            .formParam("SSHPublicKeyId", id)
            .formParam("Encoding", "PEM")
        .when().post("/").then().statusCode(200)
            .extract().path("GetSSHPublicKeyResponse.GetSSHPublicKeyResult.SSHPublicKey"
                    + ".SSHPublicKeyBody");

        assertEquals(pem, readBack, "the uploaded PEM must come back byte-identical");
    }

    /** A type token with nothing after it is recognised as the encoding and fails as the key. */
    @Test
    void anOpenSshLineWithNoBlobIsInvalid() {
        String userName = user();

        iam("UploadSSHPublicKey")
            .formParam("UserName", userName)
            .formParam("SSHPublicKeyBody", "ssh-rsa")
        .when().post("/").then().statusCode(400)
            .body(containsString("InvalidPublicKey"));
    }

    /**
     * The other direction of the same mismatch: the blob is a genuine, untouched {@code ssh-rsa}
     * blob and only the line's label is wrong. Checking the blob's own type field does not catch
     * this, and because the uploaded body is preserved the wrong label would be handed back to
     * every later reader rather than quietly re-encoded away.
     */
    @Test
    void anSshRsaBlobUnderTheWrongLabelIsInvalid() {
        String userName = user();
        String genuine = SshPublicKeys.toOpenSsh(generated());
        String mislabelled = "ssh-ed25519 " + genuine.split(" ")[1];

        iam("UploadSSHPublicKey")
            .formParam("UserName", userName)
            .formParam("SSHPublicKeyBody", mislabelled)
        .when().post("/").then().statusCode(400)
            .body(containsString("InvalidPublicKey"));
    }
}
