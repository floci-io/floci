package io.floci.aws.services.paymentcryptography;

import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.PartitionMatrix;
import io.floci.aws.testing.PartitionMatrix.PartitionCase;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class PaymentCryptographyIntegrationTest {

    // The control plane of Payment Cryptography is an awsJson 1.0 service: the AWS SDK
    // sends this content type and targets PaymentCryptographyControlPlane.<Operation>.
    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TARGET = "PaymentCryptographyControlPlane.";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given()
                .header("X-Amz-Target", TARGET + action)
                .contentType(CONTENT_TYPE)
                .body(body)
            .when().post("/");
    }

    private static Response callIn(String region, String action, String body) {
        return given()
                .header("Authorization", PartitionMatrix.sigV4Auth(region, "payment-cryptography"))
                .header("X-Amz-Target", TARGET + action)
                .contentType(CONTENT_TYPE)
                .body(body)
            .when().post("/");
    }

    private static final String CREATE_KEY = """
            {"KeyAttributes":{"KeyAlgorithm":"AES_256","KeyClass":"SYMMETRIC_KEY",
             "KeyUsage":"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY",
             "KeyModesOfUse":{"Encrypt":true,"Decrypt":true}},
             "Exportable":true}""";

    private static final String CREATE_KEY_PAIR = """
            {"KeyAttributes":{"KeyAlgorithm":"RSA_2048","KeyClass":"ASYMMETRIC_KEY_PAIR",
             "KeyUsage":"TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE",
             "KeyModesOfUse":{"Sign":true,"Verify":true}},
             "Exportable":true}""";

    @Test
    void aKeyIsCreatedAndReadBack() {
        String arn = call("CreateKey", CREATE_KEY)
                .then().statusCode(200)
                    .body("Key.KeyArn", matchesPattern(
                            "arn:aws:payment-cryptography:[a-z0-9-]+:[0-9]+:key/[0-9a-f]+"))
                    .body("Key.KeyState", equalTo("CREATE_COMPLETE"))
                    .body("Key.KeyCheckValue", matchesPattern("[0-9A-F]{6}"))
                    .body("Key.KeyCheckValueAlgorithm", equalTo("CMAC"))
                    .body("Key.Enabled", equalTo(true))
                    .extract().path("Key.KeyArn");

        call("GetKey", "{\"KeyIdentifier\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Key.KeyArn", equalTo(arn))
                    .body("Key.KeyAttributes.KeyAlgorithm", equalTo("AES_256"));
    }

    @Test
    void aDerivationKeySaysWhatItDerivesFor() {
        // The usage a derivation key derives for sits beside the attributes, and it comes
        // back with the key.
        String arn = call("CreateKey", "{\"KeyAttributes\":{\"KeyAlgorithm\":\"AES_256\","
                + "\"KeyClass\":\"SYMMETRIC_KEY\",\"KeyUsage\":\"TR31_B0_BASE_DERIVATION_KEY\","
                + "\"KeyModesOfUse\":{\"DeriveKey\":true}},"
                + "\"DeriveKeyUsage\":\"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY\","
                + "\"Exportable\":true}")
                .then().statusCode(200)
                    .body("Key.DeriveKeyUsage", equalTo("TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY"))
                    .extract().path("Key.KeyArn");

        call("GetKey", "{\"KeyIdentifier\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Key.DeriveKeyUsage", equalTo("TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY"));
    }

    @Test
    void anInitialKeyLeavesUnderTheBaseKeyItIsDerivedFrom() {
        // The export names the key that leaves and the attributes that say which key of it
        // leaves: the initial key is derived from the base key and is not the base key.
        // The base derivation key is a double-length Triple DES key, which is what the
        // derivation is defined over.
        String base = call("CreateKey", "{\"KeyAttributes\":{\"KeyAlgorithm\":\"TDES_2KEY\","
                + "\"KeyClass\":\"SYMMETRIC_KEY\",\"KeyUsage\":\"TR31_B0_BASE_DERIVATION_KEY\","
                + "\"KeyModesOfUse\":{\"DeriveKey\":true}},"
                + "\"Exportable\":true}")
                .then().statusCode(200)
                    .extract().path("Key.KeyArn");
        String protection = call("CreateKey", "{\"KeyAttributes\":{\"KeyAlgorithm\":\"AES_256\","
                + "\"KeyClass\":\"SYMMETRIC_KEY\","
                + "\"KeyUsage\":\"TR31_K1_KEY_BLOCK_PROTECTION_KEY\","
                + "\"KeyModesOfUse\":{\"Wrap\":true,\"Unwrap\":true}},"
                + "\"Exportable\":true}")
                .then().statusCode(200)
                    .extract().path("Key.KeyArn");

        call("ExportKey", """
                {"ExportKeyIdentifier":"%s",
                 "ExportAttributes":{"ExportDukptInitialKey":
                 {"KeySerialNumber":"12345678900000000001"}},
                 "KeyMaterial":{"Tr31KeyBlock":{"WrappingKeyIdentifier":"%s"}}}
                """.formatted(base, protection))
                .then().statusCode(200)
                    .body("WrappedKey.WrappedKeyMaterialFormat", equalTo("TR31_KEY_BLOCK"))
                    .body("WrappedKey.WrappingKeyArn", equalTo(protection));
    }

    @Test
    void theImportParametersAreServedOverTheWire() {
        call("GetParametersForImport", """
                {"KeyMaterialType":"KEY_CRYPTOGRAM","WrappingKeyAlgorithm":"RSA_2048"}
                """)
                .then().statusCode(200)
                    .body("ImportToken", matchesPattern("import-token-[0-9a-zA-Z]{16,64}"))
                    .body("WrappingKeyAlgorithm", equalTo("RSA_2048"))
                    .body("WrappingKeyCertificate", notNullValue())
                    .body("WrappingKeyCertificateChain", notNullValue())
                    .body("ParametersValidUntilTimestamp", greaterThanOrEqualTo(0));
    }

    @Test
    void theParametersCanBeReusedOverTheWire() {
        // A caller that asks for the last parameters gets them, token and certificate alike.
        String first = call("GetParametersForImport", """
                {"KeyMaterialType":"KEY_CRYPTOGRAM","WrappingKeyAlgorithm":"RSA_2048"}
                """)
                .then().statusCode(200)
                    .extract().path("ImportToken");

        call("GetParametersForImport", """
                {"KeyMaterialType":"KEY_CRYPTOGRAM","WrappingKeyAlgorithm":"RSA_2048",
                 "ReuseLastGeneratedToken":true}
                """)
                .then().statusCode(200)
                    .body("ImportToken", equalTo(first));
    }

    @Test
    void theExportParametersAreServedOverTheWire() {
        call("GetParametersForExport", """
                {"KeyMaterialType":"TR34_KEY_BLOCK","SigningKeyAlgorithm":"RSA_2048"}
                """)
                .then().statusCode(200)
                    .body("ExportToken", matchesPattern("export-token-[0-9a-zA-Z]{16,64}"))
                    .body("SigningKeyAlgorithm", equalTo("RSA_2048"))
                    .body("SigningKeyCertificate", notNullValue())
                    .body("SigningKeyCertificateChain", notNullValue())
                    .body("ParametersValidUntilTimestamp", greaterThanOrEqualTo(0));
    }

    @Test
    void anImportedKeyCarriesTheEnabledFlagAndTheTags() {
        // The import names the key's state and its tags, and the key comes back that way.
        String wrapping = call("CreateKey", """
                {"KeyAttributes":{"KeyAlgorithm":"AES_256","KeyClass":"SYMMETRIC_KEY",
                 "KeyUsage":"TR31_K1_KEY_BLOCK_PROTECTION_KEY",
                 "KeyModesOfUse":{"Wrap":true,"Unwrap":true}},
                 "Exportable":true}
                """)
                .then().statusCode(200)
                    .extract().path("Key.KeyArn");
        String arn = call("CreateKey", CREATE_KEY)
                .then().statusCode(200)
                    .extract().path("Key.KeyArn");
        String block = call("ExportKey", "{\"ExportKeyIdentifier\":\"" + arn
                + "\",\"KeyMaterial\":{\"Tr31KeyBlock\":{\"WrappingKeyIdentifier\":\""
                + wrapping + "\"}}}")
                .then().statusCode(200)
                    .extract().path("WrappedKey.KeyMaterial");

        String importedArn = call("ImportKey", "{\"KeyMaterial\":{\"Tr31KeyBlock\":{"
                + "\"WrappingKeyIdentifier\":\"" + wrapping + "\",\"WrappedKeyBlock\":\"" + block
                + "\"}},\"Enabled\":false,\"Tags\":[{\"Key\":\"Origin\",\"Value\":\"PRIMARY\"}]}")
                .then().statusCode(200)
                    .body("Key.Enabled", equalTo(false))
                    .extract().path("Key.KeyArn");

        // The key's answer carries no tags, so they are read the way they are read elsewhere.
        call("ListTagsForResource", "{\"ResourceArn\":\"" + importedArn + "\"}")
                .then().statusCode(200)
                    .body("Tags[0].Key", equalTo("Origin"))
                    .body("Tags[0].Value", equalTo("PRIMARY"));
    }

    @Test
    void aRequestNamingTwoFormsIsRefused() {
        // The union holds one form per request, and naming two is not naming one.
        String arn = call("CreateKey", CREATE_KEY)
                .then().statusCode(200)
                    .extract().path("Key.KeyArn");

        call("ExportKey", "{\"ExportKeyIdentifier\":\"" + arn + "\",\"KeyMaterial\":{"
                + "\"Tr31KeyBlock\":{\"WrappingKeyIdentifier\":\"" + arn + "\"},"
                + "\"KeyCryptogram\":{\"CertificateAuthorityPublicKeyIdentifier\":\"" + arn
                + "\",\"WrappingKeyCertificate\":\"x\"}}}")
                .then().statusCode(400)
                    .body("__type", equalTo("ValidationException"));
    }

    @Test
    void aRequestNamingAFormTheServiceDoesNotServeIsRefused() {
        // The TR-34 form is one the model has and this service does not make.
        String arn = call("CreateKey", CREATE_KEY)
                .then().statusCode(200)
                    .extract().path("Key.KeyArn");

        call("ExportKey", "{\"ExportKeyIdentifier\":\"" + arn + "\",\"KeyMaterial\":{"
                + "\"Tr34KeyBlock\":{\"CertificateAuthorityPublicKeyIdentifier\":\"" + arn
                + "\",\"WrappingKeyCertificate\":\"x\",\"KeyBlockFormat\":\"X9_TR34_2012\"}}}")
                .then().statusCode(400)
                    .body("__type", equalTo("ValidationException"))
                    .body("message", containsString("not a form the service exports"));
    }

    /**
     * The shape check settles the members the model requires, so a request missing one is
     * refused by it, before the operation does any work. The refusal a caller sees is
     * therefore the shape check's, and the service does not repeat the check.
     */
    @Test
    void theModelCheckRefusesAnExportWithoutAWrappingKeyCertificate() {
        String arn = call("CreateKey", CREATE_KEY)
                .then().statusCode(200)
                    .extract().path("Key.KeyArn");

        Response answer = call("ExportKey", "{\"ExportKeyIdentifier\":\"" + arn + "\",\"KeyMaterial\":{"
                + "\"KeyCryptogram\":{\"CertificateAuthorityPublicKeyIdentifier\":\"" + arn + "\"}}}");

        assertTheModelRefused(answer, "WrappingKeyCertificate");
    }

    @Test
    void theModelCheckRefusesAnImportWithoutItsKeyAttributes() {
        Response answer = call("ImportKey", "{\"KeyMaterial\":{\"KeyCryptogram\":{"
                + "\"Exportable\":true,"
                + "\"WrappedKeyCryptogram\":\"" + "0".repeat(32) + "\","
                + "\"ImportToken\":\"import-token-" + "0".repeat(24) + "\"}}}");

        assertTheModelRefused(answer, "KeyAttributes");
    }

    @Test
    void theModelCheckRefusesAnImportWhoseKeyAttributesHaveNoUsage() {
        Response answer = call("ImportKey", "{\"KeyMaterial\":{\"KeyCryptogram\":{"
                + "\"Exportable\":true,"
                + "\"WrappedKeyCryptogram\":\"" + "0".repeat(32) + "\","
                + "\"ImportToken\":\"import-token-" + "0".repeat(24) + "\","
                + "\"KeyAttributes\":{\"KeyClass\":\"SYMMETRIC_KEY\","
                + "\"KeyAlgorithm\":\"AES_128\","
                + "\"KeyModesOfUse\":{\"Encrypt\":true,\"Decrypt\":true}}}}}");

        assertTheModelRefused(answer, "KeyUsage");
    }

    @Test
    void theModelCheckRefusesAnImportOfAPublicKeyWithoutItsKeyAttributes() {
        Response answer = call("ImportKey", "{\"KeyMaterial\":{\"RootCertificatePublicKey\":{"
                + "\"PublicKeyCertificate\":\"a certificate\"}}}");

        assertTheModelRefused(answer, "KeyAttributes");
    }

    @Test
    void theModelCheckRefusesAnExportWithoutItsCertificateAuthorityPublicKeyIdentifier() {
        Response answer = call("ExportKey", "{\"ExportKeyIdentifier\":\"" + keyArn() + "\","
                + "\"KeyMaterial\":{\"KeyCryptogram\":{\"WrappingKeyCertificate\":\"a certificate\"}}}");

        assertTheModelRefused(answer, "CertificateAuthorityPublicKeyIdentifier");
    }

    @Test
    void theModelCheckRefusesAnImportWhoseCryptogramIsNotHexadecimal() {
        Response answer = call("ImportKey", "{\"KeyMaterial\":{\"KeyCryptogram\":{"
                + "\"Exportable\":true,"
                + "\"WrappedKeyCryptogram\":\"not hexadecimal at all\","
                + "\"ImportToken\":\"import-token-" + "0".repeat(24) + "\","
                + "\"KeyAttributes\":" + keyAttributes() + "}}}");

        assertTheModelRefused(answer, "WrappedKeyCryptogram");
    }

    @Test
    void theModelCheckRefusesACryptogramWithAWrappingSpecItDoesNotName() {
        Response answer = call("ExportKey", "{\"ExportKeyIdentifier\":\"" + keyArn() + "\","
                + "\"KeyMaterial\":{\"KeyCryptogram\":{"
                + "\"CertificateAuthorityPublicKeyIdentifier\":\"" + keyArn() + "\","
                + "\"WrappingKeyCertificate\":\"a certificate\","
                + "\"WrappingSpec\":\"RSA_OAEP_SHA_1\"}}}");

        assertTheModelRefused(answer, "WrappingSpec");
    }

    @Test
    void theModelCheckRefusesAnAgreementWithADerivationFunctionItDoesNotName() {
        Response answer = call("ExportKey", "{\"ExportKeyIdentifier\":\"" + keyArn() + "\","
                + "\"KeyMaterial\":{\"DiffieHellmanTr31KeyBlock\":{"
                + "\"PrivateKeyIdentifier\":\"" + keyArn() + "\","
                + "\"CertificateAuthorityPublicKeyIdentifier\":\"" + keyArn() + "\","
                + "\"PublicKeyCertificate\":\"a certificate\","
                + "\"DeriveKeyAlgorithm\":\"AES_128\","
                + "\"KeyDerivationFunction\":\"NIST_SP800_X\","
                + "\"KeyDerivationHashAlgorithm\":\"SHA_256\","
                + "\"DerivationData\":{\"SharedInformation\":\"00112233\"}}}}");

        assertTheModelRefused(answer, "KeyDerivationFunction");
    }

    @Test
    void theModelCheckRefusesAnAgreementWithADerivationHashItDoesNotName() {
        Response answer = call("ExportKey", "{\"ExportKeyIdentifier\":\"" + keyArn() + "\","
                + "\"KeyMaterial\":{\"DiffieHellmanTr31KeyBlock\":{"
                + "\"PrivateKeyIdentifier\":\"" + keyArn() + "\","
                + "\"CertificateAuthorityPublicKeyIdentifier\":\"" + keyArn() + "\","
                + "\"PublicKeyCertificate\":\"a certificate\","
                + "\"DeriveKeyAlgorithm\":\"AES_128\","
                + "\"KeyDerivationFunction\":\"NIST_SP800\","
                + "\"KeyDerivationHashAlgorithm\":\"SHA_224\","
                + "\"DerivationData\":{\"SharedInformation\":\"00112233\"}}}}");

        assertTheModelRefused(answer, "KeyDerivationHashAlgorithm");
    }

    /** The ARN of a key the request may name. */
    private static String keyArn() {
        return call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");
    }

    /** The key attributes of a symmetric key, with every member the model requires. */
    private static String keyAttributes() {
        return "{\"KeyUsage\":\"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY\","
                + "\"KeyClass\":\"SYMMETRIC_KEY\",\"KeyAlgorithm\":\"AES_128\","
                + "\"KeyModesOfUse\":{\"Encrypt\":true,\"Decrypt\":true}}";
    }

    /**
     * The refusal a caller sees when a request is missing a member the model requires: the
     * shape check's, because the shape check runs before the operation does.
     */
    private static void assertTheModelRefused(Response answer, String member) {
        answer.then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("failed to satisfy constraint"))
                .body("message", containsString(member));
    }

    @Test
    void aKeyThatAsksForACheckValueOfAnotherKindIsRefused() {
        // The kind follows the algorithm, so a request that names another is refused
        // rather than answered with a value of another kind.
        call("CreateKey", "{\"KeyAttributes\":{\"KeyAlgorithm\":\"TDES_2KEY\","
                + "\"KeyClass\":\"SYMMETRIC_KEY\",\"KeyUsage\":\"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY\","
                + "\"KeyModesOfUse\":{\"Encrypt\":true,\"Decrypt\":true}},"
                + "\"KeyCheckValueAlgorithm\":\"CMAC\"}")
                .then().statusCode(400)
                    .body("__type", equalTo("ValidationException"));
    }

    @Test
    void theKeysOfARegionAreListed() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");

        call("ListKeys", "{}")
                .then().statusCode(200)
                    .body("Keys", hasSize(greaterThanOrEqualTo(1)))
                    .body("Keys.KeyArn", hasItem(equalTo(arn)));
    }

    @Test
    void aListedKeyIsItsSummary() {
        // A list carries the key's summary and not the whole key: the identifier, the state,
        // the attributes, the check value and the two flags.
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");

        call("ListKeys", "{}")
                .then().statusCode(200)
                    .body("Keys.find { it.KeyArn == '" + arn + "' }.KeyState",
                            equalTo("CREATE_COMPLETE"))
                    .body("Keys.find { it.KeyArn == '" + arn + "' }.KeyAttributes.KeyAlgorithm",
                            equalTo("AES_256"))
                    .body("Keys.find { it.KeyArn == '" + arn + "' }.KeyAttributes.KeyUsage",
                            equalTo("TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY"))
                    .body("Keys.find { it.KeyArn == '" + arn + "' }.KeyCheckValue", notNullValue())
                    .body("Keys.find { it.KeyArn == '" + arn + "' }.Enabled", equalTo(true))
                    .body("Keys.find { it.KeyArn == '" + arn + "' }.Exportable", equalTo(true))
                    // The summary holds no timestamp and no origin: those belong to the whole
                    // key, which the list does not carry.
                    .body("Keys.find { it.KeyArn == '" + arn + "' }.CreateTimestamp", nullValue())
                    .body("Keys.find { it.KeyArn == '" + arn + "' }.KeyOrigin", nullValue())
                    .body("Keys.find { it.KeyArn == '" + arn + "' }.UsageStartTimestamp", nullValue());
    }

    @Test
    void anAliasIsCreatedAndListed() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");

        call("CreateAlias", "{\"AliasName\":\"alias/team/card-key\",\"KeyArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Alias.AliasName", equalTo("alias/team/card-key"))
                    .body("Alias.KeyArn", equalTo(arn));

        call("GetAlias", "{\"AliasName\":\"alias/team/card-key\"}")
                .then().statusCode(200)
                    .body("Alias.KeyArn", equalTo(arn));

        call("ListAliases", "{}")
                .then().statusCode(200)
                    .body("Aliases.AliasName", hasItem(equalTo("alias/team/card-key")));
    }

    @Test
    void anAliasNameIsUniqueInARegion() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");
        call("CreateAlias", "{\"AliasName\":\"alias/team/duplicate\",\"KeyArn\":\"" + arn + "\"}")
                .then().statusCode(200);

        call("CreateAlias", "{\"AliasName\":\"alias/team/duplicate\",\"KeyArn\":\"" + arn + "\"}")
                .then().statusCode(400)
                    .body("__type", containsString("ConflictException"));
    }

    @Test
    void aKeyIsTaggedAndUntagged() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");

        call("TagResource", "{\"ResourceArn\":\"" + arn + "\","
                + "\"Tags\":[{\"Key\":\"Origin\",\"Value\":\"PRIMARY\"}]}")
                .then().statusCode(200);

        call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Tags", hasSize(1))
                    .body("Tags.Key", contains(equalTo("Origin")))
                    .body("Tags.Value", contains(equalTo("PRIMARY")));

        call("UntagResource", "{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"Origin\"]}")
                .then().statusCode(200);

        call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Tags", hasSize(0));
    }

    @Test
    void aKeyThatDoesNotExistIsNotFound() {
        call("GetKey", "{\"KeyIdentifier\":\"arn:aws:payment-cryptography:us-east-1:000000000000:key/"
                + "0123456789abcdef0123456789abcdef\"}")
                .then().statusCode(400)
                    .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void aKeyIsDeleted() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");

        call("DeleteKey", "{\"KeyIdentifier\":\"" + arn + "\"}")
                .then().statusCode(200);

        call("GetKey", "{\"KeyIdentifier\":\"" + arn + "\"}")
                .then().statusCode(400)
                    .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void anUnsupportedOperationIsRefused() {
        call("EncryptKey", "{}")
                .then().statusCode(400)
                    .body("__type", containsString("UnknownOperationException"));
    }

    @Test
    void anOperationTheServiceDoesNotServeIsRefusedWhateverItsBodySays() {
        // The model names the operation and the service does not serve it, so it is refused
        // as the unknown operation it is. The body it carries does not change that: a body
        // that breaks the model's rules is not the answer to an operation that is not
        // served at all.
        call("RestoreKey", "{}")
                .then().statusCode(400)
                    .body("__type", containsString("UnknownOperationException"));
        call("RestoreKey", "{\"KeyIdentifier\":\"arn:aws:payment-cryptography:us-east-1:"
                + "000000000000:key/abcdefghijklmnop\"}")
                .then().statusCode(400)
                    .body("__type", containsString("UnknownOperationException"));
    }

    @Test
    void theCertificateOfAKeyIsServedOverTheWire() {
        // The certificate of an asymmetric key, with the chain that goes with it.
        String arn = call("CreateKey", CREATE_KEY_PAIR)
                .then().statusCode(200)
                    .extract().path("Key.KeyArn");

        call("GetPublicKeyCertificate", "{\"KeyIdentifier\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("KeyCertificate", notNullValue())
                    .body("KeyCertificateChain", notNullValue());
    }

    @Test
    void theCertificateOfAKeyIsNotServedForABareKeyId() {
        // The identifier of a key is its ARN, an alias ARN or an alias name, and the model's
        // pattern is what settles it, the same as for the other operations that take one. A
        // bare id names no key, so it is refused the way GetKey refuses it.
        String arn = call("CreateKey", CREATE_KEY_PAIR).then().statusCode(200)
                .extract().path("Key.KeyArn");
        String id = arn.substring(arn.lastIndexOf('/') + 1);

        call("GetPublicKeyCertificate", "{\"KeyIdentifier\":\"" + id + "\"}")
                .then().statusCode(400)
                    .body("__type", containsString("ValidationException"));
    }

    /**
     * A key is created with the usage and the modes of use the reference
     * requires, and a request without either of them is refused.
     */
    @Test
    void aKeyWithoutItsUsageOrItsModesIsRefused() {
        // The shape check settles the members the attributes must carry, so it is the refusal
        // that answers, and the message a caller sees is its own.
        assertTheModelRefused(call("CreateKey", "{\"KeyAttributes\":{\"KeyAlgorithm\":\"AES_256\","
                + "\"KeyClass\":\"SYMMETRIC_KEY\",\"KeyModesOfUse\":{\"Encrypt\":true}},"
                + "\"Exportable\":true}"), "KeyUsage");
        assertTheModelRefused(call("CreateKey", "{\"KeyAttributes\":{\"KeyAlgorithm\":\"AES_256\","
                + "\"KeyClass\":\"SYMMETRIC_KEY\","
                + "\"KeyUsage\":\"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY\"},\"Exportable\":true}"),
                "KeyModesOfUse");
    }

    @Test
    void aKeyWithoutItsAttributesIsRefused() {
        assertTheModelRefused(call("CreateKey", "{\"Exportable\":true}"), "KeyAttributes");
    }

    /**
     * A delete answers with the key, its state DELETE_PENDING and the moment it
     * is deleted, as the reference does.
     */
    @Test
    void aDeletedKeyIsAnsweredWithTheKey() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");

        call("DeleteKey", "{\"KeyIdentifier\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Key.KeyArn", equalTo(arn))
                    .body("Key.KeyState", equalTo("DELETE_PENDING"))
                    .body("Key.Enabled", equalTo(false))
                    .body("Key.DeletePendingTimestamp", notNullValue())
                    .body("Key.DeleteTimestamp", nullValue());
    }

    /**
     * The waiting period the reference offers is read, and a request naming one
     * outside it is refused.
     */
    @Test
    void aWaitingPeriodOutsideTheServiceRangeIsRefused() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");

        call("DeleteKey", "{\"KeyIdentifier\":\"" + arn + "\",\"DeleteKeyInDays\":1}")
                .then().statusCode(400)
                    .body("__type", containsString("ValidationException"));
        call("DeleteKey", "{\"KeyIdentifier\":\"" + arn + "\",\"DeleteKeyInDays\":7}")
                .then().statusCode(200);
    }

    /**
     * A listing names the state of the keys it wants, and a request naming
     * another state than the service knows is refused.
     */
    @Test
    void theKeysAreListedByTheirState() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");

        call("ListKeys", "{\"KeyState\":\"CREATE_COMPLETE\"}")
                .then().statusCode(200)
                    .body("Keys.KeyArn", hasItem(equalTo(arn)));
        call("ListKeys", "{\"KeyState\":\"DELETE_PENDING\"}")
                .then().statusCode(200)
                    .body("Keys", hasSize(0));
        call("ListKeys", "{\"KeyState\":\"NO_SUCH_STATE\"}")
                .then().statusCode(400)
                    .body("__type", containsString("ValidationException"));
    }

    /**
     * The key of an alias is optional, so an update naming no key removes the
     * key the alias names, and the alias stays.
     */
    @Test
    void anAliasIsDetachedFromItsKeyByAnUpdateNamingNoKey() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");
        call("CreateAlias", "{\"AliasName\":\"alias/team/detached\",\"KeyArn\":\"" + arn + "\"}")
                .then().statusCode(200);

        call("UpdateAlias", "{\"AliasName\":\"alias/team/detached\"}")
                .then().statusCode(200)
                    .body("Alias.AliasName", equalTo("alias/team/detached"))
                    .body("Alias.KeyArn", nullValue());

        call("GetAlias", "{\"AliasName\":\"alias/team/detached\"}")
                .then().statusCode(200)
                    .body("Alias.AliasName", equalTo("alias/team/detached"));
    }

    @Test
    void aKeyIsCreatedDisabledAndWithTags() {
        call("CreateKey", "{\"KeyAttributes\":{\"KeyAlgorithm\":\"AES_256\",\"KeyClass\":\"SYMMETRIC_KEY\","
                + "\"KeyUsage\":\"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY\","
                + "\"KeyModesOfUse\":{\"Encrypt\":true}},\"Exportable\":false,\"Enabled\":false,"
                + "\"Tags\":[{\"Key\":\"owner\",\"Value\":\"team\"}]}")
                .then().statusCode(200)
                    .body("Key.Enabled", equalTo(false))
                    .body("Key.Exportable", equalTo(false));

        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");

        call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Tags", hasSize(0));
    }

    @Test
    void aTaggingRequestNamesItsKeyInResourceArnOnly() {
        String named = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");
        String other = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");

        // The request names one key and another besides it. The change follows the member
        // the operation names its key in, which is the member the permission check reads.
        call("TagResource", "{\"ResourceArn\":\"" + other + "\",\"KeyIdentifier\":\"" + named + "\","
                + "\"Tags\":[{\"Key\":\"owner\",\"Value\":\"team\"}]}")
                .then().statusCode(200);

        call("ListTagsForResource", "{\"ResourceArn\":\"" + other + "\"}")
                .then().statusCode(200)
                    .body("Tags", hasSize(1));
        call("ListTagsForResource", "{\"ResourceArn\":\"" + named + "\"}")
                .then().statusCode(200)
                    .body("Tags", hasSize(0));
    }

    /**
     * The key operations name their key by the ARN of a key, the ARN of an alias or the name of an alias, as the
     * reference takes it there, so a bare id names no key and is refused.
     */
    @Test
    void aKeyIsNamedByItsArnOrByAnAliasAndNotByABareId() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");
        String id = arn.substring(arn.lastIndexOf('/') + 1);
        call("CreateAlias", "{\"AliasName\":\"alias/team/named\",\"KeyArn\":\"" + arn + "\"}")
                .then().statusCode(200);
        String aliasArn = "arn:aws:payment-cryptography:us-east-1:000000000000:alias/team/named";

        for (String named : List.of(arn, "alias/team/named", aliasArn)) {
            call("GetKey", "{\"KeyIdentifier\":\"" + named + "\"}")
                    .then().statusCode(200)
                        .body("Key.KeyArn", equalTo(arn));
        }
        call("GetKey", "{\"KeyIdentifier\":\"" + id + "\"}")
                .then().statusCode(400)
                    .body("__type", containsString("ValidationException"));
    }

    /**
     * The model of an alias requires only its name, but the reference refuses an alias without a key, so the
     * request is refused the way it refuses it.
     */
    @Test
    void anAliasMayNotBeCreatedWithoutAKey() {
        call("CreateAlias", "{\"AliasName\":\"alias/team/reserved\"}")
                .then().statusCode(400)
                    .body("__type", containsString("ValidationException"))
                    .body("message", containsString("You can't create an alias without a key."));

        // The name was not taken by the refused request.
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");
        call("CreateAlias", "{\"AliasName\":\"alias/team/reserved\",\"KeyArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Alias.AliasName", equalTo("alias/team/reserved"))
                    .body("Alias.KeyArn", equalTo(arn));
    }

    /**
     * The check value of an AES key is a CMAC, which is the only algorithm this service computes, so a request
     * naming another one is refused rather than answered with a check value of another kind.
     */
    @Test
    void aCheckValueAlgorithmOtherThanCmacIsRefused() {
        call("CreateKey", "{\"KeyAttributes\":{\"KeyAlgorithm\":\"AES_256\",\"KeyClass\":\"SYMMETRIC_KEY\","
                + "\"KeyUsage\":\"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY\","
                + "\"KeyModesOfUse\":{\"Encrypt\":true}},\"KeyCheckValueAlgorithm\":\"ANSI_X9_24\","
                + "\"Exportable\":true}")
                .then().statusCode(400)
                    .body("__type", containsString("ValidationException"));

        call("CreateKey", "{\"KeyAttributes\":{\"KeyAlgorithm\":\"AES_256\",\"KeyClass\":\"SYMMETRIC_KEY\","
                + "\"KeyUsage\":\"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY\","
                + "\"KeyModesOfUse\":{\"Encrypt\":true}},\"KeyCheckValueAlgorithm\":\"CMAC\","
                + "\"Exportable\":true}")
                .then().statusCode(200)
                    .body("Key.KeyCheckValueAlgorithm", equalTo("CMAC"));
    }

    /**
     * The answer carries every mode of use, the ones the request did not ask for as false, so a caller reading them
     * finds a boolean for each of them.
     */
    @Test
    void everyModeOfUseIsAnswered() {
        call("CreateKey", "{\"KeyAttributes\":{\"KeyAlgorithm\":\"AES_256\",\"KeyClass\":\"SYMMETRIC_KEY\","
                + "\"KeyUsage\":\"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY\","
                + "\"KeyModesOfUse\":{\"Encrypt\":true}},\"Exportable\":true}")
                .then().statusCode(200)
                    .body("Key.KeyAttributes.KeyModesOfUse", aMapWithSize(9))
                    .body("Key.KeyAttributes.KeyModesOfUse.Encrypt", equalTo(true))
                    .body("Key.KeyAttributes.KeyModesOfUse.Decrypt", equalTo(false))
                    .body("Key.KeyAttributes.KeyModesOfUse.NoRestrictions", equalTo(false));
    }

    /**
     * The tagging operations name their key by the ARN of one, as the reference takes it there, so a request naming
     * it by its id or by an alias names no key for the permission check and is refused.
     */
    @Test
    void aTaggingRequestNamingItsKeyByAnIdOrAnAliasIsRefused() {
        String arn = call("CreateKey", CREATE_KEY).then().statusCode(200)
                .extract().path("Key.KeyArn");
        String id = arn.substring(arn.lastIndexOf('/') + 1);
        call("CreateAlias", "{\"AliasName\":\"alias/team/tagged\",\"KeyArn\":\"" + arn + "\"}")
                .then().statusCode(200);

        for (String named : List.of(id, "alias/team/tagged")) {
            call("TagResource", "{\"ResourceArn\":\"" + named + "\","
                    + "\"Tags\":[{\"Key\":\"owner\",\"Value\":\"team\"}]}")
                    .then().statusCode(400)
                        .body("__type", containsString("ValidationException"));
        }
        call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Tags", hasSize(0));
    }

    @Test
    void aTaggingRequestWithoutItsKeyIsRefused() {
        call("TagResource", "{\"KeyIdentifier\":\"abc\"}")
                .then().statusCode(400)
                    .body("__type", containsString("ValidationException"));
    }

    @Test
    void theKeysAreListedAPageAtATime() {
        call("CreateKey", CREATE_KEY).then().statusCode(200);
        call("CreateKey", CREATE_KEY).then().statusCode(200);

        String first = call("ListKeys", "{\"MaxResults\":1}").then().statusCode(200)
                .body("Keys", hasSize(1))
                .extract().path("NextToken");

        call("ListKeys", "{\"MaxResults\":1,\"NextToken\":\"" + first + "\"}")
                .then().statusCode(200)
                    .body("Keys", hasSize(1));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void aKeyOutsideTheCommercialPartitionIsNamedByTheArnItWasMintedWith(PartitionCase partitionCase) {
        String region = partitionCase.region();
        String arn = callIn(region, "CreateKey", CREATE_KEY)
                .then().statusCode(200)
                    .extract().path("Key.KeyArn");
        cleanup.register(() -> callIn(region, "DeleteKey", "{\"KeyIdentifier\":\"" + arn + "\"}"));
        PartitionMatrix.assertArnIn(partitionCase, arn);

        callIn(region, "GetKey", "{\"KeyIdentifier\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Key.KeyArn", equalTo(arn));

        callIn(region, "TagResource", "{\"ResourceArn\":\"" + arn + "\","
                + "\"Tags\":[{\"Key\":\"Origin\",\"Value\":\"PRIMARY\"}]}")
                .then().statusCode(200);
        callIn(region, "ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Tags.Key", contains(equalTo("Origin")));

        callIn(region, "CreateAlias", "{\"AliasName\":\"alias/team/partition\",\"KeyArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                    .body("Alias.KeyArn", equalTo(arn));
        cleanup.register(() -> callIn(region, "DeleteAlias", "{\"AliasName\":\"alias/team/partition\"}"));
        callIn(region, "GetAlias", "{\"AliasName\":\"alias/team/partition\"}")
                .then().statusCode(200)
                    .body("Alias.KeyArn", equalTo(arn));

        String aliasArn = arn.substring(0, arn.lastIndexOf(":key/")) + ":alias/team/partition";
        callIn(region, "GetKey", "{\"KeyIdentifier\":\"" + aliasArn + "\"}")
                .then().statusCode(200)
                    .body("Key.KeyArn", equalTo(arn));
    }

    @Test
    void anArnWhoseRegionIsNotARegionIsRefusedByEveryMemberThatTakesOne() {
        for (String region : List.of("us-gov", "us-gov-west-", "u-gov-west-1", "europe-de-east-1", "US-GOV-WEST-1")) {
            String arn = "arn:aws-us-gov:payment-cryptography:" + region + ":000000000000:key/"
                    + "0123456789abcdef0123456789abcdef";

            call("GetKey", "{\"KeyIdentifier\":\"" + arn + "\"}")
                    .then().statusCode(400)
                        .body("__type", containsString("ValidationException"))
                        .body("message", containsString("regular expression"));
            call("CreateAlias", "{\"AliasName\":\"alias/team/refused\",\"KeyArn\":\"" + arn + "\"}")
                    .then().statusCode(400)
                        .body("__type", containsString("ValidationException"))
                        .body("message", containsString("regular expression"));
            call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                    .then().statusCode(400)
                        .body("__type", containsString("ValidationException"))
                        .body("message", containsString("regular expression"));
        }
    }
}
