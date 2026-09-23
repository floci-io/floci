package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Mocks both the RDS and the DocumentDB container layers, for the classes that span the two.
 */
public class RdsAndDocDbMockProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.services.rds.mock", "true", "floci.services.docdb.mock", "true");
    }
}
