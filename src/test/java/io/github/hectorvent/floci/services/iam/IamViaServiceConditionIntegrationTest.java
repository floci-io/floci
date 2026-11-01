package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.iam.model.ListUsersRequest;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class IamViaServiceConditionIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @Inject
    IamService iam;

    @ParameterizedTest
    @CsvSource({"Allow,false,true", "Allow,true,false", "Deny,false,false", "Deny,true,true"})
    void signedDirectRequestsEvaluateViaServiceConditions(String effect, String via, boolean allowed) throws Exception {
        String conditional = """
                {"Effect":"%s","Action":"iam:ListUsers","Resource":"*",
                 "Condition":{"Bool":{"aws:ViaAWSService":"%s"}}}
                """.formatted(effect, via);
        String broad = """
                {"Effect":"Allow","Action":"iam:ListUsers","Resource":"*"}
                """;
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":["
                + ("Deny".equals(effect) ? broad + "," : "") + conditional + "]}";
        SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "via-service-", "context", policy);
        try (user; IamClient client = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))).build()) {
            ListUsersRequest request = ListUsersRequest.builder().overrideConfiguration(
                    override -> override.putHeader("X-Amz-Via-Aws-Service", "true")).build();
            if (allowed) {
                assertTrue(client.listUsers(request).users().stream().anyMatch(found -> user.name().equals(found.userName())));
            } else {
                IamException failure = assertThrows(IamException.class, () -> client.listUsers(request));
                assertEquals(403, failure.statusCode());
                assertEquals("AccessDenied", failure.awsErrorDetails().errorCode());
            }
        } finally {
            assertFalse(iam.isKnownAccessKey(user.accessKeyId()));
            assertEquals("NoSuchEntity", assertThrows(AwsException.class, () -> iam.getUser(user.name())).getErrorCode());
        }
    }
}
