package io.floci.aws.services.ec2;

import io.floci.aws.core.common.SshPublicKeys;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static io.floci.aws.core.common.SshPublicKeysTest.blob;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.DSA_KEY;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.ECDSA_P256_KEY;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.ED25519_BLOB;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.ED25519_KEY;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.TRUNCATED_RSA_KEY;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.createKeyPair;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.deleteKeyPair;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.ec2;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.importKeyPair;
import static org.hamcrest.Matchers.equalTo;

/**
 * ImportKeyPair takes what AWS documents, RSA and ED25519 keys in the formats listed for each, and
 * refuses the rest with InvalidKey.Format; CreateKeyPair and ImportKeyPair hold names to the rule
 * AWS documents for both.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/create-key-pairs.html#how-to-generate-your-own-key-and-import-it-to-aws">Requirements for key pairs</a>
 */
@QuarkusTest
class Ec2KeyPairImportIntegrationTest {

    /**
     * Each refusal is the error AWS gives an import it cannot read, and stores nothing: DSA and
     * ECDSA keys, an RSA key shorter than 1024 bits, the truncated ssh-rsa blob Floci's tests once
     * imported, two key lines, an ED25519 key in RFC 4716 (an RSA-only format), a private key, and
     * a key line base64-encoded twice.
     */
    @ParameterizedTest
    @ValueSource(strings = {"dsa", "ecdsa", "rsa-768", "truncated-rsa", "two-lines", "rfc4716-ed25519",
            "private-key", "double-base64", "der-pad-bits", "der-empty-bit-string"})
    void materialEc2DoesNotImportIsRefusedWithInvalidKeyFormat(String shape) {
        String name = "refused-" + UUID.randomUUID();
        String material = switch (shape) {
            case "dsa" -> DSA_KEY;
            case "ecdsa" -> ECDSA_P256_KEY;
            case "rsa-768" -> "ssh-rsa " + base64(blob("ssh-rsa", BigInteger.valueOf(65537).toByteArray(),
                    BigInteger.ONE.shiftLeft(767).setBit(0).toByteArray()));
            // A SubjectPublicKeyInfo whose key bit string declares an unused bit: no whole key.
            case "der-pad-bits" -> base64(withPadBits(Ec2KeyMaterial.generateRsa().openSshPublicKey()));
            // An rsaEncryption SubjectPublicKeyInfo whose key bit string is empty: no key at all.
            case "der-empty-bit-string" -> "MBIwDQYJKoZIhvcNAQEBBQADAQA=";
            case "truncated-rsa" -> TRUNCATED_RSA_KEY;
            case "two-lines" -> ED25519_KEY + " first\n" + Ec2KeyMaterial.generateRsa().openSshPublicKey() + " second\n";
            case "rfc4716-ed25519" -> "---- BEGIN SSH2 PUBLIC KEY ----\n" + ED25519_BLOB
                    + "\n---- END SSH2 PUBLIC KEY ----\n";
            case "private-key" -> Ec2KeyMaterial.generateRsa().privateKeyPem();
            case "double-base64" -> base64(Ec2KeyMaterial.generateRsa().openSshPublicKey()
                    .getBytes(StandardCharsets.UTF_8));
            default -> throw new IllegalArgumentException(shape);
        };

        importKeyPair(name, material).statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidKey.Format"))
                .body("Response.Errors.Error.Message", equalTo("Key is not in valid OpenSSH public key format"));
        ec2("DescribeKeyPairs").formParam("KeyName.1", name)
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidKeyPair.NotFound"));
    }

    /**
     * One RSA key imported as an OpenSSH line, as RFC 4716, as PEM and as bare base64 DER is the same
     * key each time: the fingerprint is the MD5 of its DER, as AWS reports for an imported RSA key.
     */
    @ParameterizedTest
    @ValueSource(strings = {"openssh", "rfc4716", "pem", "der"})
    void anRsaKeyIsImportedFromEachFormatAwsDocuments(String format) throws Exception {
        String openSsh = Ec2KeyMaterial.generateRsa().openSshPublicKey();
        String blob = openSsh.split(" ")[1];
        RSAPublicKey key = SshPublicKeys.rsaKeyOf(Base64.getDecoder().decode(blob));
        String der = base64(key.getEncoded());
        String material = switch (format) {
            case "openssh" -> openSsh + " someone@example.com\n";
            case "rfc4716" -> "---- BEGIN SSH2 PUBLIC KEY ----\nComment: \"throwaway\"\n"
                    + String.join("\n", blob.split("(?<=\\G.{70})")) + "\n---- END SSH2 PUBLIC KEY ----\n";
            case "pem" -> "-----BEGIN PUBLIC KEY-----\n" + String.join("\n", der.split("(?<=\\G.{64})"))
                    + "\n-----END PUBLIC KEY-----\n";
            case "der" -> der;
            default -> throw new IllegalArgumentException(format);
        };
        String name = "imported-" + format + "-" + UUID.randomUUID();
        String expected = HexFormat.of().withDelimiter(":")
                .formatHex(MessageDigest.getInstance("MD5").digest(key.getEncoded()));

        String id = importKeyPair(name, material).statusCode(200)
                .body("ImportKeyPairResponse.keyName", equalTo(name))
                .body("ImportKeyPairResponse.keyFingerprint", equalTo(expected))
                .extract().path("ImportKeyPairResponse.keyPairId");
        try {
            ec2("DescribeKeyPairs").formParam("KeyPairId.1", id)
                    .post("/").then().statusCode(200)
                    .body("DescribeKeyPairsResponse.keySet.item.keyFingerprint", equalTo(expected))
                    .body("DescribeKeyPairsResponse.keySet.item.keyType", equalTo("rsa"));
        } finally {
            deleteKeyPair(id);
        }
    }

    /**
     * CreateKeyPair and ImportKeyPair take a name of up to 255 ASCII characters with no leading or
     * trailing space, and answer any other with InvalidParameterValue.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keyPairNamesAreHeldToTheRuleAwsDocuments(boolean imported) {
        String longest = ("k-" + UUID.randomUUID()).repeat(10).substring(0, 255);
        String id = createOrImport(imported, longest).statusCode(200)
                .body((imported ? "ImportKeyPairResponse" : "CreateKeyPairResponse") + ".keyName", equalTo(longest))
                .extract().path((imported ? "ImportKeyPairResponse" : "CreateKeyPairResponse") + ".keyPairId");
        deleteKeyPair(id);

        for (String name : new String[] {longest + "k", "cl\u00e9-" + UUID.randomUUID(), " leading-" + UUID.randomUUID(),
                "trailing-" + UUID.randomUUID() + " "}) {
            createOrImport(imported, name).statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
        }
    }

    @Test
    void anOverlongNameIsRefusedWithTheValueAndTheReason() {
        String name = "n".repeat(256);
        createOrImport(false, name).statusCode(400)
                .body("Response.Errors.Error.Message", equalTo(
                        "Value (" + name + ") for parameter KeyName is invalid. Length exceeds maximum of 255 characters."));
    }

    private static ValidatableResponse createOrImport(boolean imported, String name) {
        return imported
                ? importKeyPair(name, Ec2KeyMaterial.generateRsa().openSshPublicKey())
                : createKeyPair(name);
    }

    /**
     * The DER SubjectPublicKeyInfo of a 2048-bit OpenSSH RSA key with its bit string's pad count, the
     * byte after the BIT STRING header, set to one.
     */
    private static byte[] withPadBits(String openSshRsaKey) {
        byte[] der = SshPublicKeys.rsaKeyOf(SshPublicKeys.decodeBlob(openSshRsaKey)).getEncoded();
        der[23] = 1;
        return der;
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
