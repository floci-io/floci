package io.floci.aws.services.ec2;

import io.floci.aws.core.common.Digests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringReader;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.createKeyPair;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.deleteKeyPair;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.ec2;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.firstLine;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.importKeyPair;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.openSshEd25519Key;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.sshEd25519Blob;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CreateKeyPair's KeyType and KeyFormat over the Query protocol. AWS creates an ED25519 key for
 * KeyType=ed25519 and returns either type as a PuTTY file for KeyFormat=ppk; the fingerprint
 * follows the key's type, not its format, and a value outside either parameter's enum is refused.
 *
 * <p>The material is parsed and checked against the fingerprint and the public key DescribeKeyPairs
 * reports, rather than matched against a header alone.
 */
@QuarkusTest
class Ec2CreateKeyPairIntegrationTest {

    @Test
    void ed25519IsAnOpenSshPrivateKeyFingerprintedWithSha256() throws IOException {
        String name = uniqueName();
        XmlPath created = createKeyPair(name, "ed25519", null).statusCode(200).extract().xmlPath();
        String id = created.getString("CreateKeyPairResponse.keyPairId");
        try {
            String material = created.getString("CreateKeyPairResponse.keyMaterial");
            assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----", firstLine(material));
            assertTrue(material.endsWith("-----END OPENSSH PRIVATE KEY-----\n"), "the block ends its last line");
            byte[] blob = sshEd25519Blob(openSshEd25519Key(material));

            // "the base64-encoded SHA-256 digest", padded as AWS reports it
            assertEquals(Base64.getEncoder().encodeToString(Digests.sha256(blob)),
                    created.getString("CreateKeyPairResponse.keyFingerprint"));
            assertDescribedAs(name, id, blob, "ed25519");
        } finally {
            deleteKeyPair(id);
        }
    }

    @Test
    void anEd25519KeyImportedAfterItWasCreatedHasTheSameFingerprint() throws IOException {
        // AWS fingerprints an ED25519 key the same way whether it created the key or was given it.
        String name = uniqueName();
        XmlPath created = createKeyPair(name, "ed25519", null).statusCode(200).extract().xmlPath();
        String importedId = null;
        try {
            String publicKey = "ssh-ed25519 " + Base64.getEncoder().encodeToString(
                    sshEd25519Blob(openSshEd25519Key(created.getString("CreateKeyPairResponse.keyMaterial"))));
            XmlPath imported = importKeyPair(name + "-imported", publicKey).statusCode(200).extract().xmlPath();
            importedId = imported.getString("ImportKeyPairResponse.keyPairId");

            assertEquals(created.getString("CreateKeyPairResponse.keyFingerprint"),
                    imported.getString("ImportKeyPairResponse.keyFingerprint"));
        } finally {
            deleteKeyPair(created.getString("CreateKeyPairResponse.keyPairId"));
            if (importedId != null) {
                deleteKeyPair(importedId);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rsa", "ed25519"})
    void ppkIsAPuttyFileOfTheKeyWithTheNameAsItsComment(String keyType) throws Exception {
        String name = uniqueName();
        XmlPath created = createKeyPair(name, keyType, "ppk").statusCode(200).extract().xmlPath();
        String id = created.getString("CreateKeyPairResponse.keyPairId");
        try {
            PuttyKeyFile file = PuttyKeyFile.parse(created.getString("CreateKeyPairResponse.keyMaterial"));
            assertEquals("rsa".equals(keyType) ? "ssh-rsa" : "ssh-ed25519", file.algorithm());
            assertEquals("none", file.encryption());
            assertEquals(name, file.comment());
            assertTrue(file.macIsValid(), "Private-MAC is PuTTY's HMAC-SHA1 over the file");

            // A key EC2 creates is fingerprinted from the key, whichever format it is returned in.
            String fingerprint = "rsa".equals(keyType)
                    ? colonHex(Digests.sha1(file.rsaKey().getEncoded()))
                    : Base64.getEncoder().encodeToString(Digests.sha256(sshEd25519Blob(file.ed25519Key())));
            assertEquals(fingerprint, created.getString("CreateKeyPairResponse.keyFingerprint"));
            assertDescribedAs(name, id, file.publicBlob(), keyType);
        } finally {
            deleteKeyPair(id);
        }
    }

    @Test
    void rsaPemIsStillThePkcs1KeyFingerprintedWithSha1() throws IOException {
        String name = uniqueName();
        XmlPath created = createKeyPair(name, "rsa", "pem").statusCode(200).extract().xmlPath();
        String id = created.getString("CreateKeyPairResponse.keyPairId");
        try {
            String material = created.getString("CreateKeyPairResponse.keyMaterial");
            assertEquals("-----BEGIN RSA PRIVATE KEY-----", firstLine(material));
            Object parsed = new PEMParser(new StringReader(material)).readObject();
            RSAPrivateCrtKey key = (RSAPrivateCrtKey) new JcaPEMKeyConverter()
                    .getKeyPair((PEMKeyPair) parsed).getPrivate();
            assertEquals(2048, key.getModulus().bitLength());
            // "the SHA-1 digest of the DER encoded private key", PKCS#8 as openssl pkcs8 -topk8 writes it
            assertEquals(colonHex(Digests.sha1(key.getEncoded())),
                    created.getString("CreateKeyPairResponse.keyFingerprint"));
        } finally {
            deleteKeyPair(id);
        }
    }

    @Test
    void anUnknownKeyTypeIsRefusedAndNothingIsCreated() {
        String name = uniqueName();
        createKeyPair(name, "ecdsa", null)
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                .body("Response.Errors.Error.Message", equalTo("1 validation error detected: Value 'ecdsa' at "
                        + "'keyType' failed to satisfy constraint: Member must satisfy enum value set: [rsa, ed25519]"));
        assertNotCreated(name);
    }

    @Test
    void anUnknownKeyFormatIsRefusedAndNothingIsCreated() {
        String name = uniqueName();
        createKeyPair(name, null, "der")
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                .body("Response.Errors.Error.Message", equalTo("1 validation error detected: Value 'der' at "
                        + "'keyFormat' failed to satisfy constraint: Member must satisfy enum value set: [ppk, pem]"));
        assertNotCreated(name);
    }

    /** Floci's order for two violations: KeyType's before KeyFormat's, the model's member order. */
    @Test
    void anUnknownKeyTypeAndFormatAreReportedTogether() {
        String name = uniqueName();
        createKeyPair(name, "ecdsa", "der")
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                .body("Response.Errors.Error.Message", equalTo("2 validation errors detected: Value 'ecdsa' at "
                        + "'keyType' failed to satisfy constraint: Member must satisfy enum value set: [rsa, ed25519]; "
                        + "Value 'der' at 'keyFormat' failed to satisfy constraint: Member must satisfy enum value set: "
                        + "[ppk, pem]"));
        assertNotCreated(name);
    }

    /** Floci checks the values before it looks for a key pair with that name. */
    @Test
    void anUnknownKeyTypeIsRefusedBeforeTheNameIsFoundTaken() {
        String name = uniqueName();
        String id = createKeyPair(name, null, null).statusCode(200).extract().path("CreateKeyPairResponse.keyPairId");
        try {
            createKeyPair(name, "dsa", null)
                    .statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
        } finally {
            deleteKeyPair(id);
        }
    }

    /**
     * AWS answers DryRun before any other check on the request, a taken or empty name and an unknown
     * type included, and creates nothing.
     */
    @ParameterizedTest
    @CsvSource(value = {"new,NULL", "taken,NULL", "empty,NULL", "new,ecdsa"}, nullValues = "NULL")
    void aDryRunIsAnsweredBeforeAnyOtherCheckAndCreatesNothing(String nameKind, String keyType) {
        String name = "empty".equals(nameKind) ? "" : uniqueName();
        String takenId = "taken".equals(nameKind)
                ? createKeyPair(name, null, null).statusCode(200).extract().path("CreateKeyPairResponse.keyPairId")
                : null;
        try {
            RequestSpecification dryRun = ec2("CreateKeyPair")
                    .formParam("KeyName", name)
                    .formParam("DryRun", "true");
            if (keyType != null) {
                dryRun.formParam("KeyType", keyType);
            }
            dryRun.post("/").then()
                    .statusCode(412)
                    .body("Response.Errors.Error.Code", equalTo("DryRunOperation"))
                    .body("Response.Errors.Error.Message",
                            equalTo("Request would have succeeded, but DryRun flag is set."));
            if ("new".equals(nameKind)) {
                assertNotCreated(name);
            }
        } finally {
            if (takenId != null) {
                deleteKeyPair(takenId);
            }
        }
    }

    /**
     * DescribeKeyPairs reports the public half of the created key with the key-pair name as its
     * comment and the type that was created. AWS ends the reported key with a line break and Floci
     * does not, so the line is compared without it.
     */
    private static void assertDescribedAs(String name, String id, byte[] blob, String keyType) {
        String type = "rsa".equals(keyType) ? "ssh-rsa" : "ssh-ed25519";
        XmlPath described = ec2("DescribeKeyPairs")
                .formParam("KeyPairId.1", id)
                .formParam("IncludePublicKey", "true")
                .post("/").then().statusCode(200)
                .body("DescribeKeyPairsResponse.keySet.item.keyName", equalTo(name))
                .extract().xmlPath();
        assertEquals(type + " " + Base64.getEncoder().encodeToString(blob) + " " + name,
                described.getString("DescribeKeyPairsResponse.keySet.item.publicKey").strip());
        assertEquals(keyType, described.getString("DescribeKeyPairsResponse.keySet.item.keyType"),
                "the type of an " + keyType + " key pair");
    }

    private static void assertNotCreated(String name) {
        ec2("DescribeKeyPairs")
                .formParam("KeyName.1", name)
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidKeyPair.NotFound"));
    }

    private static String colonHex(byte[] digest) {
        return HexFormat.of().withDelimiter(":").formatHex(digest);
    }

    private static String uniqueName() {
        return "create-key-pair-" + UUID.randomUUID();
    }
}
