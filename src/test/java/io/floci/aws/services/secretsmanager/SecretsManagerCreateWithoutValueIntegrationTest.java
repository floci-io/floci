package io.floci.aws.services.secretsmanager;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretResponse;
import software.amazon.awssdk.services.secretsmanager.model.DescribeSecretResponse;
import software.amazon.awssdk.services.secretsmanager.model.FilterNameStringType;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import software.amazon.awssdk.services.secretsmanager.model.SecretListEntry;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class SecretsManagerCreateWithoutValueIntegrationTest {

    private static final String ACCOUNT = "725018802024";

    private SecretsManagerClient client;
    private String name;

    @BeforeEach
    void createClient() {
        client = AwsTestClients.create(SecretsManagerClient.builder(), ACCOUNT, Region.US_EAST_1);
        name = "no-value-" + UUID.randomUUID();
    }

    @AfterEach
    void deleteSecretAndCloseClient() {
        try {
            client.deleteSecret(request -> request.secretId(name).forceDeleteWithoutRecovery(true));
        } finally {
            client.close();
        }
    }

    @Test
    void secretCreatedWithoutAValueHasNoVersion() {
        CreateSecretResponse created = client.createSecret(request -> request.name(name));
        DescribeSecretResponse described = client.describeSecret(request -> request.secretId(name));
        SecretListEntry listed = client.listSecrets(request -> request.filters(filter -> filter
                .key(FilterNameStringType.NAME).values(name))).secretList().get(0);

        assertNull(created.versionId());
        assertFalse(described.hasVersionIdsToStages());
        assertFalse(listed.hasSecretVersionsToStages());
    }

    @Test
    void secretCreatedWithoutAValueHasNoCurrentValue() {
        client.createSecret(request -> request.name(name));

        ResourceNotFoundException error = assertThrows(ResourceNotFoundException.class,
                () -> client.getSecretValue(request -> request.secretId(name)));
        assertEquals("Secrets Manager can't find the specified secret value for staging label: AWSCURRENT",
                error.awsErrorDetails().errorMessage());
    }

    @Test
    void firstValuePutOnASecretCreatedWithoutOneIsItsOnlyVersion() {
        client.createSecret(request -> request.name(name));
        PutSecretValueResponse put = client.putSecretValue(request -> request.secretId(name).secretString("first"));
        DescribeSecretResponse described = client.describeSecret(request -> request.secretId(name));

        assertEquals("first", client.getSecretValue(request -> request.secretId(name)).secretString());
        assertEquals(Map.of(put.versionId(), List.of("AWSCURRENT")), described.versionIdsToStages());
    }
}
