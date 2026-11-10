package io.floci.aws.services.ssm;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code GetParametersByPath} is checked against the parameter its {@code Path} names, and {@code GetParameters}
 * against each parameter its {@code Names} give.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SsmParameterReadIamEnforcementIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aGrantOnAPathAllowsGetParametersByPathOnItAndNoOtherPath() {
        String prefix = "/iam-path-read-" + UUID.randomUUID();
        String allowed = prefix + "/config";
        String other = prefix + "/secrets";
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                root.putParameter(r -> r.name(allowed + "/key").type(ParameterType.STRING).value("v"));
                root.putParameter(r -> r.name(other + "/key").type(ParameterType.STRING).value("v"));
                EnforcementFixtures.UserCredentials credentials = user("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"ssm:GetParametersByPath",
                          "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter%s"}]}""".formatted(allowed));
                try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                        Region.US_EAST_1)) {
                    GetParametersByPathResponse listed = user.getParametersByPath(r -> r.path(allowed));
                    assertEquals(1, listed.parameters().size());
                    assertEquals(allowed + "/key", listed.parameters().getFirst().name());

                    SsmException denied = assertThrows(SsmException.class,
                            () -> user.getParametersByPath(r -> r.path(other)));
                    assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
                }
            } finally {
                root.deleteParameter(r -> r.name(allowed + "/key"));
                root.deleteParameter(r -> r.name(other + "/key"));
            }
        }
    }

    @Test
    void getParametersNeedsAGrantOnEveryParameterItNames() {
        String prefix = "/iam-names-read-" + UUID.randomUUID();
        String allowed = prefix + "/a";
        String other = prefix + "/b";
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                root.putParameter(r -> r.name(allowed).type(ParameterType.STRING).value("v"));
                root.putParameter(r -> r.name(other).type(ParameterType.STRING).value("v"));
                EnforcementFixtures.UserCredentials credentials = user("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"ssm:GetParameters",
                          "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter%s"}]}""".formatted(allowed));
                try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                        Region.US_EAST_1)) {
                    GetParametersResponse read = user.getParameters(r -> r.names(allowed));
                    assertEquals(1, read.parameters().size());

                    SsmException denied = assertThrows(SsmException.class,
                            () -> user.getParameters(r -> r.names(allowed, other)));
                    assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
                }
            } finally {
                root.deleteParameter(r -> r.name(allowed));
                root.deleteParameter(r -> r.name(other));
            }
        }
    }

    private EnforcementFixtures.UserCredentials user(String policy) {
        String userName = "ssm-read-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, RestAssured.port, auth(ACCOUNT, "iam"), userName);
        EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "p", policy);
        return credentials;
    }
}
