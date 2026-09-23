package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Mocks the DocumentDB container layer, and nothing else. DocumentDB shares the RDS API surface,
 * so this deliberately leaves {@code floci.services.rds.mock} alone: the classes using it exercise
 * those shared paths unmocked.
 */
public class DocDbMockProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.services.docdb.mock", "true");
    }
}
