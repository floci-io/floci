package io.github.hectorvent.floci.services.emr;

import io.github.hectorvent.floci.services.emrserverless.SparkJobRunner;

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
     * Starts {@code clusterId}'s HDFS, a NameNode and a DataNode keeping their data in a volume of their own, in a
     * container of {@code image} on a Docker network of the cluster's own, or on the host's when its steps run there,
     * unless it is running, and returns the cluster's storage with it: its primary node's {@code host} name, which a
     * step's container resolves to the address given, on the network given. A failure to start it is thrown, and
     * {@link Stopped} once {@code stopped} says the step is past starting, or a teardown has stopped the HDFS.
     */
    SparkJobRunner.Storage hdfs(String clusterId, String accountId, String region, String host, String image,
                                String dockerNetwork, BooleanSupplier stopped);

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
