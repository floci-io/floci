package io.github.hectorvent.floci.services.bedrock;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.BedrockException;
import software.amazon.awssdk.services.bedrock.model.CreateGuardrailResponse;

import java.util.List;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The resource policy actions are checked against the guardrail their {@code resourceArn} names. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class BedrockResourcePolicyIamEnforcementIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void aDenyOnOneGuardrailRefusesThePolicyActionsOnItAndNotOnAnother() {
        try (BedrockClient root = AwsTestClients.create(BedrockClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            CreateGuardrailResponse denied = create(root);
            CreateGuardrailResponse allowed = create(root);
            try {
                String userName = "bedrock-policy-" + UUID.randomUUID();
                EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                        cleanup, RestAssured.port, auth(ACCOUNT, "iam"), userName);
                EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "p", """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"bedrock:*","Resource":"*"},
                          {"Effect":"Deny","Action":["bedrock:PutResourcePolicy","bedrock:GetResourcePolicy",
                            "bedrock:DeleteResourcePolicy"],"Resource":"%s"}]}"""
                        .formatted(denied.guardrailArn()));
                try (BedrockClient user = AwsTestClients.create(BedrockClient.builder(), credentials.accessKeyId(),
                        Region.US_EAST_1)) {
                    String allowedArn = allowed.guardrailArn();
                    String allowedPolicy = policy(allowedArn);
                    assertDoesNotThrow(() -> user.putResourcePolicy(r -> r.resourceArn(allowedArn)
                            .resourcePolicy(allowedPolicy)));
                    assertEquals(allowedPolicy, user.getResourcePolicy(r -> r.resourceArn(allowedArn)).resourcePolicy());
                    assertDoesNotThrow(() -> user.deleteResourcePolicy(r -> r.resourceArn(allowedArn)));

                    String deniedArn = denied.guardrailArn();
                    String deniedPolicy = policy(deniedArn);
                    root.putResourcePolicy(r -> r.resourceArn(deniedArn).resourcePolicy(deniedPolicy));
                    List<Executable> refused = List.of(
                            () -> user.putResourcePolicy(r -> r.resourceArn(deniedArn).resourcePolicy(deniedPolicy)),
                            () -> user.getResourcePolicy(r -> r.resourceArn(deniedArn)),
                            () -> user.deleteResourcePolicy(r -> r.resourceArn(deniedArn)));
                    for (Executable call : refused) {
                        BedrockException error = assertThrows(BedrockException.class, call);
                        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
                    }
                    assertEquals(deniedPolicy, root.getResourcePolicy(r -> r.resourceArn(deniedArn)).resourcePolicy());
                }
            } finally {
                root.deleteGuardrail(r -> r.guardrailIdentifier(denied.guardrailId()));
                root.deleteGuardrail(r -> r.guardrailIdentifier(allowed.guardrailId()));
            }
        }
    }

    private static CreateGuardrailResponse create(BedrockClient client) {
        return client.createGuardrail(r -> r.name("iam-rp-" + UUID.randomUUID())
                .blockedInputMessaging("blocked").blockedOutputsMessaging("blocked"));
    }

    private static String policy(String guardrailArn) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%s:root"},\
                "Action":"bedrock:ApplyGuardrail","Resource":"%s"}]}""".formatted(ACCOUNT, guardrailArn);
    }
}
