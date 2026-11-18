package io.floci.aws.testing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.ContainerNetworkReachability;
import io.floci.aws.services.ec2.Ec2ContainerManager;
import io.floci.aws.services.ec2.Ec2MetadataServer;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared setup for the EC2 lifecycle tests, which run a real {@link Ec2ContainerManager} (and
 * usually a real {@code Ec2Service}) over real persistent storage with only Docker mocked, and judge
 * the outcome by the records on disk.
 */
public final class Ec2LifecycleFixtures {

    public static final String ACCOUNT = "000000000000";
    public static final String REGION = "us-east-1";
    private static final long AWAIT_SECONDS = 10;

    private static final ObjectMapper JSON = new ObjectMapper();

    private Ec2LifecycleFixtures() {
    }

    /** Persistent storage under {@code dir} for {@link #ACCOUNT}, with EC2 in mock or container mode. */
    public static EmulatorConfig config(Path dir, boolean mock, boolean enabled) {
        EmulatorConfig config = StorageFactories.config(dir, ACCOUNT);
        when(config.services().ec2().mock()).thenReturn(mock);
        when(config.services().ec2().enabled()).thenReturn(enabled);
        return config;
    }

    /** A manager whose collaborators are mocks, except the three a test cares about. */
    public static Ec2ContainerManager manager(EmulatorConfig config, DockerClient docker,
                                              ContainerLifecycleManager lifecycle,
                                              Ec2PortForwardManager portForwards) {
        return new Ec2ContainerManager(
                mock(ContainerBuilder.class), lifecycle, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), mock(DockerHostResolver.class), docker,
                mock(PortAllocator.class), config, mock(Ec2MetadataServer.class), portForwards,
                mock(RegionResolver.class), mock(ContainerNetworkReachability.class),
                mock(VpcNetworkManager.class), mock(ContainerReachableEndpoint.class));
    }

    public static Instance instance(String id, InstanceState state, String containerId) {
        Instance instance = new Instance();
        instance.setInstanceId(id);
        instance.setRegion(REGION);
        instance.setState(state);
        instance.setDockerContainerId(containerId);
        return instance;
    }

    /** Writes {@code instances} to the EC2 instance store of {@code account}, then closes the store. */
    public static void seed(EmulatorConfig config, String account, Instance... instances) {
        StorageFactory factory = StorageFactories.open(config, "persistent");
        try {
            AccountAwareStorageBackend<Instance> store = factory.create(
                    "ec2", "ec2-instances.json", new TypeReference<Map<String, Instance>>() { });
            for (Instance instance : instances) {
                store.putForAccount(account, REGION + "::" + instance.getInstanceId(), instance);
            }
        } finally {
            factory.shutdownAll();
        }
    }

    /** The stored record whose key ends with {@code id}, read straight from the JSON file, or null. */
    public static JsonNode persistedRecord(Path dir, String file, String id) throws IOException {
        JsonNode records = JSON.readTree(Files.readString(dir.resolve(file)));
        for (Map.Entry<String, JsonNode> entry : records.properties()) {
            if (entry.getKey().endsWith("::" + id)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** The state name stored for instance {@code id}, or null when no such record is stored. */
    public static String persistedState(Path dir, String id) throws IOException {
        JsonNode record = persistedRecord(dir, "ec2-instances.json", id);
        return record == null ? null : record.path("state").path("name").asText();
    }

    /** The {@code terminatedAt} stored for instance {@code id}, or -1 when no such record is stored. */
    public static long persistedTerminatedAt(Path dir, String id) throws IOException {
        JsonNode record = persistedRecord(dir, "ec2-instances.json", id);
        return record == null ? -1 : record.path("terminatedAt").asLong();
    }

    /** Polls until {@code condition} holds, failing with {@code description} after ten seconds. */
    public static void await(BooleanSupplier condition, String description) throws InterruptedException {
        await(condition, () -> description);
    }

    public static void await(BooleanSupplier condition, Supplier<String> description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Timed out waiting for " + description.get());
    }
}
