package io.github.hectorvent.floci.services.lambdamicrovms;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MicroVM state survives a restart. Each {@link #process()} call is a separate Floci process over
 * the same storage directory, so a later one reads what an earlier one wrote back rather than
 * sharing its objects.
 *
 * <p>CloudFormation stacks persist across restarts, so state held only in memory left a restarted
 * Floci with stacks pointing at images and connectors that no longer existed, and DeleteStack then
 * failed on the 404.
 */
class LambdaMicrovmsServicePersistenceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";

    @TempDir
    Path storageDir;

    /** A fresh process reading the storage directory from disk. */
    private LambdaMicrovmsService process() {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        LambdaMicrovmsService service =
                new LambdaMicrovmsService(regionResolver, new FileStorageFactory(storageDir));
        service.initializeStorage();
        return service;
    }

    private LambdaMicrovmsService.MicrovmImage createImage(LambdaMicrovmsService service, String name) {
        return service.createImage(REGION, ACCOUNT_ID, name,
                "arn:aws:lambda:us-east-1::microvm-image/al2023-1", "arn:aws:iam::000000000000:role/build",
                "s3://bucket/artifact.zip", "an image");
    }

    @Test
    void imagesAndTheirVersionsSurviveARestart() {
        createImage(process(), "probe-image");

        LambdaMicrovmsService reloaded = process();
        LambdaMicrovmsService.MicrovmImage image = reloaded.getImage(REGION, "probe-image");
        assertEquals("CREATED", image.state);
        assertEquals("1.0", image.latestActiveImageVersion);
        assertEquals(1, image.versions.size());
        assertEquals(2, image.versions.get(0).builds.size(), "both Graviton builds must round-trip");
        assertNotNull(image.createdAt, "Instant fields must round-trip");
        assertEquals(1, reloaded.listImages(REGION).size());
    }

    @Test
    void anInPlaceUpdateReachesTheBackend() {
        // updateImage mutates the stored image rather than replacing it, so without an explicit
        // write-back the change never reaches the backend and is lost on restart.
        LambdaMicrovmsService first = process();
        createImage(first, "probe-image");
        first.updateImage(REGION, "probe-image", "arn:aws:lambda:us-east-1::microvm-image/al2023-1",
                "arn:aws:iam::000000000000:role/other", "s3://bucket/next.zip", "updated");

        LambdaMicrovmsService.MicrovmImage image = process().getImage(REGION, "probe-image");
        assertEquals("UPDATED", image.state);
        assertEquals("updated", image.description);
        assertEquals("s3://bucket/next.zip", image.codeArtifactUri);
        assertEquals("2.0", image.latestActiveImageVersion);
    }

    @Test
    void tagMutationsReachTheBackend() {
        LambdaMicrovmsService first = process();
        String arn = createImage(first, "probe-image").imageArn;
        first.tagResource(REGION, arn, Map.of("owner", "platform", "tier", "gold"));
        first.untagResource(REGION, arn, List.of("tier"));

        assertEquals(Map.of("owner", "platform"), process().getImage(REGION, "probe-image").tags);
    }

    @Test
    void versionStatusChangesReachTheBackend() {
        LambdaMicrovmsService first = process();
        createImage(first, "probe-image");
        first.updateVersionStatus(REGION, "probe-image", "1.0", "INACTIVE");

        assertEquals("INACTIVE", process().getVersion(REGION, "probe-image", "1.0").status);
    }

    @Test
    void microvmsAndConnectorsSurviveARestart() {
        LambdaMicrovmsService first = process();
        String imageArn = createImage(first, "probe-image").imageArn;
        String vmId = first.runMicrovm(REGION, ACCOUNT_ID, imageArn).microvmId;
        String connectorId = first.createConnector(REGION, ACCOUNT_ID, "probe-connector",
                List.of("subnet-1"), List.of("sg-1"), "arn:aws:iam::000000000000:role/operator",
                "token-1", List.of("LAMBDA"), "VPC_EGRESS").id;

        LambdaMicrovmsService reloaded = process();
        assertEquals("RUNNING", reloaded.getMicrovm(REGION, vmId).state,
                "the instant settle must be written back, not applied to a detached copy");
        assertEquals("ACTIVE", reloaded.getConnector(REGION, connectorId).state);
        assertEquals(List.of("subnet-1"), reloaded.getConnector(REGION, connectorId).subnetIds);

        reloaded.terminateMicrovm(REGION, vmId);
        assertEquals("TERMINATED", process().getMicrovm(REGION, vmId).state);
    }

    @Test
    void deletesSurviveARestart() {
        LambdaMicrovmsService first = process();
        createImage(first, "probe-image");
        first.deleteImage(REGION, "probe-image");

        LambdaMicrovmsService reloaded = process();
        assertThrows(RuntimeException.class, () -> reloaded.getImage(REGION, "probe-image"));
        assertTrue(reloaded.listImages(REGION).isEmpty());
    }

    @Test
    void oneRegionDoesNotSeeAnother() {
        LambdaMicrovmsService service = process();
        createImage(service, "probe-image");
        service.createImage("eu-west-1", ACCOUNT_ID, "other-image",
                "arn:aws:lambda:eu-west-1::microvm-image/al2023-1",
                "arn:aws:iam::000000000000:role/build", "s3://bucket/artifact.zip", null);

        LambdaMicrovmsService reloaded = process();
        assertEquals(List.of("probe-image"),
                reloaded.listImages(REGION).stream().map(i -> i.name).toList());
        assertEquals(List.of("other-image"),
                reloaded.listImages("eu-west-1").stream().map(i -> i.name).toList());
    }

    /**
     * Backed by real files rather than {@code InMemoryStorage}, because an in-memory store hands
     * back the same object reference, so a restart would observe an in-place mutation that never
     * reached the backend. Only a JSON round trip separates state that was written from state that
     * was merely mutated.
     */
    private static final class FileStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();
        private final Path directory;

        private FileStorageFactory(Path directory) {
            super(null, null);
            this.directory = directory;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            // Wrap like the production factory does so the tests exercise the
            // account-prefixed key space, not a bare backend.
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName, ignored -> {
                PersistentStorage<String, V> inner =
                        new PersistentStorage<>(directory.resolve(fileName), typeReference);
                inner.load();
                return new AccountAwareStorageBackend<V>(inner, null, ACCOUNT_ID);
            });
        }
    }
}
