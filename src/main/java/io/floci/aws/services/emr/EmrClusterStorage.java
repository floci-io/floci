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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
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
 * for the cluster's instance profile's role, which goes with the container. Where it has core nodes, "Core nodes run
 * the Data Node daemon" and "a core node runs YARN NodeManager daemons", and "Task nodes don't run the Data Node
 * daemon", each is a container of its own on the cluster's network, with its data in a volume of its own.
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
    // A core or task node's container: its host name, and whether it holds a DataNode, as a resize must know them again
    // after Floci restarts.
    private static final String NODE_COMPONENT = SparkJobRunner.Storage.NODE_COMPONENT;
    private static final String NODE_HOST_LABEL = "io.floci.emr.node-host";
    private static final String NODE_CORE_LABEL = "io.floci.emr.node-core";
    private static final String NODE_HOST_VARIABLE = "FLOCI_NODE_HOST";
    private static final String NODE_CORE_VARIABLE = "FLOCI_NODE_CORE";
    // Set on a primary node whose cluster was created with core nodes, which hold its DataNodes and NodeManagers in its
    // place.
    private static final String NODE_WORKERS_VARIABLE = "FLOCI_NODE_WORKERS";
    private static final String REPLICATION_VARIABLE = "FLOCI_HDFS_REPLICATION";
    // The nodes being decommissioned, one host name to a line, as HDFS and YARN read them on the primary node.
    private static final String DFS_EXCLUDE = HDFS_CONF + "/dfs.exclude";
    private static final String YARN_EXCLUDE = HDFS_CONF + "/yarn.exclude";
    // The ResourceManager's cluster metrics on its web port, which say when the NodeManager has registered.
    private static final String RESOURCE_MANAGER_METRICS = "http://127.0.0.1:8088/ws/v1/cluster/metrics";
    private static final String RESOURCE_MANAGER_NODES = "http://127.0.0.1:8088/ws/v1/cluster/nodes?states=";
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
    // How long a node being decommissioned may take before it goes anyway: EMR's default for a graceful one, "3600s
    // (one hour)", and a minute more for the ResourceManager's own timeout to pass first.
    private static final Duration GRACEFUL_DECOMMISSION = Duration.ofHours(1);
    private static final Duration DECOMMISSION_MARGIN = Duration.ofMinutes(1);
    private static final Duration DECOMMISSION_POLL = Duration.ofSeconds(3);
    // A core node that has gone stays in HDFS's exclude list until the NameNode has it dead: taken out while the
    // NameNode still has it live, it is back in service, the copies of its blocks count again, and the NameNode drops
    // one copy of each block now held twice, which may be the remaining node's. The NameNode has a node dead after twice
    // its heartbeat recheck interval and ten heartbeats, 2 x 5s + 10 x 3s with NODE_SETUP's setting; this has a margin.
    private static final Duration DEAD_NODE_WAIT = Duration.ofSeconds(60);
    private static final int NODE_COMMAND_SECONDS = 120;
    // How much of a node's output is logged when it does not become ready, and how long reading it may take.
    private static final int NODE_LOG_LINES = 40;
    private static final Duration NODE_LOG_READ = Duration.ofSeconds(5);
    // Every daemon of a node, and each command run on it, uses the Hadoop in HADOOP_FOLDER_VARIABLE when that is set.
    private static final String HADOOP_FOLDER_SETUP = String.join("\n",
            "if [ -n \"$" + HADOOP_FOLDER_VARIABLE + "\" ]; then",
            "  h=$" + HADOOP_FOLDER_VARIABLE,
            "  export HADOOP_HOME=$h/hadoop HADOOP_COMMON_HOME=$h/hadoop HADOOP_LIBEXEC_DIR=$h/hadoop/libexec"
                    + " HADOOP_HDFS_HOME=$h/hadoop-hdfs HADOOP_MAPRED_HOME=$h/hadoop-mapreduce"
                    + " HADOOP_YARN_HOME=$h/hadoop-yarn PATH=$h/hadoop-hdfs/bin:$h/hadoop-yarn/bin:$h/hadoop/bin:$PATH",
            "fi");
    // What every node of a cluster writes before its daemons start: the Hadoop configuration, its name among the
    // nodes in HDFS's when it is given one, and the empty files listing the nodes being decommissioned.
    private static final String NODE_SETUP = String.join("\n",
            "chown hadoop:hadoop /mnt || exit",
            EmrServerlessSparkRunner.HIVE_ONE_SDK,
            "mkdir -p " + HDFS_CONF + " || exit",
            "printf '%s\\n' '<configuration>'"
                    + " \"<property><name>fs.defaultFS</name><value>hdfs://$FLOCI_HDFS_HOST:8020</value></property>\""
                    + " '</configuration>' > " + HDFS_CONF + "/core-site.xml",
            "datanode_name=; [ -z \"$" + NODE_HOST_VARIABLE + "\" ] || datanode_name=\"<property>"
                    + "<name>dfs.datanode.hostname</name><value>$" + NODE_HOST_VARIABLE + "</value></property>\"",
            ": > " + DFS_EXCLUDE + " || exit",
            ": > " + YARN_EXCLUDE + " || exit",
            "printf '%s\\n' '<configuration>'"
                    + " '<property><name>dfs.namenode.name.dir</name><value>file:///mnt/namenode</value></property>'"
                    + " '<property><name>dfs.datanode.data.dir</name><value>file:///mnt/datanode</value></property>'"
                    + " \"<property><name>dfs.replication</name><value>${" + REPLICATION_VARIABLE
                    + ":-1}</value></property>\""
                    + " '<property><name>dfs.hosts.exclude</name><value>" + DFS_EXCLUDE + "</value></property>'"
                    + " '<property><name>dfs.namenode.decommission.interval</name><value>3</value></property>'"
                    + " '<property><name>dfs.namenode.heartbeat.recheck-interval</name><value>5000</value></property>'"
                    + " \"$datanode_name\""
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
                    "yarn.nodemanager.aux-services", "mapreduce_shuffle",
                    "yarn.resourcemanager.nodes.exclude-path", YARN_EXCLUDE,
                    "yarn.resourcemanager.decommissioning-nodes-watcher.poll-interval-secs", "3"))
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
            HADOOP_FOLDER_SETUP);
    // The primary node's HDFS, started as root: its daemons log their warnings and errors to the container's output,
    // which docker logs shows; its host name, FLOCI_HDFS_HOST, leads to this container's address, where the DataNode
    // registers, so steps reach both by it; /mnt, its data's volume, becomes the image's user's, which runs a NameNode
    // and a DataNode with their data in it, the NameNode formatted the first time, with one copy of each block and
    // without permissions, listening on FLOCI_HDFS_BIND: every address of the container, on the cluster's own network,
    // or, in a namespace the steps share, the host's or a container's, only its loopback, which its name leads to, so
    // no other interface serves files anyone could change; in a container's, the hosts file the name goes in is the
    // one the namespace's containers share, the steps' too. The other daemons start with them, as none needs the HDFS
    // to start. With HIVE_METASTORE_VARIABLE set, Hive's metastore service does, with the Hive image's Hadoop
    // configuration, which the node's image keeps at HIVE_HADOOP_CONF, as Hive steps run, its settings after it: "Hive
    // records metastore information in a MySQL database on the primary node's file system", kept as a Derby database
    // in /mnt, which only the service opens, so any number of steps share it, its log in /tmp, and its warehouse in
    // the HDFS; the cluster's core-site and hive-site, last, replace any of them, as on EMR's primary node. With
    // YARN_MEMORY_VARIABLE set, YARN does, as "the primary node runs the YARN ResourceManager service": a
    // ResourceManager and, as on a cluster of one node, a NodeManager, which offers that much memory and
    // YARN_VCORES_VARIABLE's cores to the containers it runs the steps' Spark executors in, its local and log folders
    // in /mnt, Java 17 opening to both the packages they reach into. Where the cluster's core nodes hold them
    // (NODE_WORKERS_VARIABLE), the node runs no DataNode or NodeManager, but a DataNode on a volume that holds one's
    // data, so the blocks it kept stay in reach. Once the NameNode on its port is this container's own, its ClusterId
    // the one its format gave it, rather than another cluster's in that namespace, the DataNode has joined and the
    // NameNode has left safe mode, which on a restart waits for the blocks the volume holds, the folders EMR's HDFS has
    // for steps, /tmp and the hadoop user's home, are made, and the metastore's warehouse and HIVE_JARS, which Hive's
    // exec jar goes in. HDFS_READY then says the node is ready, once the metastore takes connections and the
    // NodeManager has registered. A daemon that exits, before or after, the metastore among them, ends the container,
    // its readiness gone, so the next step starts it again on its data. With HADOOP_FOLDER_VARIABLE set, every daemon
    // runs from the Hadoop in that folder.
    private static final String HDFS_SCRIPT = String.join("\n",
            "address=$FLOCI_HDFS_BIND; [ \"$address\" != 0.0.0.0 ] || address=$(hostname -i | awk '{print $1}')",
            "echo \"$address $FLOCI_HDFS_HOST\" >> /etc/hosts || exit",
            NODE_SETUP,
            "exec setpriv --reuid=hadoop --regid=hadoop --clear-groups bash -c '"
                    + "[ -d /mnt/namenode/current ] || hdfs namenode -format -nonInteractive || exit; "
                    + "cid=$(sed -n \"s/^clusterID=//p\" /mnt/namenode/current/VERSION); "
                    + "hdfs namenode & n=1; "
                    + "if [ -z \"$" + NODE_WORKERS_VARIABLE + "\" ] || [ -d /mnt/datanode/current ]; then "
                    + "hdfs datanode & n=2; fi; "
                    + "dirs=\"/tmp /user/hadoop\"; "
                    + "if [ -n \"$" + HIVE_METASTORE_VARIABLE + "\" ]; then "
                    + "dirs=\"$dirs /user/hive/warehouse " + HIVE_JARS + "\"; "
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
                    + "fi; "
                    + "if [ -n \"$" + YARN_MEMORY_VARIABLE + "\" ]; then "
                    + "JDK_JAVA_OPTIONS=\"" + yarnOpens() + "\" yarn resourcemanager & n=$((n + 1)); "
                    + "if [ -z \"$" + NODE_WORKERS_VARIABLE + "\" ]; then "
                    + "JDK_JAVA_OPTIONS=\"" + yarnOpens() + "\" yarn nodemanager & n=$((n + 1)); fi; "
                    + "fi; "
                    + "until curl -sf -m 5 \"" + NAMENODE_JMX + "NameNodeInfo\""
                    + " | grep -q \"\\\"ClusterId\\\" : \\\"$cid\\\"\"; do "
                    + "[ \"$(jobs -rp | wc -l)\" = $n ] || exit 1; sleep 0.2; done; "
                    + "until curl -sf -m 5 \"" + NAMENODE_JMX + "FSNamesystemState\""
                    + " | grep -q \"\\\"NumLiveDataNodes\\\" : [1-9]\"; do "
                    + "[ \"$(jobs -rp | wc -l)\" = $n ] || exit 1; sleep 0.2; done; "
                    + "until curl -sf -m 5 \"" + NAMENODE_JMX + "NameNodeInfo\""
                    + " | grep -q \"\\\"Safemode\\\" : \\\"\\\"\"; do "
                    + "[ \"$(jobs -rp | wc -l)\" = $n ] || exit 1; sleep 0.2; done; "
                    + "hdfs dfs -mkdir -p $dirs || exit 1; "
                    + "if [ -n \"$" + HIVE_METASTORE_VARIABLE + "\" ]; then "
                    + "jar=$(ls /usr/lib/hive/lib/hive-exec-[0-9]*.jar | head -1); "
                    + "hdfs dfs -put -f \"$jar\" " + HIVE_JARS
                    + "/$(basename \"$jar\" .jar)-$(sha256sum \"$jar\" | cut -d\" \" -f1).jar || exit 1; "
                    + "until (exec 3<>/dev/tcp/127.0.0.1/" + HIVE_METASTORE_PORT + ") 2>/dev/null; do "
                    + "[ \"$(jobs -rp | wc -l)\" = $n ] || exit 1; sleep 0.2; done; "
                    + "fi; "
                    + "if [ -n \"$" + YARN_MEMORY_VARIABLE + "\" ]; then "
                    + "until curl -sf -m 5 " + RESOURCE_MANAGER_METRICS
                    + " | grep -q \"\\\"activeNodes\\\":[1-9]\"; do "
                    + "[ \"$(jobs -rp | wc -l)\" = $n ] || exit 1; sleep 0.2; done; "
                    + "fi; "
                    + "touch " + HDFS_READY + "; "
                    // Not wait -n: an emr-6.x image's bash, 4.2, does not have it.
                    + "while [ \"$(jobs -rp | wc -l)\" = $n ]; do sleep 1; done; rm -f " + HDFS_READY
                    + "; kill $(jobs -rp) 2>/dev/null; exit 1'");

    // A core or task node, started as root: a NodeManager and, on a core node, a DataNode, which reach the primary node
    // by its name on the cluster's network and register under the node's own. It is ready once the ResourceManager, and
    // on a core node the NameNode, knows it. A daemon that exits ends the container, as on the primary node.
    private static final String NODE_SCRIPT = String.join("\n",
            NODE_SETUP,
            "exec setpriv --reuid=hadoop --regid=hadoop --clear-groups bash -c '"
                    + "n=1; if [ -n \"$" + NODE_CORE_VARIABLE + "\" ]; then hdfs datanode & n=2; fi; "
                    + "JDK_JAVA_OPTIONS=\"" + yarnOpens() + "\" yarn nodemanager & "
                    + "until curl -sf -m 5 \"http://$FLOCI_HDFS_HOST:8088/ws/v1/cluster/nodes?states=RUNNING\""
                    + " | grep -q \"\\\"nodeHostName\\\":\\\"$" + NODE_HOST_VARIABLE + "\\\"\"; do "
                    + "[ \"$(jobs -rp | wc -l)\" = $n ] || exit 1; sleep 0.2; done; "
                    + "if [ -n \"$" + NODE_CORE_VARIABLE + "\" ]; then "
                    + "until curl -sf -m 5 \"http://$FLOCI_HDFS_HOST:9870/jmx?qry=Hadoop:service=NameNode,"
                    + "name=NameNodeInfo\" | grep -q \"$" + NODE_HOST_VARIABLE + ":9866\"; do "
                    + "[ \"$(jobs -rp | wc -l)\" = $n ] || exit 1; sleep 0.2; done; fi; "
                    + "touch " + HDFS_READY + "; "
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
    // The core and task nodes' containers this process has started or found, by name.
    private final Set<String> runningNodes = ConcurrentHashMap.newKeySet();
    // What each cluster's last step started its nodes from, which a resize starts new ones from.
    private final Map<String, NodeRequest> nodeRequests = new ConcurrentHashMap<>();
    // For each cluster, the nodes being decommissioned, by container name.
    private final Map<String, Map<String, Draining>> draining = new ConcurrentHashMap<>();
    // For each cluster, the core nodes that have gone and are still in HDFS's exclude list, by host name, with the
    // nanoTime they went at.
    private final Map<String, Map<String, Long>> departed = new ConcurrentHashMap<>();
    // The clusters a thread is decommissioning nodes of.
    private final Set<String> decommissioning = ConcurrentHashMap.newKeySet();
    private final Executor decommissions;
    private final Duration decommissionPoll;
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
                HDFS_START_TIMEOUT, System::nanoTime,
                Executors.newCachedThreadPool(Thread.ofPlatform().daemon().name("emr-decommission-", 0).factory()),
                DECOMMISSION_POLL);
    }

    EmrClusterStorage(ContainerLifecycleManager lifecycleManager, ContainerBuilder containerBuilder,
                      LaunchedContainerAwsEnv awsEnv, LambdaExecutionRoleCredentials roleCredentials,
                      CurrentContainerNetworkResolver currentContainer, EmulatorConfig config, S3Service s3Service,
                      Duration hdfsStartTimeout, LongSupplier nanoTime) {
        this(lifecycleManager, containerBuilder, awsEnv, roleCredentials, currentContainer, config, s3Service,
                hdfsStartTimeout, nanoTime, Runnable::run, Duration.ZERO);
    }

    EmrClusterStorage(ContainerLifecycleManager lifecycleManager, ContainerBuilder containerBuilder,
                      LaunchedContainerAwsEnv awsEnv, LambdaExecutionRoleCredentials roleCredentials,
                      CurrentContainerNetworkResolver currentContainer, EmulatorConfig config, S3Service s3Service,
                      Duration hdfsStartTimeout, LongSupplier nanoTime, Executor decommissions,
                      Duration decommissionPoll) {
        this.decommissions = decommissions;
        this.decommissionPoll = decommissionPoll;
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
        archiveNodeLogs(clusterId, hdfsName(clusterId), accountId, region, logFolder);
        Collection<String> nodes;
        try {
            nodes = nodeContainers(clusterId).keySet();
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not find the nodes of EMR cluster {0} to write their container logs to {1}: {2}",
                    clusterId, logFolder, e.getMessage());
            return;
        }
        for (String node : nodes) {
            archiveNodeLogs(clusterId, node, accountId, region, logFolder);
        }
    }

    private void archiveNodeLogs(String clusterId, String name, String accountId, String region, String logFolder) {
        Map<String, Long> archived = archivedLogs.computeIfAbsent(name, node -> new ConcurrentHashMap<>());
        // One step's end at a time for a node, so of two reading a log that is still growing the later is written last.
        synchronized (archived) {
            try {
                S3Uri folder = S3Uri.parseAnyScheme(logFolder);
                if (lifecycleManager.probeContainer(name) == ContainerLiveness.NOT_RUNNING) {
                    archiveStoppedNodeLogs(name, archived, accountId, region, folder);
                    return;
                }
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
    // A primary node whose core nodes run the NodeManagers has no such folder, and so none.
    private List<String> changedLogs(String name, Map<String, Long> archived) {
        ContainerExec.Result listed = ContainerExec.run(lifecycleManager.getDockerClient(), name,
                new String[]{"bash", "-c", "[ ! -d " + YARN_LOGS + " ] || exec find " + YARN_LOGS
                        + " -mindepth 3 -maxdepth 3 -type f -printf '%s %P\\n'"},
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

    // A stopped node answers no command, a decommissioned one among them, whose NodeManager exits once it is, which ends
    // its container, but Docker still copies its files: the whole folder, as this is the last look at a node that goes.
    private void archiveStoppedNodeLogs(String name, Map<String, Long> archived, String accountId, String region,
                                        S3Uri folder) throws IOException {
        try (InputStream archive = lifecycleManager.getDockerClient().copyArchiveFromContainerCmd(name, YARN_LOGS)
                .exec(); TarArchiveInputStream entries = new TarArchiveInputStream(archive)) {
            TarArchiveEntry entry;
            while ((entry = entries.getNextEntry()) != null) {
                // logs/<application>/<container>/<file>, as Docker archives the folder.
                String[] path = entry.getName().split("/");
                if (!entry.isFile() || path.length != 4 || CONTAINER_LAUNCH_SCRIPT.equals(path[3])) {
                    continue;
                }
                String log = path[1] + "/" + path[2] + "/" + path[3];
                if (!Objects.equals(archived.get(log), entry.getSize())) {
                    putContainerLog(entries, accountId, region, folder.bucket(), folder.key() + log + ".gz");
                    archived.put(log, entry.getSize());
                }
            }
        }
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
                                       String dockerNetwork, HiveMetastore hiveMetastore, Supplier<Yarn> yarnNow,
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
            // Read here, so a resize before this took the lock counts, and one after it waits and finds these nodes.
            Yarn yarn = yarnNow != null ? yarnNow.get() : null;
            NodeRequest nodeRequest = yarn == null ? null
                    : new NodeRequest(clusterId, accountId, region, host, imageId, yarn);
            String containerId = null;
            List<String> nodes = List.of();
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
                // Before it is awaited, as its readiness needs a DataNode of theirs.
                nodes = nodeRequest != null ? reconcileNodes(nodeRequest, teardown) : List.of();
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
            if (nodeRequest != null) {
                awaitNodes(nodeRequest, nodes, deadline, teardown, stopped);
                nodeRequests.put(clusterId, nodeRequest);
            }
            SparkJobRunner.Storage storage = volumes(clusterId).withHdfs(host, address(clusterId, containerId), name);
            if (hiveMetastore != null) {
                storage = storage.withHiveMetastore("thrift://" + host + ":" + HIVE_METASTORE_PORT);
            }
            if (yarn == null) {
                return storage;
            }
            // Its executors reach Floci's S3 from the node, at Floci's address on the cluster's network.
            storage = storage.withYarn(host, flociEndpointOn(name));
            return yarn.nodes().stream().anyMatch(Node::core) ? storage.withReplication(yarn.replication()) : storage;
        }
    }

    @Override
    public void resize(String clusterId, Supplier<Yarn> yarn) {
        long teardown = teardowns.get();
        try {
            synchronized (starting.computeIfAbsent(clusterId, id -> new Object())) {
                NodeRequest known = nodeRequests.get(clusterId);
                if (known == null) {
                    return;
                }
                // Read here too, so of two resizes the one that takes the lock last applies what the cluster says now.
                NodeRequest request = known.resizedTo(yarn.get());
                nodeRequests.put(clusterId, request);
                reconcileNodes(request, teardown);
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not resize the nodes of EMR cluster {0}: {1}", clusterId, e.getMessage());
        }
    }

    // A cluster's core and task nodes: one wanted and not running in the image starts, on the data its volume keeps, one
    // no longer wanted is decommissioned on a thread of its own, and one being decommissioned that is wanted again
    // carries on. A core node found only as its volume, as a shutdown leaves one it cut off while it was decommissioned,
    // starts again on its data to be decommissioned, so HDFS moves its blocks; a task node's volume goes, as it holds
    // none. The names of those wanted.
    private List<String> reconcileNodes(NodeRequest request, long teardown) {
        String clusterId = request.clusterId();
        Map<String, Container> present = nodeContainers(clusterId);
        Map<String, Draining> leaving = draining.computeIfAbsent(clusterId, id -> new ConcurrentHashMap<>());
        boolean changed = false;
        List<String> wanted = new ArrayList<>();
        for (Node node : request.yarn().nodes()) {
            String name = nodeName(clusterId, node);
            wanted.add(name);
            changed |= leaving.remove(name) != null;
            Container container = present.get(name);
            if (container == null || !"running".equals(container.getState())
                    || !request.imageId().equals(container.getImageId())) {
                startNode(request, node.host(), node.core(), name, teardown);
            } else {
                runningNodes.add(name);
            }
        }
        for (Map.Entry<String, Container> node : present.entrySet()) {
            if (!wanted.contains(node.getKey()) && !leaving.containsKey(node.getKey())) {
                leaving.put(node.getKey(), draining(node.getValue().getLabels()));
                runningNodes.add(node.getKey());
                changed = true;
            }
        }
        for (Map.Entry<String, Map<String, String>> volume : nodeVolumes(clusterId).entrySet()) {
            String name = volume.getKey();
            if (wanted.contains(name) || present.containsKey(name) || leaving.containsKey(name)) {
                continue;
            }
            Draining node = draining(volume.getValue());
            if (node.core()) {
                startNode(request, node.host(), true, name, teardown);
                leaving.put(name, node);
                changed = true;
            } else {
                lifecycleManager.removeVolume(name);
            }
        }
        if (changed) {
            syncExcludes(clusterId, leaving.values());
        }
        if (!leaving.isEmpty() && decommissioning.add(clusterId)) {
            decommissions.execute(() -> decommission(request, teardown));
        }
        return wanted;
    }

    // A node being decommissioned, as its container's or its volume's labels say, with an hour and a minute to go in.
    private Draining draining(Map<String, String> labels) {
        Map<String, String> known = labels != null ? labels : Map.of();
        return new Draining(known.get(NODE_HOST_LABEL), Boolean.parseBoolean(known.get(NODE_CORE_LABEL)),
                nanoTime.getAsLong() + GRACEFUL_DECOMMISSION.plus(DECOMMISSION_MARGIN).toNanos());
    }

    // A node's container, started on the data its volume keeps; one whose start a teardown passed goes, as the teardown's
    // sweep may have looked before it was there.
    private void startNode(NodeRequest request, String host, boolean core, String name, long teardown) {
        String clusterId = request.clusterId();
        Yarn yarn = request.yarn();
        // Known before it starts, so a teardown while it starts removes it.
        runningNodes.add(name);
        lifecycleManager.removeIfExistsStrict(name);
        // Its volume says whose it is too, so a shutdown that removes its container leaves it findable.
        Map<String, String> volumeLabels = labels(clusterId, request.accountId(), request.region(), COMPONENT);
        volumeLabels.put(NODE_HOST_LABEL, host);
        volumeLabels.put(NODE_CORE_LABEL, String.valueOf(core));
        lifecycleManager.ensureVolume(name, volumeLabels);
        Map<String, String> labels = labels(clusterId, request.accountId(), request.region(), NODE_COMPONENT);
        labels.put(NODE_HOST_LABEL, host);
        labels.put(NODE_CORE_LABEL, String.valueOf(core));
        ContainerBuilder.Builder spec = containerBuilder.newContainer(request.imageId())
                .withName(name)
                .withUser("root")
                .withEntrypoint(List.of("/bin/bash", "-c", NODE_SCRIPT))
                .withEnv("FLOCI_HDFS_HOST", request.host())
                .withEnv("FLOCI_HDFS_BIND", "0.0.0.0")
                .withEnv(NODE_HOST_VARIABLE, host)
                .withEnv(REPLICATION_VARIABLE, String.valueOf(yarn.replication()))
                .withEnv(YARN_MEMORY_VARIABLE, String.valueOf(yarn.memoryMb()))
                .withEnv(YARN_VCORES_VARIABLE, String.valueOf(yarn.vcores()))
                .withEnv("HADOOP_CONF_DIR", HDFS_CONF)
                .withEnv("HADOOP_LOG_DIR", "/tmp/floci-hdfs-logs")
                .withNamedVolume(name, "/mnt")
                .withDockerNetwork(Optional.of(hdfsName(clusterId)))
                .withDnsName(host)
                .withLogRotation()
                .withLabels(labels)
                // Its executors reach Floci, Floci on the host by its address, Floci's own certificate trusted.
                .withHostDockerInternalOnLinux()
                .withJavaTrustStore();
        if (core) {
            spec.withEnv(NODE_CORE_VARIABLE, "1");
        }
        if (yarn.hadoopFolder() != null) {
            spec.withEnv(HADOOP_FOLDER_VARIABLE, yarn.hadoopFolder());
        }
        lifecycleManager.createAndStart(spec.build());
        if (teardowns.get() != teardown) {
            removeNode(name, false);
            return;
        }
        LOG.infov("Started the {0} node {1} of EMR cluster {2}", core ? "core" : "task", host, clusterId);
    }

    // Each node ready by the deadline; one that stopped before it was is started again, once.
    private void awaitNodes(NodeRequest request, List<String> nodes, long deadline, long teardown,
                            BooleanSupplier stopped) {
        List<String> wanted = nodes;
        for (int attempt = 0; ; attempt++) {
            boolean ready = true;
            for (String node : wanted) {
                ready = ready && awaitReady(request.clusterId(), node, deadline, teardown, stopped);
            }
            if (ready) {
                return;
            }
            stopIfStopped(request.clusterId(), teardown, stopped);
            if (attempt > 0) {
                throw new IllegalStateException("A node of EMR cluster " + request.clusterId()
                        + " stopped before it was ready");
            }
            wanted = reconcileNodes(request, teardown);
        }
    }

    // The containers of a cluster's core and task nodes, running or not, by name.
    private Map<String, Container> nodeContainers(String clusterId) {
        Map<String, Container> nodes = new LinkedHashMap<>();
        List<Container> containers = lifecycleManager.getDockerClient().listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(ContainerStorageHelper.COMPONENT_LABEL, NODE_COMPONENT,
                        ContainerStorageHelper.RESOURCE_ID_LABEL, clusterId,
                        ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config)))
                .exec();
        for (Container container : containers) {
            nodes.put(container.getNames()[0].substring(1), container);
        }
        return nodes;
    }

    // The volumes of a cluster's core and task nodes, with their labels, by name, whether a container has them or not.
    private Map<String, Map<String, String>> nodeVolumes(String clusterId) {
        ListVolumesResponse response = lifecycleManager.getDockerClient().listVolumesCmd()
                .withFilter("label", List.of(ContainerStorageHelper.COMPONENT_LABEL + "=" + COMPONENT,
                        ContainerStorageHelper.RESOURCE_ID_LABEL + "=" + clusterId,
                        ContainerStorageHelper.OWNER_LABEL + "=" + ContainerStorageHelper.ownerIdentity(config),
                        NODE_HOST_LABEL))
                .exec();
        Map<String, Map<String, String>> volumes = new LinkedHashMap<>();
        if (response != null && response.getVolumes() != null) {
            for (InspectVolumeResponse volume : response.getVolumes()) {
                Map<String, String> labels = volume.getLabels() != null ? volume.getLabels() : Map.of();
                if (clusterId.equals(labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL))
                        && labels.containsKey(NODE_HOST_LABEL)) {
                    volumes.put(volume.getName(), labels);
                }
            }
        }
        return volumes;
    }

    // Writes the lists of the nodes being decommissioned on the primary node, when they changed, a primary node started
    // again holding none, and has HDFS and YARN read them: "both YARN NodeManager and HDFS DataNode daemons must be
    // decommissioned" on a core node, and only the first on a task node. A node leaves YARN's list once it has gone, as
    // a node that comes back under its name is a new one, and HDFS's once the NameNode has it dead (DEAD_NODE_WAIT). A
    // refresh that fails takes its list away, so the next round's differs and is read again.
    private void syncExcludes(String clusterId, Collection<Draining> nodes) {
        String yarnHosts = hostLines(nodes.stream().filter(Draining::hdfsDone).map(Draining::host).toList());
        List<String> dfs = new ArrayList<>(nodes.stream().filter(Draining::core).map(Draining::host).toList());
        dfs.addAll(departed.getOrDefault(clusterId, Map.of()).keySet());
        String dfsHosts = hostLines(dfs);
        String script = String.join("\n", HADOOP_FOLDER_SETUP,
                "printf '" + yarnHosts + "' > " + YARN_EXCLUDE + ".new || exit 1",
                "printf '" + dfsHosts + "' > " + DFS_EXCLUDE + ".new || exit 1",
                "if ! cmp -s " + YARN_EXCLUDE + ".new " + YARN_EXCLUDE + "; then mv " + YARN_EXCLUDE + ".new "
                        + YARN_EXCLUDE + " && yarn rmadmin -refreshNodes -g " + GRACEFUL_DECOMMISSION.toSeconds()
                        + " -server || { rm -f " + YARN_EXCLUDE + "; exit 1; }; fi",
                "if ! cmp -s " + DFS_EXCLUDE + ".new " + DFS_EXCLUDE + "; then mv " + DFS_EXCLUDE + ".new "
                        + DFS_EXCLUDE + " && hdfs dfsadmin -refreshNodes || { rm -f " + DFS_EXCLUDE + "; exit 1; }; fi");
        try {
            ContainerExec.Result result = ContainerExec.run(lifecycleManager.getDockerClient(), hdfsName(clusterId),
                    new String[]{"bash", "-c", script}, NODE_COMMAND_SECONDS);
            if (result.exitCode() != 0) {
                LOG.warnv("Could not tell EMR cluster {0} which nodes to decommission, which the next round tries"
                        + " again: {1}", clusterId, result.stderr());
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not tell EMR cluster {0} which nodes to decommission, which the next round tries"
                    + " again: {1}", clusterId, e.getMessage());
        }
    }

    private static String hostLines(List<String> hosts) {
        return hosts.stream().map(host -> host + "\\n").collect(Collectors.joining());
    }

    // A thread's work: each node being decommissioned goes once HDFS and YARN have let it, or the time it was given is
    // up, and its containers' logs with it. It ends once the last core node to go is dead to the NameNode, or with its
    // cluster or a teardown. A round that fails, Docker or a command on the primary node failing, leaves every node as it
    // is, and the next asks again.
    private void decommission(NodeRequest request, long teardown) {
        String clusterId = request.clusterId();
        boolean ended = false;
        boolean failing = false;
        try {
            while (true) {
                try {
                    synchronized (starting.computeIfAbsent(clusterId, id -> new Object())) {
                        if (decommissionRound(request, teardown)) {
                            // Under the lock, so a resize from now on starts a thread of its own.
                            decommissioning.remove(clusterId);
                            ended = true;
                            return;
                        }
                    }
                    failing = false;
                } catch (RuntimeException e) {
                    if (failing) {
                        LOG.debugv(e, "Could not decommission the nodes of EMR cluster {0} again: {1}", clusterId,
                                e.getMessage());
                    } else {
                        LOG.warnv(e, "Could not decommission the nodes of EMR cluster {0}, which the next round tries"
                                + " again: {1}", clusterId, e.getMessage());
                    }
                    failing = true;
                }
                Thread.sleep(decommissionPoll.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (!ended) {
                decommissioning.remove(clusterId);
            }
        }
    }

    // One round of a cluster's decommissioning, under its lock: true once there is nothing left to do.
    private boolean decommissionRound(NodeRequest request, long teardown) {
        String clusterId = request.clusterId();
        Map<String, Draining> leaving = draining.get(clusterId);
        if (leaving == null || teardowns.get() != teardown) {
            return true;
        }
        Map<String, Long> gone = departed.computeIfAbsent(clusterId, id -> new ConcurrentHashMap<>());
        long now = nanoTime.getAsLong();
        gone.values().removeIf(at -> now - at >= DEAD_NODE_WAIT.toNanos());
        syncExcludes(clusterId, leaving.values());
        for (Map.Entry<String, Draining> node : List.copyOf(leaving.entrySet())) {
            boolean late = now >= node.getValue().deadline();
            if (late || decommissioned(request, node.getKey(), node.getValue(), teardown)) {
                if (late) {
                    LOG.warnv("Node {0} of EMR cluster {1} was not decommissioned in time, and goes anyway",
                            node.getValue().host(), clusterId);
                }
                if (removeDecommissioned(request, node.getKey())) {
                    leaving.remove(node.getKey());
                    if (node.getValue().core()) {
                        gone.put(node.getValue().host(), nanoTime.getAsLong());
                    }
                }
            } else if (!node.getValue().hdfsDone() && hdfsDecommissioned(clusterId, node.getValue())) {
                // YARN is told on the next round.
                leaving.put(node.getKey(), node.getValue().hdfsDecommissioned());
            }
        }
        if (leaving.isEmpty() && gone.isEmpty()) {
            syncExcludes(clusterId, leaving.values());
            return true;
        }
        return false;
    }

    // Whether YARN has finished with a node HDFS has finished with, or it has stopped with nothing left for HDFS to move.
    // A core node that stopped before HDFS moved its blocks holds them still, so it starts again on its data, and one
    // Docker cannot tell the state of is asked about again on the next round.
    private boolean decommissioned(NodeRequest request, String name, Draining node, long teardown) {
        ContainerLiveness liveness = lifecycleManager.probeContainer(name);
        if (liveness == ContainerLiveness.UNKNOWN) {
            return false;
        }
        if (liveness == ContainerLiveness.NOT_RUNNING) {
            if (node.hdfsDone()) {
                return true;
            }
            LOG.infov("The core node {0} of EMR cluster {1} stopped before HDFS moved its blocks, and starts again on"
                    + " its data", node.host(), request.clusterId());
            startNode(request, node.host(), true, name, teardown);
            return false;
        }
        return node.hdfsDone() && onPrimary(request.clusterId(), "curl -sf -m 5 '" + RESOURCE_MANAGER_NODES
                + "DECOMMISSIONED' | grep -q '\"nodeHostName\":\"" + node.host() + "\"'");
    }

    // Whether HDFS has moved a core node's blocks to the nodes that stay.
    private boolean hdfsDecommissioned(String clusterId, Draining node) {
        return onPrimary(clusterId, "curl -sf -m 5 '" + NAMENODE_JMX + "NameNodeInfo' | grep -Eq '" + node.host()
                + ":9866[^}]*Decommissioned'");
    }

    private boolean onPrimary(String clusterId, String command) {
        return ContainerExec.run(lifecycleManager.getDockerClient(), hdfsName(clusterId),
                new String[]{"bash", "-c", command}, NODE_COMMAND_SECONDS).exitCode() == 0;
    }

    // A node that has been decommissioned goes, with its data, as an EC2 instance's disks go with it, its containers'
    // logs in S3 first, while its container is there. Whether it has gone: one Docker keeps, or keeps the data of, is
    // tried again on the next round.
    private boolean removeDecommissioned(NodeRequest request, String name) {
        if (request.yarn().logFolder() != null && runningNodes.contains(name)) {
            archiveNodeLogs(request.clusterId(), name, request.accountId(), request.region(),
                    request.yarn().logFolder());
        }
        if (!removeNode(name, true)) {
            LOG.warnv("Docker kept node {0} of EMR cluster {1} or its data, which the next round removes",
                    name, request.clusterId());
            return false;
        }
        LOG.infov("Node {0} of EMR cluster {1} was decommissioned", name, request.clusterId());
        return true;
    }

    // Removes a node's container and, when asked, its volume; a removal Docker refuses leaves it known, so the next
    // reset or shutdown tries again. Whether it has gone.
    private boolean removeNode(String name, boolean withVolume) {
        try {
            lifecycleManager.removeIfExistsStrict(name);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not remove the EMR node container {0}: {1}", name, e.getMessage());
            return false;
        }
        runningNodes.remove(name);
        archivedLogs.remove(name);
        return !withVolume || lifecycleManager.removeVolume(name);
    }

    private record NodeRequest(String clusterId, String accountId, String region, String host, String imageId,
                               Yarn yarn) {

        NodeRequest resizedTo(Yarn resized) {
            return new NodeRequest(clusterId, accountId, region, host, imageId, resized);
        }
    }

    // A node being decommissioned, which goes anyway at the nanoTime deadline, its own, so one a later resize adds has
    // the whole time too. A core node goes to YARN's decommission only once HDFS has moved its blocks: a decommissioned
    // NodeManager exits, which ends its container, the DataNode with it.
    private record Draining(String host, boolean core, boolean hdfsDone, long deadline) {
        Draining(String host, boolean core, long deadline) {
            this(host, core, !core, deadline);
        }

        Draining hdfsDecommissioned() {
            return new Draining(host, core, true, deadline);
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

    // The JVM options that open to Hadoop's classes the packages its daemons reach into.
    private static String yarnOpens() {
        return EmrServerlessSparkRunner.yarnJavaOptions();
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
        if (!shared) {
            // Its core and task nodes reach it by its name on the network, which a new address does not change.
            spec.withDnsName(host);
        }
        if (yarn != null) {
            spec.withEnv(YARN_MEMORY_VARIABLE, String.valueOf(yarn.memoryMb()))
                    .withEnv(YARN_VCORES_VARIABLE, String.valueOf(yarn.vcores()))
                    .withEnv(REPLICATION_VARIABLE, String.valueOf(yarn.replication()));
            if (!yarn.primaryWorks()) {
                spec.withEnv(NODE_WORKERS_VARIABLE, "1");
            }
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
        LOG.warnv("A node of EMR cluster {0}, {1}, did not become ready. It last wrote:\n{2}", clusterId, containerId,
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

    // The name of a cluster's core or task node's container, and of the volume its data is in.
    private String nodeName(String clusterId, Node node) {
        return ContainerStorageHelper.dockerName(config, SERVICE + "-" + clusterId + "-node-" + node.id());
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
        nodeRequests.remove(clusterId);
        draining.remove(clusterId);
        departed.remove(clusterId);
        boolean nodesGone = true;
        try {
            for (String node : nodeContainers(clusterId).keySet()) {
                nodesGone &= removeNode(node, true);
            }
            // A node's data whose container a shutdown removed, which no list of containers finds.
            for (String volume : nodeVolumes(clusterId).keySet()) {
                nodesGone &= lifecycleManager.removeVolume(volume);
            }
        } catch (RuntimeException e) {
            // The rest is removed all the same, and the cluster's storage is asked for again.
            LOG.warnv(e, "Could not find the nodes of EMR cluster {0} to remove: {1}", clusterId, e.getMessage());
            nodesGone = false;
        }
        boolean containerGone = removeKnown(hdfsName(clusterId));
        SparkJobRunner.Storage storage = volumes(clusterId);
        // Each is tried, so one Docker refused does not keep the others.
        boolean networkGone = lifecycleManager.removeNetwork(hdfsName(clusterId));
        boolean homeGone = lifecycleManager.removeVolume(storage.home());
        boolean mntGone = lifecycleManager.removeVolume(storage.mnt());
        return lifecycleManager.removeVolume(hdfsName(clusterId)) && mntGone && homeGone && networkGone
                && containerGone && nodesGone;
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
        nodeRequests.clear();
        draining.clear();
        departed.clear();
        for (String name : List.copyOf(runningNodes)) {
            removeNode(name, false);
        }
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
                    .withLabelFilter(Map.of(ContainerStorageHelper.COMPONENT_LABEL, NODE_COMPONENT,
                            ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config)))
                    .exec()) {
                Map<String, String> labels = container.getLabels() != null ? container.getLabels() : Map.of();
                String name = container.getNames()[0].substring(1);
                runningNodes.add(name);
                if (!kept.contains(labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL))) {
                    removeNode(name, false);
                }
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not remove the nodes of EMR clusters that have ended: {0}", e.getMessage());
        }
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
