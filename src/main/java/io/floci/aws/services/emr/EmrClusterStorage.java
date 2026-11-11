package io.floci.aws.services.emr;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.ListVolumesResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.EmulatorConfig.EcsServiceConfig.ImagePullBehavior;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.S3Uri;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLiveness;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.CurrentContainerNetworkResolver;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.emrserverless.EmrServerlessSparkRunner;
import io.floci.aws.services.emrserverless.SparkJobRunner;
import io.floci.aws.services.iam.model.SessionCreds;
import io.floci.aws.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

/**
 * Keeps each EMR cluster's storage in Docker volumes, one for its primary node's {@code /home/hadoop} and one for its
 * {@code /mnt}. Both paths belong to the image's {@code hadoop} user, and Docker gives an empty volume what the image
 * has at the path it is first mounted on, owner included, so the steps, which run as that user, can write to both,
 * and what the cluster's bootstrap left there comes with them. Its HDFS, which every EMR cluster runs (from
 * emr-7.14.0 on, "When you select Trino as the only application at launch, Amazon EMR no longer starts YARN and
 * HDFS", a cluster Floci does not run), is a NameNode and a DataNode in a container of their own, the cluster's
 * primary node's, with their data in a third volume, so that the first container to mount the {@code /mnt} one is a
 * step's, which fills it from the step's image. On a cluster with Hive or HCatalog the same container runs Hive's
 * metastore service, as EMR's primary node does, with its database in that third volume, reaching S3 with a session
 * for the cluster's instance profile's role, which goes with the container.
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
    // The metastore service's --hiveconf settings, Floci's S3 among them; set only on a cluster with Hive or
    // HCatalog.
    private static final String HIVE_METASTORE_VARIABLE = "FLOCI_HIVE_METASTORE";
    // The cluster's core-site and hive-site, as --hiveconf settings one to a line, which the service gets after its
    // own.
    private static final String HIVE_METASTORE_SITE_VARIABLE = "FLOCI_HIVE_METASTORE_SITE";
    private static final int HIVE_METASTORE_PORT = 9083;
    // The folder a Hive session uploads Hive's exec jar to when it is not there, named after its SHA-256, under the
    // image's hive.user.install.directory: the primary node puts it there first, as two sessions uploading it at once
    // can leave one with a jar that changed under it, whose Tez tasks then fail to launch.
    private static final String HIVE_JARS = "/tmp/hadoop/.hiveJars";
    // The memory, in MB, and the virtual cores a cluster's NodeManager offers YARN's containers; set only on a cluster
    // whose primary node runs YARN.
    private static final String YARN_MEMORY_VARIABLE = "FLOCI_YARN_MEMORY_MB";
    private static final String YARN_VCORES_VARIABLE = "FLOCI_YARN_VCORES";
    // The folder of the image holding the Hadoop the node's daemons run from; set only when that is not the image's own.
    private static final String HADOOP_FOLDER_VARIABLE = "FLOCI_HADOOP_FOLDER";
    // The ResourceManager's cluster metrics on its web port, which say when the NodeManager has registered.
    private static final String RESOURCE_MANAGER_METRICS = "http://127.0.0.1:8088/ws/v1/cluster/metrics";
    // The java.base packages Hadoop 3.4's YARN daemons reach into, which Java 17 opens only when told, through
    // JDK_JAVA_OPTIONS, which Java 9 on reads and Java 8, on which a release may run and which refuses the options
    // themselves, ignores.
    private static final List<String> YARN_OPENED_PACKAGES = List.of("java.lang", "java.lang.reflect",
            "java.lang.invoke", "java.util", "java.util.concurrent", "java.util.regex", "java.net", "java.io",
            "java.nio", "sun.nio.ch", "sun.nio.cs", "sun.security.action", "sun.util.calendar");
    // Where a NodeManager keeps its containers' logs, on the node's volume, as <application>/<container>/<file>.
    private static final String YARN_LOGS = "/mnt/yarn/logs";
    private static final int LOG_LISTING_SECONDS = 30;
    // A container's launch script holds its environment, the step's AWS session among it, so it stays on the node.
    private static final String CONTAINER_LAUNCH_SCRIPT = "launch_container.sh";
    // The name Floci's own container has on a cluster's network, which it joins for the metastore service and YARN's
    // executors to reach it.
    private static final String FLOCI_ALIAS = "floci";
    // How long a cluster's HDFS and metastore may take to start, the NameNode formatted the first time, before a step
    // gives up.
    private static final Duration HDFS_START_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration HDFS_POLL_INTERVAL = Duration.ofMillis(500);
    // How much of a node's output is logged when it does not become ready, and how long reading it may take.
    private static final int NODE_LOG_LINES = 40;
    private static final Duration NODE_LOG_READ = Duration.ofSeconds(5);
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
    // folders EMR's HDFS has for steps, /tmp and the hadoop user's home, are made. With HIVE_METASTORE_VARIABLE set,
    // Hive's metastore service starts too, with the Hive image's Hadoop configuration, which the node's image keeps at
    // HIVE_HADOOP_CONF, as Hive steps run, its settings after it: "Hive records metastore information in a MySQL database on the primary node's file system", kept as a Derby
    // database in /mnt, which only the service opens, so any number of steps share it, its log in /tmp, and its
    // warehouse in the HDFS, Hive's exec jar in HIVE_JARS; the cluster's core-site and hive-site, last, replace any of
    // them, as on EMR's primary node. With YARN_MEMORY_VARIABLE set, YARN starts last, as "the primary node runs the
    // YARN ResourceManager service": a ResourceManager and, as on a cluster of one node, a NodeManager, which offers
    // that much memory and YARN_VCORES_VARIABLE's cores to the containers it runs the steps' Spark executors in, its
    // local and log folders in /mnt, Java 17 opening to both the packages they reach into, and the node is ready once
    // the NodeManager has registered. HDFS_READY then says the node is ready. A daemon that exits,
    // before or after, the metastore among them, ends the container, its readiness gone, so the next step starts it
    // again on its data. With HADOOP_FOLDER_VARIABLE set, every daemon runs from the Hadoop in that folder.
    private static final String HDFS_SCRIPT = String.join("\n",
            "address=$FLOCI_HDFS_BIND; [ \"$address\" != 0.0.0.0 ] || address=$(hostname -i | awk '{print $1}')",
            "echo \"$address $FLOCI_HDFS_HOST\" >> /etc/hosts || exit",
            "chown hadoop:hadoop /mnt || exit",
            EmrServerlessSparkRunner.HIVE_ONE_SDK,
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
            "if [ -n \"$" + YARN_MEMORY_VARIABLE + "\" ]; then",
            "  printf '%s\\n' '<configuration>'" + yarnProperties(List.of(
                    "yarn.resourcemanager.hostname", "$FLOCI_HDFS_HOST",
                    "yarn.resourcemanager.bind-host", "$FLOCI_HDFS_BIND",
                    "yarn.nodemanager.bind-host", "$FLOCI_HDFS_BIND",
                    "yarn.nodemanager.resource.memory-mb", "$" + YARN_MEMORY_VARIABLE,
                    "yarn.nodemanager.resource.cpu-vcores", "$" + YARN_VCORES_VARIABLE,
                    "yarn.scheduler.minimum-allocation-mb", "256",
                    "yarn.scheduler.maximum-allocation-mb", "$" + YARN_MEMORY_VARIABLE,
                    "yarn.scheduler.maximum-allocation-vcores", "$" + YARN_VCORES_VARIABLE,
                    "yarn.nodemanager.vmem-check-enabled", "false",
                    "yarn.nodemanager.pmem-check-enabled", "false",
                    "yarn.nodemanager.local-dirs", "/mnt/yarn/local",
                    "yarn.nodemanager.log-dirs", "/mnt/yarn/logs",
                    "yarn.nodemanager.disk-health-checker.max-disk-utilization-per-disk-percentage", "100",
                    "yarn.nodemanager.aux-services", "mapreduce_shuffle"))
                    + " '</configuration>' > " + HDFS_CONF + "/yarn-site.xml || exit",
            "  printf '%s\\n' '<configuration>'" + yarnProperties(List.of(
                    "yarn.scheduler.capacity.root.queues", "default",
                    "yarn.scheduler.capacity.root.default.capacity", "100",
                    "yarn.scheduler.capacity.root.default.maximum-capacity", "100",
                    "yarn.scheduler.capacity.maximum-am-resource-percent", "0.5"))
                    + " '</configuration>' > " + HDFS_CONF + "/capacity-scheduler.xml || exit",
            "fi",
            "printf '%s\\n' 'log4j.rootLogger=WARN,console' 'log4j.appender.console=org.apache.log4j.ConsoleAppender'"
                    + " 'log4j.appender.console.layout=org.apache.log4j.PatternLayout'"
                    + " 'log4j.appender.console.layout.ConversionPattern=%d %p %c: %m%n'"
                    + " > " + HDFS_CONF + "/log4j.properties",
            "if [ -n \"$" + HADOOP_FOLDER_VARIABLE + "\" ]; then",
            "  h=$" + HADOOP_FOLDER_VARIABLE,
            "  export HADOOP_HOME=$h/hadoop HADOOP_COMMON_HOME=$h/hadoop HADOOP_LIBEXEC_DIR=$h/hadoop/libexec"
                    + " HADOOP_HDFS_HOME=$h/hadoop-hdfs HADOOP_MAPRED_HOME=$h/hadoop-mapreduce"
                    + " HADOOP_YARN_HOME=$h/hadoop-yarn PATH=$h/hadoop-hdfs/bin:$h/hadoop-yarn/bin:$h/hadoop/bin:$PATH",
            "fi",
            "exec setpriv --reuid=hadoop --regid=hadoop --clear-groups bash -c '"
                    + "[ -d /mnt/namenode/current ] || hdfs namenode -format -nonInteractive || exit; "
                    + "cid=$(sed -n \"s/^clusterID=//p\" /mnt/namenode/current/VERSION); "
                    + "hdfs namenode & hdfs datanode & n=2; "
                    + "until curl -sf -m 5 \"" + NAMENODE_JMX + "NameNodeInfo\""
                    + " | grep -q \"\\\"ClusterId\\\" : \\\"$cid\\\"\"; do "
                    + "[ \"$(jobs -rp | wc -l)\" = $n ] || exit 1; sleep 0.2; done; "
                    + "until curl -sf -m 5 \"" + NAMENODE_JMX + "FSNamesystemState\""
                    + " | grep -q \"\\\"NumLiveDataNodes\\\" : 1,\"; do "
                    + "[ \"$(jobs -rp | wc -l)\" = $n ] || exit 1; sleep 0.2; done; "
                    + "until curl -sf -m 5 \"" + NAMENODE_JMX + "NameNodeInfo\""
                    + " | grep -q \"\\\"Safemode\\\" : \\\"\\\"\"; do "
                    + "[ \"$(jobs -rp | wc -l)\" = $n ] || exit 1; sleep 0.2; done; "
                    + "hdfs dfs -mkdir -p /tmp /user/hadoop || exit 1; "
                    + "if [ -n \"$" + HIVE_METASTORE_VARIABLE + "\" ]; then "
                    + "hdfs dfs -mkdir -p /user/hive/warehouse " + HIVE_JARS + " || exit 1; "
                    + "jar=$(ls /usr/lib/hive/lib/hive-exec-[0-9]*.jar | head -1); "
                    + "hdfs dfs -put -f \"$jar\" " + HIVE_JARS
                    + "/$(basename \"$jar\" .jar)-$(sha256sum \"$jar\" | cut -d\" \" -f1).jar || exit 1; "
                    + "[ -z \"$" + HIVE_METASTORE_SITE_VARIABLE + "\" ] || mapfile -t site <<< \"$"
                    + HIVE_METASTORE_SITE_VARIABLE + "\"; "
                    + "cd /tmp; LOGS_DIR=/tmp/floci-hive-logs HADOOP_CONF_DIR="
                    + EmrServerlessSparkRunner.HIVE_HADOOP_CONF
                    + " hive --service metastore -p " + HIVE_METASTORE_PORT
                    + " --hiveconf fs.defaultFS=hdfs://$FLOCI_HDFS_HOST:8020 $"
                    + HIVE_METASTORE_VARIABLE
                    + " --hiveconf \"javax.jdo.option.ConnectionURL=jdbc:derby:;"
                    + "databaseName=/mnt/var/lib/hive/metastore_db;create=true\""
                    + " --hiveconf datanucleus.schema.autoCreateAll=true"
                    + " --hiveconf hive.metastore.schema.verification=false"
                    + " --hiveconf hive.metastore.warehouse.dir=hdfs://$FLOCI_HDFS_HOST:8020/user/hive/warehouse"
                    + " \"${site[@]}\" & n=$((n + 1)); "
                    + "until (exec 3<>/dev/tcp/127.0.0.1/" + HIVE_METASTORE_PORT + ") 2>/dev/null; do "
                    + "[ \"$(jobs -rp | wc -l)\" = $n ] || exit 1; sleep 0.2; done; "
                    + "fi; "
                    + "if [ -n \"$" + YARN_MEMORY_VARIABLE + "\" ]; then "
                    + "JDK_JAVA_OPTIONS=\"" + yarnOpens() + "\" yarn resourcemanager & "
                    + "JDK_JAVA_OPTIONS=\"" + yarnOpens() + "\" yarn nodemanager & n=$((n + 2)); "
                    + "until curl -sf -m 5 " + RESOURCE_MANAGER_METRICS + " | grep -q \"\\\"activeNodes\\\":1,\"; do "
                    + "[ \"$(jobs -rp | wc -l)\" = $n ] || exit 1; sleep 0.2; done; "
                    + "fi; "
                    + "touch " + HDFS_READY + "; "
                    // Not wait -n: an emr-6.x image's bash, 4.2, does not have it.
                    + "while [ \"$(jobs -rp | wc -l)\" = $n ]; do sleep 1; done; rm -f " + HDFS_READY
                    + "; kill $(jobs -rp) 2>/dev/null; exit 1'");

    // One start at a time in a namespace the steps share, whatever the cluster, as it holds one HDFS.
    private final Object sharedNamespaceStarts = new Object();
    // One start at a time for each cluster's HDFS; a step coming while it starts waits, then finds it running.
    private final Map<String, Object> starting = new ConcurrentHashMap<>();
    // The metastore services this process started, by container name: the session each reaches S3 with, if any, and
    // the address of Floci's it was given.
    private final Map<String, MetastoreSession> sessions = new ConcurrentHashMap<>();
    // For each node, the size of each container log already in S3, by its path under YARN_LOGS.
    private final Map<String, Map<String, Long>> archivedLogs = new ConcurrentHashMap<>();
    // The HDFS containers this process has started or found running, by name, which a reset or a shutdown removes.
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerBuilder containerBuilder;
    private final LaunchedContainerAwsEnv awsEnv;
    private final LambdaExecutionRoleCredentials roleCredentials;
    private final CurrentContainerNetworkResolver currentContainer;
    private final EmulatorConfig config;
    private final S3Service s3Service;
    private final Duration hdfsStartTimeout;
    private final LongSupplier nanoTime;
    // How many teardowns, by a reset or a shutdown, have run: a step waiting for its cluster's HDFS through one stops.
    private final AtomicLong teardowns = new AtomicLong();

    @Inject
    public EmrClusterStorage(ContainerLifecycleManager lifecycleManager, ContainerBuilder containerBuilder,
                             LaunchedContainerAwsEnv awsEnv, LambdaExecutionRoleCredentials roleCredentials,
                             CurrentContainerNetworkResolver currentContainer, EmulatorConfig config,
                             S3Service s3Service) {
        this(lifecycleManager, containerBuilder, awsEnv, roleCredentials, currentContainer, config, s3Service,
                HDFS_START_TIMEOUT, System::nanoTime);
    }

    EmrClusterStorage(ContainerLifecycleManager lifecycleManager, ContainerBuilder containerBuilder,
                      LaunchedContainerAwsEnv awsEnv, LambdaExecutionRoleCredentials roleCredentials,
                      CurrentContainerNetworkResolver currentContainer, EmulatorConfig config, S3Service s3Service,
                      Duration hdfsStartTimeout, LongSupplier nanoTime) {
        this.lifecycleManager = lifecycleManager;
        this.containerBuilder = containerBuilder;
        this.awsEnv = awsEnv;
        this.roleCredentials = roleCredentials;
        this.currentContainer = currentContainer;
        this.config = config;
        this.s3Service = s3Service;
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
    public void archiveContainerLogs(String clusterId, String accountId, String region, String logFolder) {
        String name = hdfsName(clusterId);
        Map<String, Long> archived = archivedLogs.computeIfAbsent(name, node -> new ConcurrentHashMap<>());
        // One step's end at a time for a node, so of two reading a log that is still growing the later is written last.
        synchronized (archived) {
            try {
                S3Uri folder = S3Uri.parseAnyScheme(logFolder);
                for (String log : changedLogs(name, archived)) {
                    archiveLog(name, log, archived, accountId, region, folder);
                }
            } catch (IOException | RuntimeException e) {
                LOG.warnv(e, "Could not write the container logs of EMR cluster {0} to {1}: {2}", clusterId, logFolder,
                        e.getMessage());
            }
        }
    }

    // The node's container logs whose size is not the one already in S3, as <application>/<container>/<file>, but the
    // launch scripts: only those are read from Docker, so a step's end does not bring every earlier application's again.
    private List<String> changedLogs(String name, Map<String, Long> archived) {
        ContainerExec.Result listed = ContainerExec.run(lifecycleManager.getDockerClient(), name,
                new String[]{"find", YARN_LOGS, "-mindepth", "3", "-maxdepth", "3", "-type", "f", "-printf", "%s %P\\n"},
                LOG_LISTING_SECONDS);
        if (listed.exitCode() != 0) {
            throw new IllegalStateException("Could not list them: " + listed.stderr());
        }
        List<String> changed = new ArrayList<>();
        for (String line : listed.stdout().split("\n")) {
            int space = line.indexOf(' ');
            if (space > 0 && !line.endsWith("/" + CONTAINER_LAUNCH_SCRIPT)
                    && !Long.valueOf(line.substring(0, space)).equals(archived.get(line.substring(space + 1)))) {
                changed.add(line.substring(space + 1));
            }
        }
        return changed;
    }

    private void archiveLog(String name, String log, Map<String, Long> archived, String accountId, String region,
                            S3Uri folder) throws IOException {
        try (InputStream archive = lifecycleManager.getDockerClient()
                .copyArchiveFromContainerCmd(name, YARN_LOGS + "/" + log).exec();
                TarArchiveInputStream entries = new TarArchiveInputStream(archive)) {
            TarArchiveEntry entry = entries.getNextEntry();
            if (entry != null && entry.isFile()) {
                putContainerLog(entries, accountId, region, folder.bucket(), folder.key() + log + ".gz");
                // The size Docker gave, which the next look compares, as the log may have grown since it was listed.
                archived.put(log, entry.getSize());
            }
        }
    }

    // Spooled to a file while it is gzipped, as a container's log can be larger than memory should hold.
    private void putContainerLog(InputStream log, String accountId, String region, String bucket, String key)
            throws IOException {
        Path spool = Files.createTempFile("floci-emr-container-log", ".gz");
        try {
            try (OutputStream gzip = new GZIPOutputStream(Files.newOutputStream(spool))) {
                log.transferTo(gzip);
            }
            try (InputStream gzipped = Files.newInputStream(spool)) {
                EmrServerlessSparkRunner.putLog(s3Service, accountId, region, bucket, key, gzipped);
            }
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    @Override
    public boolean ownNetwork(String dockerNetwork) {
        String network = network(dockerNetwork);
        return !NO_NETWORK.equals(network) && !sharesNamespace(network);
    }

    @Override
    public SparkJobRunner.Storage hdfs(String clusterId, String accountId, String region, String host, String image,
                                       String dockerNetwork, HiveMetastore hiveMetastore, Yarn yarn,
                                       BooleanSupplier stopped) {
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
                        && inStepsNamespace(name, shared ? network : name, imageId)
                        && (shared || hiveMetastore == null || metastoreCurrent(name));
                // Known before it starts, so a teardown while it starts removes it.
                running.add(name);
                String candidate = found ? name
                        : start(clusterId, accountId, region, host, imageId, dockerNetwork, hiveMetastore, yarn);
                if (awaitReady(clusterId, candidate, deadline, teardown, stopped)) {
                    containerId = candidate;
                } else if (!found) {
                    stopIfStopped(clusterId, teardown, stopped);
                    logNodeOutput(clusterId, candidate);
                    throw new IllegalStateException("The HDFS of EMR cluster " + clusterId
                            + " stopped before it was ready");
                }
            }
            if (shared) {
                return volumes(clusterId).withHdfs(host, LOOPBACK, null);
            }
            SparkJobRunner.Storage storage = volumes(clusterId).withHdfs(host, address(clusterId, containerId), name);
            if (hiveMetastore != null) {
                storage = storage.withHiveMetastore("thrift://" + host + ":" + HIVE_METASTORE_PORT);
            }
            // Its executors reach Floci's S3 from the node, at Floci's address on the cluster's network.
            return yarn != null ? storage.withYarn(host, flociEndpointOn(name)) : storage;
        }
    }

    // Whether the metastore service of a primary node found running is one this process started, with a session it
    // holds, which reaches Floci's S3 at the address it would be given now, Floci's container joining the network again
    // if it has left. One an earlier process left has a session that process's end took away, and one started while
    // Floci's container could not join the network has an address there that no join repairs: both start again, on
    // the data their volume keeps.
    private boolean metastoreCurrent(String name) {
        MetastoreSession session = sessions.get(name);
        return session != null && session.endpoint().equals(flociEndpointOn(name));
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
    // cluster's own network, the host's, or the same container's, and of the image they run in; one a process with
    // another docker-network left, or one of another image, an earlier Floci's in the Hive image, goes, and starts
    // again on its data.
    private boolean inStepsNamespace(String name, String stepsNetwork, String imageId) {
        InspectContainerResponse container = lifecycleManager.getDockerClient().inspectContainerCmd(name).exec();
        HostConfig hostConfig = container.getHostConfig();
        String networkMode = hostConfig != null ? hostConfig.getNetworkMode() : null;
        return Objects.equals(namespaceOf(networkMode), namespaceOf(stepsNetwork))
                && imageId.equals(container.getImageId());
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

    // Each name and value as a Hadoop property, an argument of the script's printf in double quotes, so a value may
    // name one of the container's variables.
    private static String yarnProperties(List<String> namesAndValues) {
        StringBuilder properties = new StringBuilder();
        for (int i = 0; i < namesAndValues.size(); i += 2) {
            properties.append(" \"<property><name>").append(namesAndValues.get(i)).append("</name><value>")
                    .append(namesAndValues.get(i + 1)).append("</value></property>\"");
        }
        return properties.toString();
    }

    // The JVM options that open YARN_OPENED_PACKAGES to Hadoop's classes.
    private static String yarnOpens() {
        return YARN_OPENED_PACKAGES.stream().map(name -> "--add-opens=java.base/" + name + "=ALL-UNNAMED")
                .collect(Collectors.joining(" "));
    }

    // A cluster's HDFS container, started anew on the HDFS data its volume holds, which goes with the cluster's other
    // two, with its metastore service when asked; one a restart, a stop or a daemon's exit left goes first. It runs on
    // a Docker network of the cluster's own, which only the cluster's steps join, as its HDFS and its metastore serve
    // its files and tables to any client that reaches them: the steps of other clusters, of other accounts too, do
    // not. In a namespace the steps share it listens on the loopback alone, and the metastore does not run, as Hive 3's
    // has no setting for the address it listens on: it would serve every interface, the host's own for one.
    private String start(String clusterId, String accountId, String region, String host, String imageId,
                         String dockerNetwork, HiveMetastore hiveMetastore, Yarn yarn) {
        String network = network(dockerNetwork);
        if (!sharesNamespace(network)) {
            return createContainer(clusterId, accountId, region, host, imageId, network, false, hiveMetastore, yarn);
        }
        // One namespace holds one HDFS's ports, whose daemons would answer this one's readiness and its steps: the look
        // for another cluster's and this one's start go together, so two clusters' steps cannot both pass it.
        synchronized (sharedNamespaceStarts) {
            String other = otherRunningHdfs(clusterId, network);
            if (other != null) {
                throw new IllegalStateException("The HDFS of EMR cluster " + clusterId + " cannot start while that of"
                        + " EMR cluster " + other + " runs in the network namespace they share");
            }
            return createContainer(clusterId, accountId, region, host, imageId, network, true, null, null);
        }
    }

    // Creates and starts the container start() decided on, in the namespace the steps share or on a network of its
    // cluster's own.
    private String createContainer(String clusterId, String accountId, String region, String host, String imageId,
                                   String network, boolean shared, HiveMetastore hiveMetastore, Yarn yarn) {
        String name = hdfsName(clusterId);
        // One left stopped goes first, and a removal Docker refuses fails the start as it is, not as a name clash.
        lifecycleManager.removeIfExistsStrict(name);
        releaseSession(name);
        lifecycleManager.ensureVolume(name, labels(clusterId, accountId, region, COMPONENT));
        if (!shared) {
            lifecycleManager.ensureNetwork(name, labels(clusterId, accountId, region, HDFS_COMPONENT));
        }
        ContainerBuilder.Builder spec = containerBuilder.newContainer(imageId)
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
                .withLabels(labels(clusterId, accountId, region, HDFS_COMPONENT));
        if (yarn != null) {
            spec.withEnv(YARN_MEMORY_VARIABLE, String.valueOf(yarn.memoryMb()))
                    .withEnv(YARN_VCORES_VARIABLE, String.valueOf(yarn.vcores()));
            if (yarn.hadoopFolder() != null) {
                spec.withEnv(HADOOP_FOLDER_VARIABLE, yarn.hadoopFolder());
            }
        }
        // The metastore and the executors YARN runs reach Floci, Floci on the host by its address, Floci's own
        // certificate trusted when it serves TLS.
        if (hiveMetastore != null || yarn != null) {
            spec.withHostDockerInternalOnLinux().withJavaTrustStore();
        }
        String containerId;
        try {
            if (hiveMetastore != null) {
                String endpoint = flociEndpointOn(name);
                Optional<SessionCreds> credentials = roleCredentials.forRole(hiveMetastore.role(), accountId, name);
                sessions.put(name, new MetastoreSession(accountId,
                        credentials.map(SessionCreds::accessKeyId).orElse(null), endpoint));
                spec.withEnv(awsEnv.sdkBaselineEnv(region, Optional.empty(), endpoint, credentials, accountId))
                        .withEnv(HIVE_METASTORE_VARIABLE, String.join(" ",
                                EmrServerlessSparkRunner.hiveMetastoreSettings(region, accountId, endpoint)))
                        .withEnv(HIVE_METASTORE_SITE_VARIABLE, String.join("\n",
                                EmrServerlessSparkRunner.hiveSettings(hiveMetastore.settings())));
            }
            containerId = lifecycleManager.createAndStart(spec.build()).containerId();
        } catch (RuntimeException e) {
            // A start that fails leaves no session, nor Floci's container on a network with no service to reach it.
            releaseSession(name);
            if (hiveMetastore != null) {
                detachFloci(name);
            }
            throw e;
        }
        LOG.infov("Started the HDFS of EMR cluster {0} as {1}", clusterId, host);
        return containerId;
    }

    // Where the metastore service, on the cluster's network, which Docker keeps apart from Floci's, reaches Floci's
    // S3: Floci on the host at the host's address, as the steps reach it; Floci in a container of its own as
    // FLOCI_ALIAS, its container joining the cluster's network, as it joins the networks of EC2 VPCs.
    private String flociEndpointOn(String network) {
        String endpoint = awsEnv.flociEndpoint(false);
        return attachFloci(network) ? "http://" + FLOCI_ALIAS + ":" + URI.create(endpoint).getPort() : endpoint;
    }

    // Whether Floci's own container is on the network, joined to it unless it was; false when Floci runs on the host,
    // or its container could not join, which leaves the metastore service its address on Floci's network.
    private boolean attachFloci(String network) {
        Optional<String> floci = currentContainer.resolveContainerId();
        if (floci.isEmpty()) {
            return false;
        }
        try {
            lifecycleManager.ensureConnected(floci.get(), network, FLOCI_ALIAS);
            return true;
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not connect Floci's container {0} to the Docker network {1}, so the Hive metastore"
                    + " on it may not reach Floci's S3: {2}", floci.get(), network, e.getMessage());
            return false;
        }
    }

    // Floci's container leaves a cluster's network, which it joined for the metastore service, so the network can go,
    // and Floci's container, restarted, does not find itself on it. Whether it is off it.
    private boolean detachFloci(String network) {
        return currentContainer.resolveContainerId()
                .map(floci -> lifecycleManager.disconnectFromNetwork(floci, network))
                .orElse(true);
    }

    // The session the metastore service of the container named so reached S3 with goes with the container.
    private void releaseSession(String name) {
        MetastoreSession session = sessions.remove(name);
        if (session != null && session.accessKeyId() != null) {
            roleCredentials.unregister(session.accountId(), session.accessKeyId());
        }
    }

    private record MetastoreSession(String accountId, String accessKeyId, String endpoint) {
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
                logNodeOutput(clusterId, containerId);
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

    // What a node that did not become ready last wrote, in Floci's log, as the container goes with its cluster.
    private void logNodeOutput(String clusterId, String containerId) {
        LOG.warnv("The primary node of EMR cluster {0} did not become ready. It last wrote:\n{1}", clusterId,
                lifecycleManager.lastLogLines(containerId, NODE_LOG_LINES, NODE_LOG_READ));
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

    // Removes an HDFS container, forgetting it only once Docker says it is gone and Floci's container has left its
    // network: one Docker keeps, or keeps Floci's container on the network of, stays known, so the next reset or
    // shutdown tries again. Its metastore's session goes with it. Whether both are done.
    private boolean removeKnown(String name) {
        try {
            lifecycleManager.removeIfExistsStrict(name);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not remove the HDFS container {0}: {1}", name, e.getMessage());
            return false;
        }
        releaseSession(name);
        if (!detachFloci(name)) {
            return false;
        }
        running.remove(name);
        archivedLogs.remove(name);
        return true;
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
                    detachFloci(network.getName());
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
