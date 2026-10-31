package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigatewayv2.proxy.CloudMapInstanceResolver.Endpoint;
import io.github.hectorvent.floci.services.cloudmap.CloudMapService;
import io.github.hectorvent.floci.services.cloudmap.model.Instance;
import io.github.hectorvent.floci.services.cloudmap.model.Service;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudMapInstanceResolverTest {

    private static final String REGION = "us-east-1";
    private static final String CUSTOM_HEALTH_CHECK = "{\"FailureThreshold\":1}";
    private static final String HEALTH_CHECK_CONFIG = "{\"Type\":\"HTTP\"}";

    @Inject
    CloudMapService cloudMap;

    @Inject
    CloudMapInstanceResolver resolver;

    private String createService(String healthCheckConfig, String healthCheckCustomConfig) {
        String name = "resolver-" + UUID.randomUUID();
        Service service = cloudMap.createService(name, null, null, null, null, healthCheckConfig,
                healthCheckCustomConfig, null, null, REGION);
        return service.getId();
    }

    private static String arnOf(String serviceId) {
        return "arn:aws:servicediscovery:" + REGION + ":000000000000:service/" + serviceId;
    }

    private void register(String serviceId, String instanceId, Map<String, String> attributes) {
        cloudMap.registerInstance(serviceId, instanceId, null, attributes, REGION);
    }

    // Floci has no API that marks an instance UNHEALTHY, so the test flips the stored instance directly
    private void markUnhealthy(String serviceId, String instanceId) {
        Instance instance = cloudMap.getInstance(serviceId, instanceId);
        instance.setHealthStatus("UNHEALTHY");
        assertEquals("UNHEALTHY", cloudMap.getInstance(serviceId, instanceId).getHealthStatus());
    }

    private static Map<String, String> address(String ip, String port) {
        return Map.of("AWS_INSTANCE_IPV4", ip, "AWS_INSTANCE_PORT", port);
    }

    private static Map<String, String> address(String ip, String port, String stage, String deployment) {
        return Map.of("AWS_INSTANCE_IPV4", ip, "AWS_INSTANCE_PORT", port, "stage", stage, "deployment", deployment);
    }

    private void assertEveryPickIs(Endpoint expected, String serviceArn) {
        for (int i = 0; i < 50; i++) {
            assertEquals(expected, resolver.pickInstance(serviceArn).orElseThrow());
        }
    }

    private void assertUnhealthyInstanceIsNeverPicked(String serviceId) {
        register(serviceId, "up", address("10.0.0.1", "8080"));
        register(serviceId, "down", address("10.0.0.2", "8080"));
        markUnhealthy(serviceId, "down");

        assertEveryPickIs(new Endpoint("10.0.0.1", 8080), arnOf(serviceId));
    }

    @Test
    void recognizesCloudMapServiceArnsOnly() {
        String serviceArn = arnOf("srv-abc123");
        assertTrue(CloudMapInstanceResolver.isServiceArn(serviceArn));
        assertTrue(CloudMapInstanceResolver.isServiceArn(serviceArn + "?stage=prod&deployment=green"));
        assertFalse(CloudMapInstanceResolver.isServiceArn(
                "arn:aws:servicediscovery:us-east-1:000000000000:namespace/ns-abc"));
        assertFalse(CloudMapInstanceResolver.isServiceArn(
                "arn:aws:elasticloadbalancing:us-east-1:000000000000:listener/app/lb/1/2"));
        assertFalse(CloudMapInstanceResolver.isServiceArn("http://10.0.0.1:8080/"));
        assertFalse(CloudMapInstanceResolver.isServiceArn(null));
    }

    @Test
    void serviceWithACustomHealthCheckNeverPicksAnUnhealthyInstance() {
        assertUnhealthyInstanceIsNeverPicked(createService(null, CUSTOM_HEALTH_CHECK));
    }

    @Test
    void serviceWithAHealthCheckConfigNeverPicksAnUnhealthyInstance() {
        assertUnhealthyInstanceIsNeverPicked(createService(HEALTH_CHECK_CONFIG, null));
    }

    @Test
    void healthCheckedServiceWithOnlyUnhealthyInstancesHasNoCandidate() {
        String serviceId = createService(null, CUSTOM_HEALTH_CHECK);
        register(serviceId, "down", address("10.0.0.2", "8080"));
        markUnhealthy(serviceId, "down");

        assertEquals(Optional.empty(), resolver.pickInstance(arnOf(serviceId)));
    }

    @Test
    void serviceWithoutHealthCheckPicksAnUnhealthyInstance() {
        String serviceId = createService(null, null);
        register(serviceId, "down", address("10.0.0.2", "8080"));
        markUnhealthy(serviceId, "down");

        assertEveryPickIs(new Endpoint("10.0.0.2", 8080), arnOf(serviceId));
    }

    @Test
    void serviceWithNoInstanceHasNoCandidate() {
        String serviceId = createService(null, CUSTOM_HEALTH_CHECK);

        assertEquals(Optional.empty(), resolver.pickInstance(arnOf(serviceId)));
    }

    @Test
    void picksAmongEveryRegisteredInstance() {
        String serviceId = createService(null, null);
        register(serviceId, "a", address("10.0.0.1", "8080"));
        register(serviceId, "b", address("10.0.0.2", "8080"));

        Set<Endpoint> picked = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            picked.add(resolver.pickInstance(arnOf(serviceId)).orElseThrow());
        }

        assertEquals(Set.of(new Endpoint("10.0.0.1", 8080), new Endpoint("10.0.0.2", 8080)), picked);
    }

    @Test
    void instanceWithoutAddressOrPortOrWithBadValuesIsNotACandidate() {
        String serviceId = createService(null, null);
        register(serviceId, "no-port", Map.of("AWS_INSTANCE_IPV4", "10.0.0.1"));
        register(serviceId, "no-ip", Map.of("AWS_INSTANCE_PORT", "8080"));
        register(serviceId, "not-an-ip", address("evil.example/@x", "8080"));
        register(serviceId, "port-text", address("10.0.0.2", "http"));
        register(serviceId, "port-range", address("10.0.0.3", "70000"));
        register(serviceId, "port-zero", address("10.0.0.5", "0"));
        register(serviceId, "good", address("10.0.0.4", "9090"));

        assertEveryPickIs(new Endpoint("10.0.0.4", 9090), arnOf(serviceId));
    }

    @Test
    void addressWithAnOctetAboveTheIpv4RangeIsNotACandidate() {
        String serviceId = createService(null, null);
        register(serviceId, "big-first", address("999.1.1.1", "8080"));
        register(serviceId, "big-last", address("10.0.0.256", "8080"));
        register(serviceId, "big-middle", address("10.300.0.1", "8080"));
        register(serviceId, "edge", address("255.255.255.255", "9090"));

        assertEveryPickIs(new Endpoint("255.255.255.255", 9090), arnOf(serviceId));
    }

    @Test
    void arnQueryStringKeepsOnlyInstancesWithMatchingAttributes() {
        String serviceId = createService(null, null);
        register(serviceId, "prod-green", address("10.0.0.1", "8080", "prod", "green"));
        register(serviceId, "prod-blue", address("10.0.0.2", "8080", "prod", "blue"));
        register(serviceId, "dev-green", address("10.0.0.3", "8080", "dev", "green"));

        assertEveryPickIs(new Endpoint("10.0.0.1", 8080), arnOf(serviceId) + "?stage=prod&deployment=green");
    }

    @Test
    void arnQueryStringMatchingNoInstanceHasNoCandidate() {
        String serviceId = createService(null, null);
        register(serviceId, "dev", address("10.0.0.3", "8080", "dev", "green"));

        assertEquals(Optional.empty(), resolver.pickInstance(arnOf(serviceId) + "?stage=prod"));
    }

    @Test
    void missingCloudMapServicePropagatesNotFound() {
        AwsException error = assertThrows(AwsException.class,
                () -> resolver.pickInstance(arnOf("srv-doesnotexist")));

        assertEquals("ServiceNotFound", error.getErrorCode());
    }
}
