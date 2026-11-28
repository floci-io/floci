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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.AccountEnforcedGuardrailInferenceInputConfiguration;
import software.amazon.awssdk.services.bedrock.model.AccountEnforcedGuardrailOutputConfiguration;
import software.amazon.awssdk.services.bedrock.model.BedrockException;
import software.amazon.awssdk.services.bedrock.model.CreateGuardrailResponse;
import software.amazon.awssdk.services.bedrock.model.ListEnforcedGuardrailsConfigurationRequest;
import software.amazon.awssdk.services.bedrock.model.ListEnforcedGuardrailsConfigurationResponse;
import software.amazon.awssdk.services.bedrock.model.PutEnforcedGuardrailConfigurationResponse;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class BedrockEnforcedGuardrailSdkIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void sdkRoundTripRecordsTheUserAndReplacesTheExistingConfiguration() {
        try (BedrockClient root = client(ACCOUNT, Region.US_EAST_1)) {
            CreateGuardrailResponse guardrail = create(root);
            String userName = "enforced-sdk-" + UUID.randomUUID();
            EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                    cleanup, RestAssured.port, auth(ACCOUNT, "iam"), userName);
            EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "p", """
                    {"Version":"2012-10-17","Statement":[
                      {"Effect":"Allow","Action":"bedrock:*","Resource":"*"}]}
                    """);
            try (BedrockClient user = client(credentials.accessKeyId(), Region.US_EAST_1)) {
                PutEnforcedGuardrailConfigurationResponse created = user.putEnforcedGuardrailConfiguration(r ->
                        r.guardrailInferenceConfig(config(guardrail.guardrailArn())));
                String id = created.configId();
                try {
                    String userArn = "arn:aws:iam::" + ACCOUNT + ":user/" + userName;
                    assertEquals(userArn, created.updatedBy());
                    assertNotNull(created.updatedAt());
                    AccountEnforcedGuardrailOutputConfiguration first = find(user, id);
                    assertEquals(userArn, first.createdBy());
                    assertEquals(userArn, first.updatedBy());
                    assertEquals(guardrail.guardrailArn(), first.guardrailArn());
                    assertEquals(guardrail.guardrailId(), first.guardrailId());
                    assertEquals("1", first.guardrailVersion());
                    assertEquals("ACCOUNT", first.ownerAsString());
                    assertNotNull(first.createdAt());
                    assertNotNull(first.updatedAt());
                    PutEnforcedGuardrailConfigurationResponse replaced = root.putEnforcedGuardrailConfiguration(r ->
                            r.configId(id).guardrailInferenceConfig(config(guardrail.guardrailId())));
                    assertEquals(id, replaced.configId());
                    AccountEnforcedGuardrailOutputConfiguration second = find(user, id);
                    assertEquals(first.createdAt(), second.createdAt());
                    assertEquals(userArn, second.createdBy());
                    assertEquals("arn:aws:iam::" + ACCOUNT + ":root", second.updatedBy());
                    user.deleteEnforcedGuardrailConfiguration(r -> r.configId(id));
                    assertTrue(user.listEnforcedGuardrailsConfiguration(ListEnforcedGuardrailsConfigurationRequest.builder().build()).guardrailsConfig().stream()
                            .noneMatch(c -> c.configId().equals(id)));
                    BedrockException missing = assertThrows(BedrockException.class,
                            () -> user.deleteEnforcedGuardrailConfiguration(r -> r.configId(id)));
                    assertEquals("ResourceNotFoundException", missing.awsErrorDetails().errorCode());
                } finally {
                    if (root.listEnforcedGuardrailsConfiguration(ListEnforcedGuardrailsConfigurationRequest.builder().build()).guardrailsConfig().stream()
                            .anyMatch(c -> c.configId().equals(id))) {
                        root.deleteEnforcedGuardrailConfiguration(r -> r.configId(id));
                    }
                }
            } finally {
                root.deleteGuardrail(r -> r.guardrailIdentifier(guardrail.guardrailId()));
            }
        }
    }

    @Test
    void sdkConfigurationsStayInTheirAccountAndRegion() {
        try (BedrockClient root = client(ACCOUNT, Region.US_EAST_1);
             BedrockClient otherAccount = client("111111111111", Region.US_EAST_1);
             BedrockClient otherRegion = client(ACCOUNT, Region.EU_WEST_1)) {
            CreateGuardrailResponse guardrail = create(root);
            String id = root.putEnforcedGuardrailConfiguration(r ->
                    r.guardrailInferenceConfig(config(guardrail.guardrailId()))).configId();
            try {
                for (BedrockClient other : new BedrockClient[]{otherAccount, otherRegion}) {
                    assertTrue(other.listEnforcedGuardrailsConfiguration(ListEnforcedGuardrailsConfigurationRequest.builder().build()).guardrailsConfig().isEmpty());
                    BedrockException missing = assertThrows(BedrockException.class,
                            () -> other.deleteEnforcedGuardrailConfiguration(r -> r.configId(id)));
                    assertEquals("ResourceNotFoundException", missing.awsErrorDetails().errorCode());
                }
                assertEquals(id, find(root, id).configId());
            } finally {
                root.deleteEnforcedGuardrailConfiguration(r -> r.configId(id));
                root.deleteGuardrail(r -> r.guardrailIdentifier(guardrail.guardrailId()));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PutEnforcedGuardrailConfiguration", "ListEnforcedGuardrailsConfiguration",
            "DeleteEnforcedGuardrailConfiguration"})
    void eachSdkActionHonorsItsExplicitDeny(String action) {
        try (BedrockClient root = client(ACCOUNT, Region.US_EAST_1)) {
            CreateGuardrailResponse guardrail = create(root);
            String id = root.putEnforcedGuardrailConfiguration(r ->
                    r.guardrailInferenceConfig(config(guardrail.guardrailId()))).configId();
            try {
                String userName = "enforced-deny-" + UUID.randomUUID();
                EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                        cleanup, RestAssured.port, auth(ACCOUNT, "iam"), userName);
                EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "p", """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"bedrock:*","Resource":"*"},
                          {"Effect":"Deny","Action":"bedrock:%s","Resource":"*"}]}
                        """.formatted(action));
                try (BedrockClient user = client(credentials.accessKeyId(), Region.US_EAST_1)) {
                    BedrockException denied = assertThrows(BedrockException.class, () -> {
                        switch (action) {
                            case "PutEnforcedGuardrailConfiguration" -> user.putEnforcedGuardrailConfiguration(r ->
                                    r.configId(id).guardrailInferenceConfig(config(guardrail.guardrailId())));
                            case "ListEnforcedGuardrailsConfiguration" -> user.listEnforcedGuardrailsConfiguration(ListEnforcedGuardrailsConfigurationRequest.builder().build());
                            case "DeleteEnforcedGuardrailConfiguration" -> user.deleteEnforcedGuardrailConfiguration(r ->
                                    r.configId(id));
                            default -> throw new IllegalArgumentException(action);
                        }
                    });
                    assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
                    assertEquals(id, find(root, id).configId());
                }
            } finally {
                root.deleteEnforcedGuardrailConfiguration(r -> r.configId(id));
                root.deleteGuardrail(r -> r.guardrailIdentifier(guardrail.guardrailId()));
            }
        }
    }

    @Test
    void sdkPaginationReturnsEveryConfigurationOnce() {
        try (BedrockClient root = client(ACCOUNT, Region.US_EAST_1)) {
            CreateGuardrailResponse guardrail = create(root);
            Set<String> expected = new HashSet<>();
            try {
                for (int i = 0; i < 3; i++) {
                    expected.add(root.putEnforcedGuardrailConfiguration(r ->
                            r.guardrailInferenceConfig(config(guardrail.guardrailId()))).configId());
                }
                Set<String> seen = new HashSet<>();
                String token = null;
                for (int i = 0; i < 3; i++) {
                    ListEnforcedGuardrailsConfigurationResponse page = root.listEnforcedGuardrailsConfiguration(
                            ListEnforcedGuardrailsConfigurationRequest.builder().nextToken(token).build());
                    assertEquals(1, page.guardrailsConfig().size());
                    assertTrue(seen.add(page.guardrailsConfig().getFirst().configId()));
                    token = page.nextToken();
                }
                assertNull(token);
                assertEquals(expected, seen);
            } finally {
                for (String id : expected) {
                    root.deleteEnforcedGuardrailConfiguration(r -> r.configId(id));
                }
                root.deleteGuardrail(r -> r.guardrailIdentifier(guardrail.guardrailId()));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void configuredGuardrailDeletionReturnsTheModeledErrorOverTheSdk(boolean wholeGuardrail) {
        try (BedrockClient root = client(ACCOUNT, Region.US_EAST_1)) {
            CreateGuardrailResponse guardrail = create(root);
            String id = root.putEnforcedGuardrailConfiguration(r ->
                    r.guardrailInferenceConfig(config(guardrail.guardrailArn()))).configId();
            try {
                BedrockException error = assertThrows(BedrockException.class,
                        () -> root.deleteGuardrail(r -> r.guardrailIdentifier(guardrail.guardrailArn())
                                .guardrailVersion(wholeGuardrail ? null : "1")));
                assertEquals("ValidationException", error.awsErrorDetails().errorCode());
                assertEquals(400, error.statusCode());
                assertEquals("The guardrail cannot be deleted because it is associated with an account-level "
                        + "or organization-level default guardrail configuration.", error.awsErrorDetails().errorMessage());
                assertEquals(guardrail.guardrailId(), root.getGuardrail(r ->
                        r.guardrailIdentifier(guardrail.guardrailId()).guardrailVersion("1")).guardrailId());
                assertEquals(guardrail.guardrailId(), root.getGuardrail(r ->
                        r.guardrailIdentifier(guardrail.guardrailId()).guardrailVersion("DRAFT")).guardrailId());
                assertEquals(id, find(root, id).configId());
            } finally {
                root.deleteEnforcedGuardrailConfiguration(r -> r.configId(id));
                root.deleteGuardrail(r -> r.guardrailIdentifier(guardrail.guardrailId()));
            }
        }
    }

    @Test
    void unknownConfigurationDeletionReturnsTheModeledMessageOverTheSdk() {
        try (BedrockClient root = client(ACCOUNT, Region.US_EAST_1)) {
            String missingId = UUID.randomUUID().toString().replace("-", "");
            BedrockException error = assertThrows(BedrockException.class,
                    () -> root.deleteEnforcedGuardrailConfiguration(r -> r.configId(missingId)));
            assertEquals("ResourceNotFoundException", error.awsErrorDetails().errorCode());
            assertEquals(404, error.statusCode());
            assertEquals("No default guardrail configuration found for the specified config identifier.",
                    error.awsErrorDetails().errorMessage());
        }
    }

    private static BedrockClient client(String accessKey, Region region) {
        return AwsTestClients.create(BedrockClient.builder(), accessKey, region);
    }

    private static CreateGuardrailResponse create(BedrockClient client) {
        CreateGuardrailResponse created = client.createGuardrail(r -> r.name("enforced-sdk-" + UUID.randomUUID())
                .blockedInputMessaging("blocked").blockedOutputsMessaging("blocked"));
        client.createGuardrailVersion(r -> r.guardrailIdentifier(created.guardrailId()));
        return created;
    }

    private static AccountEnforcedGuardrailInferenceInputConfiguration config(String identifier) {
        return AccountEnforcedGuardrailInferenceInputConfiguration.builder()
                .guardrailIdentifier(identifier).guardrailVersion("1").build();
    }

    private static AccountEnforcedGuardrailOutputConfiguration find(BedrockClient client, String id) {
        return client.listEnforcedGuardrailsConfiguration(ListEnforcedGuardrailsConfigurationRequest.builder().build()).guardrailsConfig().stream()
                .filter(c -> c.configId().equals(id)).findFirst().orElseThrow();
    }
}
