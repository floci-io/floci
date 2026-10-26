package io.github.hectorvent.floci.services.elbv2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegionFacts;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.AvailabilityZone;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import io.github.hectorvent.floci.services.elbv2.model.Rule;
import io.github.hectorvent.floci.services.elbv2.model.RuleCondition;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import io.github.hectorvent.floci.services.elbv2.model.TargetHealth;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static io.github.hectorvent.floci.services.elbv2.ElbV2PublicationTestSupport.allowPublications;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ElbV2ServiceTest {

    private static final String REGION = "us-west-2";

    // Application Load Balancers require subnets in at least two Availability Zones.
    private static final List<String> ALB_SUBNETS = List.of("subnet-a", "subnet-b");

    private static final String HELD_CERTIFICATE =
            "arn:aws:iam::000000000000:server-certificate/held";
    private static final String OTHER_CERTIFICATE =
            "arn:aws:iam::000000000000:server-certificate/other";

    @Mock
    ElbV2DataPlane dataPlane;

    @Mock
    ElbV2HealthChecker healthChecker;

    @Mock
    Ec2Service ec2Service;

    private ElbV2Service service;

    @BeforeEach
    void setUp() {
        service = new ElbV2Service();
        allowPublications(dataPlane);
        service.dataPlane = dataPlane;
        service.healthChecker = healthChecker;
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.ec2Service = ec2Service;
        stubAlbSubnets(ec2Service);
    }

    /**
     * Every type takes Subnets or SubnetMappings, so a balancer with neither is refused before a
     * subnet is looked up or anything is stored. The messages are the ones AWS gives an Application
     * or a Network Load Balancer; a Gateway Load Balancer gets the Network one.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NONE", value = {
        "NONE|At least two subnets in two different Availability Zones must be specified",
        "application|At least two subnets in two different Availability Zones must be specified",
        "network|At least one subnet must be specified",
        "gateway|At least one subnet must be specified"
    })
    void createLoadBalancerWithoutSubnetsIsRefused(String type, String message) {
        for (List<String> subnets : Arrays.<List<String>>asList(null, List.of())) {
            AwsException refusal = assertThrows(AwsException.class, () -> service.createLoadBalancer(REGION,
                    "no-subnets", null, type, null, subnets, List.of(), Map.of()));
            assertEquals("ValidationError", refusal.getErrorCode());
            assertEquals(message, refusal.getMessage());
            assertEquals(400, refusal.getHttpStatus());
        }
        assertTrue(service.describeLoadBalancers(REGION, null, null, null, null).isEmpty());
        verify(ec2Service, never()).describeSubnets(anyString(), anyList(), any());
    }

    /**
     * An Application Load Balancer takes subnets in at least two Availability Zones, so one subnet gets
     * the refusal a balancer with none gets. AWS counts before it looks the subnet up, so the subnet is
     * not looked up here either, and nothing is stored.
     */
    @ParameterizedTest
    @CsvSource(nullValues = "NONE", value = {"NONE", "application"})
    void anApplicationLoadBalancerOnOneSubnetIsRefusedBeforeTheSubnetIsLookedUp(String type) {
        // Stubbed so that a lookup before the count would reach the zone check, not a SubnetNotFound from the mock.
        lenient().when(ec2Service.describeSubnets(REGION, List.of("subnet-a"), Map.of()))
                .thenReturn(List.of(subnet("subnet-a", REGION + "a")));
        AwsException refusal = assertThrows(AwsException.class, () -> service.createLoadBalancer(REGION,
                "one-subnet", null, type, null, List.of("subnet-a"), List.of(), Map.of()));
        assertEquals("ValidationError", refusal.getErrorCode());
        assertEquals("At least two subnets in two different Availability Zones must be specified",
                refusal.getMessage());
        assertEquals(400, refusal.getHttpStatus());
        assertTrue(service.describeLoadBalancers(REGION, null, null, null, null).isEmpty());
        verify(ec2Service, never()).describeSubnets(anyString(), anyList(), any());
    }

    /**
     * A {@code Type} outside the model's enum is refused, as AWS refuses it, before anything is looked up or
     * stored, rather than read as an Application Load Balancer.
     */
    @ParameterizedTest
    @ValueSource(strings = {"other", "Application", ""})
    void aLoadBalancerOfATypeOutsideTheEnumIsRefused(String type) {
        AwsException refusal = assertThrows(AwsException.class, () -> service.createLoadBalancer(REGION,
                "unknown-type", null, type, null, List.of("subnet-a", "subnet-b"), List.of(), Map.of()));
        assertEquals("ValidationError", refusal.getErrorCode());
        assertEquals("1 validation error detected: Value '" + type + "' at 'type' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [application, network, gateway]", refusal.getMessage());
        assertEquals(400, refusal.getHttpStatus());
        assertTrue(service.describeLoadBalancers(REGION, null, null, null, null).isEmpty());
        verify(ec2Service, never()).describeSubnets(anyString(), anyList(), any());
    }

    /** A Network or Gateway Load Balancer can be in a single Availability Zone. */
    @ParameterizedTest
    @ValueSource(strings = {"network", "gateway"})
    void aNetworkOrGatewayLoadBalancerCanBeOnOneSubnet(String type) {
        when(ec2Service.describeSubnets(REGION, List.of("subnet-a"), Map.of()))
                .thenReturn(List.of(subnet("subnet-a", REGION + "a")));
        LoadBalancer created = service.createLoadBalancer(REGION, "one-subnet", null, type, null,
                List.of("subnet-a"), List.of(), Map.of());
        assertEquals("vpc-a", created.getVpcId());
        assertEquals(List.of("subnet-a"),
                created.getAvailabilityZones().stream().map(AvailabilityZone::getSubnetId).toList());
    }

    /**
     * The API handler and the CloudFormation provisioner both take a request's subnets from here: one
     * side as given, an empty list for neither, and a refusal for both.
     */
    @Test
    void requestedSubnetsAreTheSubnetsOrTheMappingsNotBoth() {
        assertEquals(List.of("subnet-a"), ElbV2Service.requestedSubnets(List.of("subnet-a"), List.of()));
        assertEquals(List.of("subnet-b"), ElbV2Service.requestedSubnets(List.of(), List.of("subnet-b")));
        assertEquals(List.of(), ElbV2Service.requestedSubnets(List.of(), List.of()));
        AwsException both = assertThrows(AwsException.class,
                () -> ElbV2Service.requestedSubnets(List.of("subnet-a"), List.of("subnet-b")));
        assertEquals("ValidationError", both.getErrorCode());
        assertEquals("You can specify either subnets or subnet mappings, not both", both.getMessage());
        assertEquals(400, both.getHttpStatus());
    }

    /**
     * SetSubnets with neither Subnets nor SubnetMappings, left out or sent as empty lists, is refused
     * before any subnet is looked up, and the balancer keeps its zones and its VPC. A Network Load
     * Balancer gets the message AWS gives it; an Application Load Balancer gets the one
     * CreateLoadBalancer gives it, and a Gateway Load Balancer the Network one.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "application|At least two subnets in two different Availability Zones must be specified",
        "network|At least one subnet must be specified",
        "gateway|At least one subnet must be specified"
    })
    void setSubnetsWithNeitherSubnetsNorSubnetMappingsIsRefused(String type, String message) {
        LoadBalancer lb = service.createLoadBalancer(REGION, "set-none-" + type, "internal", type, "ipv4",
                ALB_SUBNETS, List.of(), Map.of());
        ElbV2QueryHandler handler = new ElbV2QueryHandler(service);
        clearInvocations(ec2Service);
        for (boolean emptyLists : new boolean[] {false, true}) {
            MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
            params.add("LoadBalancerArn", lb.getLoadBalancerArn());
            if (emptyLists) {
                params.add("Subnets", "");
                params.add("SubnetMappings", "");
            }
            Response response = handler.handle("SetSubnets", params, REGION);
            String xml = (String) response.getEntity();
            assertEquals(400, response.getStatus(), xml);
            assertTrue(xml.contains("<Code>ValidationError</Code>"), xml);
            assertTrue(xml.contains("<Message>" + message + "</Message>"), xml);
        }
        LoadBalancer kept = service.describeLoadBalancers(REGION, List.of(lb.getLoadBalancerArn()), null, null,
                null).getFirst();
        assertEquals(ALB_SUBNETS, kept.getAvailabilityZones().stream().map(AvailabilityZone::getSubnetId).toList());
        assertEquals("vpc-a", kept.getVpcId());
        verify(ec2Service, never()).describeSubnets(anyString(), anyList(), any());
    }

    /**
     * Through the SetSubnets action with neither form: the message depends on the balancer's type, so a
     * balancer that does not exist is reported first.
     */
    @Test
    void setSubnetsForAMissingBalancerReportsItBeforeMissingSubnets() {
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        params.add("LoadBalancerArn",
                "arn:aws:elasticloadbalancing:" + REGION + ":000000000000:loadbalancer/net/missing/0123456789abcdef");

        Response response = new ElbV2QueryHandler(service).handle("SetSubnets", params, REGION);

        String xml = (String) response.getEntity();
        assertEquals(400, response.getStatus(), xml);
        assertTrue(xml.contains("<Code>LoadBalancerNotFound</Code>"), xml);
    }

    /**
     * A taken name is reported before missing subnets: a CloudFormation stack update finds the
     * balancer it keeps through DuplicateLoadBalancerName, whatever subnets its template names.
     */
    @Test
    void aTakenNameIsReportedBeforeMissingSubnets() {
        service.createLoadBalancer(REGION, "taken", "internal", "application", "ipv4", ALB_SUBNETS, List.of(),
                Map.of());
        AwsException refusal = assertThrows(AwsException.class, () -> service.createLoadBalancer(REGION,
                "taken", "internal", "application", "ipv4", List.of(), List.of(), Map.of()));
        assertEquals("DuplicateLoadBalancerName", refusal.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP", "HTTPS", "TCP"})
    void createTargetGroupRejectsMissingPortBeforePublishing(String protocol) {
        AwsException error = assertThrows(AwsException.class, () -> service.createTargetGroup(
                REGION, "missing-port", protocol, null, null, "vpc-test", "ip",
                null, null, null, null, null, null, null, null, null, null, Map.of()));
        assertEquals("ValidationError", error.getErrorCode());
        assertEquals("A port must be specified", error.getMessage());
        assertEquals(400, error.getHttpStatus());
        assertTrue(service.describeTargetGroups(REGION, null, List.of(), List.of()).isEmpty());
        verifyNoInteractions(dataPlane, healthChecker);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 65536, Integer.MAX_VALUE})
    void createListenerRejectsInvalidPortWithoutPublishing(int port) {
        String lb = service.createLoadBalancer(REGION, "listener-range", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of(), Map.of()).getLoadBalancerArn();
        clearInvocations(dataPlane);
        AwsException error = assertThrows(AwsException.class, () -> service.createListener(REGION, lb,
                "HTTP", port, null, List.of(), List.of(), List.of(), Map.of()));
        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertTrue(service.describeListeners(REGION, lb, List.of()).isEmpty());
        assertTrue(service.describeRules(REGION, null, List.of()).isEmpty());
        verifyNoInteractions(dataPlane);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 65536, Integer.MAX_VALUE})
    void modifyListenerRejectsInvalidPortWithoutMutation(int port) {
        String lb = service.createLoadBalancer(REGION, "listener-change", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of(), Map.of()).getLoadBalancerArn();
        Listener listener = service.createListener(REGION, lb, "HTTP", 8080, null,
                List.of(), List.of(), List.of(), Map.of());
        clearInvocations(dataPlane);
        AwsException error = assertThrows(AwsException.class, () -> service.modifyListener(REGION,
                listener.getListenerArn(), "HTTPS", port, "changed", List.of(HELD_CERTIFICATE), null, null));
        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(8080, listener.getPort());
        assertEquals("HTTP", listener.getProtocol());
        assertNull(listener.getSslPolicy());
        assertTrue(listener.getCertificates().isEmpty());
        verifyNoInteractions(dataPlane);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 65535})
    void listenerPortBoundariesAndOmittedUpdateAreAccepted(int port) {
        String lb = service.createLoadBalancer(REGION, "listener-boundary", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of(), Map.of()).getLoadBalancerArn();
        Listener listener = service.createListener(REGION, lb, "HTTP", port, null,
                List.of(), List.of(), List.of(), Map.of());
        assertEquals(port, listener.getPort());
        int replacement = port == 1 ? 65535 : 1;
        service.modifyListener(REGION, listener.getListenerArn(), null, replacement, null, null, null, null);
        assertEquals(replacement, listener.getPort());
        service.modifyListener(REGION, listener.getListenerArn(), null, null, null, null, null, null);
        assertEquals(replacement, listener.getPort());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void httpsCreateNeedsADefaultBeforeAllocatingAListener(List<String> certificates) {
        String lb = service.createLoadBalancer(REGION, "https-default", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        AwsException refusal = assertThrows(AwsException.class, () -> service.createListener(REGION, lb,
                "HTTPS", 9443, null, certificates, List.of(), List.of(), Map.of("scope", "refused")));
        assertEquals("InvalidConfigurationRequest", refusal.getErrorCode());
        assertTrue(service.describeListeners(REGION, lb, List.of()).isEmpty());
        assertTrue(service.describeRules(REGION, null, List.of()).isEmpty());
        Listener retry = service.createListener(REGION, lb, "HTTPS", 9443, null,
                List.of(HELD_CERTIFICATE), List.of(), List.of(), Map.of());
        assertEquals(List.of(HELD_CERTIFICATE), retry.getCertificates());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void httpsModifyNeedsAnEffectiveDefaultBeforeChangingTheListener(List<String> certificates) {
        String lb = service.createLoadBalancer(REGION, "https-change", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        Listener listener = service.createListener(REGION, lb, "HTTP", 9080, null, List.of(), List.of(), List.of(), Map.of());
        AwsException refusal = assertThrows(AwsException.class, () -> service.modifyListener(REGION,
                listener.getListenerArn(), "HTTPS", 9443, "ELBSecurityPolicy-2016-08", certificates, null, null));
        assertEquals("InvalidConfigurationRequest", refusal.getErrorCode());
        assertEquals("HTTP", listener.getProtocol());
        assertEquals(9080, listener.getPort());
        assertEquals(List.of(), listener.getCertificates());
        service.modifyListener(REGION, listener.getListenerArn(), "HTTPS", 9443, null,
                List.of(HELD_CERTIFICATE), null, null);
        assertEquals("HTTPS", listener.getProtocol());
        assertEquals(List.of(HELD_CERTIFICATE), listener.getCertificates());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTPS", "TLS"})
    void defaultCertificateRemovalIsRefusedBeforeChangingTheList(String protocol) {
        String lbArn = service.createLoadBalancer(REGION, "default-cert-lb", "internal",
                "TLS".equals(protocol) ? "network" : "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String first = "arn:aws:iam::000000000000:server-certificate/first";
        String additional = "arn:aws:iam::000000000000:server-certificate/additional";
        String listenerArn = service.createListener(REGION, lbArn, protocol, 9443, null,
                List.of(first, additional), List.of(), List.of(), Map.of()).getListenerArn();
        clearInvocations(dataPlane);

        AwsException rejected = assertThrows(AwsException.class,
                () -> service.removeListenerCertificates(REGION, listenerArn, List.of(additional, first)));
        assertEquals("OperationNotPermitted", rejected.getErrorCode());
        assertEquals(List.of(first, additional), service.describeListenerCertificates(REGION, listenerArn));
        verifyNoInteractions(dataPlane);

        service.removeListenerCertificates(REGION, listenerArn, List.of(additional));
        assertEquals(List.of(first), service.describeListenerCertificates(REGION, listenerArn));
        assertThrows(AwsException.class,
                () -> service.removeListenerCertificates(REGION, listenerArn, List.of(first)));
        assertEquals(List.of(first), service.describeListenerCertificates(REGION, listenerArn));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void missingHttpsDefaultIsRefusedEvenWithoutACertificateResolver(List<String> certificates) {
        String lbArn = service.createLoadBalancer(REGION, "missing-create", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        clearInvocations(dataPlane);
        AwsException rejected = assertThrows(AwsException.class, () -> service.createListener(
                REGION, lbArn, "HTTPS", 9443, null, certificates, List.of(), List.of(), Map.of()));
        assertEquals("InvalidConfigurationRequest", rejected.getErrorCode());
        assertTrue(service.describeListeners(REGION, lbArn, List.of()).isEmpty());
        assertTrue(service.describeRules(REGION, null, List.of()).isEmpty());
        verifyNoInteractions(dataPlane);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void missingEffectiveDefaultCannotChangeAnHttpListener(List<String> certificates) {
        String lbArn = service.createLoadBalancer(REGION, "missing-modify", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        Listener listener = service.createListener(REGION, lbArn, "HTTP", 8080, null,
                List.of(), List.of(), List.of(), Map.of());
        clearInvocations(dataPlane);
        AwsException rejected = assertThrows(AwsException.class, () -> service.modifyListener(
                REGION, listener.getListenerArn(), "HTTPS", 9443, "unchanged", certificates, null, null));
        assertEquals("InvalidConfigurationRequest", rejected.getErrorCode());
        assertEquals("HTTP", listener.getProtocol());
        assertEquals(8080, listener.getPort());
        assertTrue(listener.getCertificates().isEmpty());
        verifyNoInteractions(dataPlane);
    }

    @Test
    void removalCannotDeleteADefaultPublishedByConcurrentModification() throws Exception {
        String lbArn = service.createLoadBalancer(REGION, "default-replacement-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String first = "arn:aws:iam::000000000000:server-certificate/first";
        String additional = "arn:aws:iam::000000000000:server-certificate/additional";
        String listener = service.createListener(REGION, lbArn, "HTTPS", 9443, null,
                List.of(first, additional), List.of(), List.of(), Map.of()).getListenerArn();
        CountDownLatch checkedOriginalDefault = new CountDownLatch(1);
        CountDownLatch releaseRemoval = new CountDownLatch(1);
        List<String> requested = new ArrayList<>(List.of(additional)) {
            @Override
            public boolean contains(Object value) {
                boolean contains = super.contains(value);
                checkedOriginalDefault.countDown();
                try {
                    assertTrue(releaseRemoval.await(5, TimeUnit.SECONDS), "removal was not released");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("removal interrupted", failure);
                }
                return contains;
            }
        };
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> removal = worker.submit(() -> service.removeListenerCertificates(REGION, listener, requested));
            assertTrue(checkedOriginalDefault.await(5, TimeUnit.SECONDS), "removal did not check the original default");
            service.modifyListener(REGION, listener, null, null, null, List.of(additional), null, null);
            assertEquals(List.of(additional), service.describeListenerCertificates(REGION, listener));
            releaseRemoval.countDown();
            ExecutionException refused = assertThrows(ExecutionException.class,
                    () -> removal.get(5, TimeUnit.SECONDS));
            assertEquals("OperationNotPermitted", ((AwsException) refused.getCause()).getErrorCode());
            assertEquals(List.of(additional), service.describeListenerCertificates(REGION, listener));
            AwsException rejected = assertThrows(AwsException.class,
                    () -> service.removeListenerCertificates(REGION, listener, List.of(additional)));
            assertEquals("OperationNotPermitted", rejected.getErrorCode());
            assertEquals(List.of(additional), service.describeListenerCertificates(REGION, listener));
        } finally {
            releaseRemoval.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void certificateMetadataOnAnHttpListenerKeepsItsExistingRemovalBehavior() {
        String lbArn = service.createLoadBalancer(REGION, "http-cert-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String certificate = "arn:aws:iam::000000000000:server-certificate/metadata";
        String listenerArn = service.createListener(REGION, lbArn, "HTTP", 8080, null,
                List.of(certificate), List.of(), List.of(), Map.of()).getListenerArn();
        service.removeListenerCertificates(REGION, listenerArn, List.of(certificate));
        assertEquals(List.of(), service.describeListenerCertificates(REGION, listenerArn));
    }

    @Test
    void modifyListenerProtocolAndCertificateRestartsListener() {
        String lbArn = service.createLoadBalancer(
                REGION, "tls-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String listenerArn = service.createListener(
                REGION, lbArn, "HTTP", 9443, null, List.of(),
                List.of(), List.of(), Map.of()).getListenerArn();
        clearInvocations(dataPlane);

        service.modifyListener(
                REGION, listenerArn, "HTTPS", null, "ELBSecurityPolicy-TLS13-1-2-2021-06",
                List.of("arn:aws:acm:us-west-2:000000000000:certificate/first"), null, null);

        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.captor();
        verify(dataPlane).publishListener(listenerCaptor.capture(), eq(REGION), any(), any(), any(), any());
        assertEquals("HTTPS", listenerCaptor.getValue().getProtocol());
        assertEquals(List.of("arn:aws:acm:us-west-2:000000000000:certificate/first"),
                listenerCaptor.getValue().getCertificates());
        verify(dataPlane, never()).recompileRules(anyString(), any());
    }

    @Test
    void identicalExplicitTlsModificationReappliesTheDataPlane() {
        String lbArn = service.createLoadBalancer(
                REGION, "tls-retry-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String first = "arn:aws:acm:us-west-2:000000000000:certificate/first";
        String replacement = "arn:aws:acm:us-west-2:000000000000:certificate/replacement";
        String listenerArn = service.createListener(REGION, lbArn, "HTTPS", 9443, null,
                List.of(first), List.of(), List.of(), Map.of()).getListenerArn();
        clearInvocations(dataPlane);

        service.modifyListener(REGION, listenerArn, null, null, null, List.of(replacement), null, null);
        assertEquals(List.of(replacement), service.describeListenerCertificates(REGION, listenerArn));
        verify(dataPlane).publishListener(any(Listener.class), eq(REGION), any(), any(), any(), any());
        service.modifyListener(REGION, listenerArn, null, null, null, List.of(replacement), null, null);

        verify(dataPlane, times(2)).publishListener(any(Listener.class), eq(REGION), any(), any(), any(), any());
        assertEquals(List.of(replacement), service.describeListenerCertificates(REGION, listenerArn));
        verify(dataPlane, never()).recompileRules(anyString(), any());
    }

    @Test
    void listenerCertificateLifecycleRefreshesDataPlane() {
        String lbArn = service.createLoadBalancer(
                REGION, "certificate-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String firstCertificate = "arn:aws:acm:us-west-2:000000000000:certificate/first";
        String secondCertificate = "arn:aws:acm:us-west-2:000000000000:certificate/second";
        String listenerArn = service.createListener(
                REGION, lbArn, "HTTPS", 9443, null, List.of(firstCertificate),
                List.of(), List.of(), Map.of()).getListenerArn();
        clearInvocations(dataPlane);

        service.addListenerCertificates(REGION, listenerArn, List.of(secondCertificate));
        service.removeListenerCertificates(REGION, listenerArn, List.of(secondCertificate));

        verify(dataPlane, times(2))
                .publishListener(any(Listener.class), eq(REGION), any(), any(), any(), any());
        assertEquals(List.of(firstCertificate),
                service.describeListenerCertificates(REGION, listenerArn));
    }

    @Test
    void defaultRemovalWithAdditionalCertificateDoesNotRefreshAndCanRemoveAdditional() {
        String lbArn = service.createLoadBalancer(
                REGION, "default-certificate-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String first = "arn:aws:acm:us-west-2:000000000000:certificate/default";
        String additional = "arn:aws:acm:us-west-2:000000000000:certificate/additional";
        String listener = service.createListener(REGION, lbArn, "HTTPS", 9443, null,
                List.of(first, additional), List.of(), List.of(), Map.of()).getListenerArn();
        clearInvocations(dataPlane);
        AwsException rejected = assertThrows(AwsException.class,
                () -> service.removeListenerCertificates(REGION, listener, List.of(first)));
        assertEquals("OperationNotPermitted", rejected.getErrorCode());
        assertEquals(List.of(first, additional), service.describeListenerCertificates(REGION, listener));
        verifyNoInteractions(dataPlane);
        service.removeListenerCertificates(REGION, listener, List.of(additional));
        assertEquals(List.of(first), service.describeListenerCertificates(REGION, listener));
        verify(dataPlane).publishListener(any(Listener.class), eq(REGION), any(), any(), any(), any());
    }

    @Test
    void initializeStorageRestoresHttpsListenerCertificate() {
        SharedStorageFactory storageFactory = new SharedStorageFactory();
        ElbV2Service first = serviceWithStorage(
                storageFactory, mock(ElbV2DataPlane.class), mock(ElbV2HealthChecker.class));
        String lbArn = first.createLoadBalancer(
                REGION, "persisted-https-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String certificateArn = "arn:aws:acm:us-west-2:000000000000:certificate/persisted";
        String listenerArn = first.createListener(
                REGION, lbArn, "HTTPS", 9443, "ELBSecurityPolicy-TLS13-1-2-2021-06",
                List.of(certificateArn), List.of(), List.of(), Map.of()).getListenerArn();

        ElbV2DataPlane reloadedDataPlane = mock(ElbV2DataPlane.class);
        ElbV2Service reloaded = serviceWithStorage(storageFactory, reloadedDataPlane, mock(ElbV2HealthChecker.class));
        reloaded.restorePersistedRuntime();

        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.captor();
        verify(reloadedDataPlane).publishListener(listenerCaptor.capture(), eq(REGION), any(), any(), any(), any());
        assertEquals(listenerArn, listenerCaptor.getValue().getListenerArn());
        assertEquals("HTTPS", listenerCaptor.getValue().getProtocol());
        assertEquals(List.of(certificateArn), listenerCaptor.getValue().getCertificates());
    }

    @Test
    void modifyListenerDefaultActionsRecompilesRulesWithoutRestartingListener() {
        String lbArn = service.createLoadBalancer(
                REGION, "sample-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String oldTgArn = createTargetGroup("sample-old-tg");
        String newTgArn = createTargetGroup("sample-new-tg");
        String listenerArn = service.createListener(
                REGION, lbArn, "HTTP", 9999, null, List.of(),
                List.of(forwardAction(oldTgArn)), List.of(), Map.of()).getListenerArn();
        clearInvocations(dataPlane);

        service.modifyListener(
                REGION, listenerArn, null, null, null, null,
                List.of(forwardAction(newTgArn)), null);

        ArgumentCaptor<Supplier<List<Rule>>> rulesCaptor = ArgumentCaptor.captor();
        verify(dataPlane).recompileRules(eq(listenerArn), rulesCaptor.capture());
        verify(dataPlane, never()).removeListener(anyString(), any(), any());
        verify(dataPlane, never()).publishListener(any(Listener.class), anyString(), any(), any(), any(), any());

        Rule defaultRule = rulesCaptor.getValue().get().stream()
                .filter(Rule::isDefault)
                .findFirst()
                .orElseThrow();
        assertEquals(newTgArn, defaultRule.getActions().getFirst().getTargetGroupArn());

        TargetGroup oldTargetGroup = service.describeTargetGroups(REGION, null, List.of(oldTgArn), null).getFirst();
        TargetGroup newTargetGroup = service.describeTargetGroups(REGION, null, List.of(newTgArn), null).getFirst();
        assertFalse(oldTargetGroup.getLoadBalancerArns().contains(lbArn));
        assertTrue(newTargetGroup.getLoadBalancerArns().contains(lbArn));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 65535})
    void gatewayListenerRejectsExplicitPortWithoutPublication(int port) {
        String lbArn = service.createLoadBalancer(REGION, "gateway-port", "internal", "gateway", "ipv4",
                ALB_SUBNETS, List.of(), Map.of()).getLoadBalancerArn();
        clearInvocations(dataPlane);

        AwsException createError = assertThrows(AwsException.class, () -> service.createListener(
                REGION, lbArn, "GENEVE", port, null, List.of(), List.of(), List.of(), Map.of()));
        assertEquals("InvalidConfigurationRequest", createError.getErrorCode());
        assertTrue(service.describeListeners(REGION, lbArn, null).isEmpty());
        verifyNoInteractions(dataPlane);

        Listener listener = service.createListener(REGION, lbArn, null, null, null,
                List.of(), List.of(), List.of(), Map.of());
        String originalProtocol = listener.getProtocol();
        clearInvocations(dataPlane);
        AwsException modifyError = assertThrows(AwsException.class, () -> service.modifyListener(
                REGION, listener.getListenerArn(), "TCP", port, null, null, null, null));
        assertEquals("InvalidConfigurationRequest", modifyError.getErrorCode());
        assertNull(listener.getPort());
        assertEquals(originalProtocol, listener.getProtocol());
        verifyNoInteractions(dataPlane);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GENEVE", "TCP", ""})
    void gatewayListenerRejectsExplicitProtocolWithoutPublication(String protocol) {
        String lbArn = service.createLoadBalancer(REGION, "gateway-protocol", "internal", "gateway", "ipv4",
                ALB_SUBNETS, List.of(), Map.of()).getLoadBalancerArn();
        clearInvocations(dataPlane);

        AwsException error = assertThrows(AwsException.class, () -> service.createListener(
                REGION, lbArn, protocol, null, null, List.of(), List.of(), List.of(), Map.of()));

        assertEquals("InvalidConfigurationRequest", error.getErrorCode());
        assertTrue(service.describeListeners(REGION, lbArn, null).isEmpty());
        verifyNoInteractions(dataPlane);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GENEVE", "TCP", ""})
    void gatewayListenerRejectsProtocolUpdateWithoutMutation(String protocol) {
        String lbArn = service.createLoadBalancer(REGION, "gateway-protocol", "internal", "gateway", "ipv4",
                ALB_SUBNETS, List.of(), Map.of()).getLoadBalancerArn();
        Listener listener = service.createListener(REGION, lbArn, null, null, null,
                List.of(), List.of(), List.of(), Map.of());
        String originalProtocol = listener.getProtocol();
        clearInvocations(dataPlane);

        AwsException error = assertThrows(AwsException.class, () -> service.modifyListener(
                REGION, listener.getListenerArn(), protocol, null, null, null, null, null));

        assertEquals("InvalidConfigurationRequest", error.getErrorCode());
        assertEquals(originalProtocol, listener.getProtocol());
        assertNull(listener.getPort());
        verifyNoInteractions(dataPlane);
    }

    @Test
    void gatewayListenerOmittedPortAllowsActionUpdate() {
        String lbArn = service.createLoadBalancer(REGION, "gateway-actions", "internal", "gateway", "ipv4",
                ALB_SUBNETS, List.of(), Map.of()).getLoadBalancerArn();
        String oldTarget = createTargetGroup("gateway-old");
        String newTarget = createTargetGroup("gateway-new");
        Listener listener = service.createListener(REGION, lbArn, null, null, null,
                List.of(), List.of(forwardAction(oldTarget)), List.of(), Map.of());
        assertEquals("GENEVE", listener.getProtocol());

        service.modifyListener(REGION, listener.getListenerArn(), null, null, null, null,
                List.of(forwardAction(newTarget)), null);

        assertNull(listener.getPort());
        assertEquals("GENEVE", listener.getProtocol());
        assertEquals(newTarget, listener.getDefaultActions().getFirst().getTargetGroupArn());
        assertEquals(newTarget, service.describeRules(REGION, listener.getListenerArn(), null).getFirst()
                .getActions().getFirst().getTargetGroupArn());
    }

    @Test
    void modifyListenerPortRestartsListener() {
        String lbArn = service.createLoadBalancer(
                REGION, "sample-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String tgArn = createTargetGroup("sample-tg");
        String listenerArn = service.createListener(
                REGION, lbArn, "HTTP", 9999, null, List.of(),
                List.of(forwardAction(tgArn)), List.of(), Map.of()).getListenerArn();
        clearInvocations(dataPlane);

        service.modifyListener(REGION, listenerArn, null, 10000, null, null, null, null);

        verify(dataPlane).publishListener(any(Listener.class), eq(REGION), any(), any(), any(), any());
        verify(dataPlane, never()).removeListener(anyString(), any(), any());
    }

    @Test
    void setRulePrioritiesIgnoresPrioritiesHeldOnOtherListeners() {
        String tgArn = createTargetGroup("sample-tg");
        String publicListener = createListener("public-lb", tgArn);
        String internalListener = createListener("internal-lb", tgArn);
        service.createRule(REGION, publicListener, List.of(pathPattern("/signup/*")),
                100, List.of(forwardAction(tgArn)), Map.of());
        Rule moved = service.createRule(REGION, internalListener, List.of(pathPattern("/documents/*")),
                230, List.of(forwardAction(tgArn)), Map.of());

        service.setRulePriorities(REGION, Map.of(moved.getRuleArn(), 100));

        assertEquals("100", service.describeRules(REGION, null, List.of(moved.getRuleArn())).getFirst().getPriority());
    }

    @Test
    void setRulePrioritiesChecksEachListenerOnlyAgainstItsOwnRules() {
        String tgArn = createTargetGroup("sample-tg");
        String firstListener = createListener("first-lb", tgArn);
        String secondListener = createListener("second-lb", tgArn);
        Rule first = service.createRule(REGION, firstListener, List.of(pathPattern("/a/*")),
                10, List.of(forwardAction(tgArn)), Map.of());
        service.createRule(REGION, secondListener, List.of(pathPattern("/b/*")),
                100, List.of(forwardAction(tgArn)), Map.of());
        Rule second = service.createRule(REGION, secondListener, List.of(pathPattern("/c/*")),
                20, List.of(forwardAction(tgArn)), Map.of());

        service.setRulePriorities(REGION, Map.of(first.getRuleArn(), 100, second.getRuleArn(), 200));

        assertEquals("100", service.describeRules(REGION, null, List.of(first.getRuleArn())).getFirst().getPriority());
        assertEquals("200", service.describeRules(REGION, null, List.of(second.getRuleArn())).getFirst().getPriority());
    }

    @Test
    void setRulePrioritiesRejectsPriorityHeldOnSameListener() {
        String tgArn = createTargetGroup("sample-tg");
        String listenerArn = createListener("sample-lb", tgArn);
        service.createRule(REGION, listenerArn, List.of(pathPattern("/a/*")),
                100, List.of(forwardAction(tgArn)), Map.of());
        Rule moved = service.createRule(REGION, listenerArn, List.of(pathPattern("/b/*")),
                230, List.of(forwardAction(tgArn)), Map.of());

        AwsException e = assertThrows(AwsException.class,
                () -> service.setRulePriorities(REGION, Map.of(moved.getRuleArn(), 100)));

        assertEquals("PriorityInUse", e.getErrorCode());
        assertEquals("230", service.describeRules(REGION, null, List.of(moved.getRuleArn())).getFirst().getPriority());
    }

    @Test
    void setRulePrioritiesRejectsDuplicatePrioritiesForSameListener() {
        String tgArn = createTargetGroup("sample-tg");
        String listenerArn = createListener("sample-lb", tgArn);
        Rule first = service.createRule(REGION, listenerArn, List.of(pathPattern("/a/*")),
                10, List.of(forwardAction(tgArn)), Map.of());
        Rule second = service.createRule(REGION, listenerArn, List.of(pathPattern("/b/*")),
                20, List.of(forwardAction(tgArn)), Map.of());

        AwsException e = assertThrows(AwsException.class,
                () -> service.setRulePriorities(REGION, Map.of(first.getRuleArn(), 50, second.getRuleArn(), 50)));

        assertEquals("PriorityInUse", e.getErrorCode());
        assertEquals("10", service.describeRules(REGION, null, List.of(first.getRuleArn())).getFirst().getPriority());
        assertEquals("20", service.describeRules(REGION, null, List.of(second.getRuleArn())).getFirst().getPriority());
    }

    @Test
    void setRulePrioritiesAllowsSwappingPrioritiesOnSameListener() {
        String tgArn = createTargetGroup("sample-tg");
        String listenerArn = createListener("sample-lb", tgArn);
        Rule first = service.createRule(REGION, listenerArn, List.of(pathPattern("/a/*")),
                10, List.of(forwardAction(tgArn)), Map.of());
        Rule second = service.createRule(REGION, listenerArn, List.of(pathPattern("/b/*")),
                20, List.of(forwardAction(tgArn)), Map.of());

        service.setRulePriorities(REGION, Map.of(first.getRuleArn(), 20, second.getRuleArn(), 10));

        assertEquals("20", service.describeRules(REGION, null, List.of(first.getRuleArn())).getFirst().getPriority());
        assertEquals("10", service.describeRules(REGION, null, List.of(second.getRuleArn())).getFirst().getPriority());
    }

    @Test
    void createLoadBalancerUsesConfiguredHostnameForDnsSuffix() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.hostname()).thenReturn(Optional.of("floci"));
        service.config = config;

        String dnsName = service.createLoadBalancer(
                REGION, "sample-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getDnsName();

        assertTrue(dnsName.endsWith(".elb.floci"));
    }

    @Test
    void initializeStorageReloadsPersistedResourcesAndRebuildsIndexes() {
        SharedStorageFactory storageFactory = new SharedStorageFactory();
        ElbV2DataPlane firstDataPlane = mock(ElbV2DataPlane.class);
        ElbV2HealthChecker firstHealthChecker = mock(ElbV2HealthChecker.class);
        ElbV2Service first = serviceWithStorage(storageFactory, firstDataPlane, firstHealthChecker);
        String lbArn = first.createLoadBalancer(
                REGION, "persisted-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of("owner", "platform")).getLoadBalancerArn();
        String tgArn = first.createTargetGroup(
                REGION, "persisted-tg", "HTTP", "HTTP1", 8080, "vpc-a", "instance",
                "HTTP", "traffic-port", true, "/health", 15, 5, 3, 2, "200",
                "ipv4", Map.of("tier", "web")).getTargetGroupArn();
        TargetDescription target = new TargetDescription();
        target.setId("i-1234567890abcdef0");
        target.setPort(8080);
        first.registerTargets(REGION, tgArn, List.of(target));
        String listenerArn = first.createListener(
                REGION, lbArn, "HTTP", 9080, null, List.of(),
                List.of(forwardAction(tgArn)), List.of(), Map.of("listener", "frontdoor")).getListenerArn();
        Rule rule = first.createRule(
                REGION, listenerArn, List.of(pathPattern("/api/*")),
                10, List.of(forwardAction(tgArn)), Map.of("rule", "api"));
        first.addTags(List.of(lbArn, tgArn, listenerArn, rule.getRuleArn()), Map.of("env", "test"));

        ElbV2DataPlane reloadedDataPlane = mock(ElbV2DataPlane.class);
        ElbV2HealthChecker reloadedHealthChecker = mock(ElbV2HealthChecker.class);
        ElbV2Service reloaded = serviceWithStorage(storageFactory, reloadedDataPlane, reloadedHealthChecker);

        assertEquals(lbArn, reloaded.describeLoadBalancers(REGION, null, List.of("persisted-lb"), null, null)
                .getFirst().getLoadBalancerArn());
        TargetGroup reloadedTargetGroup = reloaded.describeTargetGroups(REGION, lbArn, null, null).getFirst();
        assertEquals(tgArn, reloadedTargetGroup.getTargetGroupArn());
        assertEquals(List.of(lbArn), reloadedTargetGroup.getLoadBalancerArns());
        assertEquals("i-1234567890abcdef0", reloadedTargetGroup.getTargets().getFirst().getId());
        Listener reloadedListener = reloaded.describeListeners(REGION, lbArn, null).getFirst();
        assertEquals(listenerArn, reloadedListener.getListenerArn());
        List<Rule> reloadedRules = reloaded.describeRules(REGION, listenerArn, null);
        assertEquals(2, reloadedRules.size());
        assertTrue(reloadedRules.stream().anyMatch(Rule::isDefault));
        assertTrue(reloadedRules.stream().anyMatch(candidate -> "10".equals(candidate.getPriority())));
        assertEquals("test", reloaded.describeTags(List.of(lbArn)).get(lbArn).get("env"));
        assertThrows(AwsException.class, () -> reloaded.deleteTargetGroup(REGION, tgArn));

        // Data-plane and health-check startup is deliberately not part of construction (#1913).
        reloaded.restorePersistedRuntime();
        verify(reloadedHealthChecker).startMonitoring(any(TargetGroup.class));
        ArgumentCaptor<List<TargetDescription>> targetsCaptor = ArgumentCaptor.captor();
        verify(reloadedHealthChecker).addTargets(eq(tgArn), targetsCaptor.capture(), any(TargetGroup.class));
        assertEquals("i-1234567890abcdef0", targetsCaptor.getValue().getFirst().getId());
        assertEquals(8080, targetsCaptor.getValue().getFirst().getPort());
        verify(reloadedDataPlane).publishListener(any(Listener.class), eq(REGION), any(), any(), any(), any());

        reloaded.removeTags(List.of(rule.getRuleArn()), List.of("env"));
        ElbV2Service updatedReload = serviceWithStorage(storageFactory, mock(ElbV2DataPlane.class), mock(ElbV2HealthChecker.class));
        assertFalse(updatedReload.describeTags(List.of(rule.getRuleArn()))
                .get(rule.getRuleArn()).containsKey("env"));
    }

    @Test
    void initializeStorageDoesNotTouchCollaborators() {
        // #1913: ElbV2DataPlane.binding() calls back into this service, and during @PostConstruct the
        // ApplicationScoped context has no instance yet — so CDI re-enters bean creation and recurses
        // until the stack (or, before the storage dedupe in #1931, the heap) is gone. Nothing reachable
        // from initializeStorage() may call an injected collaborator. A mock cannot reproduce the
        // recursion, since it never calls back, so assert the absence of the interaction instead.
        SharedStorageFactory storageFactory = new SharedStorageFactory();
        ElbV2Service first = serviceWithStorage(storageFactory, mock(ElbV2DataPlane.class), mock(ElbV2HealthChecker.class));
        String lbArn = first.createLoadBalancer(
                REGION, "recursion-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String tgArn = first.createTargetGroup(
                REGION, "recursion-tg", "HTTP", "HTTP1", 8080, "vpc-a", "instance",
                "HTTP", "traffic-port", true, "/health", 15, 5, 3, 2, "200",
                "ipv4", Map.of()).getTargetGroupArn();
        String listenerArn = first.createListener(
                REGION, lbArn, "HTTP", 9090, null, List.of(),
                List.of(forwardAction(tgArn)), List.of(), Map.of()).getListenerArn();

        ElbV2DataPlane reloadedDataPlane = mock(ElbV2DataPlane.class);
        ElbV2HealthChecker reloadedHealthChecker = mock(ElbV2HealthChecker.class);
        ElbV2Service reloaded = serviceWithStorage(storageFactory, reloadedDataPlane, reloadedHealthChecker);

        verifyNoInteractions(reloadedDataPlane, reloadedHealthChecker);

        reloaded.restorePersistedRuntime();

        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.captor();
        verify(reloadedDataPlane).publishListener(listenerCaptor.capture(), eq(REGION), any(), any(), any(), any());
        assertEquals(listenerArn, listenerCaptor.getValue().getListenerArn());
        verify(reloadedHealthChecker).startMonitoring(any(TargetGroup.class));
    }

    @Test
    void restorePersistedRuntimeStartsPersistedListenersWithoutTargetGroups() {
        SharedStorageFactory storageFactory = new SharedStorageFactory();
        ElbV2Service first = serviceWithStorage(storageFactory, mock(ElbV2DataPlane.class), mock(ElbV2HealthChecker.class));
        String lbArn = first.createLoadBalancer(
                REGION, "listener-only-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String listenerArn = first.createListener(
                REGION, lbArn, "HTTP", 8080, null, List.of(),
                List.of(), List.of(), Map.of()).getListenerArn();

        ElbV2DataPlane reloadedDataPlane = mock(ElbV2DataPlane.class);
        ElbV2Service reloaded = serviceWithStorage(storageFactory, reloadedDataPlane, mock(ElbV2HealthChecker.class));
        reloaded.restorePersistedRuntime();

        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.captor();
        verify(reloadedDataPlane).publishListener(listenerCaptor.capture(), eq(REGION), any(), any(), any(), any());
        assertEquals(listenerArn, listenerCaptor.getValue().getListenerArn());
    }

    @Test
    void deleteListenerIsIgnoredForRegionWithNoListeners() {
        String emptyRegion = "eu-central-1";

        service.deleteListener(emptyRegion, "arn:aws:elasticloadbalancing:" + emptyRegion
                + ":000000000000:listener/app/sample-lb/1111111111111111/2222222222222222");

        assertTrue(service.describeListeners(emptyRegion, null, null).isEmpty());
        verifyNoInteractions(dataPlane);
    }

    @Test
    void deleteLoadBalancerIsIgnoredForRegionWithNoLoadBalancers() {
        String emptyRegion = "eu-central-1";

        service.deleteLoadBalancer(emptyRegion, "arn:aws:elasticloadbalancing:" + emptyRegion
                + ":000000000000:loadbalancer/app/sample-lb/1111111111111111");

        assertTrue(service.describeLoadBalancers(emptyRegion, null, null, null, null).isEmpty());
        verifyNoInteractions(dataPlane);
    }

    @Test
    void describeTargetHealthReturnsUnusedForExplicitUnregisteredTarget() {
        String tgArn = createTargetGroup("sample-tg");
        TargetDescription target = new TargetDescription();
        target.setId("i-1234567890abcdef0");
        target.setPort(9999);

        TargetHealth health = service.describeTargetHealth(REGION, tgArn, List.of(target)).getFirst();

        assertEquals("unused", health.getState());
        assertEquals("Target.NotRegistered", health.getReason());
        assertEquals("Target is not registered to the target group", health.getDescription());
    }

    @Test
    void registerTargetsRejectsLinkLocalAndMetadataAddresses() {
        String tgArn = createTargetGroup("metadata-tg");

        for (String address : List.of("169.254.169.254", "169.254.170.2", "fe80::1", "fd00:ec2::254")) {
            TargetDescription target = new TargetDescription();
            target.setId(address);
            AwsException ex = assertThrows(AwsException.class,
                    () -> service.registerTargets(REGION, tgArn, List.of(target)), address);
            assertEquals("InvalidTarget", ex.getErrorCode());
        }
        assertTrue(service.describeTargetGroups(REGION, null, List.of(tgArn), null).getFirst().getTargets().isEmpty());
        verify(healthChecker, never()).addTargets(eq(tgArn), anyList(), any(TargetGroup.class));
    }

    @Test
    void registerTargetsStillAcceptsLoopbackAndPrivateAddresses() {
        String tgArn = createTargetGroup("local-tg");

        for (String address : List.of("127.0.0.1", "10.0.0.5", "172.17.0.2", "192.168.1.10")) {
            TargetDescription target = new TargetDescription();
            target.setId(address);
            service.registerTargets(REGION, tgArn, List.of(target));
        }
        assertEquals(4, service.describeTargetGroups(REGION, null, List.of(tgArn), null).getFirst().getTargets().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void registerTargetsUsesEffectivePortForIdentity(boolean initiallyExplicit) {
        String tgArn = createTargetGroup("default-port-tg");
        TargetDescription original = target("i-default", initiallyExplicit ? 9999 : null);
        TargetDescription replacement = target("i-default", initiallyExplicit ? null : 9999);
        TargetDescription override = target("i-default", 10000);
        service.registerTargets(REGION, tgArn, List.of(original, override));

        service.registerTargets(REGION, tgArn, List.of(replacement));

        List<TargetDescription> registered = service.describeTargetGroups(
                REGION, null, List.of(tgArn), null).getFirst().getTargets();
        assertEquals(2, registered.size());
        assertTrue(registered.stream().allMatch(t -> "i-default".equals(t.getId())));
        List<Integer> ports = registered.stream().map(TargetDescription::getPort).toList();
        assertTrue(ports.contains(10000));
        assertTrue(ports.contains(9999));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deregisterTargetsMatchesDefaultPortWithoutRemovingAnOverride(boolean initiallyExplicit) {
        String tgArn = createTargetGroup("remove-default-port-tg");
        TargetDescription original = target("i-default", initiallyExplicit ? 9999 : null);
        TargetDescription override = target("i-default", 10000);
        service.registerTargets(REGION, tgArn, List.of(original, override));
        TargetDescription removal = target("i-default", initiallyExplicit ? null : 9999);

        service.deregisterTargets(REGION, tgArn, List.of(removal));
        service.deregisterTargets(REGION, tgArn, List.of(removal));

        List<TargetDescription> registered = service.describeTargetGroups(
                REGION, null, List.of(tgArn), null).getFirst().getTargets();
        assertEquals(1, registered.size());
        assertEquals("i-default", registered.getFirst().getId());
        assertEquals(10000, registered.getFirst().getPort());
        TargetHealth removed = service.describeTargetHealth(REGION, tgArn, List.of(removal)).getFirst();
        assertEquals("unused", removed.getState());
        assertEquals("Target.NotRegistered", removed.getReason());
        assertEquals("9999", removed.getHealthCheckPort());
        verify(healthChecker, times(2)).removeTargets(eq(tgArn), eq(List.of(removal)), any(TargetGroup.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65536, Integer.MAX_VALUE})
    void createTargetGroupRejectsOutOfRangePortBeforePublishing(int port) {
        AwsException error = assertThrows(AwsException.class, () -> service.createTargetGroup(
                REGION, "invalid-port", "HTTP", "HTTP1", port, "vpc-a", "ip",
                "HTTP", "traffic-port", true, "/", 30, 5, 5, 2, "200", "ipv4", Map.of()));
        assertEquals("ValidationError", error.getErrorCode());
        assertTrue(service.describeTargetGroups(REGION, null, null, null).isEmpty());
        verifyNoInteractions(healthChecker);
    }

    @ParameterizedTest
    @CsvSource({"register,0", "register,65536", "deregister,0", "deregister,65536", "health,0", "health,65536"})
    void targetPortValidationPrecedesLookup(String operation, int port) {
        AwsException error = assertThrows(AwsException.class,
                () -> targetOperation(operation, "missing-group", List.of(target("i-valid", port))));
        assertEquals("ValidationError", error.getErrorCode());
        assertTrue(error.getMessage().contains("targets.1.member.port"));
        verifyNoInteractions(healthChecker);
    }

    @ParameterizedTest
    @ValueSource(strings = {"register", "deregister", "health"})
    void invalidTargetPortInBatchPreservesRegisteredTargets(String operation) {
        String arn = createTargetGroup("range-batch");
        service.registerTargets(REGION, arn, List.of(target("i-valid", 9999)));
        clearInvocations(healthChecker);
        AwsException error = assertThrows(AwsException.class, () -> targetOperation(operation, arn,
                List.of(target("i-valid", null), target("i-invalid", 65536))));
        assertEquals("ValidationError", error.getErrorCode());
        assertTrue(error.getMessage().contains("targets.2.member.port"));
        List<TargetDescription> registered = service.describeTargetGroups(REGION, null, List.of(arn), null)
                .getFirst().getTargets();
        assertEquals(1, registered.size());
        assertEquals("i-valid", registered.getFirst().getId());
        assertEquals(9999, registered.getFirst().getPort());
        verifyNoInteractions(healthChecker);
    }

    @ParameterizedTest
    @CsvSource({"register,65536,65537", "deregister,65536,65537", "health,65536,65537",
            "register,65537,65536", "deregister,65537,65536", "health,65537,65536"})
    void targetPortErrorsAggregateBeforeMutation(String operation, int first, int second) {
        String arn = createTargetGroup("aggregate-ports");
        service.registerTargets(REGION, arn, List.of(target("i-valid", 9999)));
        clearInvocations(healthChecker);
        AwsException error = assertThrows(AwsException.class, () -> targetOperation(operation, arn,
                List.of(target("i-valid", null), target("i-first", first), target("i-second", second))));
        assertEquals("ValidationError", error.getErrorCode());
        assertEquals("2 validation errors detected: Value '" + first
                + "' at 'targets.2.member.port' failed to satisfy constraint: Member must have value "
                + "less than or equal to 65535; Value '" + second
                + "' at 'targets.3.member.port' failed to satisfy constraint: Member must have value "
                + "less than or equal to 65535", error.getMessage());
        List<TargetDescription> registered = service.describeTargetGroups(REGION, null, List.of(arn), null)
                .getFirst().getTargets();
        assertEquals(1, registered.size());
        assertEquals("i-valid", registered.getFirst().getId());
        assertEquals(9999, registered.getFirst().getPort());
        verifyNoInteractions(healthChecker);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 65535})
    void targetPortBoundariesRemainUsable(int port) {
        TargetGroup group = service.createTargetGroup(REGION, "boundary-port", "HTTP", "HTTP1", port,
                "vpc-a", "ip", "HTTP", "traffic-port", true, "/", 30, 5, 5, 2, "200", "ipv4", Map.of());
        String arn = group.getTargetGroupArn();
        service.registerTargets(REGION, arn, List.of(target("10.0.0.5", null), target("10.0.0.5", port)));
        assertEquals(1, group.getTargets().size());
        assertEquals(port, group.getTargets().getFirst().getPort());
        service.deregisterTargets(REGION, arn, List.of(target("10.0.0.5", null)));
        assertTrue(group.getTargets().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"register,1", "register,65535", "deregister,1", "deregister,65535", "health,1", "health,65535"})
    void lambdaTargetPortsAreRejectedBeforeBatchMutation(String operation, int port) {
        TargetGroup group = service.createTargetGroup(REGION, "lambda-port", null, null, null,
                null, "lambda", null, null, false, null, null, null, null, null, null, null, Map.of());
        String id = "arn:aws:lambda:us-west-2:000000000000:function:target";
        service.registerTargets(REGION, group.getTargetGroupArn(), List.of(target(id, null)));
        clearInvocations(healthChecker);
        AwsException error = assertThrows(AwsException.class, () -> targetOperation(operation,
                group.getTargetGroupArn(), List.of(target(id, null), target(id, port))));
        assertEquals("ValidationError", error.getErrorCode());
        assertEquals("Port cannot be specified for Lambda function targets", error.getMessage());
        assertEquals(1, group.getTargets().size());
        assertEquals(null, group.getTargets().getFirst().getPort());
        verifyNoInteractions(healthChecker);
    }

    @ParameterizedTest
    @ValueSource(strings = {"register", "deregister", "health"})
    void lambdaTargetPortRangeValidationPrecedesTargetTypeValidation(String operation) {
        TargetGroup group = service.createTargetGroup(REGION, "lambda-range", null, null, null,
                null, "lambda", null, null, false, null, null, null, null, null, null, null, Map.of());
        clearInvocations(healthChecker);
        AwsException error = assertThrows(AwsException.class, () -> targetOperation(operation,
                group.getTargetGroupArn(), List.of(target("lambda-target", 65536))));
        assertEquals("ValidationError", error.getErrorCode());
        assertTrue(error.getMessage().contains("targets.1.member.port"));
        verifyNoInteractions(healthChecker);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 65535})
    void lambdaTargetsDoNotInheritGroupPort(int port) {
        TargetGroup group = service.createTargetGroup(REGION, "lambda-group-port", null, null, port,
                null, "lambda", null, null, false, null, null, null, null, null, null, null, Map.of());
        String arn = group.getTargetGroupArn();
        String id = "arn:aws:lambda:us-west-2:000000000000:function:target";
        service.registerTargets(REGION, arn, List.of(target(id, null)));
        TargetDescription described = service.describeTargetHealth(REGION, arn, null).getFirst().getTarget();
        assertNull(described.getPort());
        service.deregisterTargets(REGION, arn, List.of(described));
        assertTrue(service.describeTargetHealth(REGION, arn, null).isEmpty());
    }

    private void targetOperation(String operation, String arn, List<TargetDescription> targets) {
        Runnable request = switch (operation) {
            case "register" -> () -> service.registerTargets(REGION, arn, targets);
            case "deregister" -> () -> service.deregisterTargets(REGION, arn, targets);
            case "health" -> () -> service.describeTargetHealth(REGION, arn, targets);
            default -> throw new IllegalArgumentException(operation);
        };
        request.run();
    }

    private static TargetDescription target(String id, Integer port) {
        TargetDescription target = new TargetDescription();
        target.setId(id);
        target.setPort(port);
        return target;
    }

    private String createTargetGroup(String name) {
        return service.createTargetGroup(
                REGION, name, "HTTP", "HTTP1", 9999, "vpc-a", "instance",
                "HTTP", "traffic-port", true, "/v1/ready", 30, 5, 5, 2, "200",
                "ipv4", Map.of()).getTargetGroupArn();
    }

    private String createListener(String loadBalancerName, String targetGroupArn) {
        String lbArn = service.createLoadBalancer(
                REGION, loadBalancerName, "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        return service.createListener(
                REGION, lbArn, "HTTP", 80, null, List.of(),
                List.of(forwardAction(targetGroupArn)), List.of(), Map.of()).getListenerArn();
    }

    private static Action forwardAction(String targetGroupArn) {
        Action action = new Action();
        action.setType("forward");
        action.setTargetGroupArn(targetGroupArn);
        return action;
    }

    private static RuleCondition pathPattern(String value) {
        RuleCondition condition = new RuleCondition();
        condition.setField("path-pattern");
        condition.setValues(List.of(value));
        condition.setPathPatternValues(List.of(value));
        return condition;
    }

    static StorageFactory sharedStorageFactory() {
        return new SharedStorageFactory();
    }

    static ElbV2Service serviceWithStorage(StorageFactory storageFactory,
                                                   ElbV2DataPlane dataPlane,
                                                   ElbV2HealthChecker healthChecker) {
        Ec2Service ec2Service = mock(Ec2Service.class);
        stubAlbSubnets(ec2Service);
        return serviceWithStorage(storageFactory, dataPlane, healthChecker, ec2Service);
    }

    static ElbV2Service serviceWithStorage(StorageFactory storageFactory,
                                                   ElbV2DataPlane dataPlane,
                                                   ElbV2HealthChecker healthChecker,
                                                   Ec2Service ec2Service) {
        ElbV2Service service = new ElbV2Service();
        allowPublications(dataPlane);
        service.dataPlane = dataPlane;
        service.healthChecker = healthChecker;
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.ec2Service = ec2Service;
        service.storageFactory = storageFactory;
        service.initializeStorage();
        return service;
    }

    private static void stubAlbSubnets(Ec2Service ec2Service) {
        lenient().when(ec2Service.supplyWithSubnetReferencesHeld(anyString(), any()))
                .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
        lenient().doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(ec2Service).runWithSubnetReferencesHeld(anyString(), any());
        Subnet subnetA = subnet("subnet-a", REGION + "a");
        Subnet subnetB = subnet("subnet-b", REGION + "b");
        lenient().when(ec2Service.requireSubnet(REGION, "subnet-a")).thenReturn(subnetA);
        lenient().when(ec2Service.requireSubnet(REGION, "subnet-b")).thenReturn(subnetB);
        lenient().when(ec2Service.describeSubnets(eq(REGION), eq(ALB_SUBNETS), eq(Map.of())))
                .thenReturn(List.of(subnetA, subnetB));
    }

    private static Subnet subnet(String subnetId, String availabilityZone) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(subnetId);
        subnet.setAvailabilityZone(availabilityZone);
        subnet.setVpcId("vpc-a");
        return subnet;
    }

    /** Files in a directory, so a second service reads back what the first wrote as JSON. */
    private static final class PersistentStorageFactory extends StorageFactory {
        private final Path dir;

        private PersistentStorageFactory(Path dir) {
            super(null, null);
            this.dir = dir;
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                     String fileName,
                                                     TypeReference<Map<String, V>> typeReference) {
            PersistentStorage<String, V> files = new PersistentStorage<>(dir.resolve(fileName), typeReference);
            files.load();
            return new AccountAwareStorageBackend<>(files, null, "000000000000");
        }
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                     String fileName,
                                                     TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName, ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }

    /**
     * A balancer stored before the zone was looked up per region and type carries the one fixed
     * zone every balancer used to report; a restart gives it the zone of its region and type back.
     */
    @Test
    void aRestoredNetworkLoadBalancerTakesTheHostedZoneOfItsRegionAndType() {
        SharedStorageFactory storageFactory = new SharedStorageFactory();
        ElbV2Service first = serviceWithStorage(storageFactory, mock(ElbV2DataPlane.class),
                mock(ElbV2HealthChecker.class));
        first.createLoadBalancer(REGION, "stored-nlb", "internal", "network", "ipv4",
                ALB_SUBNETS, List.of(), Map.of());
        first.describeLoadBalancers(REGION, null, List.of("stored-nlb"), null, null).getFirst()
                .setCanonicalHostedZoneId("Z35SXDOTRQ7X7K");

        ElbV2Service reloaded = serviceWithStorage(storageFactory, mock(ElbV2DataPlane.class),
                mock(ElbV2HealthChecker.class));
        assertEquals("Z35SXDOTRQ7X7K", reloaded.describeLoadBalancers(REGION, null, List.of("stored-nlb"), null, null)
                .getFirst().getCanonicalHostedZoneId());

        reloaded.restorePersistedRuntime();

        assertEquals(AwsRegionFacts.nlbHostedZoneId(REGION).orElseThrow(),
                reloaded.describeLoadBalancers(REGION, null, List.of("stored-nlb"), null, null)
                        .getFirst().getCanonicalHostedZoneId());
    }

    @Test
    void subnetAndVpcDependenciesComeFromPersistedLoadBalancersOnly(@TempDir Path dir) {
        ElbV2Service first = serviceWithStorage(new PersistentStorageFactory(dir), dataPlane, healthChecker);
        String arn = first.createLoadBalancer(REGION, "stored-dependency", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of(), Map.of()).getLoadBalancerArn();
        String targetGroup = first.createTargetGroup(REGION, "independent-group", "HTTP", "HTTP1", 80,
                "vpc-a", "instance", "HTTP", "traffic-port", true, "/", 30, 5, 5, 2, "200",
                "ipv4", Map.of()).getTargetGroupArn();
        ElbV2Service restored = serviceWithStorage(new PersistentStorageFactory(dir), dataPlane, healthChecker);
        assertTrue(restored.hasSubnetDependency(REGION, "subnet-a"));
        assertTrue(restored.hasSubnetDependency(REGION, "subnet-b"));
        assertTrue(restored.hasVpcDependency(REGION, "vpc-a"));
        assertFalse(restored.hasSubnetDependency("us-east-1", "subnet-a"));
        assertFalse(restored.hasVpcDependency("us-east-1", "vpc-a"));
        assertFalse(restored.hasSubnetDependency(REGION, "subnet-unrelated"));
        restored.deleteLoadBalancer(REGION, arn);
        ElbV2Service afterDelete = serviceWithStorage(new PersistentStorageFactory(dir), dataPlane, healthChecker);
        assertFalse(afterDelete.hasSubnetDependency(REGION, "subnet-a"));
        assertFalse(afterDelete.hasVpcDependency(REGION, "vpc-a"));
        assertEquals(targetGroup, afterDelete.describeTargetGroups(REGION, null,
                List.of(targetGroup), null).getFirst().getTargetGroupArn());
    }

    /**
     * A balancer saved before CreateLoadBalancer required subnets has no zones and no VPC. Its first
     * SetSubnets gives it both, taking the VPC of those subnets, since it has none of its own to keep.
     */
    @Test
    void aBalancerSavedWithoutSubnetsTakesTheVpcOfItsFirstSetSubnets() {
        LoadBalancer saved = service.createLoadBalancer(REGION, "saved-subnetless", "internal", "application",
                "ipv4", ALB_SUBNETS, List.of(), Map.of());
        saved.setAvailabilityZones(new ArrayList<>());
        saved.setVpcId(null);
        Subnet subnetC = subnet("subnet-c", REGION + "a");
        subnetC.setVpcId("vpc-b");
        Subnet subnetD = subnet("subnet-d", REGION + "b");
        subnetD.setVpcId("vpc-b");
        when(ec2Service.describeSubnets(REGION, List.of("subnet-c", "subnet-d"), Map.of()))
                .thenReturn(List.of(subnetC, subnetD));

        service.setSubnets(REGION, saved.getLoadBalancerArn(), List.of("subnet-c", "subnet-d"));

        LoadBalancer moved = service.describeLoadBalancers(REGION, List.of(saved.getLoadBalancerArn()), null,
                null, null).getFirst();
        assertEquals("vpc-b", moved.getVpcId());
        assertEquals(List.of("subnet-c", "subnet-d"),
                moved.getAvailabilityZones().stream().map(AvailabilityZone::getSubnetId).toList());
    }

    /**
     * SetSubnets counts the subnets the way CreateLoadBalancer does: one subnet for an Application Load
     * Balancer gets the refusal CreateLoadBalancer gives it, before the subnet is looked up, and the
     * balancer keeps its zones and its VPC.
     */
    @Test
    void anApplicationLoadBalancerMovedOntoOneSubnetIsRefusedBeforeTheSubnetIsLookedUp() {
        LoadBalancer lb = service.createLoadBalancer(REGION, "set-one-subnet", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of(), Map.of());
        // Stubbed so that a lookup before the count would reach the zone check, not a SubnetNotFound from the mock.
        lenient().when(ec2Service.describeSubnets(REGION, List.of("subnet-a"), Map.of()))
                .thenReturn(List.of(subnet("subnet-a", REGION + "a")));
        clearInvocations(ec2Service);

        AwsException refusal = assertThrows(AwsException.class,
                () -> service.setSubnets(REGION, lb.getLoadBalancerArn(), List.of("subnet-a")));

        assertEquals("ValidationError", refusal.getErrorCode());
        assertEquals("At least two subnets in two different Availability Zones must be specified",
                refusal.getMessage());
        assertEquals(400, refusal.getHttpStatus());
        LoadBalancer kept = service.describeLoadBalancers(REGION, List.of(lb.getLoadBalancerArn()), null, null,
                null).getFirst();
        assertEquals(ALB_SUBNETS, kept.getAvailabilityZones().stream().map(AvailabilityZone::getSubnetId).toList());
        assertEquals("vpc-a", kept.getVpcId());
        verify(ec2Service, never()).describeSubnets(anyString(), anyList(), any());
    }

    /**
     * A Network or Gateway Load Balancer can be in a single Availability Zone, so SetSubnets onto one
     * subnet is taken: the Network Load Balancer leaves one of its two subnets, and the Gateway Load
     * Balancer, which AWS lets add subnets but not drop them, is set onto the one it is on.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"network|subnet-a,subnet-b", "gateway|subnet-a"})
    void aNetworkOrGatewayLoadBalancerCanBeSetOntoOneSubnet(String type, String createdOn) {
        when(ec2Service.describeSubnets(REGION, List.of("subnet-a"), Map.of()))
                .thenReturn(List.of(subnet("subnet-a", REGION + "a")));
        LoadBalancer lb = service.createLoadBalancer(REGION, "set-one-" + type, "internal", type, "ipv4",
                List.of(createdOn.split(",")), List.of(), Map.of());

        service.setSubnets(REGION, lb.getLoadBalancerArn(), List.of("subnet-a"));

        assertEquals(List.of("subnet-a"), service.describeLoadBalancers(REGION, List.of(lb.getLoadBalancerArn()),
                null, null, null).getFirst().getAvailabilityZones().stream().map(AvailabilityZone::getSubnetId).toList());
    }

    /**
     * setSubnets counts only a list that names subnets; the SetSubnets action refuses an empty one before
     * it gets here. A CloudFormation update moves a balancer an earlier Floci version saved without
     * subnets onto the template's subnets, and when the update rolls back, the move back onto none is taken.
     */
    @Test
    void aRollbackOntoNoSubnetsIsNotCounted() {
        LoadBalancer saved = service.createLoadBalancer(REGION, "saved-without-subnets", "internal", "application",
                "ipv4", ALB_SUBNETS, List.of(), Map.of());
        saved.setAvailabilityZones(new ArrayList<>());
        saved.setVpcId(null);
        service.setSubnets(REGION, saved.getLoadBalancerArn(), ALB_SUBNETS);

        service.setSubnets(REGION, saved.getLoadBalancerArn(), List.of());

        assertTrue(service.describeLoadBalancers(REGION, List.of(saved.getLoadBalancerArn()), null, null, null)
                .getFirst().getAvailabilityZones().isEmpty());
    }

    /**
     * Through setSubnets with one subnet: the count depends on the balancer's type, so a balancer that
     * does not exist is reported first.
     */
    @Test
    void setSubnetsOntoOneSubnetReportsAMissingBalancerFirst() {
        AwsException refusal = assertThrows(AwsException.class, () -> service.setSubnets(REGION,
                "arn:aws:elasticloadbalancing:" + REGION + ":000000000000:loadbalancer/app/missing/0123456789abcdef",
                List.of("subnet-a")));

        assertEquals("LoadBalancerNotFound", refusal.getErrorCode());
        verify(ec2Service, never()).describeSubnets(anyString(), anyList(), any());
    }
}
