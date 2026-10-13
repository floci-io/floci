package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class Ec2KeyPairPublicKeyIntegrationTest {
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261009/us-east-1/ec2/aws4_request";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicKeyIsReturnedOnlyWhenRequested(boolean imported) {
        String name = "public-key-" + UUID.randomUUID();
        Ec2KeyMaterial.Generated generated = Ec2KeyMaterial.generateRsa();
        RequestSpecification create = given().header("Authorization", AUTH)
                .formParam("Action", imported ? "ImportKeyPair" : "CreateKeyPair")
                .formParam("KeyName", name);
        if (imported) {
            create.formParam("PublicKeyMaterial", Base64.getEncoder().encodeToString(
                    (generated.openSshPublicKey() + " supplied-comment").getBytes(StandardCharsets.UTF_8)));
        }
        String responseName = imported ? "ImportKeyPairResponse" : "CreateKeyPairResponse";
        String id = create.post("/").then().statusCode(200)
                .extract().path(responseName + ".keyPairId");
        try {
            for (String selection : new String[]{"KeyName.1", "KeyPairId.1"}) {
                String value = selection.startsWith("KeyName") ? name : id;
                for (String include : new String[]{null, "false", "true"}) {
                    RequestSpecification describe = given().header("Authorization", AUTH)
                            .formParam("Action", "DescribeKeyPairs").formParam(selection, value);
                    if (include != null) {
                        describe.formParam("IncludePublicKey", include);
                    }
                    ValidatableResponse response = describe.post("/").then().statusCode(200)
                            .body("DescribeKeyPairsResponse.keySet.item.keyName", equalTo(name))
                            .body("DescribeKeyPairsResponse.keySet.item.keyPairId", equalTo(id))
                            .body("DescribeKeyPairsResponse.keySet.item.keyMaterial.size()", equalTo(0))
                            .body("DescribeKeyPairsResponse.keySet.item.publicKey.size()",
                                    equalTo("true".equals(include) ? 1 : 0));
                    if ("true".equals(include)) {
                        String publicKey = response.extract().path(
                                "DescribeKeyPairsResponse.keySet.item.publicKey");
                        assertFalse(publicKey.isBlank());
                        if (imported) {
                            assertEquals(generated.openSshPublicKey() + " " + name, publicKey);
                        } else {
                            assertFalse(Ec2KeyMaterial.fingerprintOf(publicKey).isBlank());
                        }
                    }
                }
            }
        } finally {
            given().header("Authorization", AUTH).formParam("Action", "DeleteKeyPair")
                    .formParam("KeyPairId", id).post("/").then().statusCode(200);
        }
    }

    /**
     * An OpenSSH line is recognised without a comment and whatever whitespace separates or ends its
     * fields, and comes back in the canonical single-space form with the key-pair name added.
     */
    @Test
    void anOpenSshLineWithoutACommentIsDescribedWithTheKeyPairName() {
        String name = "public-key-" + UUID.randomUUID();
        String blob = "AAAAC3NzaC1lZDI1NTE5AAAAIIj7azlDjVHAsSxgcpCRZ3oWnTm0nAFM64y9jd22ioI/";
        String id = given().header("Authorization", AUTH)
                .formParam("Action", "ImportKeyPair").formParam("KeyName", name)
                .formParam("PublicKeyMaterial", Base64.getEncoder().encodeToString(
                        ("ssh-ed25519\t" + blob + "\r\n").getBytes(StandardCharsets.UTF_8)))
                .post("/").then().statusCode(200).extract().path("ImportKeyPairResponse.keyPairId");
        try {
            given().header("Authorization", AUTH).formParam("Action", "DescribeKeyPairs")
                    .formParam("KeyPairId.1", id).formParam("IncludePublicKey", "true")
                    .post("/").then().statusCode(200)
                    .body("DescribeKeyPairsResponse.keySet.item.publicKey", equalTo("ssh-ed25519 " + blob + " " + name));
        } finally {
            given().header("Authorization", AUTH).formParam("Action", "DeleteKeyPair")
                    .formParam("KeyPairId", id).post("/").then().statusCode(200);
        }
    }

    /**
     * ImportKeyPair keeps material it cannot read as it was given, so the key pair is described
     * without a public key rather than with a rewrite of text that is not an OpenSSH line. A UTF-16
     * file decodes to text with NUL characters, which XML cannot carry at all.
     */
    @ParameterizedTest
    @ValueSource(strings = {"rfc4716", "utf16"})
    void materialThatIsNotAnOpenSshLineIsDescribedWithoutAPublicKey(String format) {
        String name = "public-key-" + UUID.randomUUID();
        String openSsh = Ec2KeyMaterial.generateRsa().openSshPublicKey();
        byte[] material = "rfc4716".equals(format)
                ? ("---- BEGIN SSH2 PUBLIC KEY ----\nComment: \"" + name + "\"\n" + openSsh.split(" ")[1]
                        + "\n---- END SSH2 PUBLIC KEY ----\n").getBytes(StandardCharsets.UTF_8)
                : (openSsh + " " + name).getBytes(StandardCharsets.UTF_16LE);
        String id = given().header("Authorization", AUTH)
                .formParam("Action", "ImportKeyPair").formParam("KeyName", name)
                .formParam("PublicKeyMaterial", Base64.getEncoder().encodeToString(material))
                .post("/").then().statusCode(200).extract().path("ImportKeyPairResponse.keyPairId");
        try {
            given().header("Authorization", AUTH).formParam("Action", "DescribeKeyPairs")
                    .formParam("KeyPairId.1", id).formParam("IncludePublicKey", "true")
                    .post("/").then().statusCode(200)
                    .body("DescribeKeyPairsResponse.keySet.item.keyPairId", equalTo(id))
                    .body("DescribeKeyPairsResponse.keySet.item.keyName", equalTo(name))
                    .body("DescribeKeyPairsResponse.keySet.item.keyFingerprint", startsWith("00:00"))
                    .body("DescribeKeyPairsResponse.keySet.item.publicKey.size()", equalTo(0));
        } finally {
            given().header("Authorization", AUTH).formParam("Action", "DeleteKeyPair")
                    .formParam("KeyPairId", id).post("/").then().statusCode(200);
        }
    }
}
