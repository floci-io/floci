package io.floci.aws.services.sagemaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.EndpointResource;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.ModelResource;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.TrainingJobResource;
import io.floci.aws.testing.DockerArchiveUploadFixture;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SageMakerArchiveRetryTest {
    private final DockerClient docker = mock(DockerClient.class);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final S3Service s3 = mock(S3Service.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(docker, "container");
    private final byte[] payload = "model-data".repeat(2000).getBytes(StandardCharsets.UTF_8);
    private SageMakerEndpointManager manager;
    private SageMakerTrainingRunner runner;
    private TrainingJobResource job;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        when(config.services().sagemaker().dockerNetwork()).thenReturn(Optional.empty());
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.docker().logMaxSize()).thenReturn("10m");
        when(config.docker().logMaxFile()).thenReturn("3");
        ContainerBuilder builder = new ContainerBuilder(config,
                mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class));
        manager = new SageMakerEndpointManager(builder, lifecycle, config, mock(ContainerDetector.class), s3);
        runner = new SageMakerTrainingRunner(builder, lifecycle, mock(ContainerLogStreamer.class), config,
                mock(ContainerDetector.class), s3, mapper, mock(SageMakerGpuResolver.class));
        job = new TrainingJobResource();
        job.trainingJobName = "retry-training";
        job.region = "us-east-1";
        job.algorithmSpecification = Map.of("TrainingImage", "busybox:stable");
        job.hyperParameters = Map.of("epochs", "3");
        job.inputDataConfig = List.of(Map.of("ChannelName", "train", "DataSource",
                Map.of("S3DataSource", Map.of("S3Uri", "s3://bucket/input/"))));
        S3Object input = new S3Object("bucket", "input/nested/data.csv", payload, "text/plain");
        when(s3.getObject("bucket", "model.bin"))
                .thenReturn(new S3Object("bucket", "model.bin", payload, "application/octet-stream"));
        when(s3.listObjects("bucket", "input/", null, 1000)).thenReturn(List.of(input));
        when(s3.getObject("bucket", "input/nested/data.csv")).thenReturn(input);
        when(lifecycle.create(any(ContainerSpec.class))).thenReturn("container");
        when(lifecycle.getDockerClient()).thenReturn(docker);
    }

    @AfterEach
    void stopWorkers() {
        manager.stopManagedContainers();
        runner.stopManagedContainers();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 73, 8192})
    void modelUploadRetriesWithTheCompleteArchive(int consumed) throws Exception {
        failTransport(consumed);

        manager.copyModelData("container", "s3://bucket/model.bin", 0);

        assertRetried();
        assertArchive(Map.of("opt/ml/model/model.bin", payload));
        verify(s3).getObject("bucket", "model.bin");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 73, 8192})
    void trainingUploadRetriesWithAllConfigurationAndChannelFiles(int consumed) throws Exception {
        failTransport(consumed);

        runner.copyTrainingInput("container", job, 0);

        assertRetried();
        assertArchive(Map.of(
                "opt/ml/input/config/hyperparameters.json", mapper.writeValueAsBytes(job.hyperParameters),
                "opt/ml/input/config/resourceconfig.json",
                "{\"current_host\":\"algo-1\",\"hosts\":[\"algo-1\"]}".getBytes(StandardCharsets.UTF_8),
                "opt/ml/input/config/inputdataconfig.json",
                "{\"train\":{\"ContentType\":\"application/octet-stream\",\"TrainingInputMode\":\"File\"}}"
                        .getBytes(StandardCharsets.UTF_8),
                "opt/ml/input/data/train/nested/data.csv", payload));
        verify(s3).listObjects("bucket", "input/", null, 1000);
        verify(s3).getObject("bucket", "input/nested/data.csv");
    }

    @Test
    void compressedModelRetryPreservesNestedPathsAndRejectsTraversal() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(out))) {
            SageMakerEndpointManager.addFile(tar, "nested/model.bin", payload);
            SageMakerEndpointManager.addFile(tar, "../escaped.bin", payload);
        }
        when(s3.getObject("bucket", "model.tar.gz"))
                .thenReturn(new S3Object("bucket", "model.tar.gz", out.toByteArray(), "application/gzip"));
        failTransport(73);

        manager.copyModelData("container", "s3://bucket/model.tar.gz", 0);

        assertRetried();
        assertArchive(Map.of("opt/ml/model/nested/model.bin", payload));
        verify(s3).getObject("bucket", "model.tar.gz");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exhaustedTransportFailureStopsAtSixAttempts(boolean training) {
        failTransport(73);
        uploads.failures = 10;

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> copy(training));

        assertEquals(6, uploads.commands.size());
        assertSame(uploads.failure, thrown.getCause());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void daemonRejectionDoesNotRetry(boolean training) {
        uploads.failures = 10;
        uploads.failure = new NotFoundException("container missing");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> copy(training));

        assertEquals(1, uploads.commands.size());
        assertTrue(thrown.getMessage().contains("container missing"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sourceFailureDoesNotReachDocker(boolean training) {
        when(s3.getObject("bucket", "model.bin")).thenThrow(new IllegalArgumentException("source unavailable"));
        when(s3.getObject("bucket", "input/nested/data.csv"))
                .thenThrow(new IllegalArgumentException("source unavailable"));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> copy(training));

        assertEquals("source unavailable", thrown.getMessage());
        assertTrue(uploads.commands.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedUploadRemovesTheUnstartedContainer(boolean training) throws InterruptedException {
        uploads.failures = 1;
        uploads.failure = new NotFoundException("container missing");
        assertFailedStart(training, 1, "container missing");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exhaustedUploadRetriesRemoveTheUnstartedContainer(boolean training) throws InterruptedException {
        failTransport(73);
        uploads.failures = 10;
        assertFailedStart(training, 6, "Connection reset");
    }

    private void assertFailedStart(boolean training, int attempts, String reason) throws InterruptedException {
        SageMakerService service = mock(SageMakerService.class);
        CountDownLatch reported = new CountDownLatch(1);
        EndpointResource endpoint = new EndpointResource();
        endpoint.endpointName = "retry-endpoint";
        endpoint.region = "us-east-1";
        ModelResource model = new ModelResource();
        model.primaryContainer = Map.of("Image", "busybox:stable", "ModelDataUrl", "s3://bucket/model.bin");
        when(service.modelForEndpoint(endpoint)).thenReturn(model);
        doAnswer(invocation -> {
            reported.countDown();
            return true;
        }).when(service).finalizeEndpointStart(endpoint);
        doAnswer(invocation -> {
            reported.countDown();
            return true;
        }).when(service).updateTrainingJob(job);

        if (training) {
            runner.runAsync(job, service, 0);
        } else {
            manager.startEndpointAsync(endpoint, service, 0);
        }

        assertTrue(reported.await(5, TimeUnit.SECONDS));
        assertEquals("Failed", training ? job.trainingJobStatus : endpoint.endpointStatus);
        assertTrue((training ? job.failureReason : endpoint.failureReason).contains(reason));
        assertEquals(attempts, uploads.commands.size());
        verify(lifecycle).create(any(ContainerSpec.class));
        verify(lifecycle).stopAndRemove("container", null);
        verify(lifecycle, never()).startCreated(any(), any());
    }

    private void copy(boolean training) throws Exception {
        if (training) {
            runner.copyTrainingInput("container", job, 0);
        } else {
            manager.copyModelData("container", "s3://bucket/model.bin", 0);
        }
    }

    private void failTransport(int consumed) {
        uploads.failures = 1;
        uploads.consumedBytes = consumed;
        uploads.failure = new RuntimeException(new SocketException("Connection reset"));
    }

    private void assertRetried() {
        assertEquals(2, uploads.commands.size());
        assertNotSame(uploads.commands.get(0), uploads.commands.get(1));
        assertNotSame(uploads.streams.get(0), uploads.streams.get(1));
        for (CopyArchiveToContainerCmd command : uploads.commands) {
            verify(command).withRemotePath("/");
        }
        assertEquals(1, uploads.completed.size());
    }

    private void assertArchive(Map<String, byte[]> expected) throws Exception {
        Map<String, byte[]> actual = new LinkedHashMap<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(uploads.completed.getFirst()))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                assertEquals(0644, entry.getMode());
                actual.put(entry.getName(), tar.readAllBytes());
            }
        }
        assertEquals(expected.keySet(), actual.keySet());
        for (String name : expected.keySet()) {
            assertArrayEquals(expected.get(name), actual.get(name), name);
        }
    }
}
