package io.floci.aws.services.emr;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveFromContainerCmd;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.ListNetworksCmd;
import com.github.dockerjava.api.command.ListVolumesCmd;
import com.github.dockerjava.api.command.ListVolumesResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerHostConfig;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.EmulatorConfig.EcsServiceConfig.ImagePullBehavior;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerExecStubs;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLiveness;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.CurrentContainerNetworkResolver;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.emrserverless.EmrServerlessSparkRunner;
import io.floci.aws.services.emrserverless.SparkJobRunner;
import io.floci.aws.services.iam.model.SessionCreds;
import io.floci.aws.services.lambda.launcher.ImageCacheService;
import io.floci.aws.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.floci.aws.services.s3.S3Service;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A cluster's storage: Docker volumes, and its HDFS in a container of its own, on its primary node. */
class EmrClusterStorageTest {

    private static final String IMAGE = "public.ecr.aws/emr-serverless/hive/emr-7.5.0:latest";
    private static final String HOST = "ip-10-0-0-1.ec2.internal";
    private static final String READY = "test -f /tmp/floci-hdfs-ready";
    private static final BooleanSupplier NOT_STOPPED = () -> false;
    private static final String CORE_HOST = "ip-10-1-0-1.ec2.internal";
    private static final String TASK_HOST = "ip-10-2-0-1.ec2.internal";
    private static final ClusterStorage.Node CORE_NODE = new ClusterStorage.Node("1-1", CORE_HOST, true);
    private static final ClusterStorage.Node TASK_NODE = new ClusterStorage.Node("2-1", TASK_HOST, false);
    private static final ClusterStorage.Yarn TWO_NODES = new ClusterStorage.Yarn(4096, 4)
            .onNodes(List.of(CORE_NODE, TASK_NODE), 1, "s3://logs/j-1/containers/");
    private static final ClusterStorage.Yarn CORE_ONLY = new ClusterStorage.Yarn(4096, 4)
            .onNodes(List.of(CORE_NODE), 1, "s3://logs/j-1/containers/");
    // A cluster with Hive, whose EC2 instance profile's role is r, with no core-site or hive-site.
    private static final ClusterStorage.HiveMetastore METASTORE =
            new ClusterStorage.HiveMetastore("arn:aws:iam::123456789012:role/r", Map.of());

    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final DockerClient docker = mock(DockerClient.class);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
    private final ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
    private final LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
    private final LambdaExecutionRoleCredentials roleCredentials = mock(LambdaExecutionRoleCredentials.class);
    private final CurrentContainerNetworkResolver currentContainer = mock(CurrentContainerNetworkResolver.class);
    private final S3Service s3 = mock(S3Service.class);
    private final EmrClusterStorage storage = new EmrClusterStorage(lifecycle, containerBuilder, awsEnv,
            roleCredentials, currentContainer, config, s3, Duration.ofSeconds(120), System::nanoTime);
    private final String hdfsName = ContainerStorageHelper.dockerName(config, "emr-j-1-hdfs");
    private final String coreName = ContainerStorageHelper.dockerName(config, "emr-j-1-node-1-1");
    private final String taskName = ContainerStorageHelper.dockerName(config, "emr-j-1-node-2-1");

    @BeforeEach
    void docker() {
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(containerBuilder.resolveDockerNetwork(Optional.of("floci-network")))
                .thenReturn(Optional.of("floci-network"));
        when(containerBuilder.newContainer(IMAGE)).thenReturn(spec);
        when(containerBuilder.resolveImage(IMAGE)).thenReturn(IMAGE);
        when(lifecycle.resolveImageForLaunch(IMAGE, ImagePullBehavior.PREFER_CACHED, null))
                .thenReturn(new ImageCacheService.LaunchImage(IMAGE, null));
        when(spec.build()).thenReturn(new ContainerSpec(IMAGE));
        // No other cluster's HDFS runs.
        ListContainersCmd running = mock(ListContainersCmd.class, RETURNS_SELF);
        when(docker.listContainersCmd()).thenReturn(running);
        when(running.exec()).thenReturn(List.of());
        // No node's volume is there without its container, and Docker removes the volumes it is asked to.
        nodeVolumes();
        when(lifecycle.removeVolume(anyString())).thenReturn(true);
    }

    @Test
    void aClusterWithCoreAndTaskNodesRunsEachInAContainerOfItsOwn() {
        nodesStart(coreName, taskName);

        SparkJobRunner.Storage started = storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network",
                null, () -> TWO_NODES, NOT_STOPPED);

        assertEquals(1, started.replication());
        verify(spec).withName(coreName);
        verify(spec).withName(taskName);
        verify(lifecycle).ensureVolume(eq(coreName), any());
        verify(spec).withNamedVolume(coreName, "/mnt");
        verify(spec).withNamedVolume(taskName, "/mnt");
        // Each under the name ListInstances gives it, on the cluster's network.
        verify(spec).withDnsName(CORE_HOST);
        verify(spec).withDnsName(TASK_HOST);
        verify(spec).withDnsName(HOST);
        verify(spec).withEnv("FLOCI_NODE_HOST", CORE_HOST);
        verify(spec).withEnv("FLOCI_NODE_HOST", TASK_HOST);
        // Only a core node holds a DataNode, and with one the primary node holds neither.
        verify(spec).withEnv("FLOCI_NODE_CORE", "1");
        verify(spec).withEnv("FLOCI_NODE_WORKERS", "1");
        // What the primary node has of YARN too, and Floci in reach.
        verify(spec, times(3)).withEnv("FLOCI_YARN_MEMORY_MB", "4096");
        verify(spec, times(3)).withEnv("FLOCI_YARN_VCORES", "4");
        verify(spec, times(3)).withHostDockerInternalOnLinux();
        verify(spec, times(3)).withJavaTrustStore();
        verify(spec, times(2)).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(
                "n=1; if [ -n \"$FLOCI_NODE_CORE\" ]; then hdfs datanode & n=2; fi;")));
        // It is ready once the ResourceManager knows it, not when it starts.
        verify(spec, times(2)).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(
                "until curl -sf -m 5 \"http://$FLOCI_HDFS_HOST:8088/ws/v1/cluster/nodes?states=RUNNING\""
                        + " | grep -q \"\\\"nodeHostName\\\":\\\"$FLOCI_NODE_HOST\\\"\"")));
        verify(spec, times(2)).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(
                "grep -q \"$FLOCI_NODE_HOST:9866\"")));
        verify(spec, times(3)).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(
                "<name>dfs.hosts.exclude</name><value>/tmp/floci-hdfs-conf/dfs.exclude</value>")));
        // A node that stops is dead to the NameNode after 40s, which a core node that has gone waits for.
        verify(spec, times(3)).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(
                "<name>dfs.namenode.heartbeat.recheck-interval</name><value>5000</value>")));
        verify(spec, times(3)).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(
                "<name>yarn.resourcemanager.nodes.exclude-path</name><value>/tmp/floci-hdfs-conf/yarn.exclude</value>")));
        // The primary node runs its own DataNode and NodeManager only without core nodes, but a DataNode on a volume
        // holding one's data, so the blocks on it stay in reach.
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(
                "hdfs namenode & n=1; if [ -z \"$FLOCI_NODE_WORKERS\" ] || [ -d /mnt/datanode/current ]; then "
                        + "hdfs datanode & n=2; fi;")));
        // The primary node's readiness needs a DataNode of theirs, so they start before it is awaited.
        InOrder order = inOrder(lifecycle, docker);
        order.verify(lifecycle, times(3)).createAndStart(any());
        order.verify(docker).execCreateCmd("c-1");
        order.verify(docker).execCreateCmd(coreName);
    }

    @Test
    void aCoreNodesDataNodeRegistersUnderItsNameAndACopyCountComesFromTheCluster() {
        nodesStart(coreName, taskName);

        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null,
                () -> new ClusterStorage.Yarn(4096, 4).onNodes(List.of(CORE_NODE, TASK_NODE), 2, null), NOT_STOPPED);

        verify(spec, times(3)).withEnv("FLOCI_HDFS_REPLICATION", "2");
        verify(spec, times(3)).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(
                "<name>dfs.replication</name><value>${FLOCI_HDFS_REPLICATION:-1}</value>")
                && entrypoint.get(2).contains("datanode_name=; [ -z \"$FLOCI_NODE_HOST\" ] || datanode_name=\"<property>"
                        + "<name>dfs.datanode.hostname</name><value>$FLOCI_NODE_HOST</value></property>\"")));
    }

    @Test
    void everyNodeRunsItsDaemonsFromTheHadoopItsImageKeepsApart() {
        nodesStart(coreName, taskName);

        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null,
                () -> TWO_NODES.withHadoopFolder("/opt"), NOT_STOPPED);

        verify(spec, times(3)).withEnv("FLOCI_HADOOP_FOLDER", "/opt");
    }

    @Test
    void aNodeRunningInTheImageIsLeftRunning() {
        nodesStart(coreName, taskName);
        runningNodes(node(coreName, "running", IMAGE, CORE_HOST, true), node(taskName, "running", IMAGE, TASK_HOST, false));

        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, () -> TWO_NODES,
                NOT_STOPPED);

        verify(spec, never()).withName(coreName);
        verify(spec, never()).withName(taskName);
        verify(lifecycle, never()).removeIfExistsStrict(coreName);
    }

    @Test
    void aNodeInAnotherImageOrStoppedStartsAgainOnItsData() {
        nodesStart(coreName, taskName);
        runningNodes(node(coreName, "running", "sha256:older", CORE_HOST, true),
                node(taskName, "exited", IMAGE, TASK_HOST, false));

        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, () -> TWO_NODES,
                NOT_STOPPED);

        verify(lifecycle).removeIfExistsStrict(coreName);
        verify(spec).withName(coreName);
        verify(spec).withName(taskName);
        verify(lifecycle, never()).removeVolume(coreName);
    }

    @Test
    void aNodeThatStopsBeforeItIsReadyStartsAgainOnceThenFailsTheStep() {
        nodesStart(coreName);
        when(lifecycle.probeContainer(coreName)).thenReturn(ContainerLiveness.NOT_RUNNING);

        IllegalStateException failed = assertThrows(IllegalStateException.class,
                () -> storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null,
                        () -> CORE_ONLY, NOT_STOPPED));

        assertEquals("A node of EMR cluster j-1 stopped before it was ready", failed.getMessage());
        verify(spec, times(2)).withName(coreName);
    }

    @Test
    void aClusterWithOnlyTaskNodesKeepsItsDataNodeAndNodeManagerOnThePrimaryNode() {
        nodesStart(taskName);

        SparkJobRunner.Storage started = storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network",
                null, () -> new ClusterStorage.Yarn(4096, 4).onNodes(List.of(TASK_NODE), 1, null), NOT_STOPPED);

        verify(spec, never()).withEnv(eq("FLOCI_NODE_WORKERS"), any());
        verify(spec, never()).withEnv(eq("FLOCI_NODE_CORE"), any());
        assertEquals(0, started.replication());
    }

    @Test
    void aPrimaryNodeThatWorksKeepsItsDataNodeAndNodeManagerBesideItsCoreNodes() {
        nodesStart(coreName, taskName);

        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null,
                () -> TWO_NODES.withPrimaryWorking(true), NOT_STOPPED);

        verify(spec).withEnv("FLOCI_NODE_CORE", "1");
        verify(spec, never()).withEnv(eq("FLOCI_NODE_WORKERS"), any());
    }

    @Test
    void aResizeBeforeAStepHasStartedTheNodesStartsNone() {
        storage.resize("j-1", () -> TWO_NODES);

        verify(lifecycle, never()).createAndStart(any());
    }

    @Test
    void aNewNodeStartsOnAResizeFromWhatItsClusterSaysThen() {
        nodesStart(coreName, taskName);
        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, () -> CORE_ONLY,
                NOT_STOPPED);
        runningNodes(node(coreName, "running", IMAGE, CORE_HOST, true));

        storage.resize("j-1", () -> TWO_NODES);

        verify(spec).withName(taskName);
        verify(spec, times(1)).withName(coreName);
    }

    @Test
    void aFailedResizeIsLoggedAndLeavesTheClusterAsItIs() {
        nodesStart(coreName);
        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, () -> CORE_ONLY,
                NOT_STOPPED);
        when(docker.listContainersCmd()).thenThrow(new IllegalStateException("Docker went away"));

        assertDoesNotThrow(() -> storage.resize("j-1", () -> TWO_NODES));
    }

    @Test
    void aTaskNodeNoLongerWantedIsDecommissionedAndGoesWithItsData() throws IOException {
        nodesStart(coreName, taskName);
        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, () -> TWO_NODES,
                NOT_STOPPED);
        runningNodes(node(coreName, "running", IMAGE, CORE_HOST, true), node(taskName, "running", IMAGE, TASK_HOST, false));
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "", "");
        String taskLog = "application_1_0001/container_1_0001_01_000002/stdout";
        nodeLogs(taskName, Map.of(taskLog, "from task"));
        clearInvocations(lifecycle);

        storage.resize("j-1", () -> CORE_ONLY);

        // Told first: YARN lets a task node go alone, as it holds no HDFS.
        String told = commands.get(0).get(2);
        assertTrue(told.contains("printf '" + TASK_HOST + "\\n' > /tmp/floci-hdfs-conf/yarn.exclude.new"), told);
        assertTrue(told.contains("printf '' > /tmp/floci-hdfs-conf/dfs.exclude.new"), told);
        assertTrue(told.contains("yarn rmadmin -refreshNodes -g 3600 -server"), told);
        assertTrue(told.contains("hdfs dfsadmin -refreshNodes"), told);
        // A refresh that fails takes its list away, so the next round's differs and is read again.
        assertTrue(told.contains("-server || { rm -f /tmp/floci-hdfs-conf/yarn.exclude; exit 1; }"), told);
        assertTrue(told.contains("hdfs dfsadmin -refreshNodes || { rm -f /tmp/floci-hdfs-conf/dfs.exclude; exit 1; }"),
                told);
        String asked = commands.get(2).get(2);
        assertTrue(asked.contains("cluster/nodes?states=DECOMMISSIONED' | grep -q '\"nodeHostName\":\"" + TASK_HOST), asked);
        assertFalse(asked.contains("Decommissioned"), asked);
        verify(docker).copyArchiveFromContainerCmd(taskName, "/mnt/yarn/logs/" + taskLog);
        verify(lifecycle).removeIfExistsStrict(taskName);
        verify(lifecycle).removeVolume(taskName);
        verify(lifecycle, never()).removeIfExistsStrict(coreName);
    }

    @Test
    void aCoreNodeGoesOnlyOnceHdfsHasMovedItsBlocksAndYarnHasLetItGo() {
        // Ten seconds on each reading, so the thread sees the NameNode's dead-node interval pass.
        AtomicLong clock = new AtomicLong();
        EmrClusterStorage ticking = new EmrClusterStorage(lifecycle, containerBuilder, awsEnv, roleCredentials,
                currentContainer, config, s3, Duration.ofSeconds(120),
                () -> clock.getAndAdd(Duration.ofSeconds(10).toNanos()), Runnable::run, Duration.ZERO);
        nodesStart(coreName, taskName);
        ticking.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, () -> TWO_NODES,
                NOT_STOPPED);
        runningNodes(node(coreName, "running", IMAGE, CORE_HOST, true), node(taskName, "running", IMAGE, TASK_HOST, false));
        // HDFS told; told again and asked, not yet done; told again and asked, done; YARN told and asked, done; told
        // while the NameNode has the node live, then not.
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, hdfsName,
                List.of(0L, 0L, 1L, 0L, 0L, 0L, 0L), "", "");
        clearInvocations(lifecycle);

        ticking.resize("j-1", () -> new ClusterStorage.Yarn(4096, 4).onNodes(List.of(TASK_NODE), 1, null));

        String excluded = "printf '" + CORE_HOST + "\\n' > /tmp/floci-hdfs-conf/dfs.exclude.new";
        String told = commands.get(0).get(2);
        assertTrue(told.contains(excluded), told);
        assertTrue(told.contains("printf '' > /tmp/floci-hdfs-conf/yarn.exclude.new"), told);
        String hdfsAsked = commands.get(2).get(2);
        assertTrue(hdfsAsked.contains("grep -Eq '" + CORE_HOST + ":9866[^}]*Decommissioned'"), hdfsAsked);
        String yarnTold = commands.get(5).get(2);
        assertTrue(yarnTold.contains("printf '" + CORE_HOST + "\\n' > /tmp/floci-hdfs-conf/yarn.exclude.new"),
                yarnTold);
        String yarnAsked = commands.get(6).get(2);
        assertTrue(yarnAsked.contains("DECOMMISSIONED' | grep -q '\"nodeHostName\":\"" + CORE_HOST), yarnAsked);
        assertEquals(2, commands.stream().filter(command -> command.get(2).contains("Decommissioned'")).count());
        assertEquals(1, commands.stream().filter(command -> command.get(2).contains("DECOMMISSIONED'")).count());
        String gone = commands.get(7).get(2);
        assertTrue(gone.contains(excluded), gone);
        String dead = commands.get(commands.size() - 1).get(2);
        assertTrue(dead.contains("printf '' > /tmp/floci-hdfs-conf/dfs.exclude.new"), dead);
        verify(lifecycle).removeIfExistsStrict(coreName);
        verify(lifecycle).removeVolume(coreName);
    }

    @Test
    void aNodeThatStoppedWhileItLeftGoesWithoutWaiting() {
        nodesStart(coreName, taskName);
        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, () -> TWO_NODES,
                NOT_STOPPED);
        runningNodes(node(coreName, "running", IMAGE, CORE_HOST, true), node(taskName, "running", IMAGE, TASK_HOST, false));
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 1L, "", "");
        when(lifecycle.probeContainer(taskName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        clearInvocations(lifecycle);

        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> storage.resize("j-1", () -> CORE_ONLY));

        verify(lifecycle).removeIfExistsStrict(taskName);
    }

    @Test
    void aNodeNotDecommissionedInTimeGoesAnyway() {
        AtomicLong clock = new AtomicLong();
        EmrClusterStorage ticking = new EmrClusterStorage(lifecycle, containerBuilder, awsEnv, roleCredentials,
                currentContainer, config, s3, Duration.ofSeconds(120),
                () -> clock.getAndAdd(Duration.ofHours(2).toNanos()), Runnable::run, Duration.ZERO);
        nodesStart(coreName, taskName);
        ticking.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, () -> TWO_NODES,
                NOT_STOPPED);
        runningNodes(node(coreName, "running", IMAGE, CORE_HOST, true), node(taskName, "running", IMAGE, TASK_HOST, false));
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 1L, "", "");
        clearInvocations(lifecycle);

        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> ticking.resize("j-1", () -> CORE_ONLY));

        verify(lifecycle).removeIfExistsStrict(taskName);
    }

    @Test
    void aNodeALaterResizeLetsGoHasTheWholeTimeToo() {
        // A minute on each reading, so the rounds go by.
        AtomicLong clock = new AtomicLong();
        EmrClusterStorage ticking = new EmrClusterStorage(lifecycle, containerBuilder, awsEnv, roleCredentials,
                currentContainer, config, s3, Duration.ofSeconds(120),
                () -> clock.getAndAdd(Duration.ofMinutes(1).toNanos()), Runnable::run, Duration.ZERO);
        nodesStart(coreName, taskName);
        ticking.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, () -> TWO_NODES,
                NOT_STOPPED);
        runningNodes(node(coreName, "running", IMAGE, CORE_HOST, true), node(taskName, "running", IMAGE, TASK_HOST, false));
        // Neither HDFS nor YARN lets a node go, so each goes when its time is up.
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 1L, "", "");
        AtomicLong coreLetGo = new AtomicLong();
        AtomicBoolean shrunk = new AtomicBoolean();
        when(lifecycle.probeContainer(taskName)).thenAnswer(asked -> {
            // Fifty-nine minutes into the task node's decommissioning, a resize lets the core node go too.
            if (shrunk.compareAndSet(false, true)) {
                clock.addAndGet(Duration.ofMinutes(59).toNanos());
                coreLetGo.set(clock.get());
                ticking.resize("j-1", () -> new ClusterStorage.Yarn(4096, 4).onNodes(List.of(), 1, null));
            }
            return ContainerLiveness.RUNNING;
        });
        AtomicLong coreGone = new AtomicLong();
        doAnswer(removing -> {
            coreGone.set(clock.get());
            return null;
        }).when(lifecycle).removeIfExistsStrict(coreName);

        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> ticking.resize("j-1", () -> CORE_ONLY));

        assertTrue(coreGone.get() - coreLetGo.get() >= Duration.ofHours(1).toNanos(),
                "gone " + Duration.ofNanos(coreGone.get() - coreLetGo.get()) + " after it was let go");
    }

    @Test
    void aNodeDockerCannotTellTheStateOfIsAskedAgainRatherThanTakenAsGone() {
        AtomicLong clock = new AtomicLong();
        EmrClusterStorage ticking = new EmrClusterStorage(lifecycle, containerBuilder, awsEnv, roleCredentials,
                currentContainer, config, s3, Duration.ofSeconds(120),
                () -> clock.getAndAdd(Duration.ofMinutes(30).toNanos()), Runnable::run, Duration.ZERO);
        nodesStart(coreName, taskName);
        ticking.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, () -> TWO_NODES,
                NOT_STOPPED);
        runningNodes(node(coreName, "running", IMAGE, CORE_HOST, true), node(taskName, "running", IMAGE, TASK_HOST, false));
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 1L, "", "");
        when(lifecycle.probeContainer(taskName)).thenReturn(ContainerLiveness.UNKNOWN);
        AtomicLong taskGone = new AtomicLong();
        doAnswer(removing -> {
            taskGone.set(clock.get());
            return null;
        }).when(lifecycle).removeIfExistsStrict(taskName);
        long letGo = clock.get();

        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> ticking.resize("j-1", () -> CORE_ONLY));

        // Not on the first round, as a stopped node would: only once its time was up.
        assertTrue(taskGone.get() - letGo > Duration.ofHours(1).toNanos(),
                "gone " + Duration.ofNanos(taskGone.get() - letGo) + " after it was let go");
    }

    @Test
    void aCoreNodeThatStopsBeforeHdfsMovedItsBlocksStartsAgainOnItsData() {
        AtomicLong clock = new AtomicLong();
        EmrClusterStorage ticking = new EmrClusterStorage(lifecycle, containerBuilder, awsEnv, roleCredentials,
                currentContainer, config, s3, Duration.ofSeconds(120),
                () -> clock.getAndAdd(Duration.ofMinutes(30).toNanos()), Runnable::run, Duration.ZERO);
        nodesStart(coreName, taskName);
        ticking.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, () -> TWO_NODES,
                NOT_STOPPED);
        runningNodes(node(coreName, "running", IMAGE, CORE_HOST, true), node(taskName, "running", IMAGE, TASK_HOST, false));
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 1L, "", "");
        // It stops before HDFS has moved its blocks, and runs once started again.
        when(lifecycle.probeContainer(coreName)).thenReturn(ContainerLiveness.NOT_RUNNING, ContainerLiveness.RUNNING);
        clearInvocations(spec, lifecycle);

        assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> ticking.resize("j-1", () -> new ClusterStorage.Yarn(4096, 4).onNodes(List.of(TASK_NODE), 1, null)));

        verify(spec).withName(coreName);
        verify(spec).withNamedVolume(coreName, "/mnt");
        // Its data goes only after that, here once its time was up.
        InOrder order = inOrder(lifecycle);
        order.verify(lifecycle).createAndStart(any());
        order.verify(lifecycle).removeVolume(coreName);
    }

    @Test
    void aRoundThatFailsIsTriedAgainOnTheNext() {
        nodesStart(coreName, taskName);
        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, () -> TWO_NODES,
                NOT_STOPPED);
        runningNodes(node(coreName, "running", IMAGE, CORE_HOST, true), node(taskName, "running", IMAGE, TASK_HOST, false));
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "", "");
        when(lifecycle.probeContainer(taskName)).thenThrow(new IllegalStateException("Docker went away"))
                .thenReturn(ContainerLiveness.NOT_RUNNING);
        clearInvocations(lifecycle);

        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> storage.resize("j-1", () -> CORE_ONLY));

        verify(lifecycle).removeIfExistsStrict(taskName);
        verify(lifecycle).removeVolume(taskName);
    }

    @Test
    void aNodeWhoseDataDockerKeepsStaysToBeRemovedOnTheNextRound() {
        nodesStart(coreName, taskName);
        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, () -> TWO_NODES,
                NOT_STOPPED);
        runningNodes(node(coreName, "running", IMAGE, CORE_HOST, true), node(taskName, "running", IMAGE, TASK_HOST, false));
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "", "");
        when(lifecycle.probeContainer(taskName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.removeVolume(taskName)).thenReturn(false, true);
        clearInvocations(lifecycle);

        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> storage.resize("j-1", () -> CORE_ONLY));

        verify(lifecycle, times(2)).removeVolume(taskName);
        // Its logs are read once, while its container is there.
        verify(docker, times(1)).copyArchiveFromContainerCmd(taskName, "/mnt/yarn/logs");
    }

    @Test
    void aCoreNodeAShutdownCutOffWhileItLeftStartsAgainOnItsDataToBeDecommissioned() {
        List<Runnable> queued = new ArrayList<>();
        EmrClusterStorage queuing = queuingDecommissions(queued);
        nodesStart(coreName, taskName);
        // Its container went with the shutdown, and only its volume says it was a core node.
        nodeVolumes(nodeVolume(coreName, CORE_HOST, true));
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "", "");

        queuing.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null,
                () -> new ClusterStorage.Yarn(4096, 4).onNodes(List.of(TASK_NODE), 1, null), NOT_STOPPED);

        verify(spec).withName(coreName);
        verify(spec).withNamedVolume(coreName, "/mnt");
        verify(lifecycle, never()).removeVolume(coreName);
        String told = commands.get(0).get(2);
        assertTrue(told.contains("printf '" + CORE_HOST + "\\n' > /tmp/floci-hdfs-conf/dfs.exclude.new"), told);
        assertEquals(1, queued.size());
    }

    @Test
    void aTaskNodesDataAShutdownLeftGoesAsItHoldsNoBlocks() {
        nodesStart(coreName);
        nodeVolumes(nodeVolume(taskName, TASK_HOST, false));

        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, () -> CORE_ONLY,
                NOT_STOPPED);

        verify(lifecycle).removeVolume(taskName);
        verify(spec, never()).withName(taskName);
    }

    @Test
    void aNodeWhoseStartATeardownPassedGoes() {
        nodesStart(coreName);
        AtomicLong starts = new AtomicLong();
        when(lifecycle.createAndStart(any())).thenAnswer(starting -> {
            // The second is the core node's, and a shutdown sweeps while Docker creates it.
            if (starts.incrementAndGet() == 2) {
                storage.stopManagedContainers();
            }
            return new ContainerLifecycleManager.ContainerInfo("c-1", Map.of());
        });

        assertThrows(ClusterStorage.Stopped.class, () -> storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                "floci-network", null, () -> CORE_ONLY, NOT_STOPPED));

        // Before it was created, by the sweep, and once it was.
        verify(lifecycle, times(3)).removeIfExistsStrict(coreName);
    }

    @Test
    void aNodeWantedAgainWhileItLeavesCarriesOn() {
        List<Runnable> queued = new ArrayList<>();
        EmrClusterStorage queuing = queuingDecommissions(queued);
        nodesStart(coreName, taskName);
        queuing.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, () -> TWO_NODES,
                NOT_STOPPED);
        runningNodes(node(coreName, "running", IMAGE, CORE_HOST, true), node(taskName, "running", IMAGE, TASK_HOST, false));
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "", "");
        queuing.resize("j-1", () -> CORE_ONLY);
        assertEquals(1, queued.size());
        clearInvocations(lifecycle);

        queuing.resize("j-1", () -> TWO_NODES);
        queued.get(0).run();

        assertTrue(commands.get(1).get(2).contains("printf '' > /tmp/floci-hdfs-conf/yarn.exclude.new"),
                commands.get(1).get(2));
        verify(lifecycle, never()).removeIfExistsStrict(taskName);
        verify(lifecycle, never()).removeVolume(taskName);
    }

    @Test
    void oneThreadDecommissionsACluster() {
        List<Runnable> queued = new ArrayList<>();
        EmrClusterStorage queuing = queuingDecommissions(queued);
        nodesStart(coreName, taskName);
        queuing.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, () -> TWO_NODES,
                NOT_STOPPED);
        runningNodes(node(coreName, "running", IMAGE, CORE_HOST, true), node(taskName, "running", IMAGE, TASK_HOST, false));
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "", "");

        queuing.resize("j-1", () -> CORE_ONLY);
        queuing.resize("j-1", () -> new ClusterStorage.Yarn(4096, 4).onNodes(List.of(), 1, null));

        assertEquals(1, queued.size());
    }

    @Test
    void aClusterThatEndsWhileANodeLeavesEndsItsDecommissioning() {
        List<Runnable> queued = new ArrayList<>();
        EmrClusterStorage queuing = queuingDecommissions(queued);
        nodesStart(coreName, taskName);
        queuing.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, () -> TWO_NODES,
                NOT_STOPPED);
        runningNodes(node(coreName, "running", IMAGE, CORE_HOST, true), node(taskName, "running", IMAGE, TASK_HOST, false));
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "", "");
        queuing.resize("j-1", () -> CORE_ONLY);
        int told = commands.size();

        queuing.remove("j-1");
        queued.get(0).run();

        assertEquals(told, commands.size());
    }

    @Test
    void aClustersEndRemovesItsNodesAndTheirDataBeforeItsNetwork() {
        when(lifecycle.removeNetwork(any())).thenReturn(true);
        when(lifecycle.removeVolume(any())).thenReturn(true);
        runningNodes(node(coreName, "running", IMAGE, CORE_HOST, true), node(taskName, "running", IMAGE, TASK_HOST, false));

        assertTrue(storage.remove("j-1"));

        InOrder order = inOrder(lifecycle);
        order.verify(lifecycle).removeIfExistsStrict(coreName);
        order.verify(lifecycle).removeVolume(coreName);
        order.verify(lifecycle).removeIfExistsStrict(taskName);
        order.verify(lifecycle).removeVolume(taskName);
        order.verify(lifecycle).removeIfExistsStrict(hdfsName);
        order.verify(lifecycle).removeNetwork(hdfsName);
    }

    @Test
    void aNodeDockerWillNotRemoveKeepsItsClustersEndGoing() {
        when(lifecycle.removeNetwork(any())).thenReturn(true);
        when(lifecycle.removeVolume(any())).thenReturn(true);
        runningNodes(node(coreName, "running", IMAGE, CORE_HOST, true));
        doThrow(new IllegalStateException("busy")).when(lifecycle).removeIfExistsStrict(coreName);

        assertFalse(storage.remove("j-1"));
    }

    @Test
    void aClustersEndRemovesTheDataOfANodeAShutdownLeftWithoutItsContainer() {
        when(lifecycle.removeNetwork(any())).thenReturn(true);
        nodeVolumes(nodeVolume(coreName, CORE_HOST, true));

        assertTrue(storage.remove("j-1"));

        verify(lifecycle).removeVolume(coreName);
    }

    @Test
    void aClustersEndThatCannotFindItsNodesRemovesTheRestAndIsAskedForAgain() {
        when(lifecycle.removeNetwork(any())).thenReturn(true);
        when(docker.listContainersCmd()).thenThrow(new IllegalStateException("Docker went away"));

        assertFalse(storage.remove("j-1"));

        verify(lifecycle).removeIfExistsStrict(hdfsName);
        verify(lifecycle).removeNetwork(hdfsName);
        verify(lifecycle).removeVolume(hdfsName);
    }

    @Test
    void aResetRemovesTheNodesAndLeavesTheirDataForTheNextStep() {
        nodesStart(coreName);
        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, () -> CORE_ONLY,
                NOT_STOPPED);
        clearInvocations(lifecycle);

        storage.stopManagedContainers();

        verify(lifecycle).removeIfExistsStrict(coreName);
        verify(lifecycle, never()).removeVolume(coreName);
    }

    @Test
    void theNodesOfAClusterThatHasEndedGoAtStartAndThoseOfOneUpStay() {
        Map<String, Container> byCluster = new LinkedHashMap<>();
        String otherName = ContainerStorageHelper.dockerName(config, "emr-j-2-node-1-1");
        Container gone = nodeOf("j-1", coreName);
        Container up = nodeOf("j-2", otherName);
        byCluster.put(coreName, gone);
        byCluster.put(otherName, up);
        AtomicReference<Map<String, String>> filter = new AtomicReference<>();
        ListContainersCmd list = mock(ListContainersCmd.class, RETURNS_SELF);
        when(docker.listContainersCmd()).thenReturn(list);
        when(list.withLabelFilter(any(Map.class))).thenAnswer(asked -> {
            filter.set(asked.getArgument(0));
            return list;
        });
        when(list.exec()).thenAnswer(reading -> "emr-node".equals(filter.get().get(ContainerStorageHelper.COMPONENT_LABEL))
                ? List.copyOf(byCluster.values()) : List.of());
        ListNetworksCmd networks = mock(ListNetworksCmd.class, RETURNS_SELF);
        when(docker.listNetworksCmd()).thenReturn(networks);
        when(networks.exec()).thenReturn(List.of());
        ListVolumesCmd volumes = mock(ListVolumesCmd.class, RETURNS_SELF);
        when(docker.listVolumesCmd()).thenReturn(volumes);
        when(volumes.exec()).thenReturn(mock(ListVolumesResponse.class));

        storage.removeAllBut(Set.of("j-2"));

        verify(lifecycle).removeIfExistsStrict(coreName);
        verify(lifecycle, never()).removeIfExistsStrict(otherName);
    }

    @Test
    void aStoppedNodesLogsComeFromTheWholeFolderOnceAndNeverItsLaunchScript() throws IOException {
        runningNodes(node(taskName, "exited", IMAGE, TASK_HOST, false));
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "", "");
        // A decommissioned node's NodeManager exits, which ends its container, and Docker refuses a command there.
        when(lifecycle.probeContainer(taskName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        String container = "logs/application_1_0001/container_1_0001_01_000002/";
        byte[] logs = tar(Map.of(container + "stdout", "from task", container + "launch_container.sh", "secret"));
        CopyArchiveFromContainerCmd copy = mock(CopyArchiveFromContainerCmd.class);
        when(copy.exec()).thenAnswer(reading -> new ByteArrayInputStream(logs));
        when(docker.copyArchiveFromContainerCmd(taskName, "/mnt/yarn/logs")).thenReturn(copy);

        storage.archiveContainerLogs("j-1", "123456789012", "us-east-1", "s3://logs/j-1/containers/");
        storage.archiveContainerLogs("j-1", "123456789012", "us-east-1", "s3://logs/j-1/containers/");

        verify(s3, times(1)).putObjectFromStream(eq("logs"),
                eq("j-1/containers/application_1_0001/container_1_0001_01_000002/stdout.gz"), any(), anyString(), any());
        verify(s3, times(1)).putObjectFromStream(anyString(), anyString(), any(), anyString(), any());
        verify(docker, never()).execCreateCmd(taskName);
    }

    @Test
    void aStoppedPrimaryNodeWithNoNodeManagerHasNoLogsAndNoFailure() {
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(docker.copyArchiveFromContainerCmd(hdfsName, "/mnt/yarn/logs")).thenThrow(new NotFoundException("none"));

        assertDoesNotThrow(() -> storage.archiveContainerLogs("j-1", "123456789012", "us-east-1",
                "s3://logs/j-1/containers/"));
    }

    @Test
    void nodesThatCannotBeFoundLeaveTheStepAsItEnded() {
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(docker.copyArchiveFromContainerCmd(hdfsName, "/mnt/yarn/logs")).thenThrow(new NotFoundException("none"));
        when(docker.listContainersCmd()).thenThrow(new IllegalStateException("Docker went away"));

        assertDoesNotThrow(() -> storage.archiveContainerLogs("j-1", "123456789012", "us-east-1",
                "s3://logs/j-1/containers/"));
    }

    @Test
    void containerLogsComeFromEveryNode() throws IOException {
        runningNodes(node(coreName, "running", IMAGE, CORE_HOST, true), node(taskName, "running", IMAGE, TASK_HOST, false));
        // The primary node runs no NodeManager, so it lists none.
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "", "");
        nodeLogs(coreName, Map.of("application_1_0001/container_1_0001_01_000001/stdout", "from core"));
        nodeLogs(taskName, Map.of("application_1_0001/container_1_0001_01_000002/stdout", "from task"));

        storage.archiveContainerLogs("j-1", "123456789012", "us-east-1", "s3://logs/j-1/containers/");

        verify(s3, times(2)).putObjectFromStream(anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void aClustersHdfsStartsAsRootFromTheReleasesImageOnAVolumeOfItsOwnAndAnswersAtItsAddressOnceReady() {
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        when(lifecycle.probeContainer("c-1")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp("c-1", hdfsName)).thenReturn("172.18.0.5");
        // Not ready the first time it is looked at, then ready.
        List<List<String>> looks = ContainerExecStubs.completeEveryExec(docker, "c-1", List.of(1L, 0L), "", "");

        SparkJobRunner.Storage started = storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                "floci-network", null, null, NOT_STOPPED);

        SparkJobRunner.Storage volumes = storage.volumes("j-1");
        assertEquals(volumes.withHdfs(HOST, "172.18.0.5", hdfsName), started);
        verify(spec).withName(hdfsName);
        verify(spec).withUser("root");
        verify(spec).withEnv("FLOCI_HDFS_HOST", HOST);
        verify(spec).withEnv("FLOCI_HDFS_BIND", "0.0.0.0");
        // Not the cluster's /mnt volume, which Docker fills from the image of the first container to mount it: a
        // step's, so what the bootstrap left there comes with it.
        verify(spec).withNamedVolume(hdfsName, "/mnt");
        verify(lifecycle).ensureVolume(eq(hdfsName), argThat(labels ->
                "emr-storage".equals(labels.get(ContainerStorageHelper.COMPONENT_LABEL))
                        && "j-1".equals(labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL))));
        // On a network of the cluster's own, which only its steps join, not the one they run on, where other
        // clusters' steps would reach it.
        verify(lifecycle).ensureNetwork(eq(hdfsName), argThat(labels ->
                "emr-hdfs".equals(labels.get(ContainerStorageHelper.COMPONENT_LABEL))
                        && "j-1".equals(labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL))));
        verify(spec).withDockerNetwork(Optional.of(hdfsName));
        verify(spec).withLabels(argThat(labels -> "emr-hdfs".equals(labels.get(ContainerStorageHelper.COMPONENT_LABEL))
                && "j-1".equals(labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL))));
        // Its host name leads to the container's own address, where the DataNode registers, unless it listens on one
        // address only, which its name then leads to.
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).startsWith(String.join("\n",
                "address=$FLOCI_HDFS_BIND; [ \"$address\" != 0.0.0.0 ] || address=$(hostname -i | awk '{print $1}')",
                "echo \"$address $FLOCI_HDFS_HOST\" >> /etc/hosts || exit\n"))));
        // Every one of its daemons' listeners is on that address.
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(String.join(" ",
                "\"<property><name>dfs.namenode.rpc-bind-host</name><value>$FLOCI_HDFS_BIND</value></property>\"",
                "\"<property><name>dfs.namenode.http-address</name><value>$FLOCI_HDFS_BIND:9870</value></property>\"",
                "\"<property><name>dfs.datanode.address</name><value>$FLOCI_HDFS_BIND:9866</value></property>\"",
                "\"<property><name>dfs.datanode.http.address</name><value>$FLOCI_HDFS_BIND:9864</value></property>\"",
                "\"<property><name>dfs.datanode.ipc.address</name>"
                        + "<value>$FLOCI_HDFS_BIND:9867</value></property>\""))));
        assertEquals(List.of(List.of(READY.split(" ")), List.of(READY.split(" "))), looks);
        // Its daemons' warnings and errors in the container's output.
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(
                "printf '%s\\n' 'log4j.rootLogger=WARN,console'"
                        + " 'log4j.appender.console=org.apache.log4j.ConsoleAppender'")));
        // The NameNode answering on its port is its own, the one its format gave the ClusterId, not another
        // cluster's sharing its namespace.
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(
                "cid=$(sed -n \"s/^clusterID=//p\" /mnt/namenode/current/VERSION);"
                        + " hdfs namenode & n=1; if [ -z \"$FLOCI_NODE_WORKERS\" ] || [ -d /mnt/datanode/current ];"
                        + " then hdfs datanode & n=2; fi; dirs=\"/tmp /user/hadoop\";")
                && entrypoint.get(2).contains(String.join(" ",
                "until curl -sf -m 5 \"http://127.0.0.1:9870/jmx?qry=Hadoop:service=NameNode,name=NameNodeInfo\"",
                "| grep -q \"\\\"ClusterId\\\" : \\\"$cid\\\"\"; do",
                "[ \"$(jobs -rp | wc -l)\" = $n ] || exit 1; sleep 0.2; done;"))));
        // The image is there before the start's deadline.
        verify(lifecycle).resolveImageForLaunch(IMAGE, ImagePullBehavior.PREFER_CACHED, null);
        // Ready once the DataNode has joined and the NameNode has left safe mode, as its JMX says; a daemon that
        // exits ends it.
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(String.join(" ",
                "until curl -sf -m 5 \"http://127.0.0.1:9870/jmx?qry=Hadoop:service=NameNode,name=FSNamesystemState\"",
                "| grep -q \"\\\"NumLiveDataNodes\\\" : [1-9]\"; do",
                "[ \"$(jobs -rp | wc -l)\" = $n ] || exit 1; sleep 0.2; done;",
                "until curl -sf -m 5 \"http://127.0.0.1:9870/jmx?qry=Hadoop:service=NameNode,name=NameNodeInfo\"",
                "| grep -q \"\\\"Safemode\\\" : \\\"\\\"\"; do",
                "[ \"$(jobs -rp | wc -l)\" = $n ] || exit 1; sleep 0.2; done;",
                "hdfs dfs -mkdir -p $dirs || exit 1;"))
                && entrypoint.get(2).contains(String.join(" ", "fi;", "touch /tmp/floci-hdfs-ready;",
                // Not wait -n, which an emr-6.x image's bash, 4.2, does not have.
                "while [ \"$(jobs -rp | wc -l)\" = $n ]; do sleep 1; done;",
                "rm -f /tmp/floci-hdfs-ready; kill $(jobs -rp) 2>/dev/null; exit 1'"))));
        // A cluster without Hive runs no metastore service, and its primary node does not talk to Floci.
        verify(spec, never()).withEnv(eq("FLOCI_HIVE_METASTORE"), anyString());
        verify(spec, never()).withJavaTrustStore();
        assertNull(started.hiveMetastore());
        // One left stopped by a restart goes first.
        InOrder order = inOrder(lifecycle);
        order.verify(lifecycle).removeIfExistsStrict(hdfsName);
        order.verify(lifecycle).ensureVolume(eq(hdfsName), any());
        order.verify(lifecycle).ensureNetwork(eq(hdfsName), any());
        order.verify(lifecycle).createAndStart(any());
    }

    @Test
    void onTheHostsNetworkAStepReachesItsClustersHdfsAtTheHostsLoopback() {
        when(containerBuilder.resolveDockerNetwork(Optional.of("host"))).thenReturn(Optional.of("host"));
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.RUNNING);
        runningOn(hdfsName, "host");
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "", "");

        SparkJobRunner.Storage started = storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                "host", null, null, NOT_STOPPED);

        assertEquals("127.0.0.1", started.hdfsAddress());
        // Steps share the host's network with it, so they join no other.
        assertNull(started.hdfsNetwork());
        verify(lifecycle, never()).createAndStart(any());
    }

    @Test
    void onTheHostsNetworkAClustersHdfsListensOnlyOnTheHostsLoopback() {
        when(containerBuilder.resolveDockerNetwork(Optional.of("host"))).thenReturn(Optional.of("host"));
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        when(lifecycle.probeContainer("c-1")).thenReturn(ContainerLiveness.RUNNING);
        ContainerExecStubs.completeEveryExec(docker, "c-1", 0L, "", "");

        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "host", null, null, NOT_STOPPED);

        // The host's other interfaces would otherwise serve its files, without permissions, to anyone reaching them.
        verify(spec).withEnv("FLOCI_HDFS_BIND", "127.0.0.1");
        verify(spec).withDockerNetwork(Optional.of("host"));
        verify(lifecycle, never()).ensureNetwork(anyString(), any());
    }

    @Test
    void inAContainersNamespaceItsClustersHdfsSharesItAndListensOnItsLoopback() {
        when(containerBuilder.resolveDockerNetwork(Optional.empty())).thenReturn(Optional.of("container:floci"));
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-2", Map.of()));
        when(lifecycle.probeContainer("c-2")).thenReturn(ContainerLiveness.RUNNING);
        ContainerExecStubs.completeEveryExec(docker, "c-2", 0L, "", "");

        SparkJobRunner.Storage started = storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, null, null, null,
                NOT_STOPPED);

        // Docker joins a container placed in another's namespace to no other network, so its steps reach it there.
        assertEquals("127.0.0.1", started.hdfsAddress());
        assertNull(started.hdfsNetwork());
        verify(spec).withDockerNetwork(Optional.of("container:floci"));
        verify(spec).withEnv("FLOCI_HDFS_BIND", "127.0.0.1");
        verify(lifecycle, never()).ensureNetwork(anyString(), any());
    }

    @Test
    void aClusterThatRunsYarnHasItsPrimaryNodeRunAResourceManagerAndANodeManagerWithItsHdfs() {
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-7", Map.of()));
        when(lifecycle.probeContainer("c-7")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp("c-7", hdfsName)).thenReturn("172.18.0.8");
        ContainerExecStubs.completeEveryExec(docker, "c-7", 0L, "", "");
        // Floci on the host, reached at the host's address.
        when(awsEnv.flociEndpoint(false)).thenReturn("http://host.docker.internal:4566");

        SparkJobRunner.Storage started = storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                "floci-network", null, () -> new ClusterStorage.Yarn(4096, 4), NOT_STOPPED);

        // The steps' Spark submits to the ResourceManager on the primary node, its executors reaching Floci there.
        assertEquals(HOST, started.resourceManager());
        assertEquals("http://host.docker.internal:4566", started.flociEndpoint());
        verify(spec).withEnv("FLOCI_YARN_MEMORY_MB", "4096");
        verify(spec).withEnv("FLOCI_YARN_VCORES", "4");
        verify(spec, never()).withEnv(eq("FLOCI_HADOOP_FOLDER"), any());
        verify(spec).withHostDockerInternalOnLinux();
        verify(spec).withJavaTrustStore();
        verify(spec, never()).withEmbeddedDns();
        // Its configuration names the primary node, listens where HDFS does and offers what it was given.
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(String.join(" ",
                "\"<property><name>yarn.resourcemanager.hostname</name><value>$FLOCI_HDFS_HOST</value></property>\"",
                "\"<property><name>yarn.resourcemanager.bind-host</name><value>$FLOCI_HDFS_BIND</value></property>\"",
                "\"<property><name>yarn.nodemanager.bind-host</name><value>$FLOCI_HDFS_BIND</value></property>\"",
                "\"<property><name>yarn.nodemanager.resource.memory-mb</name>"
                        + "<value>$FLOCI_YARN_MEMORY_MB</value></property>\"",
                "\"<property><name>yarn.nodemanager.resource.cpu-vcores</name>"
                        + "<value>$FLOCI_YARN_VCORES</value></property>\""))
                // Each daemon through a variable Java 8, which refuses these options, ignores.
                && Pattern.compile("JDK_JAVA_OPTIONS=\"--add-opens=java\\.base/java\\.lang=ALL-UNNAMED[^\"]*\""
                        + " yarn resourcemanager & n=\\$\\(\\(n \\+ 1\\)\\); if \\[ -z \"\\$FLOCI_NODE_WORKERS\" \\]; then "
                        + "JDK_JAVA_OPTIONS=\"--add-opens=java\\.base/java\\.lang=ALL-UNNAMED[^\"]*\""
                        + " yarn nodemanager &").matcher(entrypoint.get(2)).find()));
        // Started with the NameNode, as neither needs the HDFS to start; ready once the NodeManager has registered,
        // and the node gone if either daemon exits while starting.
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(
                "yarn nodemanager & n=$((n + 1)); fi; fi; "
                        + "until curl -sf -m 5 \"http://127.0.0.1:9870/jmx?qry=Hadoop:service=NameNode,name=NameNodeInfo\"")
                && entrypoint.get(2).contains("if [ -n \"$FLOCI_YARN_MEMORY_MB\" ]; then "
                        + "until curl -sf -m 5 http://127.0.0.1:8088/ws/v1/cluster/metrics"
                        + " | grep -q \"\\\"activeNodes\\\":[1-9]\"; do "
                        + "[ \"$(jobs -rp | wc -l)\" = $n ] || exit 1; sleep 0.2; done; fi;"
                        + " touch /tmp/floci-hdfs-ready")));
    }

    @Test
    void aNodeWhoseImageKeepsItsHadoopApartRunsEveryDaemonFromIt() {
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-9", Map.of()));
        when(lifecycle.probeContainer("c-9")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp("c-9", hdfsName)).thenReturn("172.18.0.9");
        ContainerExecStubs.completeEveryExec(docker, "c-9", 0L, "", "");
        when(awsEnv.flociEndpoint(false)).thenReturn("http://host.docker.internal:4566");

        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null,
                () -> new ClusterStorage.Yarn(4096, 4).withHadoopFolder("/opt"), NOT_STOPPED);

        verify(spec).withEnv("FLOCI_HADOOP_FOLDER", "/opt");
        // Its homes and commands, before the daemons start as the image's user, who keeps the environment.
        verify(spec).withEntrypoint(argThat(entrypoint -> {
            String script = entrypoint.get(2);
            int export = script.indexOf("export HADOOP_HOME=$h/hadoop HADOOP_COMMON_HOME=$h/hadoop"
                    + " HADOOP_LIBEXEC_DIR=$h/hadoop/libexec HADOOP_HDFS_HOME=$h/hadoop-hdfs"
                    + " HADOOP_MAPRED_HOME=$h/hadoop-mapreduce HADOOP_YARN_HOME=$h/hadoop-yarn"
                    + " PATH=$h/hadoop-hdfs/bin:$h/hadoop-yarn/bin:$h/hadoop/bin:$PATH");
            return script.contains("  h=$FLOCI_HADOOP_FOLDER") && export > 0 && export < script.indexOf("exec setpriv");
        }));
    }

    @Test
    void aClusterThatDoesNotRunYarnHasNoResourceManager() {
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-8", Map.of()));
        when(lifecycle.probeContainer("c-8")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp("c-8", hdfsName)).thenReturn("172.18.0.9");
        ContainerExecStubs.completeEveryExec(docker, "c-8", 0L, "", "");

        SparkJobRunner.Storage started = storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                "floci-network", null, null, NOT_STOPPED);

        assertNull(started.resourceManager());
        assertNull(started.flociEndpoint());
        verify(spec, never()).withEnv(eq("FLOCI_YARN_MEMORY_MB"), anyString());
        verify(spec, never()).withHostDockerInternalOnLinux();
    }

    @Test
    void aPrimaryNodeHasANetworkOfItsOwnUnlessItsStepsHaveNoneOrShareANamespace() {
        when(containerBuilder.resolveDockerNetwork(Optional.of("host"))).thenReturn(Optional.of("host"));
        when(containerBuilder.resolveDockerNetwork(Optional.of("container:floci")))
                .thenReturn(Optional.of("container:floci"));
        when(containerBuilder.resolveDockerNetwork(Optional.of("none"))).thenReturn(Optional.of("none"));

        assertTrue(storage.ownNetwork("floci-network"));
        assertFalse(storage.ownNetwork("host"));
        assertFalse(storage.ownNetwork("container:floci"));
        assertFalse(storage.ownNetwork("none"));
    }

    @Test
    void aPrimaryNodeFoundRunningInAnotherImageThanItsStepsStartsAgain() {
        // An earlier Floci's, from the Hive image, before the node ran in the steps' image.
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.RUNNING);
        runningOn(hdfsName, hdfsName);
        when(docker.inspectContainerCmd(hdfsName).exec().getImageId()).thenReturn("sha256:hive-image");
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-9", Map.of()));
        when(lifecycle.probeContainer("c-9")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp("c-9", hdfsName)).thenReturn("172.18.0.10");
        ContainerExecStubs.completeEveryExec(docker, "c-9", 0L, "", "");

        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, null, NOT_STOPPED);

        verify(lifecycle).removeIfExistsStrict(hdfsName);
        verify(lifecycle).createAndStart(any());
    }

    @Test
    void aNodesContainerLogsGoToTheirS3FolderGzippedOnceEachAndNeverALaunchScript() throws IOException {
        String container = "application_1_0001/container_1_0001_01_000001/";
        nodeLogs(Map.of(container + "stdout", "Pi is roughly 3.14",
                container + "launch_container.sh", "export AWS_SECRET_ACCESS_KEY=secret"));
        List<String> put = new ArrayList<>();
        when(s3.putObjectFromStream(eq("logs"), anyString(), any(), eq("application/x-gzip"), any()))
                .thenAnswer(putting -> {
                    try (InputStream gzipped = new GZIPInputStream(putting.<InputStream>getArgument(2))) {
                        put.add(putting.getArgument(1) + "=" + new String(gzipped.readAllBytes(), UTF_8));
                    }
                    return null;
                });

        storage.archiveContainerLogs("j-1", "123456789012", "us-east-1", "s3://logs/emr/j-1/containers/");
        storage.archiveContainerLogs("j-1", "123456789012", "us-east-1", "s3://logs/emr/j-1/containers/");

        String key = "emr/j-1/containers/application_1_0001/container_1_0001_01_000001/stdout.gz=";
        assertEquals(List.of(key + "Pi is roughly 3.14"), put);
        verify(docker, never()).copyArchiveFromContainerCmd(hdfsName, "/mnt/yarn/logs/" + container + "launch_container.sh");

        // A log that has grown goes again.
        nodeLogs(Map.of(container + "stdout", "Pi is roughly 3.1415"));
        storage.archiveContainerLogs("j-1", "123456789012", "us-east-1", "s3://logs/emr/j-1/containers/");

        assertEquals(List.of(key + "Pi is roughly 3.14", key + "Pi is roughly 3.1415"), put);
    }

    @Test
    void aStepsEndReadsOnlyTheLogsThatChangedAndListsThemWithTheirSizes() throws IOException {
        String earlier = "application_1_0001/container_1_0001_01_000001/stdout";
        String later = "application_1_0002/container_1_0002_01_000001/stdout";
        List<List<String>> listings = nodeLogs(Map.of(earlier, "one"));
        storage.archiveContainerLogs("j-1", "123456789012", "us-east-1", "s3://logs/j-1/containers/");
        // A primary node whose core nodes run the NodeManagers has no such folder, which is none to list and no failure.
        assertEquals(List.of(List.of("bash", "-c", "[ ! -d /mnt/yarn/logs ] || exec find /mnt/yarn/logs -mindepth 3"
                + " -maxdepth 3 -type f -printf '%s %P\\n'")), listings);

        nodeLogs(Map.of(earlier, "one", later, "two"));
        storage.archiveContainerLogs("j-1", "123456789012", "us-east-1", "s3://logs/j-1/containers/");

        verify(docker).copyArchiveFromContainerCmd(hdfsName, "/mnt/yarn/logs/" + earlier);
        verify(docker).copyArchiveFromContainerCmd(hdfsName, "/mnt/yarn/logs/" + later);
        verify(s3, times(2)).putObjectFromStream(anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void twoStepsEndingAtOnceArchiveANodesLogsOneAfterTheOther() throws Exception {
        nodeLogs(Map.of("application_1_0001/container_1_0001_01_000001/stdout", "older"));
        CountDownLatch uploading = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(s3.putObjectFromStream(anyString(), anyString(), any(), anyString(), any())).thenAnswer(putting -> {
            uploading.countDown();
            assertTrue(finish.await(10, TimeUnit.SECONDS));
            return null;
        });
        AtomicReference<Thread> second = new AtomicReference<>();
        ExecutorService steps = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = steps.submit(() -> storage.archiveContainerLogs("j-1", "123456789012", "us-east-1",
                    "s3://logs/j-1/containers/"));
            assertTrue(uploading.await(10, TimeUnit.SECONDS));
            Future<?> later = steps.submit(() -> {
                second.set(Thread.currentThread());
                storage.archiveContainerLogs("j-1", "123456789012", "us-east-1", "s3://logs/j-1/containers/");
            });

            awaitBlocked(second);
            finish.countDown();

            first.get(10, TimeUnit.SECONDS);
            later.get(10, TimeUnit.SECONDS);
        } finally {
            finish.countDown();
            steps.shutdownNow();
        }
    }

    @Test
    void aRemovedNodesContainerLogsAreForgotten() throws IOException {
        nodeLogs(Map.of("application_1_0001/container_1_0001_01_000001/stdout", "out"));

        storage.archiveContainerLogs("j-1", "123456789012", "us-east-1", "s3://logs/j-1/containers/");
        storage.remove("j-1");
        storage.archiveContainerLogs("j-1", "123456789012", "us-east-1", "s3://logs/j-1/containers/");

        verify(s3, times(2)).putObjectFromStream(anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void containerLogsThatCannotBeListedLeaveTheStepAsItEnded() {
        // What it listed before it failed is not to be trusted to be all there is, or to be there at all.
        String log = "application_1_0001/container_1_0001_01_000001/stdout";
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 1L, "3 " + log + "\n", "find: no such folder");

        assertDoesNotThrow(() -> storage.archiveContainerLogs("j-1", "123456789012", "us-east-1",
                "s3://logs/emr/j-1/containers/"));

        verify(docker, never()).copyArchiveFromContainerCmd(hdfsName, "/mnt/yarn/logs/" + log);
    }

    @Test
    void containerLogsThatCannotBeReadLeaveTheStepAsItEnded() {
        String log = "application_1_0001/container_1_0001_01_000001/stdout";
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "3 " + log + "\n", "");
        when(docker.copyArchiveFromContainerCmd(hdfsName, "/mnt/yarn/logs/" + log)).thenThrow(new NotFoundException("gone"));

        assertDoesNotThrow(() -> storage.archiveContainerLogs("j-1", "123456789012", "us-east-1",
                "s3://logs/emr/j-1/containers/"));

        verify(s3, never()).putObjectFromStream(anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void aClusterWithHivesPrimaryNodeRunsItsMetastoreServiceToo() {
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-6", Map.of()));
        when(lifecycle.probeContainer("c-6")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp("c-6", hdfsName)).thenReturn("172.18.0.7");
        ContainerExecStubs.completeEveryExec(docker, "c-6", 0L, "", "");
        // A session for the cluster's instance profile's role, as its steps' S3 would have.
        Optional<SessionCreds> session = Optional.of(new SessionCreds("ASIAMETASTORE", "secret", "token"));
        when(roleCredentials.forRole("arn:aws:iam::123456789012:role/EMR_EC2_DefaultRole", "123456789012",
                hdfsName)).thenReturn(session);
        // Floci on the host, reached at the host's address.
        when(awsEnv.flociEndpoint(false)).thenReturn("http://host.docker.internal:4566");
        when(awsEnv.sdkBaselineEnv("us-east-1", Optional.empty(), "http://host.docker.internal:4566", session,
                "123456789012")).thenReturn(List.of("AWS_ACCESS_KEY_ID=ASIAMETASTORE"));

        SparkJobRunner.Storage started = storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                "floci-network",
                new ClusterStorage.HiveMetastore("arn:aws:iam::123456789012:role/EMR_EC2_DefaultRole", Map.of()), null,
                NOT_STOPPED);

        assertEquals("thrift://ip-10-0-0-1.ec2.internal:9083", started.hiveMetastore());
        verify(spec).withEnv(List.of("AWS_ACCESS_KEY_ID=ASIAMETASTORE"));
        verify(spec).withHostDockerInternalOnLinux();
        verify(spec).withJavaTrustStore();
        // Floci's resolver would answer with an address on Floci's network, which the cluster's does not reach.
        verify(spec, never()).withEmbeddedDns();
        verify(lifecycle, never()).ensureConnected(anyString(), anyString(), anyString());
        // Floci's S3 through S3A, as for the cluster's steps.
        verify(spec).withEnv(eq("FLOCI_HIVE_METASTORE"), argThat(settings -> settings.startsWith(
                "--hiveconf fs.s3a.endpoint=http://host.docker.internal:4566"
                        + " --hiveconf fs.s3a.endpoint.region=us-east-1 ")
                && settings.contains(" --hiveconf fs.s3.impl=org.apache.hadoop.fs.s3a.S3AFileSystem ")));
        // Hive's exec jar where a Hive session would upload it, so two at once do not race to, its folder made with
        // the others in one go.
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(
                "if [ -n \"$FLOCI_HIVE_METASTORE\" ]; then dirs=\"$dirs /user/hive/warehouse /tmp/hadoop/.hiveJars\";")
                && entrypoint.get(2).contains("hdfs dfs -mkdir -p $dirs || exit 1; "
                        + "if [ -n \"$FLOCI_HIVE_METASTORE\" ]; then "
                        + "jar=$(ls /usr/lib/hive/lib/hive-exec-[0-9]*.jar | head -1); hdfs dfs -put -f \"$jar\" "
                        + "/tmp/hadoop/.hiveJars/$(basename \"$jar\" .jar)"
                        + "-$(sha256sum \"$jar\" | cut -d\" \" -f1).jar || exit 1; until (exec 3<>/dev/tcp")));
        // Started with the NameNode, as it needs no HDFS to start, and waited for once the HDFS is ready.
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).indexOf("hive --service metastore")
                < entrypoint.get(2).indexOf("until curl -sf -m 5 \"http://127.0.0.1:9870/jmx")));
        // With the Hive image's Hadoop configuration, as Hive steps run; in a folder Derby can write its log in; its
        // database on the primary node's own volume, its warehouse in the HDFS, ready only once it listens, and the
        // node gone if it exits while starting.
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(
                "cd /tmp; LOGS_DIR=/tmp/floci-hive-logs HADOOP_CONF_DIR=/etc/hadoop/conf.hive"
                        + " hive --service metastore -p 9083"
                        + " --hiveconf fs.defaultFS=hdfs://$FLOCI_HDFS_HOST:8020 $FLOCI_HIVE_METASTORE"
                        + " --hiveconf \"javax.jdo.option.ConnectionURL=jdbc:derby:;"
                        + "databaseName=/mnt/var/lib/hive/metastore_db;create=true\"")
                && entrypoint.get(2).contains(
                        "hive.metastore.warehouse.dir=hdfs://$FLOCI_HDFS_HOST:8020/user/hive/warehouse"
                                + " \"${site[@]}\" & n=$((n + 1));")
                && entrypoint.get(2).contains("until (exec 3<>/dev/tcp/127.0.0.1/9083) 2>/dev/null; do "
                        + "[ \"$(jobs -rp | wc -l)\" = $n ] || exit 1; sleep 0.2; done; "
                        + "fi; if [ -n \"$FLOCI_YARN_MEMORY_MB\" ]; then")));

        // A table's location in S3 is the metastore's to look at, with S3A, so it too leaves Hive one SDK first, as
        // root, which owns Hive's lib folder.
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(
                "chown hadoop:hadoop /mnt || exit\n" + EmrServerlessSparkRunner.HIVE_ONE_SDK + "\n")));

        // The session goes with the container.
        storage.remove("j-1");
        verify(roleCredentials).unregister("123456789012", "ASIAMETASTORE");
    }

    @Test
    void aMetastoresSessionGoesWithItsContainerOnAResetOrAShutdownAndWhenItCannotStart() {
        ClusterStorage.HiveMetastore metastore = METASTORE;
        when(roleCredentials.forRole("arn:aws:iam::123456789012:role/r", "123456789012", hdfsName))
                .thenReturn(Optional.of(new SessionCreds("ASIAONE", "s", "t")), Optional.of(new SessionCreds("ASIATWO",
                        "s", "t")));
        when(awsEnv.flociEndpoint(false)).thenReturn("http://host.docker.internal:4566");
        when(awsEnv.sdkBaselineEnv(anyString(), any(), anyString(), any(), anyString())).thenReturn(List.of());
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-8", Map.of()))
                .thenThrow(new IllegalStateException("Could not start the container"));
        when(lifecycle.probeContainer("c-8")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp("c-8", hdfsName)).thenReturn("172.18.0.10");
        ContainerExecStubs.completeEveryExec(docker, "c-8", 0L, "", "");
        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", metastore, null, NOT_STOPPED);

        storage.stopManagedContainers();
        verify(roleCredentials).unregister("123456789012", "ASIAONE");
        assertThrows(IllegalStateException.class, () -> storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                "floci-network", metastore, null, NOT_STOPPED));

        verify(roleCredentials).unregister("123456789012", "ASIATWO");
    }

    @Test
    void aMetastoreStartedAgainAfterItsContainerStoppedReleasesTheSessionOfTheOneBefore() {
        ClusterStorage.HiveMetastore metastore = METASTORE;
        when(roleCredentials.forRole("arn:aws:iam::123456789012:role/r", "123456789012", hdfsName))
                .thenReturn(Optional.of(new SessionCreds("ASIAONE", "s", "t")),
                        Optional.of(new SessionCreds("ASIATWO", "s", "t")));
        when(awsEnv.flociEndpoint(false)).thenReturn("http://host.docker.internal:4566");
        when(awsEnv.sdkBaselineEnv(anyString(), any(), anyString(), any(), anyString())).thenReturn(List.of());
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-8", Map.of()),
                new ContainerLifecycleManager.ContainerInfo("c-9", Map.of()));
        when(lifecycle.probeContainer("c-8")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.probeContainer("c-9")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp("c-8", hdfsName)).thenReturn("172.18.0.10");
        when(lifecycle.resolveContainerNetworkIp("c-9", hdfsName)).thenReturn("172.18.0.11");
        ContainerExecStubs.completeEveryExec(docker, "c-8", 0L, "", "");
        ContainerExecStubs.completeEveryExec(docker, "c-9", 0L, "", "");
        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", metastore, null, NOT_STOPPED);

        // Its metastore exited, which ended the container; the cluster's next step starts it again.
        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", metastore, null, NOT_STOPPED);

        verify(roleCredentials).unregister("123456789012", "ASIAONE");
        verify(roleCredentials, never()).unregister("123456789012", "ASIATWO");
    }

    @Test
    void aMetastoresSessionStaysWhileDockerKeepsItsContainer() {
        ClusterStorage.HiveMetastore metastore = METASTORE;
        when(roleCredentials.forRole("arn:aws:iam::123456789012:role/r", "123456789012", hdfsName))
                .thenReturn(Optional.of(new SessionCreds("ASIAONE", "s", "t")));
        when(awsEnv.flociEndpoint(false)).thenReturn("http://host.docker.internal:4566");
        when(awsEnv.sdkBaselineEnv(anyString(), any(), anyString(), any(), anyString())).thenReturn(List.of());
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-8", Map.of()));
        when(lifecycle.probeContainer("c-8")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp("c-8", hdfsName)).thenReturn("172.18.0.10");
        ContainerExecStubs.completeEveryExec(docker, "c-8", 0L, "", "");
        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", metastore, null, NOT_STOPPED);
        doThrow(new IllegalStateException("Failed to remove container " + hdfsName)).doNothing()
                .when(lifecycle).removeIfExistsStrict(hdfsName);

        storage.stopManagedContainers();
        // Its metastore may still reach S3 with it.
        verify(roleCredentials, never()).unregister(anyString(), anyString());
        storage.stopManagedContainers();

        verify(roleCredentials).unregister("123456789012", "ASIAONE");
    }

    @Test
    void inANamespaceTheStepsShareTheMetastoreServiceDoesNotRunAsItWouldServeEveryInterface() {
        when(containerBuilder.resolveDockerNetwork(Optional.of("host"))).thenReturn(Optional.of("host"));
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        when(lifecycle.probeContainer("c-1")).thenReturn(ContainerLiveness.RUNNING);
        ContainerExecStubs.completeEveryExec(docker, "c-1", 0L, "", "");

        SparkJobRunner.Storage started = storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "host",
                METASTORE, null, NOT_STOPPED);

        // Hive steps keep a metastore of their own.
        assertNull(started.hiveMetastore());
        verify(spec, never()).withEnv(eq("FLOCI_HIVE_METASTORE"), anyString());
        verify(roleCredentials, never()).forRole(anyString(), anyString(), anyString());
    }

    @Test
    void withFlociInAContainerItsContainerJoinsTheClustersNetworkForTheMetastoreToReachItsS3() {
        when(currentContainer.resolveContainerId()).thenReturn(Optional.of("floci-container"));
        when(awsEnv.flociEndpoint(false)).thenReturn("http://172.20.0.2:4566");
        when(awsEnv.sdkBaselineEnv(anyString(), any(), anyString(), any(), anyString())).thenReturn(List.of());
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-6", Map.of()));
        when(lifecycle.probeContainer("c-6")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp("c-6", hdfsName)).thenReturn("172.18.0.7");
        ContainerExecStubs.completeEveryExec(docker, "c-6", 0L, "", "");

        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network",
                METASTORE, null, NOT_STOPPED);

        // Docker keeps the cluster's network apart from Floci's, where Floci's address is.
        InOrder start = inOrder(lifecycle);
        start.verify(lifecycle).ensureNetwork(eq(hdfsName), any());
        start.verify(lifecycle).ensureConnected("floci-container", hdfsName, "floci");
        start.verify(lifecycle).createAndStart(any());
        verify(awsEnv).sdkBaselineEnv(anyString(), any(), eq("http://floci:4566"), any(), anyString());
        verify(spec).withEnv(eq("FLOCI_HIVE_METASTORE"), argThat(settings -> settings.startsWith(
                "--hiveconf fs.s3a.endpoint=http://floci:4566 ")));

        // It leaves the network with the container, so the network can go.
        when(lifecycle.removeNetwork(hdfsName)).thenReturn(true);
        storage.remove("j-1");
        InOrder removal = inOrder(lifecycle);
        removal.verify(lifecycle).removeIfExistsStrict(hdfsName);
        removal.verify(lifecycle).disconnectFromNetwork("floci-container", hdfsName);
        removal.verify(lifecycle).removeNetwork(hdfsName);
    }

    @Test
    void aFlociContainerThatCannotJoinTheClustersNetworkLeavesTheMetastoreFlocisOwnAddress() {
        when(currentContainer.resolveContainerId()).thenReturn(Optional.of("floci-container"));
        doThrow(new DockerException("No such container: floci-container", 404))
                .when(lifecycle).ensureConnected("floci-container", hdfsName, "floci");
        when(awsEnv.flociEndpoint(false)).thenReturn("http://192.168.1.5:4566");
        when(awsEnv.sdkBaselineEnv(anyString(), any(), anyString(), any(), anyString())).thenReturn(List.of());
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-6", Map.of()));
        when(lifecycle.probeContainer("c-6")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp("c-6", hdfsName)).thenReturn("172.18.0.7");
        ContainerExecStubs.completeEveryExec(docker, "c-6", 0L, "", "");

        SparkJobRunner.Storage started = storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                "floci-network", METASTORE, null, NOT_STOPPED);

        // The HDFS still starts, as do its steps' tables there.
        assertEquals("172.18.0.7", started.hdfsAddress());
        verify(spec).withEnv(eq("FLOCI_HIVE_METASTORE"), argThat(settings -> settings.startsWith(
                "--hiveconf fs.s3a.endpoint=http://192.168.1.5:4566 ")));
    }

    @Test
    void aMetastoreThisFlociStartedIsUsedAsItIsHavingFlocisContainerJoinItsNetworkAgain() {
        when(currentContainer.resolveContainerId()).thenReturn(Optional.of("floci-container"));
        when(awsEnv.flociEndpoint(false)).thenReturn("http://172.20.0.2:4566");
        when(awsEnv.sdkBaselineEnv(anyString(), any(), anyString(), any(), anyString())).thenReturn(List.of());
        // Started by this process, then found running by the cluster's next step.
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING, ContainerLiveness.RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-6", Map.of()));
        when(lifecycle.probeContainer("c-6")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp(anyString(), eq(hdfsName))).thenReturn("172.18.0.7");
        runningOn(hdfsName, hdfsName);
        ContainerExecStubs.completeEveryExec(docker, "c-6", 0L, "", "");
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "", "");
        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", METASTORE, null, NOT_STOPPED);

        SparkJobRunner.Storage found = storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                "floci-network", METASTORE, null, NOT_STOPPED);

        assertEquals("thrift://ip-10-0-0-1.ec2.internal:9083", found.hiveMetastore());
        verify(lifecycle, times(1)).createAndStart(any());
        // In case Floci's container has left the network since.
        verify(lifecycle, times(2)).ensureConnected("floci-container", hdfsName, "floci");
    }

    @Test
    void aMetastoreAnEarlierFlociLeftRunningStartsAgainWithASessionOfThisOnes() {
        // Its session went with the process that started it.
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.RUNNING);
        runningOn(hdfsName, hdfsName);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-9", Map.of()));
        when(lifecycle.probeContainer("c-9")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp("c-9", hdfsName)).thenReturn("172.18.0.9");
        ContainerExecStubs.completeEveryExec(docker, "c-9", 0L, "", "");
        Optional<SessionCreds> session = Optional.of(new SessionCreds("ASIANEW", "s", "t"));
        when(roleCredentials.forRole("arn:aws:iam::123456789012:role/r", "123456789012", hdfsName)).thenReturn(session);
        when(awsEnv.flociEndpoint(false)).thenReturn("http://host.docker.internal:4566");
        when(awsEnv.sdkBaselineEnv("us-east-1", Optional.empty(), "http://host.docker.internal:4566", session,
                "123456789012")).thenReturn(List.of("AWS_ACCESS_KEY_ID=ASIANEW"));

        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", METASTORE, null, NOT_STOPPED);

        InOrder order = inOrder(lifecycle);
        order.verify(lifecycle).removeIfExistsStrict(hdfsName);
        order.verify(lifecycle).createAndStart(any());
        verify(spec).withEnv(List.of("AWS_ACCESS_KEY_ID=ASIANEW"));
        // On the data its volume keeps.
        verify(lifecycle, never()).removeVolume(anyString());
    }

    @Test
    void aMetastoreStartedWhileFlocisContainerCouldNotJoinItsNetworkStartsAgainOnceItCan() {
        when(currentContainer.resolveContainerId()).thenReturn(Optional.of("floci-container"));
        doThrow(new DockerException("The daemon is busy", 500)).doNothing()
                .when(lifecycle).ensureConnected("floci-container", hdfsName, "floci");
        when(awsEnv.flociEndpoint(false)).thenReturn("http://172.20.0.2:4566");
        when(awsEnv.sdkBaselineEnv(anyString(), any(), anyString(), any(), anyString())).thenReturn(List.of());
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING, ContainerLiveness.RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-6", Map.of()),
                new ContainerLifecycleManager.ContainerInfo("c-7", Map.of()));
        when(lifecycle.probeContainer("c-6")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.probeContainer("c-7")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp(anyString(), eq(hdfsName))).thenReturn("172.18.0.7");
        runningOn(hdfsName, hdfsName);
        ContainerExecStubs.completeEveryExec(docker, "c-6", 0L, "", "");
        ContainerExecStubs.completeEveryExec(docker, "c-7", 0L, "", "");
        // Given Floci's address on Floci's network, which the cluster's does not reach.
        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", METASTORE, null, NOT_STOPPED);

        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", METASTORE, null, NOT_STOPPED);

        // A join cannot change the address it was given, so it starts again with Floci's on its network.
        verify(lifecycle, times(2)).createAndStart(any());
        // It had no session to give back: the role has none here.
        verify(roleCredentials, never()).unregister(any(), any());
        verify(spec).withEnv(eq("FLOCI_HIVE_METASTORE"), argThat(settings -> settings.startsWith(
                "--hiveconf fs.s3a.endpoint=http://floci:4566 ")));
    }

    @Test
    void aMetastoreThatFailsToStartHasFlocisContainerLeaveItsNetwork() {
        when(currentContainer.resolveContainerId()).thenReturn(Optional.of("floci-container"));
        when(awsEnv.flociEndpoint(false)).thenReturn("http://172.20.0.2:4566");
        when(awsEnv.sdkBaselineEnv(anyString(), any(), anyString(), any(), anyString())).thenReturn(List.of());
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenThrow(new IllegalStateException("Could not start the container"));

        assertThrows(IllegalStateException.class, () -> storage.hdfs("j-1", "123456789012", "us-east-1", HOST,
                IMAGE, "floci-network", METASTORE, null, NOT_STOPPED));

        InOrder order = inOrder(lifecycle);
        order.verify(lifecycle).ensureConnected("floci-container", hdfsName, "floci");
        order.verify(lifecycle).createAndStart(any());
        order.verify(lifecycle).disconnectFromNetwork("floci-container", hdfsName);
    }

    @Test
    void anHdfsWhoseNetworkDockerKeepsFlocisContainerOnStaysKnownForTheNextTeardown() {
        when(currentContainer.resolveContainerId()).thenReturn(Optional.of("floci-container"));
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp(hdfsName, hdfsName)).thenReturn("172.18.0.9");
        runningOn(hdfsName, hdfsName);
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "", "");
        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, null, NOT_STOPPED);
        when(lifecycle.disconnectFromNetwork("floci-container", hdfsName)).thenReturn(false, true);

        storage.stopManagedContainers();
        storage.stopManagedContainers();
        storage.stopManagedContainers();

        // Kept on it the first time, so still known; off it the second; not asked again the third.
        verify(lifecycle, times(2)).disconnectFromNetwork("floci-container", hdfsName);
        // Nor is a storage whose network Docker keeps it on gone.
        when(lifecycle.disconnectFromNetwork("floci-container", hdfsName)).thenReturn(false);
        when(lifecycle.removeVolume(anyString())).thenReturn(true);
        when(lifecycle.removeNetwork(hdfsName)).thenReturn(true);
        assertFalse(storage.remove("j-1"));
    }

    @Test
    void aMetastoreGetsTheClustersCoreSiteAndHiveSiteAfterItsOwnSettings() {
        when(awsEnv.flociEndpoint(false)).thenReturn("http://host.docker.internal:4566");
        when(awsEnv.sdkBaselineEnv(anyString(), any(), anyString(), any(), anyString())).thenReturn(List.of());
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-6", Map.of()));
        when(lifecycle.probeContainer("c-6")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp("c-6", hdfsName)).thenReturn("172.18.0.7");
        ContainerExecStubs.completeEveryExec(docker, "c-6", 0L, "", "");
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("fs.s3a.endpoint", "http://s3.example:9000");
        settings.put("hive.metastore.warehouse.dir", "s3://bucket/warehouse dir");

        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network",
                new ClusterStorage.HiveMetastore("arn:aws:iam::123456789012:role/r", settings), null, NOT_STOPPED);

        // One to a line, so a value with a space stays one argument.
        verify(spec).withEnv("FLOCI_HIVE_METASTORE_SITE", String.join("\n", "--hiveconf",
                "fs.s3a.endpoint=http://s3.example:9000", "--hiveconf",
                "hive.metastore.warehouse.dir=s3://bucket/warehouse dir"));
        // Last, so each replaces the service's own, its S3 and warehouse among them.
        verify(spec).withEntrypoint(argThat(entrypoint -> entrypoint.get(2).contains(
                "[ -z \"$FLOCI_HIVE_METASTORE_SITE\" ] || mapfile -t site <<< \"$FLOCI_HIVE_METASTORE_SITE\"; cd /tmp;")
                && entrypoint.get(2).contains(
                        "hive.metastore.warehouse.dir=hdfs://$FLOCI_HDFS_HOST:8020/user/hive/warehouse"
                                + " \"${site[@]}\" & n=$((n + 1));")));
    }

    @Test
    void aClustersHdfsStartsFromTheImageIdItsPullResolvedWhichAnotherPullCannotMove() {
        String id = "sha256:" + "a".repeat(64);
        when(lifecycle.resolveImageForLaunch(IMAGE, ImagePullBehavior.PREFER_CACHED, null))
                .thenReturn(new ImageCacheService.LaunchImage(id, null));
        when(containerBuilder.newContainer(id)).thenReturn(spec);
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        when(lifecycle.probeContainer("c-1")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp("c-1", hdfsName)).thenReturn("172.18.0.5");
        ContainerExecStubs.completeEveryExec(docker, "c-1", 0L, "", "");

        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, null, NOT_STOPPED);

        verify(containerBuilder).newContainer(id);
        verify(containerBuilder, never()).newContainer(IMAGE);
    }

    @Test
    void anHdfsAnotherDockerNetworkLeftRunningStartsAgainInTheStepsNamespaceOnItsData() {
        // A process on the cluster's own network left it there, running and ready; this one's steps run on the host's.
        when(containerBuilder.resolveDockerNetwork(Optional.of("host"))).thenReturn(Optional.of("host"));
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.RUNNING);
        runningOn(hdfsName, hdfsName);
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "", "");
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        when(lifecycle.probeContainer("c-1")).thenReturn(ContainerLiveness.RUNNING);
        ContainerExecStubs.completeEveryExec(docker, "c-1", 0L, "", "");

        assertEquals("127.0.0.1", storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "host", null, null,
                NOT_STOPPED).hdfsAddress());

        verify(lifecycle).removeIfExistsStrict(hdfsName);
        verify(lifecycle).createAndStart(any());
        verify(spec).withDockerNetwork(Optional.of("host"));
        verify(spec).withNamedVolume(hdfsName, "/mnt");
    }

    @Test
    void twoClustersStartingTheirHdfsOnTheHostsNetworkAtOnceStartOneOfThem() throws Exception {
        when(containerBuilder.resolveDockerNetwork(Optional.of("host"))).thenReturn(Optional.of("host"));
        String secondName = ContainerStorageHelper.dockerName(config, "emr-j-2-hdfs");
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.probeContainer(secondName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        // Once the first cluster's HDFS is created it is running, on the host's network.
        List<Container> running = new CopyOnWriteArrayList<>();
        ListContainersCmd list = mock(ListContainersCmd.class, RETURNS_SELF);
        when(docker.listContainersCmd()).thenReturn(list);
        when(list.exec()).thenAnswer(listing -> List.copyOf(running));
        Container first = hdfsContainer("c-1", "j-1");
        ContainerHostConfig hostNetwork = mock(ContainerHostConfig.class);
        when(hostNetwork.getNetworkMode()).thenReturn("host");
        when(first.getHostConfig()).thenReturn(hostNetwork);
        when(lifecycle.createAndStart(any())).thenAnswer(creating -> {
            running.add(first);
            return new ContainerLifecycleManager.ContainerInfo("c-1", Map.of());
        });
        when(lifecycle.probeContainer("c-1")).thenReturn(ContainerLiveness.RUNNING);
        ContainerExecStubs.completeEveryExec(docker, "c-1", 0L, "", "");
        // The first's start, past its look for another cluster's, waits until the second has looked too, or waits
        // for it to look.
        AtomicReference<Thread> second = new AtomicReference<>();
        CountDownLatch firstLooked = new CountDownLatch(1);
        ExecutorService steps = Executors.newFixedThreadPool(2);
        try {
            doAnswer(volume -> {
                firstLooked.countDown();
                awaitBlockedOrDone(second);
                return null;
            }).when(lifecycle).ensureVolume(eq(hdfsName), any());
            Future<SparkJobRunner.Storage> starting = steps.submit(
                    () -> storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "host", null, null,
                            NOT_STOPPED));
            assertTrue(firstLooked.await(10, TimeUnit.SECONDS));
            Future<SparkJobRunner.Storage> waiting = steps.submit(() -> {
                second.set(Thread.currentThread());
                return storage.hdfs("j-2", "123456789012", "us-east-1", HOST, IMAGE, "host", null, null, NOT_STOPPED);
            });

            assertEquals("127.0.0.1", starting.get(10, TimeUnit.SECONDS).hdfsAddress());
            ExecutionException refused = assertThrows(ExecutionException.class,
                    () -> waiting.get(10, TimeUnit.SECONDS));

            assertEquals("The HDFS of EMR cluster j-2 cannot start while that of EMR cluster j-1 runs in the network"
                    + " namespace they share", refused.getCause().getMessage());
            verify(lifecycle, times(1)).createAndStart(any());
        } finally {
            steps.shutdownNow();
        }
    }

    @Test
    void anotherClustersHdfsOnItsOwnNetworkDoesNotKeepOneFromStartingOnTheHosts() {
        when(containerBuilder.resolveDockerNetwork(Optional.of("host"))).thenReturn(Optional.of("host"));
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        ListContainersCmd running = mock(ListContainersCmd.class, RETURNS_SELF);
        when(docker.listContainersCmd()).thenReturn(running);
        Container bridged = hdfsContainer("c-other", "j-2");
        ContainerHostConfig ownNetwork = mock(ContainerHostConfig.class);
        when(ownNetwork.getNetworkMode()).thenReturn("emr-j-2-hdfs");
        when(bridged.getHostConfig()).thenReturn(ownNetwork);
        when(running.exec()).thenReturn(List.of(bridged));
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        when(lifecycle.probeContainer("c-1")).thenReturn(ContainerLiveness.RUNNING);
        ContainerExecStubs.completeEveryExec(docker, "c-1", 0L, "", "");

        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "host", null, null, NOT_STOPPED);

        verify(lifecycle).createAndStart(any());
    }

    @Test
    void aStaleHdfsContainerDockerWillNotRemoveFailsTheStartAsSuch() {
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        doThrow(new IllegalStateException("Failed to remove stale container " + hdfsName))
                .when(lifecycle).removeIfExistsStrict(hdfsName);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                        "floci-network", null, null, NOT_STOPPED));

        // Not a name clash on the create that follows.
        assertEquals("Failed to remove stale container " + hdfsName, failure.getMessage());
        verify(lifecycle, never()).createAndStart(any());
    }

    @Test
    void aClustersHdfsWithNoAddressOnItsNetworkFailsTheStep() {
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        when(lifecycle.probeContainer("c-1")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp("c-1", hdfsName)).thenReturn("");
        ContainerExecStubs.completeEveryExec(docker, "c-1", 0L, "", "");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                        "floci-network", null, null, NOT_STOPPED));

        assertEquals("The HDFS of EMR cluster j-1 has no address on Docker network " + hdfsName,
                failure.getMessage());
    }

    @Test
    void aClustersHdfsThatRunsIsUsedAsItIs() {
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp(hdfsName, hdfsName)).thenReturn("172.18.0.6");
        runningOn(hdfsName, hdfsName);
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "", "");

        assertEquals("172.18.0.6", storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                "floci-network", null, null, NOT_STOPPED).hdfsAddress());

        verify(lifecycle, never()).createAndStart(any());
        verify(lifecycle, never()).removeIfExists(anyString());
        verify(lifecycle, never()).removeIfExistsStrict(anyString());
    }

    @Test
    void aClustersHdfsThatDockerCannotTellTheStateOfIsLeftAsItIs() {
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.UNKNOWN);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                        "floci-network", null, null, NOT_STOPPED));

        assertEquals("Could not tell whether the HDFS of EMR cluster j-1 runs", failure.getMessage());
        verify(lifecycle, never()).removeIfExists(anyString());
        verify(lifecycle, never()).removeIfExistsStrict(anyString());
        verify(lifecycle, never()).createAndStart(any());
    }

    @Test
    void aClustersHdfsFoundRunningThatStopsBeforeItIsReadyIsStartedAgainOnItsData() {
        // Found running, then stopped, as a container whose daemon has just exited is.
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.RUNNING, ContainerLiveness.NOT_RUNNING);
        runningOn(hdfsName, hdfsName);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-7", Map.of()));
        when(lifecycle.probeContainer("c-7")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp("c-7", hdfsName)).thenReturn("172.18.0.8");
        ContainerExecStubs.completeEveryExec(docker, "c-7", 0L, "", "");

        assertEquals("172.18.0.8", storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                "floci-network", null, null, NOT_STOPPED).hdfsAddress());

        InOrder order = inOrder(lifecycle);
        order.verify(lifecycle).removeIfExistsStrict(hdfsName);
        order.verify(lifecycle).createAndStart(any());
        verify(spec).withNamedVolume(hdfsName, "/mnt");
    }

    @Test
    void aClustersHdfsThatStopsBeforeItIsReadyFailsWithoutAnExecDockerWouldRefuse() {
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-3", Map.of()));
        when(lifecycle.probeContainer("c-3")).thenReturn(ContainerLiveness.NOT_RUNNING);
        List<List<String>> looks = ContainerExecStubs.completeEveryExec(docker, "c-3", 1L, "", "");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                        "floci-network", null, null, NOT_STOPPED));

        assertEquals("The HDFS of EMR cluster j-1 stopped before it was ready", failure.getMessage());
        assertEquals(List.of(), looks);
        // What it last wrote is in Floci's log, as the container goes with its cluster.
        verify(lifecycle).lastLogLines("c-3", 40, Duration.ofSeconds(5));
    }

    @Test
    void aClustersHdfsThatIsNotReadyInTimeFailsToStart() {
        EmrClusterStorage impatient = new EmrClusterStorage(lifecycle, containerBuilder, awsEnv,
                roleCredentials, currentContainer, config, s3, Duration.ZERO,
                System::nanoTime);
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-4", Map.of()));
        when(lifecycle.probeContainer("c-4")).thenReturn(ContainerLiveness.RUNNING);
        ContainerExecStubs.completeEveryExec(docker, "c-4", 1L, "", "");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> impatient.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                        "floci-network", null, null, NOT_STOPPED));

        assertEquals("The HDFS of EMR cluster j-1 did not start within 0 seconds", failure.getMessage());
        verify(lifecycle).lastLogLines("c-4", 40, Duration.ofSeconds(5));
    }

    @Test
    void aStepWaitingWhileAnotherStartsItsClustersHdfsHasOnlyWhatIsLeftOfTheStartTimeout() throws Exception {
        AtomicLong clock = new AtomicLong();
        long timeout = Duration.ofMinutes(2).toNanos();
        EmrClusterStorage timed = new EmrClusterStorage(lifecycle, containerBuilder, awsEnv,
                roleCredentials, currentContainer, config, s3, Duration.ofMinutes(2),
                clock::get);
        // The first step starts it; the second, which waited for it, finds it running.
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING, ContainerLiveness.RUNNING);
        runningOn(hdfsName, hdfsName);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-6", Map.of()));
        when(lifecycle.probeContainer("c-6")).thenReturn(ContainerLiveness.RUNNING);
        ContainerExecStubs.completeEveryExec(docker, "c-6", 1L, "", "");
        List<List<String>> secondLooks = ContainerExecStubs.completeEveryExec(docker, hdfsName, 1L, "", "");
        CountDownLatch firstLooks = new CountDownLatch(1);
        AtomicReference<Thread> second = new AtomicReference<>();
        // The first step's look lasts until the second waits for it, and past the timeout.
        onEachExec("c-6", () -> {
            firstLooks.countDown();
            awaitBlocked(second);
            clock.addAndGet(timeout + 1);
        });
        // Each of the second's looks takes a minute, which would leave it time for another from a deadline of its own.
        onEachExec(hdfsName, () -> clock.addAndGet(Duration.ofMinutes(1).toNanos()));
        ExecutorService steps = Executors.newFixedThreadPool(2);
        try {
            Future<SparkJobRunner.Storage> first = steps.submit(
                    () -> timed.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                            "floci-network", null, null, NOT_STOPPED));
            assertTrue(firstLooks.await(10, TimeUnit.SECONDS));
            Future<SparkJobRunner.Storage> waiting = steps.submit(() -> {
                second.set(Thread.currentThread());
                return timed.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, null,
                        NOT_STOPPED);
            });

            assertThrows(ExecutionException.class, () -> first.get(10, TimeUnit.SECONDS));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> waiting.get(10, TimeUnit.SECONDS));

            assertEquals("The HDFS of EMR cluster j-1 did not start within 120 seconds",
                    failure.getCause().getMessage());
            assertEquals(1, secondLooks.size());
        } finally {
            steps.shutdownNow();
        }
    }

    @Test
    void aStepWaitingForItsClustersHdfsThroughATeardownStopsAsItsDriverWouldAndStartsItNoMore() {
        AtomicLong clock = new AtomicLong();
        EmrClusterStorage timed = new EmrClusterStorage(lifecycle, containerBuilder, awsEnv,
                roleCredentials, currentContainer, config, s3, Duration.ofMinutes(2),
                clock::get);
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-5", Map.of()));
        when(lifecycle.probeContainer("c-5")).thenReturn(ContainerLiveness.RUNNING);
        ContainerExecStubs.completeEveryExec(docker, "c-5", 1L, "", "");
        // A reset or a shutdown tears it down while it is being looked at; each look takes a minute.
        onEachExec("c-5", () -> {
            timed.stopManagedContainers();
            clock.addAndGet(Duration.ofMinutes(1).toNanos());
        });

        assertThrows(ClusterStorage.Stopped.class,
                () -> timed.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, null,
                        NOT_STOPPED));

        verify(lifecycle, times(1)).createAndStart(any());
        // As it starts, one left from before goes, and the teardown removes it; the step does again, in case it
        // started it after the teardown looked.
        verify(lifecycle, times(3)).removeIfExistsStrict(hdfsName);
        verify(lifecycle, never()).removeIfExists(anyString());
    }

    @Test
    void aStepPastStartingStartsNoHdfsAndRemovesNone() {
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);

        assertThrows(ClusterStorage.Stopped.class,
                () -> storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, null,
                        () -> true));

        verify(lifecycle, never()).createAndStart(any());
        verify(lifecycle, never()).removeIfExists(anyString());
        verify(lifecycle, never()).removeIfExistsStrict(anyString());
        verify(lifecycle, never()).resolveImageForLaunch(anyString(), any(), any());
    }

    @Test
    void theFirstPullOfItsImageIsNoPartOfTheHdfsStartTimeout() {
        AtomicLong clock = new AtomicLong();
        EmrClusterStorage timed = new EmrClusterStorage(lifecycle, containerBuilder, awsEnv,
                roleCredentials, currentContainer, config, s3, Duration.ofMinutes(2),
                clock::get);
        // The Hive image is not there yet, and its pull takes five minutes.
        when(lifecycle.resolveImageForLaunch(IMAGE, ImagePullBehavior.PREFER_CACHED, null)).thenAnswer(pull -> {
            clock.addAndGet(Duration.ofMinutes(5).toNanos());
            return new ImageCacheService.LaunchImage(IMAGE, null);
        });
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-4", Map.of()));
        when(lifecycle.probeContainer("c-4")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp("c-4", hdfsName)).thenReturn("172.18.0.11");
        // Not ready the first time it is looked at, then ready.
        ContainerExecStubs.completeEveryExec(docker, "c-4", List.of(1L, 0L), "", "");

        assertEquals("172.18.0.11", timed.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network",
                null, null,
                NOT_STOPPED).hdfsAddress());
    }

    @Test
    void inANamespaceAnotherClustersHdfsRunsInItsClustersHdfsDoesNotStart() {
        when(containerBuilder.resolveDockerNetwork(Optional.of("host"))).thenReturn(Optional.of("host"));
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        ListContainersCmd running = mock(ListContainersCmd.class, RETURNS_SELF);
        when(docker.listContainersCmd()).thenReturn(running);
        Container other = hdfsContainer("c-other", "j-2");
        ContainerHostConfig hostNetwork = mock(ContainerHostConfig.class);
        when(hostNetwork.getNetworkMode()).thenReturn("host");
        when(other.getHostConfig()).thenReturn(hostNetwork);
        when(running.exec()).thenReturn(List.of(other));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "host", null, null, NOT_STOPPED));

        // Its daemons, on the ports j-2's hold, would never bind, and j-2's would answer for them.
        assertEquals("The HDFS of EMR cluster j-1 cannot start while that of EMR cluster j-2 runs in the network"
                + " namespace they share", failure.getMessage());
        verify(running).withLabelFilter(argThat((Map<String, String> filter) ->
                "emr-hdfs".equals(filter.get(ContainerStorageHelper.COMPONENT_LABEL))));
        verify(lifecycle, never()).createAndStart(any());
    }

    @Test
    void aStepCancelledWhileItsClustersHdfsStartsStopsAndLeavesItToTheClustersOtherSteps() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicLong clock = new AtomicLong();
        EmrClusterStorage timed = new EmrClusterStorage(lifecycle, containerBuilder, awsEnv,
                roleCredentials, currentContainer, config, s3, Duration.ofMinutes(2),
                clock::get);
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-8", Map.of()));
        when(lifecycle.probeContainer("c-8")).thenReturn(ContainerLiveness.RUNNING);
        ContainerExecStubs.completeEveryExec(docker, "c-8", 1L, "", "");
        // The step is cancelled while its HDFS is looked at; each look takes a minute.
        onEachExec("c-8", () -> {
            cancelled.set(true);
            clock.addAndGet(Duration.ofMinutes(1).toNanos());
        });

        assertThrows(ClusterStorage.Stopped.class,
                () -> timed.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                        "floci-network", null, null, cancelled::get));

        // Only one left from before went, as it started; the one it started is the next step's.
        verify(lifecycle, times(1)).removeIfExistsStrict(hdfsName);
    }

    @Test
    void aClustersHdfsThatStopsWhileItsReadinessIsLookedAtIsStartedAgainOnItsData() {
        // Found running, still running when looked at, then stopped, so Docker refuses the exec.
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.RUNNING, ContainerLiveness.RUNNING,
                ContainerLiveness.NOT_RUNNING);
        runningOn(hdfsName, hdfsName);
        when(docker.execCreateCmd(hdfsName)).thenThrow(new ConflictException("Container is not running"));
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-9", Map.of()));
        when(lifecycle.probeContainer("c-9")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp("c-9", hdfsName)).thenReturn("172.18.0.10");
        ContainerExecStubs.completeEveryExec(docker, "c-9", 0L, "", "");

        assertEquals("172.18.0.10", storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                "floci-network", null, null, NOT_STOPPED).hdfsAddress());

        verify(lifecycle).createAndStart(any());
    }

    @Test
    void aClustersHdfsWhoseReadinessDockerCannotLookAtWhileItRunsFailsTheStepAndIsLeftAsItIs() {
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.RUNNING);
        runningOn(hdfsName, hdfsName);
        when(docker.execCreateCmd(hdfsName)).thenThrow(new DockerException("The daemon is busy", 500));

        assertThrows(DockerException.class,
                () -> storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE,
                        "floci-network", null, null, NOT_STOPPED));

        verify(lifecycle, never()).createAndStart(any());
        verify(lifecycle, never()).removeIfExists(anyString());
        verify(lifecycle, never()).removeIfExistsStrict(anyString());
    }

    @Test
    void aClustersHdfsIsTornDownAfterTheDriversThatUseIt() {
        ContainerTeardown drivers = () -> { };

        assertTrue(storage.teardownOrder() > drivers.teardownOrder());
    }

    @Test
    void aClustersStorageGoesWithItsHdfsFirstAsItHoldsTheVolumeOfItsData() {
        SparkJobRunner.Storage volumes = storage.volumes("j-1");
        when(lifecycle.removeVolume(anyString())).thenReturn(true);
        when(lifecycle.removeNetwork(hdfsName)).thenReturn(true);

        assertTrue(storage.remove("j-1"));

        InOrder order = inOrder(lifecycle);
        order.verify(lifecycle).removeIfExistsStrict(hdfsName);
        order.verify(lifecycle).removeNetwork(hdfsName);
        order.verify(lifecycle).removeVolume(hdfsName);
        verify(lifecycle).removeVolume(volumes.home());
        verify(lifecycle).removeVolume(volumes.mnt());
    }

    @Test
    void aClustersStorageThatDockerKeepsAnyOfIsNotGone() {
        when(lifecycle.removeVolume(anyString())).thenReturn(true);
        when(lifecycle.removeNetwork(hdfsName)).thenReturn(true);
        when(lifecycle.removeVolume(hdfsName)).thenReturn(false);

        assertFalse(storage.remove("j-1"));

        // Nor is one whose network Docker keeps.
        when(lifecycle.removeVolume(hdfsName)).thenReturn(true);
        when(lifecycle.removeNetwork(hdfsName)).thenReturn(false);

        assertFalse(storage.remove("j-1"));
    }

    @Test
    void aClustersStorageWhoseHdfsDockerKeepsIsNotGoneAndItsHdfsStaysKnown() {
        when(lifecycle.removeVolume(anyString())).thenReturn(true);
        when(lifecycle.removeNetwork(hdfsName)).thenReturn(true);
        doThrow(new IllegalStateException("Failed to remove stale container " + hdfsName)).doNothing()
                .when(lifecycle).removeIfExistsStrict(hdfsName);
        // Known from a step's start.
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp(hdfsName, hdfsName)).thenReturn("172.18.0.9");
        runningOn(hdfsName, hdfsName);
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "", "");
        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, null, NOT_STOPPED);

        assertFalse(storage.remove("j-1"));
        // The next reset or shutdown removes it.
        storage.stopManagedContainers();

        verify(lifecycle, times(2)).removeIfExistsStrict(hdfsName);
    }

    @Test
    void withNoNetworkItsStepsRunWithoutAnHdfs() {
        when(containerBuilder.resolveDockerNetwork(Optional.of("none"))).thenReturn(Optional.of("none"));

        SparkJobRunner.Storage storageOnly = storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "none",
                null, null,
                NOT_STOPPED);

        // Docker joins a container placed there to no network, the HDFS's included.
        assertEquals(storage.volumes("j-1"), storageOnly);
        verify(lifecycle, never()).resolveImageForLaunch(anyString(), any(), any());
        verify(lifecycle, never()).createAndStart(any());
    }

    @Test
    void anHdfsLeftInAnotherContainersNamespaceStartsAgainInTheStepsOne() {
        when(containerBuilder.resolveDockerNetwork(Optional.of("container:b"))).thenReturn(Optional.of("container:b"));
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.RUNNING);
        runningOn(hdfsName, "container:a");
        containerIdOf("a", "1111");
        containerIdOf("b", "2222");
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "", "");
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        when(lifecycle.probeContainer("c-1")).thenReturn(ContainerLiveness.RUNNING);
        ContainerExecStubs.completeEveryExec(docker, "c-1", 0L, "", "");

        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "container:b", null, null, NOT_STOPPED);

        verify(lifecycle).createAndStart(any());
        verify(spec).withDockerNetwork(Optional.of("container:b"));
    }

    @Test
    void anHdfsInTheSameContainersNamespaceByItsIdIsUsedAsItIs() {
        // Docker records the namespace's container by its id; the steps name it.
        when(containerBuilder.resolveDockerNetwork(Optional.of("container:floci")))
                .thenReturn(Optional.of("container:floci"));
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.RUNNING);
        runningOn(hdfsName, "container:3333");
        containerIdOf("floci", "3333");
        containerIdOf("3333", "3333");
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "", "");

        assertEquals("127.0.0.1", storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "container:floci",
                null, null,
                NOT_STOPPED).hdfsAddress());

        verify(lifecycle, never()).createAndStart(any());
    }

    @Test
    void anotherClustersHdfsInAnotherNamespaceDoesNotKeepOneFromStarting() {
        when(containerBuilder.resolveDockerNetwork(Optional.of("container:b"))).thenReturn(Optional.of("container:b"));
        containerIdOf("b", "2222");
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        ListContainersCmd running = mock(ListContainersCmd.class, RETURNS_SELF);
        when(docker.listContainersCmd()).thenReturn(running);
        Container onHost = hdfsContainer("c-other", "j-2");
        ContainerHostConfig hostNetwork = mock(ContainerHostConfig.class);
        when(hostNetwork.getNetworkMode()).thenReturn("host");
        when(onHost.getHostConfig()).thenReturn(hostNetwork);
        when(running.exec()).thenReturn(List.of(onHost));
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        when(lifecycle.probeContainer("c-1")).thenReturn(ContainerLiveness.RUNNING);
        ContainerExecStubs.completeEveryExec(docker, "c-1", 0L, "", "");

        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "container:b", null, null, NOT_STOPPED);

        verify(lifecycle).createAndStart(any());
    }

    @Test
    void aResetOrAShutdownRemovesTheHdfsItFoundRunningAndKeepsTheVolumeOfItsData() {
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp(hdfsName, hdfsName)).thenReturn("172.18.0.9");
        runningOn(hdfsName, hdfsName);
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "", "");
        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, null, NOT_STOPPED);

        storage.stopManagedContainers();
        storage.stopManagedContainers();

        verify(lifecycle).removeIfExistsStrict(hdfsName);
        verify(lifecycle, never()).removeVolume(anyString());
        verify(lifecycle, never()).removeNetwork(anyString());
    }

    @Test
    void anHdfsDockerKeptThroughATeardownIsRemovedByTheNext() {
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp(hdfsName, hdfsName)).thenReturn("172.18.0.9");
        runningOn(hdfsName, hdfsName);
        ContainerExecStubs.completeEveryExec(docker, hdfsName, 0L, "", "");
        storage.hdfs("j-1", "123456789012", "us-east-1", HOST, IMAGE, "floci-network", null, null, NOT_STOPPED);
        doThrow(new IllegalStateException("Failed to remove container " + hdfsName)).doNothing()
                .when(lifecycle).removeIfExistsStrict(hdfsName);

        storage.stopManagedContainers();
        storage.stopManagedContainers();
        storage.stopManagedContainers();

        // Refused the first time, so still known; removed the second; not asked for again the third.
        verify(lifecycle, times(2)).removeIfExistsStrict(hdfsName);
    }

    @Test
    void aResetOrAShutdownAsksDockerNothingWhenNoClustersHdfsRuns() {
        storage.stopManagedContainers();

        verify(lifecycle, never()).getDockerClient();
        verify(lifecycle, never()).removeIfExistsStrict(anyString());
    }

    @Test
    void theHdfsOfClustersThatAreGoneOrHaveEndedGoesWithTheirStorage() {
        ListContainersCmd list = mock(ListContainersCmd.class, RETURNS_SELF);
        when(docker.listContainersCmd()).thenReturn(list);
        Container kept = hdfsContainer("c-kept", "j-up");
        Container gone = hdfsContainer("c-gone", "j-ended");
        when(list.exec()).thenReturn(List.of(kept, gone));
        ListNetworksCmd networks = mock(ListNetworksCmd.class, RETURNS_SELF);
        when(docker.listNetworksCmd()).thenReturn(networks);
        Network keptNetwork = hdfsNetwork("emr-j-up-hdfs", "j-up");
        Network goneNetwork = hdfsNetwork("emr-j-ended-hdfs", "j-ended");
        when(networks.exec()).thenReturn(List.of(keptNetwork, goneNetwork));
        ListVolumesCmd volumes = mock(ListVolumesCmd.class, RETURNS_SELF);
        when(docker.listVolumesCmd()).thenReturn(volumes);
        when(volumes.exec()).thenReturn(mock(ListVolumesResponse.class));

        storage.removeAllBut(Set.of("j-up"));

        verify(list).withLabelFilter(argThat((Map<String, String> filter) ->
                "emr-hdfs".equals(filter.get(ContainerStorageHelper.COMPONENT_LABEL))));
        verify(lifecycle).removeIfExistsStrict(ContainerStorageHelper.dockerName(config, "emr-j-ended-hdfs"));
        verify(lifecycle, never()).removeIfExists(anyString());
        verify(networks).withFilter(eq("label"), argThat((List<String> filter) ->
                filter.contains(ContainerStorageHelper.COMPONENT_LABEL + "=emr-hdfs")));
        verify(lifecycle).removeNetwork("emr-j-ended-hdfs");
        verify(lifecycle, never()).removeNetwork("emr-j-up-hdfs");
        // The one kept, which an earlier process left, goes with the next reset or shutdown.
        storage.stopManagedContainers();
        verify(lifecycle).removeIfExistsStrict(ContainerStorageHelper.dockerName(config, "emr-j-up-hdfs"));
    }

    @Test
    void anEndedClustersHdfsDockerKeepsThroughTheSweepIsRemovedByTheNextTeardown() {
        String ended = ContainerStorageHelper.dockerName(config, "emr-j-ended-hdfs");
        ListContainersCmd list = mock(ListContainersCmd.class, RETURNS_SELF);
        when(docker.listContainersCmd()).thenReturn(list);
        Container gone = hdfsContainer("c-gone", "j-ended");
        when(list.exec()).thenReturn(List.of(gone));
        ListNetworksCmd networks = mock(ListNetworksCmd.class, RETURNS_SELF);
        when(docker.listNetworksCmd()).thenReturn(networks);
        when(networks.exec()).thenReturn(List.of());
        ListVolumesCmd volumes = mock(ListVolumesCmd.class, RETURNS_SELF);
        when(docker.listVolumesCmd()).thenReturn(volumes);
        when(volumes.exec()).thenReturn(mock(ListVolumesResponse.class));
        doThrow(new IllegalStateException("Failed to remove stale container " + ended)).doNothing()
                .when(lifecycle).removeIfExistsStrict(ended);

        storage.removeAllBut(Set.of());
        storage.stopManagedContainers();
        storage.stopManagedContainers();

        // Refused in the sweep, so still known; removed by the next teardown; not asked for again after.
        verify(lifecycle, times(2)).removeIfExistsStrict(ended);
    }

    @Test
    void theSweepHasFlocisContainerLeaveTheNetworksItRemoves() {
        when(currentContainer.resolveContainerId()).thenReturn(Optional.of("floci-container"));
        ListContainersCmd list = mock(ListContainersCmd.class, RETURNS_SELF);
        when(docker.listContainersCmd()).thenReturn(list);
        when(list.exec()).thenReturn(List.of());
        ListNetworksCmd networks = mock(ListNetworksCmd.class, RETURNS_SELF);
        when(docker.listNetworksCmd()).thenReturn(networks);
        Network keptNetwork = hdfsNetwork("emr-j-up-hdfs", "j-up");
        Network goneNetwork = hdfsNetwork("emr-j-ended-hdfs", "j-ended");
        when(networks.exec()).thenReturn(List.of(keptNetwork, goneNetwork));
        ListVolumesCmd volumes = mock(ListVolumesCmd.class, RETURNS_SELF);
        when(docker.listVolumesCmd()).thenReturn(volumes);
        when(volumes.exec()).thenReturn(mock(ListVolumesResponse.class));

        storage.removeAllBut(Set.of("j-up"));

        // An earlier Floci's container, this one after a restart, joined it, and Docker removes no network in use.
        InOrder order = inOrder(lifecycle);
        order.verify(lifecycle).disconnectFromNetwork("floci-container", "emr-j-ended-hdfs");
        order.verify(lifecycle).removeNetwork("emr-j-ended-hdfs");
        verify(lifecycle, never()).disconnectFromNetwork("floci-container", "emr-j-up-hdfs");
    }

    // The primary node's container logs, by their path under the NodeManager's folder, as it lists and gives them. What
    // it is asked to list.
    private List<List<String>> nodeLogs(Map<String, String> logs) throws IOException {
        return nodeLogs(hdfsName, logs);
    }

    private List<List<String>> nodeLogs(String node, Map<String, String> logs) throws IOException {
        StringBuilder listing = new StringBuilder();
        for (Map.Entry<String, String> log : logs.entrySet()) {
            listing.append(log.getValue().length()).append(' ').append(log.getKey()).append('\n');
            byte[] archive = tar(Map.of(log.getKey().substring(log.getKey().lastIndexOf('/') + 1), log.getValue()));
            CopyArchiveFromContainerCmd copy = mock(CopyArchiveFromContainerCmd.class);
            when(copy.exec()).thenAnswer(reading -> new ByteArrayInputStream(archive));
            when(docker.copyArchiveFromContainerCmd(node, "/mnt/yarn/logs/" + log.getKey())).thenReturn(copy);
        }
        return ContainerExecStubs.completeEveryExec(docker, node, 0L, listing.toString(), "");
    }

    // The primary node starts and answers ready, and so does each node named.
    private void nodesStart(String... names) {
        when(lifecycle.probeContainer(hdfsName)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        when(lifecycle.probeContainer("c-1")).thenReturn(ContainerLiveness.RUNNING);
        when(lifecycle.resolveContainerNetworkIp("c-1", hdfsName)).thenReturn("172.18.0.5");
        ContainerExecStubs.completeEveryExec(docker, "c-1", 0L, "", "");
        for (String name : names) {
            when(lifecycle.probeContainer(name)).thenReturn(ContainerLiveness.RUNNING);
            ContainerExecStubs.completeEveryExec(docker, name, 0L, "", "");
        }
    }

    private void runningNodes(Container... nodes) {
        ListContainersCmd list = mock(ListContainersCmd.class, RETURNS_SELF);
        when(docker.listContainersCmd()).thenReturn(list);
        when(list.exec()).thenReturn(List.of(nodes));
    }

    private static Container node(String name, String state, String image, String host, boolean core) {
        Container container = mock(Container.class);
        when(container.getNames()).thenReturn(new String[]{"/" + name});
        when(container.getState()).thenReturn(state);
        when(container.getImageId()).thenReturn(image);
        when(container.getLabels()).thenReturn(Map.of("io.floci.emr.node-host", host, "io.floci.emr.node-core",
                String.valueOf(core)));
        return container;
    }

    // The volumes of cluster j-1's nodes Docker lists, whatever their containers.
    private void nodeVolumes(InspectVolumeResponse... volumes) {
        ListVolumesCmd list = mock(ListVolumesCmd.class, RETURNS_SELF);
        when(docker.listVolumesCmd()).thenReturn(list);
        ListVolumesResponse response = mock(ListVolumesResponse.class);
        when(response.getVolumes()).thenReturn(List.of(volumes));
        when(list.exec()).thenReturn(response);
    }

    private static InspectVolumeResponse nodeVolume(String name, String host, boolean core) {
        InspectVolumeResponse volume = mock(InspectVolumeResponse.class);
        when(volume.getName()).thenReturn(name);
        when(volume.getLabels()).thenReturn(Map.of(ContainerStorageHelper.RESOURCE_ID_LABEL, "j-1",
                "io.floci.emr.node-host", host, "io.floci.emr.node-core", String.valueOf(core)));
        return volume;
    }

    private static Container nodeOf(String clusterId, String name) {
        Container container = mock(Container.class);
        when(container.getNames()).thenReturn(new String[]{"/" + name});
        when(container.getLabels()).thenReturn(Map.of(ContainerStorageHelper.RESOURCE_ID_LABEL, clusterId));
        return container;
    }

    private EmrClusterStorage queuingDecommissions(List<Runnable> queued) {
        return new EmrClusterStorage(lifecycle, containerBuilder, awsEnv, roleCredentials, currentContainer, config, s3,
                Duration.ofSeconds(120), System::nanoTime, queued::add, Duration.ZERO);
    }

    // The files, by their paths in the archive, as Docker archives a folder.
    private static byte[] tar(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(bytes)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                byte[] content = file.getValue().getBytes(UTF_8);
                TarArchiveEntry entry = new TarArchiveEntry(file.getKey());
                entry.setSize(content.length);
                out.putArchiveEntry(entry);
                out.write(content);
                out.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    // The container name runs with networkMode as its network mode, of the image the steps run in, as Docker inspects
    // it.
    private void runningOn(String name, String networkMode) {
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        when(docker.inspectContainerCmd(name)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);
        when(response.getHostConfig()).thenReturn(HostConfig.newHostConfig().withNetworkMode(networkMode));
        when(response.getImageId()).thenReturn(IMAGE);
    }

    // Docker inspects the container nameOrId as the one of id.
    private void containerIdOf(String nameOrId, String id) {
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        when(docker.inspectContainerCmd(nameOrId)).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);
        when(response.getId()).thenReturn(id);
    }

    // Runs hook as each exec into containerId is made, which then answers as its stubs say.
    private void onEachExec(String containerId, Runnable hook) {
        ExecCreateCmd stubbed = docker.execCreateCmd(containerId);
        when(docker.execCreateCmd(containerId)).thenAnswer(invocation -> {
            hook.run();
            return stubbed;
        });
    }

    // Waits until the thread of thread waits for a lock another holds, or has ended, or a second has passed.
    private static void awaitBlockedOrDone(AtomicReference<Thread> thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            Thread waiting = thread.get();
            if (waiting != null && (waiting.getState() == Thread.State.BLOCKED
                    || waiting.getState() == Thread.State.TERMINATED || waiting.getState() == Thread.State.WAITING)) {
                return;
            }
            Thread.onSpinWait();
        }
    }

    // Waits until the thread of thread waits for a lock another holds.
    private static void awaitBlocked(AtomicReference<Thread> thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.get() == null || thread.get().getState() != Thread.State.BLOCKED) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("The second step never waited for the first");
            }
            Thread.onSpinWait();
        }
    }

    @Test
    void aSweepThatDockerRefusesTheNetworksOfStillRemovesTheVolumes() {
        ListContainersCmd list = mock(ListContainersCmd.class, RETURNS_SELF);
        when(docker.listContainersCmd()).thenReturn(list);
        when(list.exec()).thenReturn(List.of());
        ListNetworksCmd networks = mock(ListNetworksCmd.class, RETURNS_SELF);
        when(docker.listNetworksCmd()).thenReturn(networks);
        when(networks.exec()).thenThrow(new DockerException("The daemon is busy", 500));
        ListVolumesCmd volumes = mock(ListVolumesCmd.class, RETURNS_SELF);
        when(docker.listVolumesCmd()).thenReturn(volumes);
        ListVolumesResponse response = mock(ListVolumesResponse.class);
        InspectVolumeResponse ended = mock(InspectVolumeResponse.class);
        when(ended.getName()).thenReturn("emr-j-ended-mnt");
        when(ended.getLabels()).thenReturn(Map.of(ContainerStorageHelper.RESOURCE_ID_LABEL, "j-ended"));
        when(response.getVolumes()).thenReturn(List.of(ended));
        when(volumes.exec()).thenReturn(response);

        storage.removeAllBut(Set.of("j-up"));

        verify(lifecycle).removeVolume("emr-j-ended-mnt");
    }

    private static Network hdfsNetwork(String name, String clusterId) {
        Network network = mock(Network.class);
        when(network.getName()).thenReturn(name);
        when(network.getLabels()).thenReturn(Map.of(ContainerStorageHelper.RESOURCE_ID_LABEL, clusterId));
        return network;
    }

    private static Container hdfsContainer(String id, String clusterId) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getLabels()).thenReturn(Map.of(ContainerStorageHelper.RESOURCE_ID_LABEL, clusterId));
        return container;
    }
}
