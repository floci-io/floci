package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class RedshiftService {
    private final AccountAwareStorageBackend<Cluster> clusters;
    private final RedshiftContainerManager containerManager;

    @Inject
    public RedshiftService(StorageFactory storageFactory, RedshiftContainerManager containerManager) {
        this.clusters = storageFactory.create("redshift", "redshift-clusters.json", new TypeReference<Map<String, Cluster>>() {});
        this.containerManager = containerManager;
    }

    public Cluster createCluster(String identifier, String nodeType, String username, String password) {
        if (clusters.get(identifier).isPresent()) {
            throw new AwsException("ClusterAlreadyExists", "Cluster " + identifier + " already exists", 400);
        }

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(identifier);
        cluster.setNodeType(nodeType);
        cluster.setMasterUsername(username);
        cluster.setClusterStatus("creating");
        clusters.put(identifier, cluster);
        clusters.flush();

        // Start container
        try {
            RedshiftContainerHandle handle = containerManager.start(identifier, username, password);
            Endpoint endpoint = new Endpoint();
            endpoint.setAddress(handle.getHost());
            endpoint.setPort(handle.getPort());
            cluster.setEndpoint(endpoint);
            cluster.setClusterStatus("available");
        } catch (Exception e) {
            cluster.setClusterStatus("failed");
            clusters.flush();
            throw new AwsException("InternalFailure", "Failed to start container: " + e.getMessage(), 500);
        }
        
        clusters.put(identifier, cluster);
        clusters.flush();
        return cluster;
    }

    public List<Cluster> describeClusters(String identifier) {
        if (identifier != null) {
            Optional<Cluster> cluster = clusters.get(identifier);
            if (cluster.isEmpty()) {
                throw new AwsException("ClusterNotFound", "Cluster " + identifier + " not found", 404);
            }
            return List.of(cluster.get());
        }
        return clusters.scan(k -> true);
    }

    public Cluster deleteCluster(String identifier) {
        Optional<Cluster> clusterOpt = clusters.get(identifier);
        if (clusterOpt.isEmpty()) {
            throw new AwsException("ClusterNotFound", "Cluster " + identifier + " not found", 404);
        }
        Cluster cluster = clusterOpt.get();
        
        containerManager.stop(identifier);
        clusters.delete(identifier);
        clusters.flush();
        
        cluster.setClusterStatus("deleting");
        return cluster;
    }
}
