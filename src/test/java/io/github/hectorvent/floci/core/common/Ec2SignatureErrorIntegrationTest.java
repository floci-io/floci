package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.ValidateSignaturesProfile;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;

import java.net.URI;

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

    @Test
    void anEc2SigningScopeDoesNotChangeRestJsonErrors() {
        given().filter(AwsRequestSigner.signedAs("AKIAUNKNOWNACCESSKEY", "some-secret", "ec2"))
                .get("/2015-03-31/functions").then()
                .statusCode(403).contentType("application/json")
                .body("__type", equalTo("UnrecognizedClientException"));
    }
}
