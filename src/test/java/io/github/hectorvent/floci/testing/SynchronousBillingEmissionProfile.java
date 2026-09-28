package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Wires the full sync path for the billing report emitters: emit-mode=synchronous plus a fixed
 * Floci HTTP port, so the DuckDB sidecar can reach Floci S3 over the network. Quarkus builds the
 * application once per distinct profile class, so the classes that need this share one profile
 * rather than each declaring an identical nested copy.
 */
public class SynchronousBillingEmissionProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.http.test-port", "4566",
                "floci.base-url", "http://localhost:4566",
                "floci.services.cur.emit-mode", "synchronous",
                "floci.services.bcm-data-exports.emit-mode", "synchronous");
    }
}
