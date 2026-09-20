package io.github.hectorvent.floci.services.redshift;

import java.util.List;

/**
 * Static answers for the read-only "what can I order" APIs that IaC providers call before creating a
 * cluster. Every emulated cluster is one PostgreSQL container, so these values only need to be
 * accepted by SDK validation, not to describe real capacity.
 */
final class RedshiftClusterCatalog {

    static final String CLUSTER_VERSION = "1.0";
    static final String PARAMETER_GROUP_FAMILY = "redshift-1.0";

    record OrderableOption(String clusterType, String nodeType) {}

    private static final List<OrderableOption> ORDERABLE_OPTIONS = List.of(
            new OrderableOption("single-node", "dc2.large"),
            new OrderableOption("multi-node", "dc2.large"),
            new OrderableOption("multi-node", "dc2.8xlarge"),
            new OrderableOption("multi-node", "ra3.xlplus"),
            new OrderableOption("multi-node", "ra3.4xlarge"),
            new OrderableOption("multi-node", "ra3.16xlarge"));

    private RedshiftClusterCatalog() {
    }

    static List<OrderableOption> orderableOptions(String clusterVersion, String nodeType) {
        if (clusterVersion != null && !clusterVersion.isBlank() && !CLUSTER_VERSION.equals(clusterVersion)) {
            return List.of();
        }
        return ORDERABLE_OPTIONS.stream()
                .filter(option -> nodeType == null || nodeType.isBlank() || option.nodeType().equals(nodeType))
                .toList();
    }
}
