package io.floci.aws.services.iam;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SecretsManagerMissingNameIntegrationTest {
    private static final String ACCOUNT = "111111111111";
    private static final String PREFIX = "arn:aws:secretsmanager:us-east-1:" + ACCOUNT + ":secret:";

    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void scopedSessionCanInspectAnAbsentNameWithoutGainingOtherSecrets() {
        String name = "missing-name-" + UUID.randomUUID();
        String rolePolicy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"secretsmanager:DescribeSecret","Resource":"%s%s*-??????"},
                  {"Effect":"Deny","Action":"secretsmanager:DescribeSecret","Resource":"%s%s-denied-??????"}]}
                """.formatted(PREFIX, name, PREFIX, name);
        String role = EnforcementFixtures.createRole(cleanup, endpoint.getPort(), ACCOUNT, "/", name, rolePolicy);
        String sessionPolicy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"secretsmanager:DescribeSecret","Resource":["%s%s-??????","%s%s-denied-??????"]}]}
                """.formatted(PREFIX, name, PREFIX, name);
        try (StsClient sts = AwsTestClients.create(StsClient.builder(), ACCOUNT, Region.US_EAST_1, endpoint);
             SecretsManagerClient admin = AwsTestClients.create(SecretsManagerClient.builder(), ACCOUNT,
                     Region.US_EAST_1, endpoint)) {
            Credentials temporary = sts.assumeRole(r -> r.roleArn(role).roleSessionName("existence-check")
                    .policy(sessionPolicy)).credentials();
            AwsSessionCredentials credentials = AwsSessionCredentials.create(temporary.accessKeyId(),
                    temporary.secretAccessKey(), temporary.sessionToken());
            try (SecretsManagerClient client = AwsTestClients.create(SecretsManagerClient.builder(), credentials,
                    Region.US_EAST_1, endpoint)) {
                assertThrows(ResourceNotFoundException.class, () -> client.describeSecret(r -> r.secretId(name)));
                denied(() -> client.describeSecret(r -> r.secretId(name + "-sibling")));
                denied(() -> client.describeSecret(r -> r.secretId(name + "-denied")));
                denied(() -> client.describeSecret(r -> r.secretId("unrelated-" + name)));
                // An absent full ARN is kept exact; it cannot borrow a grant for a different suffix.
                denied(() -> client.describeSecret(r -> r.secretId(PREFIX + name + "-extra-AbC123")));
                String arn = admin.createSecret(r -> r.name(name).secretString("fixture")).arn();
                try {
                    assertEquals(arn, client.describeSecret(r -> r.secretId(name)).arn());
                    assertEquals(arn, client.describeSecret(r -> r.secretId(arn)).arn());
                } finally {
                    admin.deleteSecret(r -> r.secretId(arn).forceDeleteWithoutRecovery(true));
                }
                assertThrows(ResourceNotFoundException.class, () -> client.describeSecret(r -> r.secretId(name)));
            }
        }
    }

    private static void denied(Runnable operation) {
        SecretsManagerException error = assertThrows(SecretsManagerException.class, operation::run);
        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
    }
}
