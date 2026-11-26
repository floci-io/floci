package io.floci.aws.services.emr;

import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.emrserverless.SparkJobRunner;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A cluster's storage in the EMR {@code docker} runner mode: the disks of its primary node, which every one of
 * its steps' containers mounts, so what a step leaves in {@code /home/hadoop}, or in HDFS, is there for the
 * next, until the cluster ends ("HDFS is ephemeral storage that is reclaimed when you terminate a cluster").
 * {@link EmrClusterStorage} is the Docker implementation, and a test supplies its own.
 */
public interface ClusterStorage {

    /** The name {@link #identify} knows a cluster's primary node by: the first instance of its group numbered 0. */
    String PRIMARY_NODE = "0-1";

    /** The volumes holding {@code clusterId}'s storage, whether or not they are there yet. */
    SparkJobRunner.Storage volumes(String clusterId);

    /** Makes the volumes of {@code clusterId}'s storage, in its account and Region, when they are not there. */
    void create(String clusterId, String accountId, String region);

    /**
     * Whether a cluster's primary node runs on a Docker network of the cluster's own with its steps on
     * {@code dockerNetwork}: not on one with no network, nor in a namespace the steps share, where a service on it
     * would serve every interface of the namespace.
     */
    boolean ownNetwork(String dockerNetwork);

    /**
     * Whether a cluster whose steps run on {@code dockerNetwork} has an instance metadata relay on a network of its
     * own, which {@link #metadataNetwork} makes.
     */
    default boolean metadataServed(String dockerNetwork) {
        return false;
    }

    /**
     * Cluster {@code clusterId}'s network, its own, made when missing, with its instance metadata relay running there in
     * {@code relayImage}, which the cluster's containers join to read their nodes' metadata at 169.254.169.254; null
     * where its steps share a namespace or have no network, or no relay serves the metadata.
     */
    default String metadataNetwork(String clusterId, String accountId, String region, String relayImage,
                                   String dockerNetwork) {
        return null;
    }

    /**
     * A link-local address on cluster {@code clusterId}'s network, which {@link #metadataNetwork} made, for a container
     * that is {@code instance}, whose metadata its relay answers until the address is released; null where none is.
     */
    default String metadataAddress(String clusterId, Instance instance) {
        return null;
    }

    /** The container given {@code address} has gone, and its metadata with it. */
    default void releaseMetadataAddress(String address) {
    }

    /**
     * How cluster {@code clusterId}'s nodes are known as EC2 instances, by node name: {@link #PRIMARY_NODE} its primary
     * node, {@code <group>-<n>} each core or task node, as {@link Node#id} has it; each node's container started
     * from then on is given its metadata.
     */
    default void identify(String clusterId, Function<String, Instance> nodes) {
    }

    /**
     * Starts {@code clusterId}'s HDFS, a NameNode and a DataNode keeping their data in a volume of their own, and,
     * unless {@code hiveMetastore} is null, Hive's metastore service, and unless {@code yarn} is null, YARN, in a
     * container of {@code image} on a Docker network of the cluster's own, or on the host's when its steps run there,
     * unless it is running, and returns the cluster's storage with them: its primary node's {@code host} name, which a
     * step's container resolves to the address given, on the network given, the metastore's URI, and YARN's
     * ResourceManager with the address Floci has on that network, the metastore and YARN only on a network of the
     * cluster's own, where Trino's coordinator runs too, in a container of its own on the primary node's network,
     * when {@code hiveMetastore} asks for it. Its core and task nodes, {@code yarn}'s, run in containers of their own
     * on that network, and {@code yarn} is read as they start, so a resize since the step was queued counts. A failure
     * to start them is thrown, and {@link Stopped} once {@code stopped} says the step is past starting, or a teardown
     * has stopped the HDFS.
     */
    SparkJobRunner.Storage hdfs(String clusterId, String accountId, String region, String host, String image,
                                String dockerNetwork, HiveMetastore hiveMetastore, Supplier<Yarn> yarn,
                                BooleanSupplier stopped);

    /**
     * Brings {@code clusterId}'s core and task nodes to what {@code yarn} says at that moment, when a step has started
     * them: a node that is new starts, and one that is no longer wanted is decommissioned and then goes, while the rest
     * carry on. Clusters no step has started yet get theirs when one does.
     */
    void resize(String clusterId, Supplier<Yarn> yarn);

    /**
     * Puts the logs of the YARN applications on {@code clusterId}'s nodes in {@code logFolder}, an S3 folder
     * ending in a slash, each container's files gzipped in {@code <application>/<container>/}, those already there
     * and unchanged left as they are. Logs that cannot be read or written leave the step as it ended, and are only
     * logged.
     */
    void archiveContainerLogs(String clusterId, String accountId, String region, String logFolder);

    /**
     * Hive's metastore service on a cluster's primary node, which reaches S3 as the cluster's EC2 instance profile's
     * {@code role} does, or as its account when that is null, with the cluster's {@code settings}, its
     * {@code core-site} and {@code hive-site} properties, after its own, and {@code trino} beside it unless that is
     * null, as EMR installs {@code hcatalog-server} with Trino.
     */
    record HiveMetastore(String role, Map<String, String> settings, Trino trino) {

        /** The metastore alone. */
        HiveMetastore(String role, Map<String, String> settings) {
            this(role, settings, null);
        }
    }

    /**
     * Trino's coordinator on a cluster's primary node: the {@code image} it runs from, the memory its container gets,
     * the cluster's {@code trino-connector-<catalog>} properties by catalog, which its catalogs get after Floci's (see
     * {@link EmrTrino#catalogs}), its {@code trino-config} properties, which its {@code config.properties} gets after
     * Floci's (see {@link EmrTrino#config}), its {@code trino-node} and {@code trino-log} properties, which its
     * {@code node.properties} and {@code log.properties} get after its image's own (see {@link EmrTrino#ENTRYPOINT}),
     * and the variables its {@code trino-env} exports, which its environment gets after Floci's own. It reaches S3 and
     * Glue as the metastore does.
     */
    record Trino(String image, int memoryMb, Map<String, Map<String, String>> connectors, Map<String, String> config,
                 Map<String, String> node, Map<String, String> log, Map<String, String> environment) {
    }

    /**
     * YARN on a cluster: a ResourceManager on its primary node, and a NodeManager on each of its {@code nodes}, and on
     * the primary node too when it {@code primaryWorks}, each offering its containers {@code memoryMb} of memory and
     * {@code vcores} virtual cores, which run its steps' Spark executors; the folder of the nodes' image holding the
     * Hadoop their daemons run from, or null for the image's own; the copies of a block its HDFS keeps; the S3
     * folder its applications' logs go in, or null; whether its primary node runs a Spark History Server, as on a
     * cluster with Spark; and whether it runs the YARN timeline server, as on a cluster with Hadoop, and the Tez UI, as
     * on one with Tez. A primary node that works runs a DataNode as well.
     */
    record Yarn(int memoryMb, int vcores, String hadoopFolder, List<Node> nodes, int replication, String logFolder,
                boolean primaryWorks, boolean sparkHistory, boolean timelineServer, boolean tezUi) {

        Yarn(int memoryMb, int vcores) {
            this(memoryMb, vcores, null, List.of(), 1, null, true, false, false, false);
        }

        /** This YARN with its daemons run from the Hadoop in {@code folder}. */
        Yarn withHadoopFolder(String folder) {
            return new Yarn(memoryMb, vcores, folder, nodes, replication, logFolder, primaryWorks, sparkHistory,
                    timelineServer, tezUi);
        }

        /**
         * This YARN on these nodes, its HDFS keeping {@code copies} of a block, its logs going to {@code folder}, its
         * primary node working when none of them is a core node.
         */
        Yarn onNodes(List<Node> workers, int copies, String folder) {
            return new Yarn(memoryMb, vcores, hadoopFolder, List.copyOf(workers), copies, folder,
                    workers.stream().noneMatch(Node::core), sparkHistory, timelineServer, tezUi);
        }

        /** This YARN with its primary node running a DataNode and a NodeManager, or not. */
        Yarn withPrimaryWorking(boolean works) {
            return new Yarn(memoryMb, vcores, hadoopFolder, nodes, replication, logFolder, works, sparkHistory,
                    timelineServer, tezUi);
        }

        /** This YARN with its primary node running the YARN timeline server, and the Tez UI, or not. */
        Yarn withHistories(boolean timeline, boolean tez) {
            return new Yarn(memoryMb, vcores, hadoopFolder, nodes, replication, logFolder, primaryWorks, sparkHistory,
                    timeline, tez);
        }

        /** This YARN with its primary node running a Spark History Server, or not. */
        Yarn withSparkHistory(boolean runs) {
            return new Yarn(memoryMb, vcores, hadoopFolder, nodes, replication, logFolder, primaryWorks, runs,
                    timelineServer, tezUi);
        }
    }

    /**
     * A core or task node of a cluster: {@code id} tells its container from its cluster's others, {@code host} is its
     * private DNS name, a {@code core} node holds a DataNode beside its NodeManager, and {@code image} is the image its
     * own bootstrap left, which it runs from, null where it has none and runs from the steps' image.
     */
    record Node(String id, String host, boolean core, String image) {

        Node(String id, String host, boolean core) {
            this(id, host, core, null);
        }
    }

    /**
     * Where Floci reaches {@code port}, a web interface's, on {@code clusterId}'s primary node: empty while the node is
     * not running and ready, or where Floci cannot reach it.
     */
    default Optional<ContainerLifecycleManager.EndpointInfo> webInterface(String clusterId, int port) {
        return Optional.empty();
    }

    /**
     * Where Floci reaches {@code port}, its Trino's, on {@code clusterId}'s primary node: empty until that Trino has
     * answered queries ({@link #hdfs}), and while it is not running, or where Floci cannot reach it.
     */
    default Optional<ContainerLifecycleManager.EndpointInfo> trinoInterface(String clusterId, int port) {
        return Optional.empty();
    }

    /**
     * Keeps what a cluster's history interfaces show once it has ended, before its storage goes: the Spark
     * applications' event logs, copied out of its HDFS into the volume of its primary node that already holds the
     * YARN timeline server's store, which outlives the cluster's other storage until {@link #removeHistoriesBut} lets
     * it go: true once they are kept, or where there is nothing it can keep, the node not running or keeping no
     * history; false where the copy failed, so the caller keeps the storage, which holds them, and tries again.
     */
    default boolean keepHistory(String clusterId) {
        return true;
    }

    /** Whether a cluster's history is kept. */
    default boolean historyKept(String clusterId) {
        return false;
    }

    /**
     * Where Floci reaches {@code port} of the container that serves a cluster's kept history: its Spark History
     * Server, YARN timeline server and Tez UI, as {@code yarn} had its primary node run them, their links naming
     * {@code primaryHost}, as the node's did. Started, as {@code accountId} in {@code region}, in the image
     * {@code image} gives, {@code released} told once the container is made, where it is not running; empty until it
     * answers.
     */
    default Optional<ContainerLifecycleManager.EndpointInfo> historyInterface(String clusterId, String accountId,
                                                                              String region, Supplier<String> image,
                                                                              Consumer<String> released,
                                                                              String primaryHost, Yarn yarn,
                                                                              int port) {
        return Optional.empty();
    }

    /** Stops the container serving a cluster's kept history, which a later link starts again. */
    default void stopHistory(String clusterId) {
    }

    /** Removes the kept history of every cluster but those in {@code kept}, and the container serving it. */
    default void removeHistoriesBut(Set<String> kept) {
    }

    /**
     * Removes {@code clusterId}'s HDFS and the volumes of its storage once it no longer needs them: true when the
     * volumes are gone, false when Docker refused, so the caller can try again. Its kept history stays.
     */
    boolean remove(String clusterId);

    /**
     * Removes the storage of every cluster of this deployment but those in {@code kept}, by id: after a
     * restart, the storage of the clusters that are gone or have ended, and on a reset all of it.
     */
    void removeAllBut(Set<String> kept);

    /** A step stopped while it waited for its cluster's HDFS, by a cancel, its cluster's end or a teardown. */
    final class Stopped extends RuntimeException {

        public Stopped(String message) {
            super(message);
        }
    }
}
