package io.floci.aws.services.kms;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DryRun on the KMS calls that take it is answered with DryRunOperationException once the request has passed
 * the checks the call makes: CreateGrant, RetireGrant and RevokeGrant change no grant, and the cryptographic
 * calls return no result. A dry run the call would refuse keeps the call's error, and a DryRun value KMS's
 * JSON deserializer does not read as a boolean is refused as it refuses one.
 */
@QuarkusTest
class KmsDryRunIntegrationTest {

    private static final String KMS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String MESSAGE = Base64.getEncoder()
            .encodeToString("kms dry run".getBytes(StandardCharsets.UTF_8));
    private static final String GRANTEE = "arn:aws:iam::000000000000:role/kms-dry-run";
    private static final String MISSING_KEY = "11111111-2222-3333-4444-555555555555";

    /** The fixture's values by the names the request bodies give them in braces, such as {key}. */
    private final Map<String, String> values = new HashMap<>();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /**
     * A symmetric key holding one grant, with a ciphertext of the message; an HMAC key with the message's
     * MAC; and an ECC signing key with the message's signature.
     */
    @BeforeEach
    void createTheKeys() {
        values.clear();
        values.put("message", MESSAGE);
        values.put("key", createKey("{}"));
        values.put("macKey", createKey("{'KeyUsage': 'GENERATE_VERIFY_MAC', 'KeySpec': 'HMAC_256'}"));
        values.put("signingKey", createKey("{'KeyUsage': 'SIGN_VERIFY', 'KeySpec': 'ECC_NIST_P256'}"));
        values.put("ciphertext", result("Encrypt", "{'KeyId': '{key}', 'Plaintext': '{message}'}", "CiphertextBlob"));
        values.put("mac", result("GenerateMac",
                "{'KeyId': '{macKey}', 'Message': '{message}', 'MacAlgorithm': 'HMAC_SHA_256'}", "Mac"));
        values.put("signature", result("Sign",
                "{'KeyId': '{signingKey}', 'Message': '{message}', 'SigningAlgorithm': 'ECDSA_SHA_256'}", "Signature"));
        ExtractableResponse<Response> grant = call("CreateGrant",
                "{'KeyId': '{key}', 'GranteePrincipal': '" + GRANTEE + "', 'Operations': ['Encrypt']}")
                .statusCode(200).extract();
        values.put("grantId", grant.path("GrantId"));
        values.put("grantToken", grant.path("GrantToken"));
    }

    /** A request each call accepts in the fixture. */
    static Stream<String[]> acceptedRequests() {
        return Stream.of(
                new String[] {"CreateGrant",
                        "{'KeyId': '{key}', 'GranteePrincipal': '" + GRANTEE + "', 'Operations': ['Encrypt']}"},
                new String[] {"RetireGrant", "{'GrantToken': '{grantToken}'}"},
                new String[] {"RevokeGrant", "{'KeyId': '{key}', 'GrantId': '{grantId}'}"},
                new String[] {"Encrypt", "{'KeyId': '{key}', 'Plaintext': '{message}'}"},
                new String[] {"Decrypt", "{'CiphertextBlob': '{ciphertext}'}"},
                new String[] {"ReEncrypt", "{'CiphertextBlob': '{ciphertext}', 'DestinationKeyId': '{key}'}"},
                new String[] {"GenerateDataKey", "{'KeyId': '{key}', 'KeySpec': 'AES_256'}"},
                new String[] {"GenerateDataKeyWithoutPlaintext", "{'KeyId': '{key}', 'KeySpec': 'AES_256'}"},
                new String[] {"GenerateDataKeyPair", "{'KeyId': '{key}', 'KeyPairSpec': 'ECC_NIST_P256'}"},
                new String[] {"GenerateDataKeyPairWithoutPlaintext",
                        "{'KeyId': '{key}', 'KeyPairSpec': 'ECC_NIST_P256'}"},
                new String[] {"Sign",
                        "{'KeyId': '{signingKey}', 'Message': '{message}', 'SigningAlgorithm': 'ECDSA_SHA_256'}"},
                new String[] {"Verify", "{'KeyId': '{signingKey}', 'Message': '{message}', 'Signature': '{signature}',"
                        + " 'SigningAlgorithm': 'ECDSA_SHA_256'}"},
                new String[] {"GenerateMac",
                        "{'KeyId': '{macKey}', 'Message': '{message}', 'MacAlgorithm': 'HMAC_SHA_256'}"},
                new String[] {"VerifyMac",
                        "{'KeyId': '{macKey}', 'Message': '{message}', 'Mac': '{mac}', 'MacAlgorithm': 'HMAC_SHA_256'}"});
    }

    @ParameterizedTest
    @MethodSource("acceptedRequests")
    void aDryRunIsAnsweredWithDryRunOperationExceptionAndChangesNothing(String action, String body) {
        String before = grants();

        call(action, withDryRun(body, "true"))
                .statusCode(400)
                .body("__type", equalTo("DryRunOperationException"))
                .body("message", equalTo("The request would have succeeded, but the DryRun option is set."));

        assertEquals(before, grants());
    }

    @ParameterizedTest
    @MethodSource("acceptedRequests")
    void aDryRunValueThatIsNotABooleanIsRefused(String action, String body) {
        String before = grants();

        call(action, withDryRun(body, "'maybe'"))
                .statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("STRING_VALUE can not be converted to an Boolean"));

        assertEquals(before, grants());
    }

    /** A string naming true, in any case, is read as true, as KMS's deserializer reads it. */
    @ParameterizedTest
    @ValueSource(strings = {"'true'", "'TRUE'", "'yes'", "'y'", "'t'", "'1'"})
    void aStringNamingTrueIsADryRun(String value) {
        call("Encrypt", withDryRun("{'KeyId': '{key}', 'Plaintext': '{message}'}", value))
                .statusCode(400)
                .body("__type", equalTo("DryRunOperationException"));
    }

    /** False, null, and a string naming false in any case, make the call itself. */
    @ParameterizedTest
    @ValueSource(strings = {"false", "null", "'false'", "'No'", "'n'", "'f'", "'0'"})
    void aValueNamingFalseMakesTheCall(String value) {
        call("Encrypt", withDryRun("{'KeyId': '{key}', 'Plaintext': '{message}'}", value))
                .statusCode(200)
                .body("CiphertextBlob", notNullValue());
    }

    /** Any other value is refused with the deserializer's message for it (measured on AWS). */
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "2 | NUMBER_VALUE can not be converted to a Boolean",
            "1.5 | DECIMAL_VALUE cannot be converted to Boolean",
            "[true] | Start of list found where not expected",
            "{} | Start of structure or map found where not expected."
    })
    void anyOtherDryRunValueIsRefused(String value, String message) {
        call("Encrypt", withDryRun("{'KeyId': '{key}', 'Plaintext': '{message}'}", value))
                .statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo(message));
    }

    /** The call refuses each of these; its dry run keeps the same error, and neither changes a grant. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "CreateGrant | {'KeyId': '{key}', 'GranteePrincipal': '" + GRANTEE + "', 'Operations': ['Bogus']}"
                    + " | ValidationException",
            "RevokeGrant | {'KeyId': '{key}', 'GrantId': '0123456789abcdef'} | NotFoundException",
            "Encrypt | {'KeyId': '" + MISSING_KEY + "', 'Plaintext': '{message}'} | NotFoundException",
            "Decrypt | {'CiphertextBlob': 'AAAA'} | InvalidCiphertextException",
            "GenerateDataKey | {'KeyId': '{signingKey}', 'KeySpec': 'AES_256'} | InvalidKeyUsageException",
            "Sign | {'KeyId': '{macKey}', 'Message': '{message}', 'SigningAlgorithm': 'ECDSA_SHA_256'}"
                    + " | InvalidKeyUsageException",
            "Verify | {'KeyId': '{signingKey}', 'Message': '{message}', 'Signature': '{mac}',"
                    + " 'SigningAlgorithm': 'ECDSA_SHA_256'} | KMSInvalidSignatureException",
            "VerifyMac | {'KeyId': '{macKey}', 'Message': '{message}', 'Mac': '{message}',"
                    + " 'MacAlgorithm': 'HMAC_SHA_256'} | KMSInvalidMacException"
    })
    void aDryRunTheCallWouldRefuseKeepsItsError(String action, String body, String code) {
        String before = grants();

        for (String dryRun : List.of("true", "false")) {
            call(action, withDryRun(body, dryRun))
                    .statusCode(400)
                    .body("__type", equalTo(code));
        }

        assertEquals(before, grants());
    }

    /** The symmetric key's grants, the only state these calls can change. */
    private String grants() {
        return call("ListGrants", "{'KeyId': '{key}'}").statusCode(200).extract().asString();
    }

    private String createKey(String body) {
        return result("CreateKey", body, "KeyMetadata.KeyId");
    }

    private String result(String action, String body, String path) {
        return call(action, body).statusCode(200).extract().path(path);
    }

    /** {@code body} with a DryRun member holding {@code value}, written with single quotes for double ones. */
    private static String withDryRun(String body, String value) {
        return RestAssuredJsonUtils.withMember(body, "DryRun", value);
    }

    /**
     * Sends {@code body}, written with single quotes for JSON's double ones and with a fixture's name in braces
     * standing for its value, to {@code action}.
     */
    private ValidatableResponse call(String action, String body) {
        String json = body.replace('\'', '"');
        for (Map.Entry<String, String> value : values.entrySet()) {
            json = json.replace("{" + value.getKey() + "}", value.getValue());
        }
        return given()
                .header("X-Amz-Target", "TrentService." + action)
                .contentType(KMS_CONTENT_TYPE)
                .body(json)
                .when().post("/")
                .then();
    }
}
