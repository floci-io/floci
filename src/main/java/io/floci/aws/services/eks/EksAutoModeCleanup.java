package io.floci.aws.services.eks;

import io.floci.aws.services.eks.model.Cluster;

/** An EKS Auto Mode capability with resources of its own outside the cluster, which go when the cluster is deleted. */
interface EksAutoModeCleanup {

    /** Deletes what the capability made for {@code cluster}, as the request deleting it runs. */
    void clusterDeleted(Cluster cluster, String region);
}
