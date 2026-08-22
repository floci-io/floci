package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.model.Cluster;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@ApplicationScoped
public class RedshiftService {
    private final Map<String, Cluster> clusters = new ConcurrentHashMap<>();

    public Cluster createCluster(String identifier, String nodeType, String username, String password) {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(identifier);
        cluster.setNodeType(nodeType);
        cluster.setMasterUsername(username);
        cluster.setClusterStatus("creating");
        clusters.put(identifier, cluster);
        return cluster;
    }
}
