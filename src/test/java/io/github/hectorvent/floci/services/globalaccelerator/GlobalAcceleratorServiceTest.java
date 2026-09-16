package io.github.hectorvent.floci.services.globalaccelerator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.globalaccelerator.model.Accelerator;
import io.github.hectorvent.floci.services.globalaccelerator.model.AcceleratorAttributes;
import io.github.hectorvent.floci.services.globalaccelerator.model.EndpointGroup;
import io.github.hectorvent.floci.services.globalaccelerator.model.Listener;
import io.github.hectorvent.floci.services.globalaccelerator.model.PortOverride;
import io.github.hectorvent.floci.services.globalaccelerator.model.PortRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalAcceleratorServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GlobalAcceleratorService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT);
        when(regionResolver.buildArn(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                AwsArnUtils.Arn.of(invocation.getArgument(0), invocation.getArgument(1),
                        ACCOUNT, invocation.getArgument(2)).toString());
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory(ACCOUNT));

        service = new GlobalAcceleratorService(storageFactory, regionResolver);
    }

    @Test
    void acceleratorArnCarriesAnEmptyRegionSegment() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, Map.of());

        assertTrue(accelerator.getAcceleratorArn().startsWith("arn:aws:globalaccelerator::" + ACCOUNT + ":accelerator/"),
                "unexpected ARN: " + accelerator.getAcceleratorArn());
    }

    @Test
    void createAcceleratorAppliesAwsDefaults() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);

        assertEquals("IPV4", accelerator.getIpAddressType());
        assertEquals(Boolean.TRUE, accelerator.getEnabled());
        assertEquals("DEPLOYED", accelerator.getStatus());
        assertEquals(1, accelerator.getIpSets().size());
        assertEquals("IPv4", accelerator.getIpSets().get(0).getIpAddressFamily());
        assertEquals(2, accelerator.getIpSets().get(0).getIpAddresses().size());
        assertTrue(accelerator.getDnsName().endsWith(".awsglobalaccelerator.com"));
        assertNull(accelerator.getDualStackDnsName());
    }

    @Test
    void dualStackAcceleratorGetsAnIpv6SetAndADualStackDnsName() {
        Accelerator accelerator = service.createAccelerator("edge", "DUAL_STACK", null, null, null);

        assertEquals(2, accelerator.getIpSets().size());
        assertEquals("IPv6", accelerator.getIpSets().get(1).getIpAddressFamily());
        assertTrue(accelerator.getDualStackDnsName().endsWith(".dualstack.awsglobalaccelerator.com"));
    }

    @Test
    void byoipAddressesArePinnedAndShortfallIsFilled() {
        Accelerator accelerator = service.createAccelerator("edge", null, List.of("198.51.100.10"), null, null);

        List<String> addresses = accelerator.getIpSets().get(0).getIpAddresses();
        assertEquals(2, addresses.size());
        assertEquals("198.51.100.10", addresses.get(0));
    }

    @Test
    void createAcceleratorRejectsAnUnknownIpAddressType() {
        AwsException failure = assertThrows(AwsException.class,
                () -> service.createAccelerator("edge", "IPV6", null, null, null));

        assertEquals("InvalidArgumentException", failure.getErrorCode());
    }

    @Test
    void describeUnknownAcceleratorReportsAcceleratorNotFound() {
        AwsException failure = assertThrows(AwsException.class,
                () -> service.describeAccelerator("arn:aws:globalaccelerator::" + ACCOUNT + ":accelerator/missing"));

        assertEquals("AcceleratorNotFoundException", failure.getErrorCode());
        assertEquals(400, failure.getHttpStatus());
    }

    @Test
    void switchingBackToIpv4ClearsTheDualStackDnsName() {
        Accelerator accelerator = service.createAccelerator("edge", "DUAL_STACK", null, null, null);

        Accelerator updated = service.updateAccelerator(accelerator.getAcceleratorArn(),
                null, "IPV4", null, null);

        assertEquals(1, updated.getIpSets().size());
        assertNull(updated.getDualStackDnsName());
    }

    @Test
    void enabledAcceleratorCannotBeDeleted() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, true, null);

        AwsException failure = assertThrows(AwsException.class,
                () -> service.deleteAccelerator(accelerator.getAcceleratorArn()));

        assertEquals("AcceleratorNotDisabledException", failure.getErrorCode());
    }

    @Test
    void acceleratorWithListenersCannotBeDeleted() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, false, null);
        service.createListener(accelerator.getAcceleratorArn(), portRange(80, 80), "TCP", null);

        AwsException failure = assertThrows(AwsException.class,
                () -> service.deleteAccelerator(accelerator.getAcceleratorArn()));

        assertEquals("AssociatedListenerFoundException", failure.getErrorCode());
    }

    @Test
    void flowLogsCannotBeEnabledWithoutABucket() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);

        AwsException failure = assertThrows(AwsException.class, () ->
                service.updateAcceleratorAttributes(accelerator.getAcceleratorArn(), true, null, null));

        assertEquals("InvalidArgumentException", failure.getErrorCode());
        assertFalse(service.describeAcceleratorAttributes(accelerator.getAcceleratorArn()).getFlowLogsEnabled());
    }

    @Test
    void acceleratorAttributesDefaultToFlowLogsDisabled() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);

        AcceleratorAttributes attributes =
                service.describeAcceleratorAttributes(accelerator.getAcceleratorArn());

        assertEquals(Boolean.FALSE, attributes.getFlowLogsEnabled());
    }

    @Test
    void listenerArnExtendsTheAcceleratorArnAndListingIsScopedByIt() {
        Accelerator first = service.createAccelerator("first", null, null, null, null);
        Accelerator second = service.createAccelerator("second", null, null, null, null);
        Listener listener = service.createListener(first.getAcceleratorArn(), portRange(80, 81), "TCP", null);

        assertTrue(listener.getListenerArn().startsWith(first.getAcceleratorArn() + "/listener/"));
        assertEquals("NONE", listener.getClientAffinity());
        assertEquals(List.of(listener.getListenerArn()),
                service.listListeners(first.getAcceleratorArn()).stream().map(Listener::getListenerArn).toList());
        assertTrue(service.listListeners(second.getAcceleratorArn()).isEmpty());
    }

    @Test
    void invertedPortRangeReportsInvalidPortRange() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);

        AwsException failure = assertThrows(AwsException.class,
                () -> service.createListener(accelerator.getAcceleratorArn(), portRange(900, 80), "TCP", null));

        assertEquals("InvalidPortRangeException", failure.getErrorCode());
    }

    @Test
    void createListenerRejectsAnUnknownClientAffinity() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);

        AwsException failure = assertThrows(AwsException.class, () ->
                service.createListener(accelerator.getAcceleratorArn(), portRange(80, 80), "TCP", "SOURCE"));

        assertEquals("InvalidArgumentException", failure.getErrorCode());
    }

    @Test
    void endpointGroupDefaultsFollowTheListenerAndTheAwsDefaults() {
        EndpointGroup group = endpointGroup("us-west-2", "[{\"EndpointId\":\"i-0123456789abcdef0\"}]");

        assertEquals(100.0f, group.getTrafficDialPercentage());
        assertEquals(80, group.getHealthCheckPort());
        assertEquals("TCP", group.getHealthCheckProtocol());
        assertEquals("/", group.getHealthCheckPath());
        assertEquals(30, group.getHealthCheckIntervalSeconds());
        assertEquals(3, group.getThresholdCount());
        assertEquals(128, group.getEndpointDescriptions().get(0).getWeight());
        assertEquals("HEALTHY", group.getEndpointDescriptions().get(0).getHealthState());
    }

    @Test
    void aSecondEndpointGroupInTheSameRegionIsRejected() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);
        Listener listener = service.createListener(accelerator.getAcceleratorArn(), portRange(80, 80), "TCP", null);
        service.createEndpointGroup(listener.getListenerArn(), "us-west-2", null,
                null, null, null, null, null, null, null);

        AwsException failure = assertThrows(AwsException.class, () ->
                service.createEndpointGroup(listener.getListenerArn(), "us-west-2", null,
                        null, null, null, null, null, null, null));

        assertEquals("EndpointGroupAlreadyExistsException", failure.getErrorCode());
    }

    @Test
    void healthCheckIntervalOutsideTheModelRangeIsRejected() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);
        Listener listener = service.createListener(accelerator.getAcceleratorArn(), portRange(80, 80), "TCP", null);

        AwsException failure = assertThrows(AwsException.class, () ->
                service.createEndpointGroup(listener.getListenerArn(), "us-west-2", null,
                        null, null, null, null, 5, null, null));

        assertEquals("InvalidArgumentException", failure.getErrorCode());
    }

    @Test
    void trafficDialAboveOneHundredIsRejected() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);
        Listener listener = service.createListener(accelerator.getAcceleratorArn(), portRange(80, 80), "TCP", null);

        AwsException failure = assertThrows(AwsException.class, () ->
                service.createEndpointGroup(listener.getListenerArn(), "us-west-2", null,
                        101.0f, null, null, null, null, null, null));

        assertEquals("InvalidArgumentException", failure.getErrorCode());
    }

    @Test
    void listenerWithEndpointGroupsCannotBeDeleted() {
        EndpointGroup group = endpointGroup("us-west-2", "[]");
        String listenerArn = group.getEndpointGroupArn().substring(0,
                group.getEndpointGroupArn().indexOf("/endpoint-group/"));

        AwsException failure = assertThrows(AwsException.class, () -> service.deleteListener(listenerArn));

        assertEquals("AssociatedEndpointGroupFoundException", failure.getErrorCode());
    }

    @Test
    void listEndpointGroupsOnAnUnknownListenerReportsListenerNotFound() {
        AwsException failure = assertThrows(AwsException.class,
                () -> service.listEndpointGroups("arn:aws:globalaccelerator::" + ACCOUNT
                        + ":accelerator/missing/listener/abcd1234"));

        assertEquals("ListenerNotFoundException", failure.getErrorCode());
    }

    @Test
    void addEndpointsReplacesAnEndpointWithTheSameId() {
        EndpointGroup group = endpointGroup("us-west-2", "[{\"EndpointId\":\"i-aaaa\",\"Weight\":10}]");

        EndpointGroup merged = service.addEndpoints(group.getEndpointGroupArn(),
                json("[{\"EndpointId\":\"i-aaaa\",\"Weight\":200},{\"EndpointId\":\"i-bbbb\"}]"));

        assertEquals(2, merged.getEndpointDescriptions().size());
        assertEquals(200, merged.getEndpointDescriptions().get(0).getWeight());
        assertEquals("i-bbbb", merged.getEndpointDescriptions().get(1).getEndpointId());
    }

    @Test
    void removeEndpointsDropsOnlyTheNamedEndpoints() {
        EndpointGroup group = endpointGroup("us-west-2",
                "[{\"EndpointId\":\"i-aaaa\"},{\"EndpointId\":\"i-bbbb\"}]");

        service.removeEndpoints(group.getEndpointGroupArn(), List.of("i-aaaa"));

        EndpointGroup reloaded = service.describeEndpointGroup(group.getEndpointGroupArn());
        assertEquals(1, reloaded.getEndpointDescriptions().size());
        assertEquals("i-bbbb", reloaded.getEndpointDescriptions().get(0).getEndpointId());
    }

    @Test
    void clientIpPreservationDefaultsToTrueForApplicationLoadBalancers() {
        EndpointGroup group = endpointGroup("us-west-2",
                "[{\"EndpointId\":\"arn:aws:elasticloadbalancing:us-west-2:" + ACCOUNT
                        + ":loadbalancer/app/web/abc\"},{\"EndpointId\":\"i-aaaa\"}]");

        assertEquals(Boolean.TRUE, group.getEndpointDescriptions().get(0).getClientIpPreservationEnabled());
        assertEquals(Boolean.FALSE, group.getEndpointDescriptions().get(1).getClientIpPreservationEnabled());
    }

    @Test
    void portOverrideWithNoEndpointPortIsRejected() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);
        Listener listener = service.createListener(accelerator.getAcceleratorArn(), portRange(80, 80), "TCP", null);
        PortOverride override = new PortOverride();
        override.setListenerPort(80);

        AwsException failure = assertThrows(AwsException.class, () ->
                service.createEndpointGroup(listener.getListenerArn(), "us-west-2", null,
                        null, null, null, null, null, null, List.of(override)));

        assertEquals("InvalidPortRangeException", failure.getErrorCode());
    }

    @Test
    void tagsAreScopedToTheResourceArn() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, Map.of("env", "test"));
        Listener listener = service.createListener(accelerator.getAcceleratorArn(), portRange(80, 80), "TCP", null);

        service.tagResource(listener.getListenerArn(), Map.of("team", "platform"));

        assertEquals(Map.of("env", "test"), service.listTagsForResource(accelerator.getAcceleratorArn()));
        assertEquals(Map.of("team", "platform"), service.listTagsForResource(listener.getListenerArn()));

        service.untagResource(listener.getListenerArn(), List.of("team"));
        assertTrue(service.listTagsForResource(listener.getListenerArn()).isEmpty());
    }

    @Test
    void tagResourceOnAnUnknownAcceleratorReportsAcceleratorNotFound() {
        AwsException failure = assertThrows(AwsException.class, () ->
                service.tagResource("arn:aws:globalaccelerator::" + ACCOUNT + ":accelerator/missing",
                        Map.of("env", "test")));

        assertEquals("AcceleratorNotFoundException", failure.getErrorCode());
    }

    @Test
    void tagResourceWithoutTagsIsRejected() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);

        AwsException failure = assertThrows(AwsException.class,
                () -> service.tagResource(accelerator.getAcceleratorArn(), null));

        assertEquals("InvalidArgumentException", failure.getErrorCode());
    }

    @Test
    void listenerAcceptsTenPortRanges() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);

        Listener listener = service.createListener(accelerator.getAcceleratorArn(), portRanges(10), "TCP", null);

        assertEquals(10, listener.getPortRanges().size());
    }

    @Test
    void listenerRejectsMoreThanTenPortRanges() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);

        AwsException failure = assertThrows(AwsException.class, () ->
                service.createListener(accelerator.getAcceleratorArn(), portRanges(11), "TCP", null));

        assertEquals("InvalidArgumentException", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("PortRanges"), failure.getMessage());
    }

    @Test
    void updateListenerRejectsMoreThanTenPortRanges() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);
        Listener listener = service.createListener(accelerator.getAcceleratorArn(), portRange(80, 80), "TCP", null);

        AwsException failure = assertThrows(AwsException.class, () ->
                service.updateListener(listener.getListenerArn(), portRanges(11), null, null));

        assertEquals("InvalidArgumentException", failure.getErrorCode());
    }

    @Test
    void endpointGroupAcceptsTenEndpointConfigurations() {
        EndpointGroup group = endpointGroup("us-west-2", endpointConfigurations(10));

        assertEquals(10, group.getEndpointDescriptions().size());
    }

    @Test
    void endpointGroupRejectsMoreThanTenEndpointConfigurations() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);
        Listener listener = service.createListener(accelerator.getAcceleratorArn(), portRange(80, 80), "TCP", null);

        AwsException failure = assertThrows(AwsException.class, () ->
                service.createEndpointGroup(listener.getListenerArn(), "us-west-2",
                        json(endpointConfigurations(11)), null, null, null, null, null, null, null));

        assertEquals("InvalidArgumentException", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("EndpointConfigurations"), failure.getMessage());
    }

    @Test
    void addEndpointsRejectsMoreThanTenEndpointConfigurations() {
        EndpointGroup group = endpointGroup("us-west-2", "[]");

        AwsException failure = assertThrows(AwsException.class, () ->
                service.addEndpoints(group.getEndpointGroupArn(), json(endpointConfigurations(11))));

        assertEquals("InvalidArgumentException", failure.getErrorCode());
    }

    @Test
    void endpointGroupAcceptsTenPortOverrides() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);
        Listener listener = service.createListener(accelerator.getAcceleratorArn(), portRange(80, 80), "TCP", null);

        EndpointGroup group = service.createEndpointGroup(listener.getListenerArn(), "us-west-2", null,
                null, null, null, null, null, null, portOverrides(10));

        assertEquals(10, group.getPortOverrides().size());
    }

    @Test
    void endpointGroupRejectsMoreThanTenPortOverrides() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);
        Listener listener = service.createListener(accelerator.getAcceleratorArn(), portRange(80, 80), "TCP", null);

        AwsException failure = assertThrows(AwsException.class, () ->
                service.createEndpointGroup(listener.getListenerArn(), "us-west-2", null,
                        null, null, null, null, null, null, portOverrides(11)));

        assertEquals("InvalidArgumentException", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("PortOverrides"), failure.getMessage());
    }

    @Test
    void createAcceleratorAcceptsTwoByoipAddresses() {
        Accelerator accelerator = service.createAccelerator("edge", null,
                List.of("192.0.2.1", "192.0.2.2"), null, null);

        assertEquals(List.of("192.0.2.1", "192.0.2.2"), accelerator.getIpSets().get(0).getIpAddresses());
    }

    @Test
    void createAcceleratorRejectsMoreThanTwoByoipAddresses() {
        AwsException failure = assertThrows(AwsException.class, () -> service.createAccelerator("edge", null,
                List.of("192.0.2.1", "192.0.2.2", "192.0.2.3"), null, null));

        assertEquals("InvalidArgumentException", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("IpAddresses"), failure.getMessage());
    }

    @Test
    void updateAcceleratorRejectsMoreThanTwoByoipAddresses() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);

        AwsException failure = assertThrows(AwsException.class, () ->
                service.updateAccelerator(accelerator.getAcceleratorArn(), null, null,
                        List.of("192.0.2.1", "192.0.2.2", "192.0.2.3"), null));

        assertEquals("InvalidArgumentException", failure.getErrorCode());
    }

    @Test
    void endpointGroupAcceptsAHealthCheckPathOfTwoHundredFiftyFiveCharacters() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);
        Listener listener = service.createListener(accelerator.getAcceleratorArn(), portRange(80, 80), "TCP", null);
        String path = "/" + "a".repeat(254);

        EndpointGroup group = service.createEndpointGroup(listener.getListenerArn(), "us-west-2", null,
                null, null, "HTTP", path, null, null, null);

        assertEquals(path, group.getHealthCheckPath());
    }

    @Test
    void endpointGroupRejectsAHealthCheckPathLongerThanTwoHundredFiftyFiveCharacters() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);
        Listener listener = service.createListener(accelerator.getAcceleratorArn(), portRange(80, 80), "TCP", null);

        AwsException failure = assertThrows(AwsException.class, () ->
                service.createEndpointGroup(listener.getListenerArn(), "us-west-2", null,
                        null, null, "HTTP", "/" + "a".repeat(255), null, null, null));

        assertEquals("InvalidArgumentException", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("HealthCheckPath"), failure.getMessage());
    }

    @Test
    void endpointGroupRejectsAHealthCheckPathThatDoesNotStartWithASlash() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);
        Listener listener = service.createListener(accelerator.getAcceleratorArn(), portRange(80, 80), "TCP", null);

        AwsException failure = assertThrows(AwsException.class, () ->
                service.createEndpointGroup(listener.getListenerArn(), "us-west-2", null,
                        null, null, "HTTP", "health", null, null, null));

        assertEquals("InvalidArgumentException", failure.getErrorCode());
    }

    @Test
    void endpointGroupRejectsAHealthCheckPathWithCharactersOutsideTheModelPattern() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);
        Listener listener = service.createListener(accelerator.getAcceleratorArn(), portRange(80, 80), "TCP", null);

        AwsException failure = assertThrows(AwsException.class, () ->
                service.createEndpointGroup(listener.getListenerArn(), "us-west-2", null,
                        null, null, "HTTP", "/health check", null, null, null));

        assertEquals("InvalidArgumentException", failure.getErrorCode());
    }

    @Test
    void endpointGroupAcceptsTheUrlPathCharactersTheModelAllows() {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);
        Listener listener = service.createListener(accelerator.getAcceleratorArn(), portRange(80, 80), "TCP", null);
        String path = "/health-check_v2/status.json?deep=1&x=a+b~c#frag@host:8080%20";

        EndpointGroup group = service.createEndpointGroup(listener.getListenerArn(), "us-west-2", null,
                null, null, "HTTP", path, null, null, null);

        assertEquals(path, group.getHealthCheckPath());
    }

    @Test
    void updateEndpointGroupRejectsAnInvalidHealthCheckPath() {
        EndpointGroup group = endpointGroup("us-west-2", "[]");

        AwsException failure = assertThrows(AwsException.class, () ->
                service.updateEndpointGroup(group.getEndpointGroupArn(), null, null, null,
                        null, "health", null, null, null));

        assertEquals("InvalidArgumentException", failure.getErrorCode());
    }

    private EndpointGroup endpointGroup(String region, String endpointConfigurations) {
        Accelerator accelerator = service.createAccelerator("edge", null, null, null, null);
        Listener listener = service.createListener(accelerator.getAcceleratorArn(), portRange(80, 80), "TCP", null);
        return service.createEndpointGroup(listener.getListenerArn(), region, json(endpointConfigurations),
                null, null, null, null, null, null, null);
    }

    private static List<PortRange> portRanges(int count) {
        List<PortRange> ranges = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            PortRange range = new PortRange();
            range.setFromPort(1000 + i);
            range.setToPort(1000 + i);
            ranges.add(range);
        }
        return ranges;
    }

    private static List<PortOverride> portOverrides(int count) {
        List<PortOverride> overrides = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            PortOverride override = new PortOverride();
            override.setListenerPort(1000 + i);
            override.setEndpointPort(2000 + i);
            overrides.add(override);
        }
        return overrides;
    }

    private static String endpointConfigurations(int count) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append("{\"EndpointId\":\"i-").append(i).append("\"}");
        }
        return builder.append(']').toString();
    }

    private static List<PortRange> portRange(int from, int to) {
        PortRange range = new PortRange();
        range.setFromPort(from);
        range.setToPort(to);
        return List.of(range);
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("test fixture is not valid JSON: " + raw, e);
        }
    }
}
