package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Runs the container-backed data planes the test defaults mock or leave off, for the classes that
 * test them. Quarkus builds the application once per distinct profile class, so they share this
 * one rather than each declaring their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.services.timestream-influxdb.mock=false} backs each DB instance with an
 *   InfluxDB container, published on a host port from {@code host-port-base} to
 *   {@code host-port-max}, set here to 18186 and 18285. {@code TimestreamInfluxDbDockerIntegrationTest}
 *   relies on it.</li>
 *   <li>{@code floci.services.eks.imds=true} and {@code floci.services.eks.imds-pod-network=true}
 *   program the link-local IMDS endpoint into a cluster's container, for the node and for its pods.
 *   {@code EksImdsDockerIntegrationTest} relies on them.</li>
 * </ul>
 *
 * <p>Each override is inert for the other class. An InfluxDB container only starts when a DB
 * instance is created, which the IMDS test never does. The IMDS settings only act when a cluster's
 * container starts, and the test defaults keep EKS mocked, so only {@code EksImdsDockerIntegrationTest},
 * which configures the endpoint on its own container, reaches them. The metadata server it starts
 * stays up for the Timestream test, on a port that test does not use.
 *
 * <p>The ELBv2 data-plane classes stay on {@link RealElbV2DataPlaneProfile}: one of them,
 * {@code ElbV2WebSocketIntegrationTest}, injects a spy, and a class that does is not merged into
 * another group.
 */
public class ContainerDataPlaneProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.services.timestream-influxdb.mock", "false",
                "floci.services.timestream-influxdb.host-port-base", "18186",
                "floci.services.timestream-influxdb.host-port-max", "18285",
                "floci.services.eks.imds", "true",
                "floci.services.eks.imds-pod-network", "true");
    }
}
