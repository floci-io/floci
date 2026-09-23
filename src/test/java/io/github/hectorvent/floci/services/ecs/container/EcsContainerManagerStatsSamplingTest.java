package io.github.hectorvent.floci.services.ecs.container;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.model.Statistics;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the task metadata endpoint's {@code /stats} paths sample the daemon. Docker only sends its
 * second stats sample on the next collection tick, so a task's containers have to be sampled
 * alongside each other: {@code /task/stats} is the path a sidecar polls, and sampling one
 * container after another would cost it a tick per container.
 *
 * <p>The daemon is mocked. A stream sends its first sample as soon as it is opened and holds its
 * second one back until every stream the pass opens is open, which is what a serial implementation
 * could never satisfy.
 */
class EcsContainerManagerStatsSamplingTest {

    private static final String FIRST_READ = "2026-01-01T10:05:00.000000000Z";
    private static final String SECOND_READ = "2026-01-01T10:05:01.000000000Z";

    private final Map<String, StatsCmd> commands = new ConcurrentHashMap<>();
    private EcsContainerManager manager;

    /**
     * Trips once every stream a pass is expected to open has been opened. Only the pass that is
     * about the ordering raises it: the other tests leave it open so their samples flow at once.
     */
    private CountDownLatch allStreamsOpen;

    @BeforeEach
    void setUp() {
        allStreamsOpen = new CountDownLatch(0);

        DockerClient dockerClient = mock(DockerClient.class);
        when(dockerClient.statsCmd(anyString())).thenAnswer(invocation ->
                commands.computeIfAbsent(invocation.getArgument(0), id -> tickingStatsCmd()));

        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

        manager = new EcsContainerManager(mock(ContainerBuilder.class), lifecycleManager,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), mock(RegionResolver.class),
                mock(LaunchedContainerAwsEnv.class), mock(SsmService.class),
                mock(SecretsManagerService.class), mock(S3Service.class),
                mock(EcrRegistryManager.class), mock(HostVolumePolicy.class));
    }

    @Test
    @Timeout(10)
    void aTasksContainersAreSampledAlongsideEachOtherRatherThanOneAfterAnother() {
        List<String> dockerIds = List.of("docker-1", "docker-2", "docker-3", "docker-4");
        // No stream sends its second sample until all four are open, so a pass that waits on one
        // stream before opening the next cannot collect a pair for any of them.
        allStreamsOpen = new CountDownLatch(dockerIds.size());

        Map<String, EcsContainerManager.ContainerStats> sampled = manager.sampleContainerStats(dockerIds);

        assertEquals(dockerIds.size(), sampled.size(), "every container in the task reports a sample");
        for (String dockerId : dockerIds) {
            EcsContainerManager.ContainerStats stats = sampled.get(dockerId);
            assertEquals(SECOND_READ, stats.statistics().getRead(), "the later sample is the one reported");
            assertNotNull(stats.rxBytesPerSecond(), "a pair arrived for " + dockerId + ", so it has rates");
            assertEquals(100.0, stats.rxBytesPerSecond(), 0.0001, "the rate is the delta across the pair");
            assertEquals(200.0, stats.txBytesPerSecond(), 0.0001);
        }
    }

    @Test
    void everyStreamIsClosedOnceThePassIsDone() throws IOException {
        manager.sampleContainerStats(List.of("docker-1", "docker-2"));

        for (StatsCmd command : commands.values()) {
            verify(command).close();
        }
    }

    @Test
    void aContainerTheDaemonRefusesIsLeftOutWithoutFailingTheRest() {
        commands.put("docker-gone", failingStatsCmd());

        Map<String, EcsContainerManager.ContainerStats> sampled =
                manager.sampleContainerStats(List.of("docker-gone", "docker-1"));

        assertFalse(sampled.containsKey("docker-gone"), "a container with no stream reports no sample");
        assertTrue(sampled.containsKey("docker-1"), "the containers alongside it are still sampled");
    }

    @Test
    void oneContainerIsSampledThroughTheSamePass() {
        Optional<EcsContainerManager.ContainerStats> stats = manager.sampleContainerStats("docker-1");

        assertTrue(stats.isPresent());
        assertEquals(SECOND_READ, stats.get().statistics().getRead());
        assertTrue(manager.sampleContainerStats((String) null).isEmpty(), "no id means no sample");
    }

    /**
     * A stats stream that sends its first sample as soon as it is opened and its second one only
     * once every stream of the pass is open, standing in for Docker's collection tick.
     */
    private StatsCmd tickingStatsCmd() {
        StatsCmd command = mock(StatsCmd.class);
        when(command.exec(any())).thenAnswer(invocation -> {
            ResultCallback<Statistics> callback = invocation.getArgument(0);
            allStreamsOpen.countDown();
            Thread emitter = new Thread(() -> {
                try {
                    callback.onNext(sample(FIRST_READ, 100));
                    if (allStreamsOpen.await(5, TimeUnit.SECONDS)) {
                        callback.onNext(sample(SECOND_READ, 200));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("the fixed stats sample must parse", e);
                }
            });
            emitter.setDaemon(true);
            emitter.start();
            return callback;
        });
        return command;
    }

    /** A container the daemon has already forgotten: opening its stream throws. */
    private static StatsCmd failingStatsCmd() {
        StatsCmd command = mock(StatsCmd.class);
        when(command.exec(any())).thenThrow(new IllegalStateException("no such container"));
        return command;
    }

    private static Statistics sample(String read, long rxBytes) throws JsonProcessingException {
        String json = """
                {
                  "read": "%s",
                  "cpu_stats": {"cpu_usage": {"total_usage": 42000000}},
                  "memory_stats": {"usage": 1048576},
                  "networks": {"eth0": {"rx_bytes": %d, "tx_bytes": %d}}
                }
                """.formatted(read, rxBytes, rxBytes * 2);
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build()
                .readValue(json, Statistics.class);
    }
}
