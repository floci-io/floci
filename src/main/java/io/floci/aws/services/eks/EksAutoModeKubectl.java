package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.services.eks.model.Cluster;
import org.jboss.logging.Logger;

/** The Kubernetes objects EKS Auto Mode's load balancing reads and writes, through {@code kubectl} in the node. */
final class EksAutoModeKubectl {

    private static final Logger LOG = Logger.getLogger(EksAutoModeKubectl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EksClusterManager clusterManager;

    EksAutoModeKubectl(EksClusterManager clusterManager) {
        this.clusterManager = clusterManager;
    }

    /** {@code kubectl get <kind> -A -o json}: every object of {@code kind} in the cluster. */
    JsonNode list(Cluster cluster, String kind) {
        ContainerExec.Result result = clusterManager.execInContainerForResult(cluster.getContainerId(),
                new String[]{"kubectl", "get", kind, "-A", "-o", "json"}, 15);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("kubectl get " + kind + " failed: " + result.summary());
        }
        try {
            return MAPPER.readTree(result.stdout());
        } catch (Exception e) {
            throw new IllegalStateException("kubectl get " + kind + " returned no JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Writes a load balancer's DNS name to an object's {@code status.loadBalancer}, as the controller on AWS does.
     * The patch names the object's UID, so one made again under the same name meanwhile refuses it.
     */
    void writeHostname(Cluster cluster, String kind, String namespace, String name, String uid, String dnsName) {
        String identity = uid.isEmpty() ? "" : "\"metadata\":{\"uid\":\"" + uid + "\"},";
        String patch = "{" + identity + "\"status\":{\"loadBalancer\":{\"ingress\":[{\"hostname\":\"" + dnsName
                + "\"}]}}}";
        ContainerExec.Result result = clusterManager.execInContainerForResult(cluster.getContainerId(),
                new String[]{"kubectl", "patch", kind, name, "-n", namespace, "--subresource=status", "--type=merge",
                        "-p", patch}, 15);
        if (result.exitCode() != 0) {
            LOG.warnv("Could not write the load balancer of {0} {1}/{2} to its status: {3}", kind, namespace, name,
                    result.summary());
        }
    }
}
