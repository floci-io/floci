package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshiftserverless.RedshiftServerlessClient;
import software.amazon.awssdk.services.redshiftserverless.model.ResourceNotFoundException;
import software.amazon.awssdk.services.redshiftserverless.model.Snapshot;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RedshiftServerlessSnapshotExpirationIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    @InjectMock
    RedshiftServerlessRuntime runtime;

    @Inject
    RedshiftServerlessSnapshotExpirationScheduler scheduler;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void sdkObservesExpirationAndRecreationWithoutInheritingPolicy() {
        String namespace = "expiry-" + UUID.randomUUID().toString().substring(0, 8);
        try (RedshiftServerlessClient client = RedshiftServerlessClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test"))).build()) {
            client.createNamespace(request -> request.namespaceName(namespace).adminUsername("admin")
                    .adminUserPassword("Password123!"));
            Snapshot finite = client.createSnapshot(request -> request.namespaceName(namespace)
                    .snapshotName(namespace + "-finite").retentionPeriod(1)).snapshot();
            Snapshot indefinite = client.createSnapshot(request -> request.namespaceName(namespace)
                    .snapshotName(namespace + "-indefinite").retentionPeriod(-1)).snapshot();
            client.putResourcePolicy(request -> request.resourceArn(finite.snapshotArn()).policy("{}"));
            Instant deadline = finite.snapshotCreateTime().plusSeconds(86400);

            scheduler.tick(deadline.minusSeconds(1));
            assertEquals("AVAILABLE", client.getSnapshot(request -> request.snapshotName(finite.snapshotName()))
                    .snapshot().statusAsString());
            scheduler.tick(deadline.plusSeconds(1));
            assertThrows(ResourceNotFoundException.class,
                    () -> client.getSnapshot(request -> request.snapshotName(finite.snapshotName())));
            assertThrows(ResourceNotFoundException.class,
                    () -> client.getSnapshot(request -> request.snapshotArn(finite.snapshotArn())));
            assertThrows(ResourceNotFoundException.class,
                    () -> client.updateSnapshot(request -> request.snapshotName(finite.snapshotName()).retentionPeriod(7)));
            assertThrows(ResourceNotFoundException.class,
                    () -> client.getResourcePolicy(request -> request.resourceArn(finite.snapshotArn())));
            assertTrue(client.listSnapshots(request -> request.namespaceName(namespace)).snapshots().stream()
                    .noneMatch(snapshot -> finite.snapshotName().equals(snapshot.snapshotName())));
            assertEquals(indefinite.snapshotName(), client.getSnapshot(request -> request
                    .snapshotName(indefinite.snapshotName())).snapshot().snapshotName());

            client.createSnapshot(request -> request.namespaceName(namespace).snapshotName(finite.snapshotName()));
            assertThrows(ResourceNotFoundException.class,
                    () -> client.getResourcePolicy(request -> request.resourceArn(finite.snapshotArn())));
        }
    }
}
