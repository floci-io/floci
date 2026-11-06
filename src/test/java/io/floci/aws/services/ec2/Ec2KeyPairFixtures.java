package io.floci.aws.services.ec2;

import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The key pair calls and the throwaway keys shared by the EC2 key pair tests. */
final class Ec2KeyPairFixtures {

    static final String REGION = "us-east-1";
    static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20261020/" + REGION + "/ec2/aws4_request";

    /** A throwaway 2048-bit RSA key. */
    static final String RSA_KEY = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCp7mGC9"
            + "NkQI+loxf1G9bM6HnCs9iR1nnzZA/f/o7hx/Wv1oDhx03k6H83I+Q49eE1XO56WBPxnr8/2G6UmS9D0R"
            + "FKe9L+HJrfiZF7oLQ09JwEK91VLNSkD0Bq2zhnfWJe/ULkaPQ7FgHEghRi8aI5PsATH6VCaJDKWxl+2b"
            + "zM7MWlbKRAo8uuu2evnGrgnu+RmuXJQCRYz6lG+JESVzm6MnHXYxme+UD+7c/tTYwzoswfXh8VN8QVzX"
            + "mjfHi2Ve3PJ+YuF2X2gKpRMNMf7cLWMCTOhZI2AgXX+NLDlCG0dEUm/DXdSKRTDhm3mIJmF67eGYuff+"
            + "zHusBZ9cSBkW9i9";

    /**
     * RSA_KEY's fingerprint, the MD5 of its DER as EC2 reports it for an imported RSA key:
     * {@code openssl pkey -pubin -outform DER | openssl md5 -c}.
     */
    static final String RSA_FINGERPRINT = "4d:1a:39:2e:6a:18:60:9a:c5:2a:cb:cc:6c:de:22:b5";

    /** A throwaway ED25519 public key as an OpenSSH line without a comment. */
    static final String ED25519_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAII0fBPBUZHaEOBc2mySfmI5btu4mkvFfNRujmF7RH2fj";

    /** The base64 blob of {@link #ED25519_KEY}. */
    static final String ED25519_BLOB = ED25519_KEY.split(" ")[1];

    /**
     * ED25519_KEY's fingerprint as EC2 reports it, the base64 SHA-256 with its padding kept:
     * {@code ssh-keygen -l} prints it as {@code SHA256:UOyzahv0Ty520U89wfCvKdTlp2TbtpmnlpJHPW3MbMk}.
     */
    static final String ED25519_FINGERPRINT = "UOyzahv0Ty520U89wfCvKdTlp2TbtpmnlpJHPW3MbMk=";

    /**
     * The throwaway ED25519 key of the version 2 key file in PuTTY's test suite (test/cryptsuite.py,
     * testPPKLoadSave): its 32-byte private key, and its public key line.
     */
    static final byte[] PUTTY_TEST_KEY =
            HexFormat.of().parseHex("6bef22997c8f2aa7f17b9f930eafdc59c41ad5c5dca5b0e7900fdff006101a58");
    static final String PUTTY_TEST_PUBLIC_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHJCszOHaI9X/yGLtjn22f0hO6VPMQDVtctkym6FJH1W";

    /** A throwaway DSA key from {@code ssh-keygen -t dsa}. */
    static final String DSA_KEY = "ssh-dss AAAAB3NzaC1kc3MAAACBAOFToDsRbL7Z8AqAZOKXROc1Cy5SA1ZgM5odRC57"
            + "MSEruUjfUv2//jGbOLemgQBFsqZY1dyKlrpR3dm+tpfXGQR26Pkqm+5BF8kaykQZAYdwCzRhwzM7c8pei36Qi2XJ1s+MeLbIXZ2TTf"
            + "yvkYhckXSrnf90oVJaMpaTIiDzu/CVAAAAFQCk1LBHtzCpTFHCInI4jnrGMjyyQQAAAIBz+pU2CQ0d8tQyxfQJ71wCCowSoVuUk5Zh"
            + "1kAnIOoSPOTOuqos4OqicYdSH/AK8G0gKoXbp1BJX8H1yB401LAl8gccL1YbZBheZAtfNjeBd1xOZQZXWSYOEPQWVMcrqkId3un13z"
            + "55qV0vl6ohU3Mp9ekGjBHuQWhDk8RYGgL9vwAAAIB6eL+huYUrgr06l0ozSlF9pf87rehfJaCLLJPPco8F9uCqx8sYsi/8b6gkbfq4"
            + "w/k75H62ew8LVhpab9bP3/6/HQodnGrh1rthcapynsUzpFcjS86okexUcDkR3OnPLJ3CNGBS7MM0yw+NeXZN2BkeTdKAfbAdFw1mrb"
            + "/AKu9GDg== throwaway";

    /** A throwaway ECDSA P-256 key from {@code ssh-keygen -t ecdsa -b 256}. */
    static final String ECDSA_P256_KEY = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAy"
            + "NTYAAABBBFaxjAHsoCE0FmjbKLGzgVRKfOKFmYj1/JpnSjsuLjy23mHIUPkLJ5thgE47igHp+KufC1V2KqJE4OP5w4EK9SU= throwaway";

    /** The truncated ssh-rsa blob Floci's own tests once imported: a type, an exponent, and part of a modulus. */
    static final String TRUNCATED_RSA_KEY = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAQQDHnLTTbpnrFWkfVt test@test";

    private Ec2KeyPairFixtures() {
    }

    /** A request for the given EC2 action, signed for us-east-1. */
    static RequestSpecification ec2(String action) {
        return given().header("Authorization", AUTH).formParam("Action", action);
    }

    /** CreateKeyPair with this name, whatever the answer. */
    static ValidatableResponse createKeyPair(String name) {
        return createKeyPair(name, null, null);
    }

    /** CreateKeyPair with this name, and the KeyType and KeyFormat when they are not null. */
    static ValidatableResponse createKeyPair(String name, String keyType, String keyFormat) {
        RequestSpecification request = ec2("CreateKeyPair").formParam("KeyName", name);
        if (keyType != null) {
            request.formParam("KeyType", keyType);
        }
        if (keyFormat != null) {
            request.formParam("KeyFormat", keyFormat);
        }
        return request.post("/").then();
    }

    /** ImportKeyPair with this name and material, the material base64-encoded as the API takes it. */
    static ValidatableResponse importKeyPair(String name, byte[] material) {
        return ec2("ImportKeyPair").formParam("KeyName", name)
                .formParam("PublicKeyMaterial", Base64.getEncoder().encodeToString(material))
                .post("/").then();
    }

    /** ImportKeyPair with this name and the UTF-8 bytes of this material. */
    static ValidatableResponse importKeyPair(String name, String material) {
        return importKeyPair(name, material.getBytes(StandardCharsets.UTF_8));
    }

    /** Creates a key pair and returns its id. */
    static String createdKeyPairId(String name) {
        return createKeyPair(name).statusCode(200).extract().path("CreateKeyPairResponse.keyPairId");
    }

    /** Imports the material and returns the new key pair's id. */
    static String importedKeyPairId(String name, byte[] material) {
        return importKeyPair(name, material).statusCode(200).extract().path("ImportKeyPairResponse.keyPairId");
    }

    /** Imports the UTF-8 bytes of the material and returns the new key pair's id. */
    static String importedKeyPairId(String name, String material) {
        return importedKeyPairId(name, material.getBytes(StandardCharsets.UTF_8));
    }

    /** Deletes the key pair with this id, and checks DeleteKeyPair answered 200. */
    static void deleteKeyPair(String id) {
        ec2("DeleteKeyPair").formParam("KeyPairId", id).post("/").then().statusCode(200);
    }

    /** The ED25519 key of an OpenSSH private key file, read with Bouncy Castle's OpenSSH reader. */
    static Ed25519PrivateKeyParameters openSshEd25519Key(String material) throws IOException {
        try (PemReader reader = new PemReader(new StringReader(material))) {
            PemObject pem = reader.readPemObject();
            assertEquals("OPENSSH PRIVATE KEY", pem.getType());
            AsymmetricKeyParameter key = OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(pem.getContent());
            assertTrue(key instanceof Ed25519PrivateKeyParameters, "expected an ED25519 key, got " + key);
            return (Ed25519PrivateKeyParameters) key;
        }
    }

    /** The OpenSSH public key blob of an ED25519 key. */
    static byte[] sshEd25519Blob(Ed25519PrivateKeyParameters key) throws IOException {
        return OpenSSHPublicKeyUtil.encodePublicKey(key.generatePublicKey());
    }

    /** The first line of key material, which a failure message can show without disclosing the key. */
    static String firstLine(String material) {
        return material.lines().findFirst().orElse("");
    }
}
