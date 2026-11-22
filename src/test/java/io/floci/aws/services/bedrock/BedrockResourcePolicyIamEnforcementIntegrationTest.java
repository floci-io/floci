package io.floci.aws.services.bedrock;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
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
import software.amazon.awssdk.services.bedrock.model.Tag;

import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The resource policy and tagging actions are checked against the guardrail their ARN names, and a guardrail
 * created with tags needs the tagging action as well.
 */
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
            try (BedrockClient user = userWith(denyOn(denied.guardrailArn(), "bedrock:PutResourcePolicy",
                    "bedrock:GetResourcePolicy", "bedrock:DeleteResourcePolicy"))) {
                String allowedArn = allowed.guardrailArn();
                String allowedPolicy = policy(allowedArn);
                assertDoesNotThrow(() -> user.putResourcePolicy(r -> r.resourceArn(allowedArn)
                        .resourcePolicy(allowedPolicy)));
                assertEquals(allowedPolicy, user.getResourcePolicy(r -> r.resourceArn(allowedArn)).resourcePolicy());
                assertDoesNotThrow(() -> user.deleteResourcePolicy(r -> r.resourceArn(allowedArn)));

                String deniedArn = denied.guardrailArn();
                String deniedPolicy = policy(deniedArn);
                root.putResourcePolicy(r -> r.resourceArn(deniedArn).resourcePolicy(deniedPolicy));
                assertRefused(List.of(
                        () -> user.putResourcePolicy(r -> r.resourceArn(deniedArn).resourcePolicy(deniedPolicy)),
                        () -> user.getResourcePolicy(r -> r.resourceArn(deniedArn)),
                        () -> user.deleteResourcePolicy(r -> r.resourceArn(deniedArn))));
                assertEquals(deniedPolicy, root.getResourcePolicy(r -> r.resourceArn(deniedArn)).resourcePolicy());
            } finally {
                root.deleteGuardrail(r -> r.guardrailIdentifier(denied.guardrailId()));
                root.deleteGuardrail(r -> r.guardrailIdentifier(allowed.guardrailId()));
            }
        }
    }

    @Test
    void aDenyOnOneGuardrailRefusesTheTagActionsOnItAndNotOnAnother() {
        Tag tag = Tag.builder().key("team").value("search").build();
        try (BedrockClient root = AwsTestClients.create(BedrockClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            CreateGuardrailResponse denied = create(root);
            CreateGuardrailResponse allowed = create(root);
            try (BedrockClient user = userWith(denyOn(denied.guardrailArn(), "bedrock:TagResource",
                    "bedrock:UntagResource", "bedrock:ListTagsForResource"))) {
                String allowedArn = allowed.guardrailArn();
                assertDoesNotThrow(() -> user.tagResource(r -> r.resourceARN(allowedArn).tags(tag)));
                assertEquals(List.of(tag), user.listTagsForResource(r -> r.resourceARN(allowedArn)).tags());
                assertDoesNotThrow(() -> user.untagResource(r -> r.resourceARN(allowedArn).tagKeys("team")));

                String deniedArn = denied.guardrailArn();
                root.tagResource(r -> r.resourceARN(deniedArn).tags(tag));
                assertRefused(List.of(
                        () -> user.tagResource(r -> r.resourceARN(deniedArn).tags(tag)),
                        () -> user.listTagsForResource(r -> r.resourceARN(deniedArn)),
                        () -> user.untagResource(r -> r.resourceARN(deniedArn).tagKeys("team"))));
                assertEquals(List.of(tag), root.listTagsForResource(r -> r.resourceARN(deniedArn)).tags());
            } finally {
                root.deleteGuardrail(r -> r.guardrailIdentifier(denied.guardrailId()));
                root.deleteGuardrail(r -> r.guardrailIdentifier(allowed.guardrailId()));
            }
        }
    }

    @Test
    void aGuardrailCreatedWithTagsNeedsTheTaggingAction() {
        try (BedrockClient root = AwsTestClients.create(BedrockClient.builder(), ACCOUNT, Region.US_EAST_1);
             BedrockClient user = userWith(denyOn("*", "bedrock:TagResource"))) {
            CreateGuardrailResponse untagged = create(user);
            try {
                String name = "iam-tagged-" + UUID.randomUUID();
                assertRefused(List.of(() -> user.createGuardrail(r -> r.name(name)
                        .blockedInputMessaging("blocked").blockedOutputsMessaging("blocked")
                        .tags(Tag.builder().key("team").value("search").build()))));
                assertEquals(List.of(), root.listGuardrails(r -> { }).guardrails().stream()
                        .filter(guardrail -> name.equals(guardrail.name())).toList());
            } finally {
                root.deleteGuardrail(r -> r.guardrailIdentifier(untagged.guardrailId()));
            }
        }
    }

    /** A client for a new user allowed every Bedrock action, under the policy given. */
    private BedrockClient userWith(String policy) {
        String userName = "bedrock-iam-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, RestAssured.port, auth(ACCOUNT, "iam"), userName);
        EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "p", policy);
        return AwsTestClients.create(BedrockClient.builder(), credentials.accessKeyId(), Region.US_EAST_1);
    }

    /** A policy allowing every Bedrock action on every resource and denying the actions given on the one given. */
    private static String denyOn(String resource, String... actions) {
        String denied = String.join("\",\"", actions);
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"bedrock:*","Resource":"*"},
                  {"Effect":"Deny","Action":["%s"],"Resource":"%s"}]}""".formatted(denied, resource);
    }

    private static void assertRefused(List<Executable> calls) {
        for (Executable call : calls) {
            BedrockException error = assertThrows(BedrockException.class, call);
            assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
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
