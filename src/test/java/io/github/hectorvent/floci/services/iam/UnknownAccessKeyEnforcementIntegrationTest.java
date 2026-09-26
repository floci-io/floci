package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With enforcement on, an access key that exists nowhere must be rejected rather than waved
 * through. Before this was closed, any string in the credential scope authorized the request.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class UnknownAccessKeyEnforcementIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static final String UNKNOWN_KEY_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKIADOESNOTEXIST0000/20260101/us-east-1/%s/aws4_request, "
                    + "SignedHeaders=host, Signature=not-a-real-signature";

    @Test
    void jsonServiceRejectsAnUnknownAccessKey() {
        given()
                .header("Authorization", UNKNOWN_KEY_AUTH.formatted("lambda"))
                .contentType("application/json")
        .when()
                .get("/2015-03-31/functions")
        .then()
                .statusCode(403)
                .body(containsString("UnrecognizedClientException"));
    }

    @Test
    void s3RejectsAnUnknownAccessKeyWithTheS3ErrorCode() {
        given()
                .header("Authorization", UNKNOWN_KEY_AUTH.formatted("s3"))
        .when()
                .get("/")
        .then()
                .statusCode(403)
                .body(containsString("InvalidAccessKeyId"));
    }

    @Test
    void anUnsignedJsonServiceCallIsRefused() {
        given()
                .contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "DynamoDB_20120810.ListTables")
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(403)
                .body(containsString("MissingAuthenticationToken"));
    }

    @Test
    void anUnsignedQueryCallIsRefused() {
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateUser")
                .formParam("UserName", "unsigned-user")
                .formParam("Version", "2010-05-08")
        .when()
                .post("/")
        .then()
                .statusCode(403)
                .body(containsString("MissingAuthenticationToken"));
    }

    @Test
    void anUnsignedCborCallIsRefusedInCbor() {
        byte[] body = given()
                .contentType("application/x-amz-cbor-1.1")
                .header("X-Amz-Target", "Kinesis_20131202.ListStreams")
                .body(new byte[] {(byte) 0xa0})
        .when()
                .post("/")
        .then()
                .statusCode(403)
                .header("smithy-protocol", "rpc-v2-cbor")
                .contentType("application/x-amz-cbor-1.1")
                .extract().asByteArray();

        // CBOR text strings carry their content literally, so the type is readable in the bytes
        // without decoding. A JSON body would have been unreadable to the client that sent this.
        assertTrue(new String(body, StandardCharsets.ISO_8859_1).contains("MissingAuthenticationToken"),
                "the rejection must arrive CBOR-encoded, not as JSON");
    }

    @ParameterizedTest
    @ValueSource(strings = {"InitiateAuth", "SignUp", "ForgotPassword", "ConfirmSignUp", "GetUser"})
    void anUnsignedCognitoFlowAwsServesWithoutCredentialsIsNotRefused(String operation) {
        // AWS marks these noAuth: the user has no AWS credentials yet, which is the point of them.
        // They must reach the service and fail on their own merits, not on a missing signature.
        given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService." + operation)
                .body("{}")
        .when()
                .post("/")
        .then()
                .body("__type", not(equalTo("MissingAuthenticationTokenException")));
    }

    @Test
    void anUnsignedAssumeRoleWithWebIdentityIsNotRefused() {
        // Also noAuth: the web identity token is the credential, so AWS requires no signature.
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "AssumeRoleWithWebIdentity")
                .formParam("Version", "2011-06-15")
                .formParam("RoleArn", "arn:aws:iam::000000000000:role/web-identity-role")
                .formParam("RoleSessionName", "unsigned-session")
                .formParam("WebIdentityToken", "dummy-token")
        .when()
                .post("/")
        .then()
                .body(not(containsString("MissingAuthenticationToken")));
    }

    @Test
    void theRootStandInKeyStillWorks() {
        given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/s3/aws4_request, "
                        + "SignedHeaders=host, Signature=whatever")
        .when()
                .get("/")
        .then()
                .statusCode(200);
    }
}
