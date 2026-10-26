package io.github.hectorvent.floci.services.dsql;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.HashMap;
import java.util.Map;

/** {@link DsqlDatabasesProfile}'s clusters with IAM enforcement on, for what a cluster's policy decides on connect. */
public final class DsqlEnforcementProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(new DsqlDatabasesProfile().getConfigOverrides());
        overrides.put("floci.services.iam.enforcement-enabled", "true");
        return overrides;
    }
}
