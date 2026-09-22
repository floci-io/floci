package io.github.hectorvent.floci.services.eks;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService.LogEventsResult;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogGroup;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogStream;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.LogSetup;
import io.github.hectorvent.floci.services.eks.model.Logging;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EksControlPlaneLogsDockerIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksControlPlaneLogsDockerIntegrationTest.class);
    private static final String TEST_IMAGE = "alpine:3.21";
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerBuilder containerBuilder;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    EksClusterManager eksClusterManager;

    @Inject
    CloudWatchLogsService cloudWatchLogsService;

    private String containerId;
    private Cluster cluster;

    @BeforeEach
    void requireDocker() {
        boolean dockerAvailable = isDockerAvailable();
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksControlPlaneLogsDockerIntegrationTest");
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon must be available for EKS control plane logs integration test");
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            try {
                eksClusterManager.stopCluster(cluster);
            } catch (Exception ignored) {
            }
        }
        if (containerId != null) {
            try {
                lifecycleManager.stopAndRemove(containerId, null);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void clusterWithLoggingDeliversEventsToCloudWatchLogs() throws Exception {
        String clusterName = "cplogs-it-" + UUID.randomUUID().toString().substring(0, 8);
        cluster = new Cluster();
        cluster.setName(clusterName);
        cluster.setAccountId(ACCOUNT);
        cluster.setArn("arn:aws:eks:" + REGION + ":" + ACCOUNT + ":cluster/" + clusterName);
        cluster.setLogging(new Logging(List.of(new LogSetup(List.of("api"), true))));

        String expectedMessage = "eks-cp-log-message-" + UUID.randomUUID();
        ContainerSpec spec = containerBuilder.newContainer(TEST_IMAGE)
                .withName("floci-eks-cp-test-" + clusterName)
                .withCmd(List.of("sh", "-c", "echo '" + expectedMessage + "'; sleep 30"))
                .build();

        containerId = lifecycleManager.createAndStart(spec).containerId();
        assertNotNull(containerId, "Container ID must not be null");
        cluster.setContainerId(containerId);

        eksClusterManager.attachClusterLogs(cluster);
        assertNotNull(eksClusterManager.getLogHandle(cluster), "Log handle should be active after attach");

        String logGroup = "/aws/eks/" + clusterName + "/cluster";
        String streamPrefix = "kube-apiserver-";

        List<LogStream> streams = List.of();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                streams = cloudWatchLogsService.describeLogStreams(logGroup, streamPrefix, REGION);
                if (!streams.isEmpty()) {
                    break;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(100);
        }
        assertFalse(streams.isEmpty(), "Expected log stream starting with " + streamPrefix + " in group " + logGroup);

        String streamName = streams.get(0).getLogStreamName();
        assertTrue(streamName.startsWith(streamPrefix), "Stream name should start with prefix: " + streamName);

        List<LogEvent> events = List.of();
        while (System.currentTimeMillis() < deadline) {
            try {
                LogEventsResult result = cloudWatchLogsService.getLogEvents(
                        logGroup, streamName, null, null, 10, true, null, REGION);
                if (!result.events().isEmpty()) {
                    events = result.events();
                    break;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(100);
        }
        assertFalse(events.isEmpty(), "Expected log events in stream " + streamName);
        assertTrue(events.stream().anyMatch(e -> e.getMessage().contains(expectedMessage)),
                "Expected event containing: " + expectedMessage);

        eksClusterManager.stopCluster(cluster);
        assertNull(eksClusterManager.getLogHandle(cluster), "Log handle should be removed after stop");
    }

    @Test
    void restoredClusterDoesNotReplayOldLogs() throws Exception {
        String clusterName = "cplogs-restore-" + UUID.randomUUID().toString().substring(0, 8);
        cluster = new Cluster();
        cluster.setName(clusterName);
        cluster.setAccountId(ACCOUNT);
        cluster.setArn("arn:aws:eks:" + REGION + ":" + ACCOUNT + ":cluster/" + clusterName);
        cluster.setLogging(new Logging(List.of(new LogSetup(List.of("api"), true))));

        String oldMessage = "eks-old-msg-" + UUID.randomUUID();
        String newMessage = "eks-new-msg-" + UUID.randomUUID();
        ContainerSpec spec = containerBuilder.newContainer(TEST_IMAGE)
                .withName("floci-eks-cp-test-" + clusterName)
                .withCmd(List.of("sh", "-c", "echo '" + oldMessage + "'; sleep 2; echo '" + newMessage + "'; sleep 30"))
                .build();

        containerId = lifecycleManager.createAndStart(spec).containerId();
        assertNotNull(containerId, "Container ID must not be null");
        cluster.setContainerId(containerId);

        // Allow old message to be emitted before attaching
        Thread.sleep(1500);

        eksClusterManager.attachClusterLogsFromNow(cluster);
        assertNotNull(eksClusterManager.getLogHandle(cluster), "Log handle should be active after attachFromNow");

        String logGroup = "/aws/eks/" + clusterName + "/cluster";
        String streamPrefix = "kube-apiserver-";

        List<LogStream> streams = List.of();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                streams = cloudWatchLogsService.describeLogStreams(logGroup, streamPrefix, REGION);
                if (!streams.isEmpty()) {
                    break;
                }
            } catch (Exception ignored) {
                // Log group or stream not yet created
            }
            Thread.sleep(100);
        }
        assertFalse(streams.isEmpty(), "Expected log stream starting with " + streamPrefix + " in group " + logGroup);

        String streamName = streams.get(0).getLogStreamName();

        List<LogEvent> events = List.of();
        while (System.currentTimeMillis() < deadline) {
            try {
                LogEventsResult result = cloudWatchLogsService.getLogEvents(
                        logGroup, streamName, null, null, 10, true, null, REGION);
                if (!result.events().isEmpty()) {
                    events = result.events();
                    if (events.stream().anyMatch(e -> e.getMessage().contains(newMessage))) {
                        break;
                    }
                }
            } catch (Exception ignored) {
                // Stream not yet populated
            }
            Thread.sleep(100);
        }
        assertFalse(events.isEmpty(), "Expected log events in stream " + streamName);
        assertTrue(events.stream().anyMatch(e -> e.getMessage().contains(newMessage)),
                "Expected event containing new message: " + newMessage);
        assertFalse(events.stream().anyMatch(e -> e.getMessage().contains(oldMessage)),
                "Did not expect old message to be replayed: " + oldMessage);

        eksClusterManager.stopCluster(cluster);
        assertNull(eksClusterManager.getLogHandle(cluster), "Log handle should be removed after stop");
    }

    @Test
    void clusterWithoutLoggingDoesNotCreateLogGroup() {
        String clusterName = "cplogs-off-" + UUID.randomUUID().toString().substring(0, 8);
        cluster = new Cluster();
        cluster.setName(clusterName);
        cluster.setAccountId(ACCOUNT);
        cluster.setArn("arn:aws:eks:" + REGION + ":" + ACCOUNT + ":cluster/" + clusterName);
        cluster.setLogging(new Logging(List.of(new LogSetup(List.of("api"), false))));

        ContainerSpec spec = containerBuilder.newContainer(TEST_IMAGE)
                .withName("floci-eks-cp-test-" + clusterName)
                .withCmd(List.of("sh", "-c", "echo 'should-not-stream'; sleep 10"))
                .build();

        containerId = lifecycleManager.createAndStart(spec).containerId();
        cluster.setContainerId(containerId);

        eksClusterManager.attachClusterLogs(cluster);
        assertNull(eksClusterManager.getLogHandle(cluster), "No log handle expected when logging disabled");

        String logGroup = "/aws/eks/" + clusterName + "/cluster";
        List<LogGroup> groupsResult = cloudWatchLogsService.describeLogGroups(logGroup, REGION);
        assertTrue(groupsResult.isEmpty(), "Log group should not exist when logging is disabled");
    }

    @Test
    void clusterWithAuditLoggingDeliversAuditEventsToCloudWatchLogs() throws Exception {
        String clusterName = "audit-it-" + UUID.randomUUID().toString().substring(0, 8);
        cluster = new Cluster();
        cluster.setName(clusterName);
        cluster.setAccountId(ACCOUNT);
        cluster.setArn("arn:aws:eks:" + REGION + ":" + ACCOUNT + ":cluster/" + clusterName);
        cluster.setLogging(new Logging(List.of(new LogSetup(List.of("audit"), true))));

        String auditEventJson = "{\"kind\":\"Event\",\"apiVersion\":\"audit.k8s.io/v1\",\"level\":\"Metadata\",\"stage\":\"ResponseComplete\",\"verb\":\"get\",\"user\":{\"username\":\"test-admin\"}}";
        ContainerSpec spec = containerBuilder.newContainer(TEST_IMAGE)
                .withName("floci-eks-cp-test-" + clusterName)
                .withCmd(List.of("sh", "-c", "mkdir -p /var/log && echo '" + auditEventJson + "' >> /var/log/audit.log; sleep 30"))
                .build();

        containerId = lifecycleManager.createAndStart(spec).containerId();
        assertNotNull(containerId, "Container ID must not be null");
        cluster.setContainerId(containerId);

        eksClusterManager.attachClusterLogs(cluster);
        assertNotNull(eksClusterManager.getLogHandle(cluster), "Log handle should be active after attach");

        String logGroup = "/aws/eks/" + clusterName + "/cluster";
        String streamPrefix = "kube-apiserver-audit-";

        List<LogStream> streams = List.of();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                streams = cloudWatchLogsService.describeLogStreams(logGroup, streamPrefix, REGION);
                if (!streams.isEmpty()) {
                    break;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(100);
        }
        assertFalse(streams.isEmpty(), "Expected log stream starting with " + streamPrefix + " in group " + logGroup);

        String streamName = streams.get(0).getLogStreamName();
        assertTrue(streamName.startsWith(streamPrefix), "Stream name should start with prefix: " + streamName);

        List<LogEvent> events = List.of();
        while (System.currentTimeMillis() < deadline) {
            try {
                LogEventsResult result = cloudWatchLogsService.getLogEvents(
                        logGroup, streamName, null, null, 10, true, null, REGION);
                if (!result.events().isEmpty()) {
                    events = result.events();
                    break;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(100);
        }
        assertFalse(events.isEmpty(), "Expected audit log events in stream " + streamName);
        assertTrue(events.stream().anyMatch(e -> e.getMessage().contains("\"kind\":\"Event\"")
                        && e.getMessage().contains("\"apiVersion\":\"audit.k8s.io/v1\"")),
                "Expected audit event containing kind Event and apiVersion audit.k8s.io/v1");

        eksClusterManager.stopCluster(cluster);
        assertNull(eksClusterManager.getLogHandle(cluster), "Log handle should be removed after stop");
    }

    @Test
    void clusterWithBothApiAndAuditLoggingDeliversToBothStreams() throws Exception {
        String clusterName = "both-it-" + UUID.randomUUID().toString().substring(0, 8);
        cluster = new Cluster();
        cluster.setName(clusterName);
        cluster.setAccountId(ACCOUNT);
        cluster.setArn("arn:aws:eks:" + REGION + ":" + ACCOUNT + ":cluster/" + clusterName);
        cluster.setLogging(new Logging(List.of(
                new LogSetup(List.of("api", "audit"), true)
        )));

        String apiMsg = "api-msg-" + UUID.randomUUID();
        String auditMsg = "{\"kind\":\"Event\",\"auditId\":\"" + UUID.randomUUID() + "\"}";

        ContainerSpec spec = containerBuilder.newContainer(TEST_IMAGE)
                .withName("floci-eks-cp-test-" + clusterName)
                .withCmd(List.of("sh", "-c", "echo '" + apiMsg + "'; mkdir -p /var/log && echo '" + auditMsg + "' >> /var/log/audit.log; sleep 30"))
                .build();

        containerId = lifecycleManager.createAndStart(spec).containerId();
        cluster.setContainerId(containerId);

        eksClusterManager.attachClusterLogs(cluster);
        assertNotNull(eksClusterManager.getLogHandle(cluster));

        String logGroup = "/aws/eks/" + clusterName + "/cluster";
        long deadline = System.currentTimeMillis() + 10_000;

        List<LogStream> apiStreams = List.of();
        while (System.currentTimeMillis() < deadline) {
            try {
                apiStreams = cloudWatchLogsService.describeLogStreams(logGroup, "kube-apiserver-", REGION);
                if (!apiStreams.isEmpty()) {
                    break;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(100);
        }
        assertFalse(apiStreams.isEmpty(), "Expected kube-apiserver- stream");

        List<LogStream> auditStreams = List.of();
        while (System.currentTimeMillis() < deadline) {
            try {
                auditStreams = cloudWatchLogsService.describeLogStreams(logGroup, "kube-apiserver-audit-", REGION);
                if (!auditStreams.isEmpty()) {
                    break;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(100);
        }
        assertFalse(auditStreams.isEmpty(), "Expected kube-apiserver-audit- stream");

        eksClusterManager.stopCluster(cluster);
        assertNull(eksClusterManager.getLogHandle(cluster));
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
