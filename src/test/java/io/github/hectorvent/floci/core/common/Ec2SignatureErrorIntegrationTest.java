package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.ValidateSignaturesProfile;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(ValidateSignaturesProfile.class)
class Ec2SignatureErrorIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @Inject
    IamService iamService;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"matching", "wrong", "missing", "live"})
    void temporaryCredentialExpiryRequiresTheMatchingToken(String tokenMode) throws Exception {
        String key = "ASIA" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        cleanup.register(() -> iamService.unregisterSession("000000000000", key));
        iamService.registerSession(key, "session-secret", "issued-token",
                "arn:aws:iam::000000000000:role/expired-session", "live".equals(tokenMode)
                        ? Instant.now().plusSeconds(3600) : Instant.parse("2020-01-01T00:00:00Z"), null);
        AwsCredentials credentials = "missing".equals(tokenMode)
                ? AwsBasicCredentials.create(key, "session-secret")
                : AwsSessionCredentials.create(key, "session-secret",
                        "wrong".equals(tokenMode) ? "wrong-token" : "issued-token");
        try (Ec2Client client = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(Apache5HttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(credentials)).build()) {
            if ("live".equals(tokenMode)) {
                assertNotNull(client.describeVpcs().vpcs());
                return;
            }
            Ec2Exception denied = assertThrows(Ec2Exception.class, client::describeVpcs);
            assertEquals("matching".equals(tokenMode) ? 400 : 401, denied.statusCode());
            assertEquals("matching".equals(tokenMode) ? "RequestExpired" : "AuthFailure",
                    denied.awsErrorDetails().errorCode());
            assertNotNull(denied.requestId());
        }
        Map<String, String> query = new HashMap<>(Map.of("Action", "DescribeVpcs", "Version", "2016-11-15"));
        if (!"missing".equals(tokenMode)) {
            query.put("X-Amz-Security-Token", "wrong".equals(tokenMode) ? "wrong-token" : "issued-token");
        }
        URI presigned = AwsRequestSigner.signedAs(key, "session-secret", "ec2")
                .presignedUri("GET", endpoint, query, new byte[0]);
        given().urlEncodingEnabled(false).get(presigned).then()
                .statusCode("matching".equals(tokenMode) ? 400 : 401)
                .body("Response.Errors.Error.Code",
                        equalTo("matching".equals(tokenMode) ? "RequestExpired" : "AuthFailure"));
    }

    @Test
    void sdkParsesAnUnknownAccessKeyWithSignatureValidation() {
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(),
                "AKIAUNKNOWNACCESSKEY", "some-secret", Region.US_EAST_1, Apache5HttpClient.builder(), endpoint)) {
            Ec2Exception denied = assertThrows(Ec2Exception.class, client::describeVpcs);
            assertEquals(401, denied.statusCode());
            assertEquals("AuthFailure", denied.awsErrorDetails().errorCode());
            assertEquals("AWS was not able to validate the provided access credentials",
                    denied.awsErrorDetails().errorMessage());
            assertNotNull(denied.requestId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownKeysOnSignedAndPresignedGetUseEc2Envelope(boolean presigned) throws Exception {
        AwsRequestSigner signer = AwsRequestSigner.signedAs("AKIAUNKNOWNACCESSKEY", "some-secret", "ec2");
        Ec2ErrorAssertions.assertGetError(signer, endpoint, presigned, 401, "AuthFailure",
                "AWS was not able to validate the provided access credentials");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void badSignaturesOnSignedAndPresignedGetUseEc2Envelope(boolean presigned) throws Exception {
        AwsRequestSigner signer = AwsRequestSigner.signedAs("test", "wrong-secret", "ec2");
        Ec2ErrorAssertions.assertGetError(signer, endpoint, presigned, 403, "SignatureDoesNotMatch",
                "The request signature we calculated does not match");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1800, 1800})
    void headerTimestampSkewUsesRequestExpired(long offset) throws Exception {
        AwsRequestSigner signer = AwsRequestSigner.signedAs("test", "test", "ec2")
                .signedAt(Instant.now().plusSeconds(offset));
        Ec2ErrorAssertions.assertGetError(signer, endpoint, false, 400, "RequestExpired",
                offset < 0 ? "Signature expired:" : "Signature not yet current:");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1800, 1800})
    void presignedExpiryAndFutureTimestampUseRequestExpired(long offset) throws Exception {
        AwsRequestSigner signer = AwsRequestSigner.signedAs("test", "test", "ec2")
                .signedAt(Instant.now().plusSeconds(offset));
        Ec2ErrorAssertions.assertGetError(signer, endpoint, true, 400, "RequestExpired",
                offset < 0 ? "Signature expired:" : "Signature not yet current:");
    }

    @Test
    void sdkParsesBadSignature() {
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(),
                "test", "wrong-secret", Region.US_EAST_1, endpoint)) {
            Ec2Exception denied = assertThrows(Ec2Exception.class, client::describeVpcs);
            assertEquals(403, denied.statusCode());
            assertEquals("SignatureDoesNotMatch", denied.awsErrorDetails().errorCode());
            assertNotNull(denied.requestId());
        }
    }

    @Test
    void incompleteSignatureUsesEc2Envelope() {
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261105/us-east-1/ec2/aws4_request")
                .queryParam("Action", "DescribeVpcs").queryParam("Version", "2016-11-15")
                .get(endpoint).then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("IncompleteSignature"));
    }

    @Test
    void anEc2SigningScopeDoesNotChangeRestJsonErrors() {
        given().filter(AwsRequestSigner.signedAs("AKIAUNKNOWNACCESSKEY", "some-secret", "ec2"))
                .get("/2015-03-31/functions").then()
                .statusCode(403).contentType("application/json")
                .body("__type", equalTo("UnrecognizedClientException"));
    }
}
