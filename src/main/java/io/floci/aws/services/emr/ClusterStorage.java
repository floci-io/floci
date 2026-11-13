package io.floci.aws.services.emr;

import io.floci.aws.services.emrserverless.SparkJobRunner;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A cluster's storage in the EMR {@code docker} runner mode: the disks of its primary node, which every one of
 * its steps' containers mounts, so what a step leaves in {@code /home/hadoop}, or in HDFS, is there for the
 * next, until the cluster ends ("HDFS is ephemeral storage that is reclaimed when you terminate a cluster").
 * {@link EmrClusterStorage} is the Docker implementation, and a test supplies its own.
 */
public interface ClusterStorage {

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
     * Starts {@code clusterId}'s HDFS, a NameNode and a DataNode keeping their data in a volume of their own, and,
     * unless {@code hiveMetastore} is null, Hive's metastore service, and unless {@code yarn} is null, YARN, in a
     * container of {@code image} on a Docker network of the cluster's own, or on the host's when its steps run there,
     * unless it is running, and returns the cluster's storage with them: its primary node's {@code host} name, which a
     * step's container resolves to the address given, on the network given, the metastore's URI, and YARN's
     * ResourceManager with the address Floci has on that network, the metastore and YARN only on a network of the
     * cluster's own. Its core and task nodes, {@code yarn}'s, run in containers of their own on that network, and
     * {@code yarn} is read as they start, so a resize since the step was queued counts. A failure to start them is
     * thrown, and {@link Stopped} once {@code stopped} says the step is past starting, or a teardown
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
     * {@code core-site} and {@code hive-site} properties, after its own.
     */
    record HiveMetastore(String role, Map<String, String> settings) {
    }

    /**
     * YARN on a cluster: a ResourceManager on its primary node, and a NodeManager on each of its {@code nodes}, and on
     * the primary node too when it {@code primaryWorks}, each offering its containers {@code memoryMb} of memory and
     * {@code vcores} virtual cores, which run its steps' Spark executors; the folder of the nodes' image holding the
     * Hadoop their daemons run from, or null for the image's own; the copies of a block its HDFS keeps; and the S3
     * folder its applications' logs go in, or null. A primary node that works runs a DataNode as well.
     */
    record Yarn(int memoryMb, int vcores, String hadoopFolder, List<Node> nodes, int replication, String logFolder,
                boolean primaryWorks) {

        Yarn(int memoryMb, int vcores) {
            this(memoryMb, vcores, null, List.of(), 1, null, true);
        }

        /** This YARN with its daemons run from the Hadoop in {@code folder}. */
        Yarn withHadoopFolder(String folder) {
            return new Yarn(memoryMb, vcores, folder, nodes, replication, logFolder, primaryWorks);
        }

        /**
         * This YARN on these nodes, its HDFS keeping {@code copies} of a block, its logs going to {@code folder}, its
         * primary node working when none of them is a core node.
         */
        Yarn onNodes(List<Node> workers, int copies, String folder) {
            return new Yarn(memoryMb, vcores, hadoopFolder, List.copyOf(workers), copies, folder,
                    workers.stream().noneMatch(Node::core));
        }

        /** This YARN with its primary node running a DataNode and a NodeManager, or not. */
        Yarn withPrimaryWorking(boolean works) {
            return new Yarn(memoryMb, vcores, hadoopFolder, nodes, replication, logFolder, works);
        }
    }

    /**
     * A core or task node of a cluster: {@code id} tells its container from its cluster's others, {@code host} is its
     * private DNS name, and a {@code core} node holds a DataNode beside its NodeManager.
     */
    record Node(String id, String host, boolean core) {
    }

    /**
     * Removes {@code clusterId}'s HDFS and the volumes of its storage once it no longer needs them: true when the
     * volumes are gone, false when Docker refused, so the caller can try again.
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
