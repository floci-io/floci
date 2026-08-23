package io.github.hectorvent.floci.services.kinesisanalytics.container;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.services.kinesisanalytics.model.FlinkApplication;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link FlinkContainerManager}'s container specifications and
 * {@code application_properties.json} construction. The JSON shape must exactly match real MSF/KDA's
 * runtime file so a real {@code KinesisAnalyticsRuntime.getApplicationProperties()} call in a user's
 * JAR finds it. The file-injection path itself was verified live against a real {@code apache/flink}
 * image rather than re-implemented here with a mocked DockerClient.
 */
class FlinkContainerManagerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> AWS_ENV = List.of(
            "AWS_DEFAULT_REGION=us-west-2",
            "AWS_REGION=us-west-2",
            "AWS_ACCESS_KEY_ID=test",
            "AWS_SECRET_ACCESS_KEY=test",
            "AWS_SESSION_TOKEN=test",
            "FLOCI_HOSTNAME=localhost.floci.io",
            "FLOCI_ENDPOINT=http://localhost.floci.io:4566",
            "AWS_ENDPOINT_URL=http://localhost.floci.io:4566");

    private ContainerLifecycleManager lifecycleManager;
    private LaunchedContainerAwsEnv awsEnv;
    private S3Service s3Service;
    private FlinkContainerManager manager;

    @BeforeEach
    void setUp() {
        EmulatorConfig.DockerConfig dockerConfig = mock(EmulatorConfig.DockerConfig.class);
        when(dockerConfig.imageRegistryBase()).thenReturn(Optional.empty());
        when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
        when(dockerConfig.logMaxSize()).thenReturn("10m");
        when(dockerConfig.logMaxFile()).thenReturn("3");

        EmulatorConfig.KinesisAnalyticsServiceConfig kinesisAnalyticsConfig =
                mock(EmulatorConfig.KinesisAnalyticsServiceConfig.class);
        when(kinesisAnalyticsConfig.defaultImage()).thenReturn(Optional.empty());

        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        when(servicesConfig.kinesisAnalytics()).thenReturn(kinesisAnalyticsConfig);
        when(servicesConfig.dockerNetwork()).thenReturn(Optional.of("floci-network"));

        EmulatorConfig.DnsConfig dnsConfig = mock(EmulatorConfig.DnsConfig.class);
        when(dnsConfig.containerFallbackEnabled()).thenReturn(false);

        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.docker()).thenReturn(dockerConfig);
        when(config.services()).thenReturn(servicesConfig);
        when(config.dns()).thenReturn(dnsConfig);
        when(config.defaultRegion()).thenReturn("us-west-2");

        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.isLinuxHost()).thenReturn(true);
        EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.of("172.18.0.2"));
        ContainerBuilder containerBuilder = new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer);

        lifecycleManager = mock(ContainerLifecycleManager.class);
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        s3Service = mock(S3Service.class);
        awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(eq("us-west-2"), eq(Optional.empty()))).thenReturn(AWS_ENV);

        manager = new FlinkContainerManager(
                containerBuilder,
                lifecycleManager,
                logStreamer,
                containerDetector,
                config,
                regionResolver,
                awsEnv,
                s3Service,
                mock(FlinkRestClient.class),
                MAPPER);
    }

    private FlinkApplication application(String name) {
        return new FlinkApplication(name,
                "arn:aws:kinesisanalytics:us-west-2:000000000000:application/" + name,
                "FLINK-1_18", "arn:aws:iam::000000000000:role/x", "STREAMING");
    }

    @Test
    void applicationPropertiesJsonMatchesTheRealMsfFileShape() throws Exception {
        FlinkApplication app = new FlinkApplication("demo", "arn:aws:kinesisanalytics:us-east-1:000000000000:application/demo",
                "FLINK-1_18", "arn:aws:iam::000000000000:role/x", "STREAMING");
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        groups.put("ProducerConfigProperties", Map.of("flink.stream.initpos", "LATEST", "aws.region", "us-west-2"));
        groups.put("ConsumerConfigProperties", Map.of("aws.region", "us-west-2"));
        app.setEnvironmentProperties(groups);

        byte[] json = manager.applicationPropertiesJson(app);
        JsonNode root = MAPPER.readTree(json);

        assertTrue(root.isArray());
        assertEquals(2, root.size());
        assertEquals("ProducerConfigProperties", root.get(0).get("PropertyGroupId").asText());
        assertEquals("LATEST", root.get(0).get("PropertyMap").get("flink.stream.initpos").asText());
        assertEquals("us-west-2", root.get(0).get("PropertyMap").get("aws.region").asText());
        assertEquals("ConsumerConfigProperties", root.get(1).get("PropertyGroupId").asText());
    }

    @Test
    void applicationPropertiesJsonIsAnEmptyArrayWhenNoPropertiesConfigured() throws Exception {
        FlinkApplication app = new FlinkApplication("bare", "arn:aws:kinesisanalytics:us-east-1:000000000000:application/bare",
                "FLINK-1_18", "arn:aws:iam::000000000000:role/x", "STREAMING");

        byte[] json = manager.applicationPropertiesJson(app);
        JsonNode root = MAPPER.readTree(json);

        // Real MSF always provides the file, even with zero property groups configured, so
        // KinesisAnalyticsRuntime.getApplicationProperties() never has to handle a missing file.
        assertTrue(root.isArray());
        assertEquals(0, root.size());
    }

    @Test
    void bareClusterInjectsAwsSdkEnvironmentAndContainerReachability() {
        FlinkApplication app = application("bare");
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerInfo(
                "jm-id", Map.of(8081, new EndpointInfo("localhost", 49152))));

        manager.startCluster(app);

        ContainerSpec jmSpec = captureCreatedSpecs().getFirst();
        assertTrue(jmSpec.env().containsAll(AWS_ENV));
        assertTrue(jmSpec.env().contains(
                "FLINK_PROPERTIES=jobmanager.rpc.address: localhost\nrest.bind-address: 0.0.0.0"));
        assertEquals(List.of("jobmanager"), jmSpec.cmd());
        assertEquals("floci-network", jmSpec.networkMode());
        assertTrue(jmSpec.extraHosts().contains("host.docker.internal:host-gateway"));
        assertTrue(jmSpec.dnsServers().contains("172.18.0.2"));
        verify(awsEnv).sdkBaselineEnv("us-west-2", Optional.empty());
    }

    @Test
    void codeClusterInjectsTheSameAwsSdkEnvironmentIntoBothFlinkProcesses() {
        FlinkApplication app = application("with-code");
        app.setCodeS3Bucket("code-bucket");
        app.setCodeS3Key("jobs/demo.jar");
        app.setParallelism(3);
        when(s3Service.getObject("code-bucket", "jobs/demo.jar", null))
                .thenReturn(new S3Object("code-bucket", "jobs/demo.jar", new byte[]{1}, "application/java-archive"));
        when(lifecycleManager.createAndStart(any()))
                .thenReturn(new ContainerInfo("jm-id", Map.of(8081, new EndpointInfo("localhost", 49152))))
                .thenReturn(new ContainerInfo("tm-id", Map.of()));

        manager.startCluster(app);

        List<ContainerSpec> specs = captureCreatedSpecs();
        ContainerSpec jmSpec = specs.get(0);
        ContainerSpec tmSpec = specs.get(1);
        assertTrue(jmSpec.env().containsAll(AWS_ENV));
        assertTrue(tmSpec.env().containsAll(AWS_ENV));
        assertEquals(List.of("jobmanager"), jmSpec.cmd());
        assertEquals(List.of("taskmanager"), tmSpec.cmd());
        assertTrue(jmSpec.env().stream().anyMatch(env -> env.startsWith("FLINK_PROPERTIES=jobmanager.rpc.address")));
        assertTrue(tmSpec.env().contains(
                "FLINK_PROPERTIES=jobmanager.rpc.address: localhost\ntaskmanager.numberOfTaskSlots: 3"));
        assertEquals("container:jm-id", tmSpec.networkMode());
        verify(awsEnv).sdkBaselineEnv("us-west-2", Optional.empty());
    }

    @Test
    void jobManagerStartFailureRemovesThePartialCluster() {
        FlinkApplication app = application("jm-failure");
        when(lifecycleManager.createAndStart(any())).thenThrow(new RuntimeException("jobmanager failed"));

        assertThrows(RuntimeException.class, () -> manager.startCluster(app));

        verify(lifecycleManager, times(2)).removeIfExists("floci-kinesisanalytics-jm-failure");
        verify(lifecycleManager, atLeastOnce()).removeIfExists("floci-kinesisanalytics-jm-failure-tm");
        assertNull(app.getContainerId());
        assertNull(app.getTaskManagerContainerId());
    }

    @Test
    void taskManagerStartFailureStopsTheJobManagerAndClearsClusterState() {
        FlinkApplication app = application("tm-failure");
        app.setCodeS3Bucket("code-bucket");
        app.setCodeS3Key("jobs/demo.jar");
        when(s3Service.getObject("code-bucket", "jobs/demo.jar", null))
                .thenReturn(new S3Object("code-bucket", "jobs/demo.jar", new byte[]{1}, "application/java-archive"));
        when(lifecycleManager.createAndStart(any()))
                .thenReturn(new ContainerInfo("jm-id", Map.of(8081, new EndpointInfo("localhost", 49152))))
                .thenThrow(new RuntimeException("taskmanager failed"));

        assertThrows(RuntimeException.class, () -> manager.startCluster(app));

        verify(lifecycleManager).stopAndRemove("jm-id", null);
        verify(lifecycleManager, atLeastOnce()).removeIfExists("floci-kinesisanalytics-tm-failure-tm");
        assertNull(app.getContainerId());
        assertNull(app.getRestEndpoint());
        assertNull(app.getTaskManagerContainerId());
    }

    private List<ContainerSpec> captureCreatedSpecs() {
        ArgumentCaptor<ContainerSpec> captor = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager, atLeastOnce()).createAndStart(captor.capture());
        return captor.getAllValues();
    }
}
