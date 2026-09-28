package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Pins the Floci HTTP port so the DuckDB sidecar can reach Floci S3 over the network, and names
 * both billing emitters as off so a report is only written when the test drives the emitter
 * itself. {@code CurEmissionScheduler} acts only on {@code daily} and {@code synchronous}, so
 * every other value leaves the emitters idle; the configured test default already resolves to one
 * of those inert values, and spelling it out here keeps the assumption visible.
 *
 * <p>Quarkus builds the application once per distinct profile class, so the classes that need this
 * share one profile rather than each declaring their own.
 */
public class FixedPortNoEmissionProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.http.test-port", "4566",
                "floci.base-url", "http://localhost:4566",
                "floci.services.cur.emit-mode", "off",
                "floci.services.bcm-data-exports.emit-mode", "off");
    }
}
