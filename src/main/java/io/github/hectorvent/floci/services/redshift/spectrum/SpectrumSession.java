package io.github.hectorvent.floci.services.redshift.spectrum;

import java.util.List;

public record SpectrumSession(String accountId, String clusterKey, String databaseName,
                              List<String> iamRoleArns, boolean inTransaction) {
    public SpectrumSession {
        iamRoleArns = iamRoleArns == null ? List.of() : List.copyOf(iamRoleArns);
    }
}
