package io.floci.aws.services.secretsmanager;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.InvalidParameterException;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SecretsManagerVersionStageIntegrationTest {

    private SecretsManagerClient client;
    private String name;
    private String previous;
    private String current;
    private String pending;

    @BeforeEach
    void createSecretVersions() {
        client = AwsTestClients.create(SecretsManagerClient.builder(), "test", Region.US_EAST_1);
        name = "stage-validation-" + UUID.randomUUID();
        previous = client.createSecret(request -> request.name(name).secretString("previous")).versionId();
        current = client.putSecretValue(request -> request.secretId(name).secretString("current")).versionId();
        pending = client.putSecretValue(request -> request.secretId(name).secretString("pending")
                .versionStages("AWSPENDING")).versionId();
        client.updateSecretVersionStage(request -> request.secretId(name)
                .versionStage("REVIEW").moveToVersionId(current));
    }

    @AfterEach
    void deleteSecretAndCloseClient() {
        try {
            client.deleteSecret(request -> request.secretId(name).forceDeleteWithoutRecovery(true));
        } finally {
            client.close();
        }
    }

    @ParameterizedTest
    @CsvSource({"AWSCURRENT,true", "AWSCURRENT,false", "REVIEW,true", "REVIEW,false"})
    void invalidRemovalTakesPrecedenceOverMissingDestination(String stage, boolean omitRemovalId) {
        Map<String, List<String>> before = client.describeSecret(request -> request.secretId(name))
                .versionIdsToStages();
        String removal = omitRemovalId ? null : pending;
        String missing = UUID.randomUUID().toString();

        InvalidParameterException refused = assertThrows(InvalidParameterException.class,
                () -> client.updateSecretVersionStage(request -> request.secretId(name).versionStage(stage)
                        .removeFromVersionId(removal).moveToVersionId(missing)));

        assertEquals(400, refused.statusCode());
        assertEquals("InvalidParameterException", refused.awsErrorDetails().errorCode());
        String expectedMessage = omitRemovalId
                ? ("The parameter RemoveFromVersionId can't be empty. Staging label %s is currently attached to "
                    + "version %s, so you must explicitly reference that version in RemoveFromVersionId.")
                    .formatted(stage, current)
                : ("When you move staging label %s, if you specify RemoveFromVersionId, it must be set to the "
                    + "version that currently has the staging label %s.").formatted(stage, current);
        assertEquals(expectedMessage, refused.awsErrorDetails().errorMessage());
        assertEquals(before, client.describeSecret(request -> request.secretId(name)).versionIdsToStages());
        assertEquals(current, client.getSecretValue(request -> request.secretId(name)).versionId());
        assertEquals("current", client.getSecretValue(request -> request.secretId(name).versionStage(stage))
                .secretString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AWSCURRENT", "REVIEW"})
    void missingDestinationPreservesAllStagesAndAllowsALaterMove(String stage) {
        Map<String, List<String>> before = client.describeSecret(request -> request.secretId(name))
                .versionIdsToStages();
        assertTrue(before.get(previous).contains("AWSPREVIOUS"));
        assertTrue(before.get(current).contains(stage));
        assertEquals(List.of("AWSPENDING"), before.get(pending));
        String missing = UUID.randomUUID().toString();

        ResourceNotFoundException refused = assertThrows(ResourceNotFoundException.class,
                () -> client.updateSecretVersionStage(request -> request.secretId(name).versionStage(stage)
                        .removeFromVersionId(current).moveToVersionId(missing)));

        assertEquals(400, refused.statusCode());
        assertEquals("ResourceNotFoundException", refused.awsErrorDetails().errorCode());
        assertEquals("Secrets Manager can't find the specified secret value for VersionId: " + missing + ".",
                refused.awsErrorDetails().errorMessage());
        assertEquals(before, client.describeSecret(request -> request.secretId(name)).versionIdsToStages());
        assertEquals(current, client.getSecretValue(request -> request.secretId(name)).versionId());
        assertEquals("current", client.getSecretValue(request -> request.secretId(name).versionStage(stage))
                .secretString());

        client.updateSecretVersionStage(request -> request.secretId(name).versionStage(stage)
                .removeFromVersionId(current).moveToVersionId(pending));

        Map<String, List<String>> after = client.describeSecret(request -> request.secretId(name))
                .versionIdsToStages();
        assertFalse(after.get(current).contains(stage));
        assertTrue(after.get(pending).contains(stage));
        assertEquals("pending", client.getSecretValue(request -> request.secretId(name).versionStage(stage))
                .secretString());
        if ("AWSCURRENT".equals(stage)) {
            assertEquals(pending, client.getSecretValue(request -> request.secretId(name)).versionId());
            assertTrue(after.get(current).contains("AWSPREVIOUS"));
            assertFalse(after.containsKey(previous));
        } else {
            assertEquals(current, client.getSecretValue(request -> request.secretId(name)).versionId());
            assertTrue(after.get(previous).contains("AWSPREVIOUS"));
        }
    }
}
