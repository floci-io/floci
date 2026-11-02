package io.github.hectorvent.floci.services.emr;

import io.github.hectorvent.floci.services.emrserverless.SparkJobRunner;

import java.util.Set;

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
     * Removes the volumes of {@code clusterId}'s storage once it no longer needs them: true when they are gone,
     * false when Docker refused, so the caller can try again.
     */
    boolean remove(String clusterId);

    /**
     * Removes the storage of every cluster of this deployment but those in {@code kept}, by id: after a
     * restart, the storage of the clusters that are gone or have ended, and on a reset all of it.
     */
    void removeAllBut(Set<String> kept);
}
