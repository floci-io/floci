package io.github.hectorvent.floci.services.emr;

import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.ListVolumesResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.EcsServiceConfig.ImagePullBehavior;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLiveness;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.emrserverless.SparkJobRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Keeps each EMR cluster's storage in Docker volumes, one for its primary node's {@code /home/hadoop} and one for its
 * {@code /mnt}. Both paths belong to the image's {@code hadoop} user, and Docker gives an empty volume what the image
 * has at the path it is first mounted on, owner included, so the steps, which run as that user, can write to both,
 * and what the cluster's bootstrap left there comes with them. Its HDFS, which every EMR cluster runs (from
 * emr-7.14.0 on, "When you select Trino as the only application at launch, Amazon EMR no longer starts YARN and
 * HDFS", a cluster Floci does not run), is a NameNode and a DataNode in a container of their own, the cluster's
 * primary node's, with their data in a third volume, so that the first container to mount the {@code /mnt} one is a
 * step's, which fills it from the step's image.
 */
@ApplicationScoped
public class EmrClusterStorage implements ClusterStorage, ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(EmrClusterStorage.class);
    private static final String SERVICE = "emr";
    private static final String COMPONENT = "emr-storage";
    private static final String HDFS_COMPONENT = SparkJobRunner.Storage.HDFS_COMPONENT;
    private static final String HDFS_CONF = "/tmp/floci-hdfs-conf";
    private static final String HDFS_READY = "/tmp/floci-hdfs-ready";
    private static final String HOST_NETWORK = "host";
    private static final String CONTAINER_NETWORK_PREFIX = "container:";
    private static final String NO_NETWORK = "none";
    private static final String LOOPBACK = "127.0.0.1";
    // The NameNode's JMX on its web port, which says when the DataNode has joined and safe mode has ended without
    // starting a JVM of hdfs's own each time it is asked; each look is given five seconds, so one the NameNode does not
    // answer while it starts is asked again.
    private static final String NAMENODE_JMX = "http://127.0.0.1:9870/jmx?qry=Hadoop:service=NameNode,name=";
    // How long a cluster's HDFS may take to start, its NameNode formatted the first time, before a step gives up.
    private static final Duration HDFS_START_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration HDFS_POLL_INTERVAL = Duration.ofMillis(500);
    // The primary node's HDFS, started as root: its daemons log their warnings and errors to the container's output,
    // which docker logs shows; its host name, FLOCI_HDFS_HOST, leads to this container's address, where the DataNode
    // registers, so steps reach both by it; /mnt, its data's volume, becomes the image's user's, which runs a NameNode
    // and a DataNode with their data in it, the NameNode formatted the first time, with one copy of each block and
    // without permissions, listening on FLOCI_HDFS_BIND: every address of the container, on the cluster's own network,
    // or, in a namespace the steps share, the host's or a container's, only its loopback, which its name leads to, so
    // no other interface serves files anyone could change; in a container's, the hosts file the name goes in is the
    // one the namespace's containers share, the steps' too. Once the NameNode on its port is this container's own,
    // its ClusterId the one its format gave it, rather than another cluster's in that namespace, the DataNode has
    // joined and the NameNode has left safe mode, which on a restart waits for the blocks the volume holds, the
    // folders EMR's HDFS has for steps, /tmp and the hadoop user's home, are made, and HDFS_READY says it is ready. A
    // daemon that exits, before or after, ends the container, its readiness gone, so the next step starts it again on
    // its data.
    private static final String HDFS_SCRIPT = String.join("\n",
            "address=$FLOCI_HDFS_BIND; [ \"$address\" != 0.0.0.0 ] || address=$(hostname -i | awk '{print $1}')",
            "echo \"$address $FLOCI_HDFS_HOST\" >> /etc/hosts || exit",
            "chown hadoop:hadoop /mnt || exit",
            "mkdir -p " + HDFS_CONF + " || exit",
            "printf '%s\\n' '<configuration>'"
                    + " \"<property><name>fs.defaultFS</name><value>hdfs://$FLOCI_HDFS_HOST:8020</value></property>\""
                    + " '</configuration>' > " + HDFS_CONF + "/core-site.xml",
            "printf '%s\\n' '<configuration>'"
                    + " '<property><name>dfs.namenode.name.dir</name><value>file:///mnt/namenode</value></property>'"
                    + " '<property><name>dfs.datanode.data.dir</name><value>file:///mnt/datanode</value></property>'"
                    + " '<property><name>dfs.replication</name><value>1</value></property>'"
                    + " '<property><name>dfs.permissions.enabled</name><value>false</value></property>'"
                    + " \"<property><name>dfs.namenode.rpc-bind-host</name><value>$FLOCI_HDFS_BIND</value></property>\""
                    + " \"<property><name>dfs.namenode.http-address</name>"
                    + "<value>$FLOCI_HDFS_BIND:9870</value></property>\""
                    + " \"<property><name>dfs.datanode.address</name><value>$FLOCI_HDFS_BIND:9866</value></property>\""
                    + " \"<property><name>dfs.datanode.http.address</name>"
                    + "<value>$FLOCI_HDFS_BIND:9864</value></property>\""
                    + " \"<property><name>dfs.datanode.ipc.address</name>"
                    + "<value>$FLOCI_HDFS_BIND:9867</value></property>\""
                    + " '</configuration>' > " + HDFS_CONF + "/hdfs-site.xml",
            "printf '%s\\n' 'log4j.rootLogger=WARN,console' 'log4j.appender.console=org.apache.log4j.ConsoleAppender'"
                    + " 'log4j.appender.console.layout=org.apache.log4j.PatternLayout'"
                    + " 'log4j.appender.console.layout.ConversionPattern=%d %p %c: %m%n'"
                    + " > " + HDFS_CONF + "/log4j.properties",
            "exec setpriv --reuid=hadoop --regid=hadoop --clear-groups bash -c '"
                    + "[ -d /mnt/namenode/current ] || hdfs namenode -format -nonInteractive || exit; "
                    + "cid=$(sed -n \"s/^clusterID=//p\" /mnt/namenode/current/VERSION); "
                    + "hdfs namenode & hdfs datanode & "
                    + "until curl -sf -m 5 \"" + NAMENODE_JMX + "NameNodeInfo\""
                    + " | grep -q \"\\\"ClusterId\\\" : \\\"$cid\\\"\"; do "
                    + "[ \"$(jobs -rp | wc -l)\" = 2 ] || exit 1; sleep 0.2; done; "
                    + "until curl -sf -m 5 \"" + NAMENODE_JMX + "FSNamesystemState\""
                    + " | grep -q \"\\\"NumLiveDataNodes\\\" : 1,\"; do "
                    + "[ \"$(jobs -rp | wc -l)\" = 2 ] || exit 1; sleep 0.2; done; "
                    + "until curl -sf -m 5 \"" + NAMENODE_JMX + "NameNodeInfo\""
                    + " | grep -q \"\\\"Safemode\\\" : \\\"\\\"\"; do "
                    + "[ \"$(jobs -rp | wc -l)\" = 2 ] || exit 1; sleep 0.2; done; "
                    + "hdfs dfs -mkdir -p /tmp /user/hadoop || exit 1; "
                    + "touch " + HDFS_READY + "; "
                    + "wait -n; rm -f " + HDFS_READY + "; kill $(jobs -rp) 2>/dev/null; exit 1'");

    // One start at a time in a namespace the steps share, whatever the cluster, as it holds one HDFS.
    private final Object sharedNamespaceStarts = new Object();
    // One start at a time for each cluster's HDFS; a step coming while it starts waits, then finds it running.
    private final Map<String, Object> starting = new ConcurrentHashMap<>();
    // The HDFS containers this process has started or found running, by name, which a reset or a shutdown removes.
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerBuilder containerBuilder;
    private final EmulatorConfig config;
    private final Duration hdfsStartTimeout;
    private final LongSupplier nanoTime;
    // How many teardowns, by a reset or a shutdown, have run: a step waiting for its cluster's HDFS through one stops.
    private final AtomicLong teardowns = new AtomicLong();

    @Inject
    public EmrClusterStorage(ContainerLifecycleManager lifecycleManager, ContainerBuilder containerBuilder,
                             EmulatorConfig config) {
        this(lifecycleManager, containerBuilder, config, HDFS_START_TIMEOUT, System::nanoTime);
    }

    EmrClusterStorage(ContainerLifecycleManager lifecycleManager, ContainerBuilder containerBuilder,
                      EmulatorConfig config, Duration hdfsStartTimeout, LongSupplier nanoTime) {
        this.lifecycleManager = lifecycleManager;
        this.containerBuilder = containerBuilder;
        this.config = config;
        this.hdfsStartTimeout = hdfsStartTimeout;
        this.nanoTime = nanoTime;
    }

    @Override
    public SparkJobRunner.Storage volumes(String clusterId) {
        return new SparkJobRunner.Storage(
                ContainerStorageHelper.dockerName(config, SERVICE + "-" + clusterId + "-home"),
                ContainerStorageHelper.dockerName(config, SERVICE + "-" + clusterId + "-mnt"));
    }

    @Override
    public void create(String clusterId, String accountId, String region) {
        Map<String, String> labels = labels(clusterId, accountId, region, COMPONENT);
        SparkJobRunner.Storage storage = volumes(clusterId);
        lifecycleManager.ensureVolume(storage.home(), labels);
        lifecycleManager.ensureVolume(storage.mnt(), labels);
    }

    @Override
    public SparkJobRunner.Storage hdfs(String clusterId, String accountId, String region, String host, String image,
                                       String dockerNetwork, BooleanSupplier stopped) {
        String name = hdfsName(clusterId);
        String network = network(dockerNetwork);
        if (NO_NETWORK.equals(network)) {
            // Its steps have no network, so no HDFS reaches them: they run as without one.
            return volumes(clusterId);
        }
        long teardown = teardowns.get();
        stopIfStopped(clusterId, teardown, stopped);
        // Its image is there first, pulled when missing: the first pull of the Hive image, 2.7 GB, is no part of the
        // HDFS's start, which the deadline gives two minutes from then, a step waiting while another starts it too.
        // It starts from the id that pull resolved, which another pull of the same tag cannot move.
        String imageId = lifecycleManager.resolveImageForLaunch(containerBuilder.resolveImage(image),
                ImagePullBehavior.PREFER_CACHED, null).imageId();
        boolean shared = sharesNamespace(network);
        long deadline = nanoTime.getAsLong() + hdfsStartTimeout.toNanos();
        synchronized (starting.computeIfAbsent(clusterId, id -> new Object())) {
            String containerId = null;
            // One found running whose daemon has just exited stops before it is ready, and is started again, once.
            for (int attempt = 0; containerId == null; attempt++) {
                stopIfStopped(clusterId, teardown, stopped);
                ContainerLiveness liveness = lifecycleManager.probeContainer(name);
                if (liveness == ContainerLiveness.UNKNOWN) {
                    // Docker did not answer: one that may be serving the cluster's other steps is left as it is.
                    throw new IllegalStateException("Could not tell whether the HDFS of EMR cluster " + clusterId
                            + " runs");
                }
                boolean found = liveness == ContainerLiveness.RUNNING && attempt == 0
                        && inStepsNamespace(name, shared ? network : name);
                // Known before it starts, so a teardown while it starts removes it.
                running.add(name);
                String candidate = found ? name : start(clusterId, accountId, region, host, imageId, dockerNetwork);
                if (awaitReady(clusterId, candidate, deadline, teardown, stopped)) {
                    containerId = candidate;
                } else if (!found) {
                    stopIfStopped(clusterId, teardown, stopped);
                    throw new IllegalStateException("The HDFS of EMR cluster " + clusterId
                            + " stopped before it was ready");
                }
            }
            if (shared) {
                return volumes(clusterId).withHdfs(host, LOOPBACK, null);
            }
            return volumes(clusterId).withHdfs(host, address(clusterId, containerId), name);
        }
    }

    // Where a step reaches the cluster's HDFS: its container's address on the cluster's own Docker network.
    private String address(String clusterId, String containerId) {
        String address = lifecycleManager.resolveContainerNetworkIp(containerId, hdfsName(clusterId));
        if (address == null || address.isBlank()) {
            throw new IllegalStateException("The HDFS of EMR cluster " + clusterId
                    + " has no address on Docker network " + hdfsName(clusterId));
        }
        return address;
    }

    // Whether the HDFS container found running is in the namespace the steps run in, as its network mode says: the
    // cluster's own network, the host's, or the same container's; one a process with another docker-network left
    // goes, and starts again on its data.
    private boolean inStepsNamespace(String name, String stepsNetwork) {
        HostConfig hostConfig = lifecycleManager.getDockerClient().inspectContainerCmd(name).exec().getHostConfig();
        String networkMode = hostConfig != null ? hostConfig.getNetworkMode() : null;
        return Objects.equals(namespaceOf(networkMode), namespaceOf(stepsNetwork));
    }

    // The namespace a network mode puts a container in, the same for a container's name and its id: the network,
    // the host's, or the id of the container whose namespace it shares.
    private String namespaceOf(String networkMode) {
        if (networkMode == null || !networkMode.startsWith(CONTAINER_NETWORK_PREFIX)) {
            return networkMode;
        }
        String owner = networkMode.substring(CONTAINER_NETWORK_PREFIX.length());
        try {
            return CONTAINER_NETWORK_PREFIX
                    + lifecycleManager.getDockerClient().inspectContainerCmd(owner).exec().getId();
        } catch (RuntimeException e) {
            return networkMode;
        }
    }

    // The network steps run on: docker-network's, else the global one, else Floci's own.
    private String network(String dockerNetwork) {
        return containerBuilder.resolveDockerNetwork(Optional.ofNullable(dockerNetwork)).orElse(null);
    }

    // Whether the steps run in a network namespace they share, the host's or a container's, which the HDFS shares
    // too, as Docker joins a container placed there to no other network.
    private static boolean sharesNamespace(String network) {
        return HOST_NETWORK.equals(network) || network != null && network.startsWith(CONTAINER_NETWORK_PREFIX);
    }

    // A cluster's HDFS container, started anew on the HDFS data its volume holds, which goes with the cluster's other
    // two; one a restart, a stop or a daemon's exit left goes first. It runs on a Docker network of the cluster's own,
    // which only the cluster's steps join, as its HDFS serves its files to any client that reaches it: the steps of
    // other clusters, of other accounts too, do not. In a namespace the steps share it listens on the loopback alone.
    private String start(String clusterId, String accountId, String region, String host, String imageId,
                         String dockerNetwork) {
        String network = network(dockerNetwork);
        if (!sharesNamespace(network)) {
            return createContainer(clusterId, accountId, region, host, imageId, network, false);
        }
        // One namespace holds one HDFS's ports, whose daemons would answer this one's readiness and its steps: the look
        // for another cluster's and this one's start go together, so two clusters' steps cannot both pass it.
        synchronized (sharedNamespaceStarts) {
            String other = otherRunningHdfs(clusterId, network);
            if (other != null) {
                throw new IllegalStateException("The HDFS of EMR cluster " + clusterId + " cannot start while that of"
                        + " EMR cluster " + other + " runs in the network namespace they share");
            }
            return createContainer(clusterId, accountId, region, host, imageId, network, true);
        }
    }

    // Creates and starts the container start() decided on, in the namespace the steps share or on a network of its
    // cluster's own.
    private String createContainer(String clusterId, String accountId, String region, String host, String imageId,
                                   String network, boolean shared) {
        String name = hdfsName(clusterId);
        // One left stopped goes first, and a removal Docker refuses fails the start as it is, not as a name clash.
        lifecycleManager.removeIfExistsStrict(name);
        lifecycleManager.ensureVolume(name, labels(clusterId, accountId, region, COMPONENT));
        if (!shared) {
            lifecycleManager.ensureNetwork(name, labels(clusterId, accountId, region, HDFS_COMPONENT));
        }
        ContainerSpec spec = containerBuilder.newContainer(imageId)
                .withName(name)
                .withUser("root")
                .withEntrypoint(List.of("/bin/bash", "-c", HDFS_SCRIPT))
                .withEnv("FLOCI_HDFS_HOST", host)
                .withEnv("FLOCI_HDFS_BIND", shared ? LOOPBACK : "0.0.0.0")
                .withEnv("HADOOP_CONF_DIR", HDFS_CONF)
                .withEnv("HADOOP_LOG_DIR", "/tmp/floci-hdfs-logs")
                .withNamedVolume(name, "/mnt")
                .withDockerNetwork(Optional.of(shared ? network : name))
                .withLogRotation()
                .withLabels(labels(clusterId, accountId, region, HDFS_COMPONENT))
                .build();
        String containerId = lifecycleManager.createAndStart(spec).containerId();
        LOG.infov("Started the HDFS of EMR cluster {0} as {1}", clusterId, host);
        return containerId;
    }

    // The cluster of another HDFS container of this deployment running in the namespace network gives, or null.
    private String otherRunningHdfs(String clusterId, String network) {
        for (Container container : lifecycleManager.getDockerClient().listContainersCmd()
                .withLabelFilter(Map.of(ContainerStorageHelper.COMPONENT_LABEL, HDFS_COMPONENT,
                        ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config)))
                .exec()) {
            String other = container.getLabels() != null
                    ? container.getLabels().get(ContainerStorageHelper.RESOURCE_ID_LABEL) : null;
            // Only one in this very namespace holds its ports, not one on its own network or in another namespace.
            String networkMode = container.getHostConfig() != null ? container.getHostConfig().getNetworkMode() : null;
            if (other != null && !other.equals(clusterId)
                    && Objects.equals(namespaceOf(networkMode), namespaceOf(network))) {
                return other;
            }
        }
        return null;
    }

    // Whether the container became ready by the deadline, false when it stopped first, or the step did, or a teardown
    // has run since it asked; a failure to become ready in time is thrown.
    private boolean awaitReady(String clusterId, String containerId, long deadline, long teardown,
                               BooleanSupplier stopped) {
        while (teardowns.get() == teardown && !stopped.getAsBoolean()) {
            // Docker refuses an exec into a container that has stopped, so that is looked at first.
            if (lifecycleManager.probeContainer(containerId) == ContainerLiveness.NOT_RUNNING) {
                return false;
            }
            long exitCode;
            try {
                exitCode = ContainerExec.run(lifecycleManager.getDockerClient(), containerId,
                        new String[]{"test", "-f", HDFS_READY}, 10).exitCode();
            } catch (RuntimeException e) {
                // It may have stopped after it was looked at, which makes Docker refuse the exec.
                if (lifecycleManager.probeContainer(containerId) == ContainerLiveness.NOT_RUNNING) {
                    return false;
                }
                throw e;
            }
            if (exitCode == 0) {
                return true;
            }
            if (nanoTime.getAsLong() >= deadline) {
                throw new IllegalStateException("The HDFS of EMR cluster " + clusterId + " did not start within "
                        + hdfsStartTimeout.toSeconds() + " seconds");
            }
            try {
                Thread.sleep(HDFS_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Stopped waiting for the HDFS of EMR cluster " + clusterId, e);
            }
        }
        return false;
    }

    // A teardown since the step asked for its cluster's HDFS stops the step as it would its driver, and the container
    // goes, one the step started after the teardown looked too; a step past starting otherwise, by a cancel, its
    // cluster's end or a teardown that has not reached the HDFS yet, stops and leaves it to the cluster's other steps.
    private void stopIfStopped(String clusterId, long teardown, BooleanSupplier stopped) {
        if (teardowns.get() != teardown) {
            removeKnown(hdfsName(clusterId));
            throw new ClusterStorage.Stopped("Floci stopped the HDFS of EMR cluster " + clusterId);
        }
        if (stopped.getAsBoolean()) {
            throw new ClusterStorage.Stopped("A step of EMR cluster " + clusterId + " stopped while its HDFS started");
        }
    }

    // The name of a cluster's HDFS container, of the volume its data is in, and of the Docker network it is on.
    private String hdfsName(String clusterId) {
        return ContainerStorageHelper.dockerName(config, SERVICE + "-" + clusterId + "-hdfs");
    }

    private Map<String, String> labels(String clusterId, String accountId, String region, String component) {
        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.resourceIdentityLabels(SERVICE,
                clusterId, accountId, region));
        labels.put(ContainerStorageHelper.COMPONENT_LABEL, component);
        labels.put(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config));
        return labels;
    }

    @Override
    public boolean remove(String clusterId) {
        // Its HDFS goes first, as its container holds the volume of its data and its network, and its lock with it, so
        // the clusters that have ended leave none behind.
        starting.remove(clusterId);
        boolean containerGone = removeKnown(hdfsName(clusterId));
        SparkJobRunner.Storage storage = volumes(clusterId);
        // Each is tried, so one Docker refused does not keep the others.
        boolean networkGone = lifecycleManager.removeNetwork(hdfsName(clusterId));
        boolean homeGone = lifecycleManager.removeVolume(storage.home());
        boolean mntGone = lifecycleManager.removeVolume(storage.mnt());
        return lifecycleManager.removeVolume(hdfsName(clusterId)) && mntGone && homeGone && networkGone
                && containerGone;
    }

    /**
     * Removes the HDFS containers this process has started or found running, so a reset or a shutdown leaves none
     * running; a cluster's next step starts its HDFS again, on the data its volume keeps. A step waiting for one
     * stops, as its driver would, and removes one it started since. This runs after the teardowns of the drivers,
     * which use the HDFS, so theirs stop first.
     */
    @Override
    public void stopManagedContainers() {
        teardowns.incrementAndGet();
        for (String name : List.copyOf(running)) {
            removeKnown(name);
        }
    }

    // Removes an HDFS container, forgetting it only once Docker says it is gone: one Docker keeps stays known, so the
    // next reset or shutdown tries again. Whether it is gone.
    private boolean removeKnown(String name) {
        try {
            lifecycleManager.removeIfExistsStrict(name);
            running.remove(name);
            return true;
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not remove the HDFS container {0}: {1}", name, e.getMessage());
            return false;
        }
    }

    @Override
    public int teardownOrder() {
        return 1;
    }

    @Override
    public void removeAllBut(Set<String> kept) {
        // Each sweep on its own, so Docker refusing one list does not keep the others from going.
        try {
            for (Container container : lifecycleManager.getDockerClient().listContainersCmd().withShowAll(true)
                    .withLabelFilter(Map.of(ContainerStorageHelper.COMPONENT_LABEL, HDFS_COMPONENT,
                            ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config)))
                    .exec()) {
                Map<String, String> labels = container.getLabels() != null ? container.getLabels() : Map.of();
                String clusterId = labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL);
                if (!kept.contains(clusterId)) {
                    // Known first, so one Docker refuses to remove stays for the next reset or shutdown to remove.
                    String name = clusterId != null ? hdfsName(clusterId) : container.getId();
                    running.add(name);
                    removeKnown(name);
                } else {
                    // A cluster still up keeps the HDFS an earlier process left, which a reset or a shutdown removes.
                    running.add(hdfsName(clusterId));
                }
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not remove the HDFS of EMR clusters that have ended: {0}", e.getMessage());
        }
        try {
            for (Network network : lifecycleManager.getDockerClient().listNetworksCmd()
                    .withFilter("label", List.of(ContainerStorageHelper.COMPONENT_LABEL + "=" + HDFS_COMPONENT,
                            ContainerStorageHelper.OWNER_LABEL + "=" + ContainerStorageHelper.ownerIdentity(config)))
                    .exec()) {
                Map<String, String> labels = network.getLabels() != null ? network.getLabels() : Map.of();
                if (!kept.contains(labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL))) {
                    lifecycleManager.removeNetwork(network.getName());
                }
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not remove the HDFS networks of EMR clusters that have ended: {0}", e.getMessage());
        }
        try {
            ListVolumesResponse response = lifecycleManager.getDockerClient().listVolumesCmd()
                    .withFilter("label", List.of(ContainerStorageHelper.COMPONENT_LABEL + "=" + COMPONENT,
                            ContainerStorageHelper.OWNER_LABEL + "=" + ContainerStorageHelper.ownerIdentity(config)))
                    .exec();
            List<InspectVolumeResponse> volumes = response != null && response.getVolumes() != null
                    ? response.getVolumes() : List.of();
            for (InspectVolumeResponse volume : volumes) {
                Map<String, String> labels = volume.getLabels() != null ? volume.getLabels() : Map.of();
                if (!kept.contains(labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL))) {
                    lifecycleManager.removeVolume(volume.getName());
                }
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not remove the storage of EMR clusters that have ended: {0}", e.getMessage());
        }
    }
}
