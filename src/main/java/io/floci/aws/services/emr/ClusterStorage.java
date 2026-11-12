package io.floci.aws.services.emr;

import io.floci.aws.services.emrserverless.SparkJobRunner;

import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

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
     * cluster's own. A failure to start them is thrown, and {@link Stopped} once {@code stopped} says the step is past
     * starting, or a teardown has stopped the HDFS.
     */
    SparkJobRunner.Storage hdfs(String clusterId, String accountId, String region, String host, String image,
                                String dockerNetwork, HiveMetastore hiveMetastore, Yarn yarn, BooleanSupplier stopped);

    /**
     * Puts the logs of the YARN applications on {@code clusterId}'s primary node in {@code logFolder}, an S3 folder
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
     * YARN on a cluster's primary node: a ResourceManager, and a NodeManager offering its containers
     * {@code memoryMb} of memory and {@code vcores} virtual cores, which run its steps' Spark executors, and the folder
     * of the node's image holding the Hadoop its daemons run from, or null for the image's own.
     */
    record Yarn(int memoryMb, int vcores, String hadoopFolder) {

        Yarn(int memoryMb, int vcores) {
            this(memoryMb, vcores, null);
        }

        /** This YARN with its daemons run from the Hadoop in {@code folder}. */
        Yarn withHadoopFolder(String folder) {
            return new Yarn(memoryMb, vcores, folder);
        }
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
