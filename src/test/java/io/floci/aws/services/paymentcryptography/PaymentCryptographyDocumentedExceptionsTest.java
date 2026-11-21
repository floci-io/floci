package io.floci.aws.services.paymentcryptography;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * The errors the reference documents, and the status it answers them with.
 *
 * <p>The reference's API pages state, for every action, the errors it raises and the status
 * each one carries. The control plane answers them all with 400, the not-found error
 * included, which is worth a test of its own: an emulator that answers a missing key with a
 * 404 is a client-visible difference that no other test would catch.
 *
 * <p>The table is the reference's own: an action that names a resource that is not there
 * answers with the not-found error.
 */
@QuarkusTest
class PaymentCryptographyDocumentedExceptionsTest {

    /** The control plane of Payment Cryptography is an awsJson 1.0 service. */
    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";

    private static final String TARGET = "PaymentCryptographyControlPlane.";

    /** A key identifier of a key the service does not hold. */
    private static final String MISSING_KEY = "arn:aws:payment-cryptography:us-east-1:"
            + "000000000000:key/0123456789abcdef0123456789abcdef";

    /** The alias of an alias the service does not hold. */
    private static final String MISSING_ALIAS = "alias/team/missing";

    /** The key the requests that need one name, made once for the whole class. */
    private static String keyArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /**
     * A key of the service, made once for the whole class.
     *
     * <p>The requests that name a key make one, so the ones that name a key that is not
     * there are answered with the not-found error rather than with the error of a request
     * that names nothing.
     *
     * @return the ARN of the key
     */
    private static synchronized String keyArn() {
        if (keyArn == null) {
            keyArn = given()
                    .header("X-Amz-Target", TARGET + "CreateKey")
                    .contentType(CONTENT_TYPE)
                    .body("{\"KeyAttributes\":{\"KeyAlgorithm\":\"AES_256\","
                            + "\"KeyClass\":\"SYMMETRIC_KEY\","
                            + "\"KeyUsage\":\"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY\","
                            + "\"KeyModesOfUse\":{\"Encrypt\":true,\"Decrypt\":true}},"
                            + "\"Exportable\":true}")
                .when().post("/")
                .then().statusCode(200)
                    .extract().path("Key.KeyArn");
        }
        return keyArn;
    }

    private static Response call(String action, String body) {
        return given()
                .header("X-Amz-Target", TARGET + action)
                .contentType(CONTENT_TYPE)
                .body(body)
            .when().post("/");
    }

    /**
     * The actions that name a key or an alias, with the body that names one that is not
     * there.
     *
     * @return the action and the body of each of them
     */
    private static Stream<Arguments> actionsThatNameAMissingResource() {
        return Stream.of(
                Arguments.of("GetKey", "{\"KeyIdentifier\":\"" + MISSING_KEY + "\"}"),
                Arguments.of("DeleteKey", "{\"KeyIdentifier\":\"" + MISSING_KEY + "\"}"),
                Arguments.of("RestoreKey", "{\"KeyIdentifier\":\"" + MISSING_KEY + "\"}"),
                Arguments.of("StartKeyUsage", "{\"KeyIdentifier\":\"" + MISSING_KEY + "\"}"),
                Arguments.of("StopKeyUsage", "{\"KeyIdentifier\":\"" + MISSING_KEY + "\"}"),
                Arguments.of("GetPublicKeyCertificate", "{\"KeyIdentifier\":\"" + MISSING_KEY + "\"}"),
                Arguments.of("AddKeyReplicationRegions", "{\"KeyIdentifier\":\"" + MISSING_KEY
                        + "\",\"ReplicationRegions\":[\"us-west-2\"]}"),
                Arguments.of("RemoveKeyReplicationRegions", "{\"KeyIdentifier\":\"" + MISSING_KEY
                        + "\",\"ReplicationRegions\":[\"us-west-2\"]}"),
                Arguments.of("TagResource", "{\"ResourceArn\":\"" + MISSING_KEY
                        + "\",\"Tags\":[{\"Key\":\"Origin\",\"Value\":\"PRIMARY\"}]}"),
                Arguments.of("UntagResource", "{\"ResourceArn\":\"" + MISSING_KEY
                        + "\",\"TagKeys\":[\"Origin\"]}"),
                Arguments.of("ListTagsForResource", "{\"ResourceArn\":\"" + MISSING_KEY + "\"}"),
                Arguments.of("GetResourcePolicy", "{\"ResourceArn\":\"" + MISSING_KEY + "\"}"),
                Arguments.of("DeleteResourcePolicy", "{\"ResourceArn\":\"" + MISSING_KEY + "\"}"),
                Arguments.of("PutResourcePolicy", "{\"ResourceArn\":\"" + MISSING_KEY
                        + "\",\"Policy\":\"{\\\"Version\\\":\\\"2012-10-17\\\",\\\"Statement\\\":[]}\"}"),
                Arguments.of("GetAlias", "{\"AliasName\":\"" + MISSING_ALIAS + "\"}"),
                Arguments.of("DeleteAlias", "{\"AliasName\":\"" + MISSING_ALIAS + "\"}"),
                Arguments.of("UpdateAlias", "{\"AliasName\":\"" + MISSING_ALIAS + "\"}"),
                Arguments.of("GetMpaTeamAssociation",
                        "{\"Action\":\"IMPORT_ROOT_PUBLIC_KEY_CERTIFICATE\"}"));
    }

    @ParameterizedTest
    @MethodSource("actionsThatNameAMissingResource")
    void anActionThatNamesAKeyThatIsNotThereAnswersNotFound(String action, String body) {
        // The reference answers a resource it cannot find with the not-found error, and the
        // control plane answers every one of its errors with 400.
        call(action, body)
                .then().statusCode(400)
                    .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void anActionThatNamesAnAliasThatIsNotThereAnswersNotFound() {
        // The alias of the update is named, and the key it would point at is not there
        // either, so the alias is what the request cannot find.
        call("UpdateAlias", "{\"AliasName\":\"" + MISSING_ALIAS + "\",\"KeyArn\":\""
                + keyArn() + "\"}")
                .then().statusCode(400)
                    .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void anActionThatNamesNoResourceAtAllIsRefused() {
        // The reference's model makes the member of each of these requests required, so a
        // request that leaves it out is refused before the action does any work.
        call("GetKey", "{}")
                .then().statusCode(400)
                    .body("__type", containsString("ValidationException"));
        call("GetAlias", "{}")
                .then().statusCode(400)
                    .body("__type", containsString("ValidationException"));
        call("ListTagsForResource", "{}")
                .then().statusCode(400)
                    .body("__type", containsString("ValidationException"));
    }

    @Test
    void anActionThatIsNotServedIsRefused() {
        call("NotAnAction", "{}")
                .then().statusCode(400)
                    .body("__type", containsString("UnknownOperationException"));
    }

    @Test
    void anActionThatChangesNothingAnswersWithAnEmptyBody() {
        // The reference's pages for these three actions show a response with no members at
        // all, so the answer carries nothing.
        call("TagResource", "{\"ResourceArn\":\"" + keyArn()
                + "\",\"Tags\":[{\"Key\":\"Origin\",\"Value\":\"PRIMARY\"}]}")
                .then().statusCode(200)
                    .body(equalTo("{}"));
        call("UntagResource", "{\"ResourceArn\":\"" + keyArn() + "\",\"TagKeys\":[\"Origin\"]}")
                .then().statusCode(200)
                    .body(equalTo("{}"));
        // The policy is put first: a key that carries none has no policy to delete, and the
        // reference answers that with the not-found error rather than with an empty answer.
        // The document is carried in one line: a line break inside a JSON string is not a
        // JSON string, and the request would never reach the service.
        String policy = PaymentCryptographyTestFixtures.CROSS_ACCOUNT_POLICY
                .replaceAll("\\s*\\n\\s*", " ");
        call("PutResourcePolicy", "{\"ResourceArn\":\"" + keyArn()
                + "\",\"Policy\":\"" + policy.replace("\"", "\\\"") + "\"}")
                .then().statusCode(200);
        call("DeleteResourcePolicy", "{\"ResourceArn\":\"" + keyArn() + "\"}")
                .then().statusCode(200)
                    .body(equalTo("{}"));
    }
}
