package io.floci.aws.services.dsql;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/** Clusters with a real database in Docker, served on an ephemeral port. */
public final class DsqlDatabasesProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.services.dsql.mock", "false", "floci.services.dsql.port", "0");
    }
}
