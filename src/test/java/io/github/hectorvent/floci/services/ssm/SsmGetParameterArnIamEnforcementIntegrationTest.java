package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SsmGetParameterArnIamEnforcementIntegrationTest {

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void arnReadsStillRequirePermissionForTheRequestedParameter() {
        String account = "000000000000";
        String prefix = "/iam-read-arn-" + UUID.randomUUID();
        String allowed = prefix + "/allowed";
        String denied = prefix + "/denied";
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), account, Region.US_EAST_1)) {
            try {
                root.putParameter(request -> request.name(allowed).type("String").value("allowed-value"));
                root.putParameter(request -> request.name(denied).type("String").value("denied-value"));
                String allowedArn = root.getParameter(request -> request.name(allowed)).parameter().arn();
                String deniedArn = root.getParameter(request -> request.name(denied)).parameter().arn();
                String userName = "ssm-arn-" + UUID.randomUUID();
                EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                        cleanup, RestAssured.port, auth(account, "iam"), userName);
                EnforcementFixtures.putUserPolicy(RestAssured.port, auth(account, "iam"), userName, "read-one", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                          "Action":"ssm:GetParameter","Resource":"%s"}]}""".formatted(allowedArn));
                try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                        credentials.secretAccessKey(), Region.US_EAST_1)) {
                    assertEquals("allowed-value", assertDoesNotThrow(() -> user.getParameter(request -> request
                            .name(allowedArn))).parameter().value());
                    SsmException error = assertThrows(SsmException.class,
                            () -> user.getParameter(request -> request.name(deniedArn)));
                    assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
                }
            } finally {
                root.deleteParameter(request -> request.name(allowed));
                root.deleteParameter(request -> request.name(denied));
            }
        }
    }

}
