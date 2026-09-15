package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * Query-protocol operations that RDS, DocumentDB, and Neptune answer identically, since
 * DocumentDB and Neptune both accept the RDS-family wire protocol and claim compatibility for
 * these operations. Real SDKs sign DocumentDB and Neptune requests with the "rds" scope and can
 * land on any of the three handlers, so a correction here reaches all of them at once.
 */
public final class RdsFamilyQuerySupport {

    private RdsFamilyQuerySupport() {
    }

    /**
     * Answers DescribeGlobalClusters the same way for all three services: global clusters are
     * not modeled, so the response is always an empty list, once MaxRecords,
     * GlobalClusterIdentifier, and Marker have been validated the way a live account would.
     *
     * <p>MaxRecords is rejected before the identifier is looked up, and a marker after it, the
     * order a live account applies them in. Naming a specific identifier is a different question
     * from listing none, and AWS errors on it. Filters are accepted without validation, since the
     * answer is empty for every name AWS accepts, and a partial list of accepted names would
     * reject filters a live account allows.
     */
    public static Response handleDescribeGlobalClusters(Logger log, MultivaluedMap<String, String> params) {
        String maxRecords = params.getFirst("MaxRecords");
        if (maxRecords != null && !maxRecords.isBlank()) {
            int max = -1;
            try {
                max = Integer.parseInt(maxRecords.trim());
            } catch (NumberFormatException e) {
                log.debugv("Non-numeric MaxRecords {0} on DescribeGlobalClusters", maxRecords);
            }
            if (max < 20 || max > 100) {
                throw new AwsException("InvalidParameterValue",
                        "Invalid value " + maxRecords + " for MaxRecords. Must be between 20 and 100", 400);
            }
        }
        String identifier = params.getFirst("GlobalClusterIdentifier");
        if (identifier != null && !identifier.isBlank()) {
            throw new AwsException("GlobalClusterNotFoundFault",
                    "Global cluster '" + identifier + "' not found", 404);
        }
        String marker = params.getFirst("Marker");
        if (marker != null && !marker.isBlank()) {
            throw new AwsException("InvalidParameterValue", "The request token is invalid.", 400);
        }
        String xml = new XmlBuilder().start("GlobalClusters").end("GlobalClusters").build();
        return Response.ok(AwsQueryResponse.envelope("DescribeGlobalClusters", AwsNamespaces.RDS, xml)).build();
    }
}
