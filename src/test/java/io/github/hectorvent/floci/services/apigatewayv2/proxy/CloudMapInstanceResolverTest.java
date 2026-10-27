package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigatewayv2.proxy.CloudMapInstanceResolver.Endpoint;
import io.github.hectorvent.floci.services.cloudmap.CloudMapService;
import io.github.hectorvent.floci.services.cloudmap.model.Instance;
import io.github.hectorvent.floci.services.cloudmap.model.Service;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudMapInstanceResolverTest {

    private static final String SERVICE_ID = "srv-abc123";
    private static final String SERVICE_ARN = "arn:aws:servicediscovery:us-east-1:000000000000:service/" + SERVICE_ID;
    private static final String CUSTOM_HEALTH_CHECK = "{\"FailureThreshold\":1}";

    private final CloudMapService cloudMap = mock(CloudMapService.class);
    private final CloudMapInstanceResolver resolver = new CloudMapInstanceResolver(cloudMap);

    private void givenService(String healthCheckCustomConfig, Instance... instances) {
        Service service = new Service();
        service.setId(SERVICE_ID);
        service.setHealthCheckCustomConfig(healthCheckCustomConfig);
        when(cloudMap.getService(SERVICE_ID)).thenReturn(service);
        when(cloudMap.listInstances(SERVICE_ID)).thenReturn(List.of(instances));
    }

    private static Instance instance(String id, String health, Map<String, String> attributes) {
        Instance instance = new Instance();
        instance.setInstanceId(id);
        instance.setHealthStatus(health);
        instance.setAttributes(attributes);
        return instance;
    }

    private static Map<String, String> address(String ip, String port) {
        return Map.of("AWS_INSTANCE_IPV4", ip, "AWS_INSTANCE_PORT", port);
    }

    @Test
    void recognizesCloudMapServiceArnsOnly() {
        assertTrue(CloudMapInstanceResolver.isServiceArn(SERVICE_ARN));
        assertTrue(CloudMapInstanceResolver.isServiceArn(SERVICE_ARN + "?stage=prod&deployment=green"));
        assertFalse(CloudMapInstanceResolver.isServiceArn(
                "arn:aws:servicediscovery:us-east-1:000000000000:namespace/ns-abc"));
        assertFalse(CloudMapInstanceResolver.isServiceArn(
                "arn:aws:elasticloadbalancing:us-east-1:000000000000:listener/app/lb/1/2"));
        assertFalse(CloudMapInstanceResolver.isServiceArn("http://10.0.0.1:8080/"));
        assertFalse(CloudMapInstanceResolver.isServiceArn(null));
    }

    @Test
    void healthCheckedServiceNeverPicksAnUnhealthyInstance() {
        givenService(CUSTOM_HEALTH_CHECK,
                instance("up-1", "HEALTHY", address("10.0.0.1", "8080")),
                instance("down", "UNHEALTHY", address("10.0.0.2", "8080")),
                instance("up-2", "HEALTHY", address("10.0.0.3", "8080")));

        Set<Endpoint> picked = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            picked.add(resolver.pickInstance(SERVICE_ARN).orElseThrow());
        }

        assertEquals(Set.of(new Endpoint("10.0.0.1", 8080), new Endpoint("10.0.0.3", 8080)), picked);
    }

    @Test
    void healthCheckedServiceWithOnlyUnhealthyInstancesHasNoCandidate() {
        givenService(CUSTOM_HEALTH_CHECK, instance("down", "UNHEALTHY", address("10.0.0.2", "8080")));

        assertEquals(Optional.empty(), resolver.pickInstance(SERVICE_ARN));
    }

    @Test
    void serviceWithoutHealthCheckCountsEveryInstance() {
        givenService(null,
                instance("a", "UNHEALTHY", address("10.0.0.1", "8080")),
                instance("b", "HEALTHY", address("10.0.0.2", "8080")));

        Set<Endpoint> picked = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            picked.add(resolver.pickInstance(SERVICE_ARN).orElseThrow());
        }

        assertEquals(Set.of(new Endpoint("10.0.0.1", 8080), new Endpoint("10.0.0.2", 8080)), picked);
    }

    @Test
    void instanceWithoutAddressOrPortOrWithBadValuesIsNotACandidate() {
        givenService(null,
                instance("no-port", "HEALTHY", Map.of("AWS_INSTANCE_IPV4", "10.0.0.1")),
                instance("no-ip", "HEALTHY", Map.of("AWS_INSTANCE_PORT", "8080")),
                instance("not-an-ip", "HEALTHY", address("evil.example/@x", "8080")),
                instance("port-text", "HEALTHY", address("10.0.0.2", "http")),
                instance("port-range", "HEALTHY", address("10.0.0.3", "70000")),
                instance("good", "HEALTHY", address("10.0.0.4", "9090")));

        for (int i = 0; i < 50; i++) {
            assertEquals(new Endpoint("10.0.0.4", 9090), resolver.pickInstance(SERVICE_ARN).orElseThrow());
        }
    }

    @Test
    void addressWithAnOctetAboveTheIpv4RangeIsNotACandidate() {
        givenService(null,
                instance("big-first", "HEALTHY", address("999.1.1.1", "8080")),
                instance("big-last", "HEALTHY", address("10.0.0.256", "8080")),
                instance("big-middle", "HEALTHY", address("10.300.0.1", "8080")),
                instance("edge", "HEALTHY", address("255.255.255.255", "9090")));

        for (int i = 0; i < 50; i++) {
            assertEquals(new Endpoint("255.255.255.255", 9090), resolver.pickInstance(SERVICE_ARN).orElseThrow());
        }
    }

    @Test
    void arnQueryStringFiltersInstancesByAttribute() {
        givenService(null,
                instance("prod", "HEALTHY", Map.of("AWS_INSTANCE_IPV4", "10.0.0.1",
                        "AWS_INSTANCE_PORT", "8080", "stage", "prod", "deployment", "green")),
                instance("dev", "HEALTHY", Map.of("AWS_INSTANCE_IPV4", "10.0.0.2",
                        "AWS_INSTANCE_PORT", "8080", "stage", "dev", "deployment", "green")));

        for (int i = 0; i < 50; i++) {
            assertEquals(new Endpoint("10.0.0.1", 8080),
                    resolver.pickInstance(SERVICE_ARN + "?stage=prod&deployment=green").orElseThrow());
        }
        assertEquals(Optional.empty(), resolver.pickInstance(SERVICE_ARN + "?stage=qa"));
    }

    @Test
    void missingCloudMapServicePropagatesNotFound() {
        when(cloudMap.getService(SERVICE_ID)).thenThrow(new AwsException("ServiceNotFound", "gone", 404));

        assertThrows(AwsException.class, () -> resolver.pickInstance(SERVICE_ARN));
    }
}
