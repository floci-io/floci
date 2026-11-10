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
import software.amazon.awssdk.services.ssm.model.DeleteParametersResponse;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code DeleteParameters} is checked against each parameter its {@code Names} give, before any of them is
 * deleted.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SsmDeleteParametersIamEnforcementIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void grantsOnEveryNamedParameterAllowTheBatchDeletion() {
        String prefix = "/iam-names-delete-" + UUID.randomUUID();
        String first = prefix + "/a";
        String second = prefix + "/b";
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                root.putParameter(r -> r.name(first).type(ParameterType.STRING).value("v"));
                root.putParameter(r -> r.name(second).type(ParameterType.STRING).value("v"));
                EnforcementFixtures.UserCredentials credentials = user("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"ssm:DeleteParameters",
                          "Resource":["arn:aws:ssm:us-east-1:000000000000:parameter%s",
                                      "arn:aws:ssm:us-east-1:000000000000:parameter%s"]}]}"""
                        .formatted(first, second));
                try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                        Region.US_EAST_1)) {
                    DeleteParametersResponse deleted = user.deleteParameters(r -> r.names(first, second));
                    assertEquals(List.of(first, second), deleted.deletedParameters());
                }
                assertEquals(List.of(first, second),
                        root.getParameters(r -> r.names(first, second)).invalidParameters());
            } finally {
                root.deleteParameters(r -> r.names(first, second));
            }
        }
    }

    @Test
    void aDenyOnOneNamedParameterRefusesTheBatchAndDeletesNeither() {
        String prefix = "/iam-names-delete-" + UUID.randomUUID();
        String allowed = prefix + "/allowed";
        String denied = prefix + "/denied";
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                root.putParameter(r -> r.name(allowed).type(ParameterType.STRING).value("v"));
                root.putParameter(r -> r.name(denied).type(ParameterType.STRING).value("v"));
                EnforcementFixtures.UserCredentials credentials = user("""
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"ssm:DeleteParameters",
                           "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter/*"},
                          {"Effect":"Deny","Action":"ssm:DeleteParameters",
                           "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter%s"}]}""".formatted(denied));
                try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                        Region.US_EAST_1)) {
                    SsmException refused = assertThrows(SsmException.class,
                            () -> user.deleteParameters(r -> r.names(allowed, denied)));
                    assertEquals("AccessDeniedException", refused.awsErrorDetails().errorCode());
                }
                assertEquals(2, root.getParameters(r -> r.names(allowed, denied)).parameters().size());
            } finally {
                root.deleteParameters(r -> r.names(allowed, denied));
            }
        }
    }

    private EnforcementFixtures.UserCredentials user(String policy) {
        String userName = "ssm-delete-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, RestAssured.port, auth(ACCOUNT, "iam"), userName);
        EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "p", policy);
        return credentials;
    }
}
