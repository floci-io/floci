package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class Ec2IamErrorIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void sdkParsesDenialAndAllowsTheSameUserAfterPermissionIsGranted() {
        String name = "ec2-denial-" + UUID.randomUUID();
        UserCredentials credentials = user(name);
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(),
                credentials.accessKeyId(), credentials.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            Ec2Exception denied = assertThrows(Ec2Exception.class, client::describeVpcs);
            assertEquals(403, denied.statusCode());
            assertEquals("UnauthorizedOperation", denied.awsErrorDetails().errorCode());
            assertTrue(denied.awsErrorDetails().errorMessage()
                    .startsWith("You are not authorized to perform this operation."));
            assertNotNull(denied.requestId());
            EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("000000000000", "iam"), name,
                    "describe", """
                    {"Version":"2012-10-17","Statement":[
                      {"Effect":"Allow","Action":"ec2:DescribeVpcs","Resource":"*"}
                    ]}
                    """);
            assertNotNull(client.describeVpcs().vpcs());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void signedAndPresignedGetDenialsUseEc2Envelope(boolean presigned) throws Exception {
        UserCredentials credentials = user("ec2-get-denial-" + UUID.randomUUID());
        AwsRequestSigner signer = AwsRequestSigner.signedAs(
                credentials.accessKeyId(), credentials.secretAccessKey(), "ec2");
        Ec2ErrorAssertions.assertGetError(signer, endpoint, presigned, 403, "UnauthorizedOperation",
                "You are not authorized to perform this operation.");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownKeysOnSignedAndPresignedGetUseEc2AuthenticationError(boolean presigned) throws Exception {
        AwsRequestSigner signer = AwsRequestSigner.signedAs("AKIAUNKNOWNACCESSKEY", "some-secret", "ec2");
        Ec2ErrorAssertions.assertGetError(signer, endpoint, presigned, 401, "AuthFailure",
                "AWS was not able to validate the provided access credentials");
    }

    @Test
    void missingCredentialsUseEc2Envelope() {
        given().formParam("Action", "DescribeVpcs").formParam("Version", "2016-11-15")
                .post(endpoint).then().statusCode(403)
                .body("Response.Errors.Error.Code", equalTo("MissingAuthenticationToken"))
                .body("Response.RequestID", not(emptyOrNullString()));
    }

    @Test
    void unreadableCredentialUsesEc2Envelope() {
        given().header("Authorization", "AWS4-HMAC-SHA256 broken")
                .formParam("Action", "DescribeVpcs").formParam("Version", "2016-11-15")
                .post(endpoint).then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("IncompleteSignature"));
    }

    private UserCredentials user(String name) {
        return EnforcementFixtures.createUser(cleanup, endpoint.getPort(), auth("000000000000", "iam"), name);
    }

    @Test
    void sdkParsesAnUnknownAccessKeyWithIamEnforcement() {
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
}
