package io.floci.aws.services.redshift.spectrum;

import java.util.List;

public record SpectrumSession(String accountId, String clusterKey, String databaseName,
                              List<String> iamRoleArns, boolean inTransaction) {
    public SpectrumSession {
        iamRoleArns = iamRoleArns == null ? List.of() : List.copyOf(iamRoleArns);
    }

    /** {@link #clusterIdentifier(String)} of this session's cluster key. */
    public String clusterIdentifier() {
        return clusterIdentifier(clusterKey);
    }

    /**
     * The cluster identifier a cluster key {@code <account>:<identifier>} names, the key the proxy and the Data
     * API run a cluster's sessions under; the whole key when it has no account part, and null for none.
     */
    public static String clusterIdentifier(String clusterKey) {
        if (clusterKey == null) {
            return null;
        }
        int separator = clusterKey.indexOf(':');
        return separator < 0 ? clusterKey : clusterKey.substring(separator + 1);
    }
}
