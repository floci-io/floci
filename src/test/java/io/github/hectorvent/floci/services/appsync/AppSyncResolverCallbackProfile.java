package io.github.hectorvent.floci.services.appsync;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link AppSyncGraphqlSidecarProfile} plus a Floci base URL the sidecar container can actually
 * reach back on.
 *
 * <p>The resolver callback URL is built from {@code floci.base-url}'s port, which in production is
 * the port Floci listens on. Under {@code @QuarkusTest} the application listens on the test port
 * instead, so without this the sidecar would call back to 4566 and every resolver-backed field
 * would fail with a connection error. Both values are pinned together here so they cannot drift.
 */
public class AppSyncResolverCallbackProfile extends AppSyncGraphqlSidecarProfile {

    private static final String TEST_PORT = "8081";

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new LinkedHashMap<>(super.getConfigOverrides());
        overrides.put("quarkus.http.test-port", TEST_PORT);
        overrides.put("floci.base-url", "http://localhost:" + TEST_PORT);
        return overrides;
    }
}
