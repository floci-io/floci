package io.floci.aws.services.dsql;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Clusters with a real database in Docker, served on an ephemeral port, with IAM enforcement on,
 * for what a cluster's policy decides on connect.
 */
public final class DsqlEnforcementProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.services.dsql.mock", "false",
                "floci.services.iam.enforcement-enabled", "true");
    }
}
