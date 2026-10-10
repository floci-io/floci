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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With enforcement on, a request whose {@code Authorization} header carries no readable credential
 * has nothing to check. It used to skip enforcement altogether: leaving off the
 * {@code aws4_request} terminator, or sending a header that names no access key at all, let any
 * caller make the management calls an unsigned request is refused.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class UnreadableCredentialEnforcementIntegrationTest {

    private static final String UNREADABLE_SCOPE_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKIADOESNOTEXIST0000/20260101/us-east-1/%s, "
                    + "SignedHeaders=host, Signature=not-a-real-signature";

    private static final String NO_KEY_AUTH =
            "AWS4-HMAC-SHA256 Credential=/20260101/us-east-1/%s/aws4_request, "
                    + "SignedHeaders=host, Signature=not-a-real-signature";

    private static final String NO_CREDENTIAL_AUTH =
            "AWS4-HMAC-SHA256 SignedHeaders=host, Signature=not-a-real-signature";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            UNREADABLE_SCOPE_AUTH,
            NO_KEY_AUTH,
            NO_CREDENTIAL_AUTH,
            "Bearer not-an-aws-credential",
            "AWS AKIADOESNOTEXIST0000:c2lnbmF0dXJl",
            "Credential=AKIADOESNOTEXIST0000/20260101/us-east-1/iam/aws4_request",
            "garbage",
            ""})
    void aQueryCallIsRefusedAndDoesNothing(String authorization) {
        String userName = "unreadable-credential-" + Integer.toHexString(authorization.hashCode());
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", authorization.formatted("iam"))
                .formParam("Action", "CreateUser")
                .formParam("UserName", userName)
                .formParam("Version", "2010-05-08")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body(containsString("<Code>IncompleteSignature</Code>"));

        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/iam/aws4_request, "
                        + "SignedHeaders=host, Signature=setup")
                .formParam("Action", "GetUser")
                .formParam("UserName", userName)
                .formParam("Version", "2010-05-08")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body(containsString("NoSuchEntity"));
    }

    @Test
    void anEmptyHeaderIsRefusedWithTheGuidesWordingForIt() {
        // The IAM User Guide's "Troubleshoot SigV4" gives the message for this case.
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", "")
                .formParam("Action", "ListUsers")
                .formParam("Version", "2010-05-08")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body(containsString("<Message>Authorization header cannot be empty.</Message>"));
    }

    @Test
    void aSigV4HeaderWithoutACredentialIsRefusedWithTheGuidesWordingForIt() {
        given()
                .contentType("application/x-amz-json-1.0")
                .header("Authorization", NO_CREDENTIAL_AUTH)
                .header("X-Amz-Target", "DynamoDB_20120810.ListTables")
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("IncompleteSignatureException"))
                .body("message", equalTo("Authorization header requires 'Credential' parameter."));
    }

    @ParameterizedTest
    @ValueSource(strings = {UNREADABLE_SCOPE_AUTH, "Bearer not-an-aws-credential"})
    void aJsonCallIsRefused(String authorization) {
        given()
                .contentType("application/x-amz-json-1.0")
                .header("Authorization", authorization.formatted("dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.ListTables")
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", containsString("IncompleteSignatureException"));
    }

    @Test
    void aCborCallIsRefusedInCbor() {
        byte[] body = given()
                .contentType("application/x-amz-cbor-1.1")
                .header("Authorization", UNREADABLE_SCOPE_AUTH.formatted("kinesis"))
                .header("X-Amz-Target", "Kinesis_20131202.ListStreams")
                .body(new byte[] {(byte) 0xa0})
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .contentType("application/x-amz-cbor-1.1")
                .extract().asByteArray();

        assertTrue(new String(body, StandardCharsets.ISO_8859_1).contains("IncompleteSignatureException"),
                "the rejection must arrive CBOR-encoded, not as JSON");
    }

    @Test
    void aCognitoFlowAwsServesWithoutCredentialsIsNotRefused() {
        // As for an unsigned request: the operation needs no credential, so an unreadable one
        // does not stop it, and Cognito answers the missing client itself.
        given()
                .contentType("application/x-amz-json-1.1")
                .header("Authorization", "Bearer not-an-aws-credential")
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService.InitiateAuth")
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {UNREADABLE_SCOPE_AUTH, NO_KEY_AUTH})
    void anS3CallClaimingSigV4IsRefusedWithTheS3ErrorCode(String authorization) {
        given()
                .header("Authorization", authorization.formatted("s3"))
        .when()
                .get("/")
        .then()
                .statusCode(400)
                .body(containsString("<Code>AuthorizationHeaderMalformed</Code>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Slashes encoded twice, as in an AWS-recorded presigned URL: one segment, no scope.
            "AKIADOESNOTEXIST0000%252F20260101%252Fus-east-1%252Fs3%252Faws4_request",
            "AKIADOESNOTEXIST0000%2F20260101%2Fus-east-1%2Fs3"})
    void aPresignedS3UrlWithAMalformedCredentialIsRefusedWithTheQueryErrorCode(String credential) {
        given()
                .urlEncodingEnabled(false)
        .when()
                .get("/unreadable-credential-bucket/key?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                        + "&X-Amz-Credential=" + credential
                        // A signing time the URL's expiry check never passes, so the test reads no clock.
                        + "&X-Amz-Date=29990101T000000Z"
                        + "&X-Amz-Expires=300&X-Amz-SignedHeaders=host&X-Amz-Signature=not-a-real-signature")
        .then()
                .statusCode(400)
                .body(containsString("<Code>AuthorizationQueryParametersError</Code>"));
    }

    @Test
    void aRestCallWithAnotherSchemeIsTreatedAsUnsigned() {
        // AppSync, CodeArtifact's package endpoints and API Gateway's authorizers take Bearer and
        // Basic tokens of their own, so a REST request carrying one is not refused here.
        given()
                .header("Authorization", "Bearer not-an-aws-credential")
        .when()
                .get("/")
        .then()
                .statusCode(200);
    }

    @Test
    void aRestRouteNoActionMapsIsLeftToItsHandler() {
        // As for an unsigned REST request: the filter cannot tell it from the API Gateway execute
        // path, function URLs and the other paths it sees that are unsigned by design.
        given()
                .header("Authorization", UNREADABLE_SCOPE_AUTH.formatted("iam"))
        .when()
                .get("/_floci/health")
        .then()
                .statusCode(200);
    }
}
