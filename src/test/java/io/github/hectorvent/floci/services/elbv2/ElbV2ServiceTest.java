package io.github.hectorvent.floci.services.elbv2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegionFacts;
import io.github.hectorvent.floci.core.common.CertificateReferenceResolver;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.Rule;
import io.github.hectorvent.floci.services.elbv2.model.RuleCondition;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import io.github.hectorvent.floci.services.elbv2.model.TargetHealth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        service.dataPlane = dataPlane;
        service.healthChecker = healthChecker;
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.ec2Service = ec2Service;
        stubAlbSubnets(ec2Service);
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
            removal.get(5, TimeUnit.SECONDS);
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

        ArgumentCaptor<List<Rule>> rulesCaptor = ArgumentCaptor.captor();
        verify(dataPlane).recompileRules(eq(listenerArn), rulesCaptor.capture());
        verify(dataPlane, never()).stopListener(anyString());
        verify(dataPlane, never()).startListener(any(Listener.class), anyString(), anyList());
        verify(dataPlane, never()).restartListener(any(Listener.class), anyString(), anyList());

        Rule defaultRule = rulesCaptor.getValue().stream()
                .filter(Rule::isDefault)
                .findFirst()
                .orElseThrow();
        assertEquals(newTgArn, defaultRule.getActions().getFirst().getTargetGroupArn());

        TargetGroup oldTargetGroup = service.describeTargetGroups(REGION, null, List.of(oldTgArn), null).getFirst();
        TargetGroup newTargetGroup = service.describeTargetGroups(REGION, null, List.of(newTgArn), null).getFirst();
        assertFalse(oldTargetGroup.getLoadBalancerArns().contains(lbArn));
        assertTrue(newTargetGroup.getLoadBalancerArns().contains(lbArn));
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

        verify(dataPlane).restartListener(any(Listener.class), eq(REGION), anyList());
        verify(dataPlane, never()).stopListener(anyString());
        verify(dataPlane, never()).startListener(any(Listener.class), anyString(), anyList());
    }

    @Test
    void modifyListenerRefusedUnderTheCertificateGuardLeavesTheListenerWhereItWas() {
        String lbArn = service.createLoadBalancer(
                REGION, "sample-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String tgArn = createTargetGroup("sample-tg");
        String listenerArn = service.createListener(
                REGION, lbArn, "HTTPS", 443, "ELBSecurityPolicy-2016-08", List.of(HELD_CERTIFICATE),
                List.of(forwardAction(tgArn)), List.of(), Map.of()).getListenerArn();
        clearInvocations(dataPlane);
        service.certificateResolver = deletedOnTheSecondLook();

        AwsException e = assertThrows(AwsException.class, () -> service.modifyListener(
                REGION, listenerArn, "TLS", 8443, "ELBSecurityPolicy-TLS13-1-2-2021-06",
                List.of(OTHER_CERTIFICATE), null, null));

        assertEquals("CertificateNotFound", e.getErrorCode());
        Listener listener = service.describeListeners(REGION, lbArn, List.of(listenerArn)).getFirst();
        assertEquals(Integer.valueOf(443), listener.getPort());
        assertEquals("HTTPS", listener.getProtocol());
        assertEquals("ELBSecurityPolicy-2016-08", listener.getSslPolicy());
        assertEquals(List.of(HELD_CERTIFICATE), listener.getCertificates());
        verify(dataPlane, never()).restartListener(any(Listener.class), anyString(), anyList());
    }

    @Test
    void modifyListenerProtocolAloneTakesTheGuardForTheCertificatesItPutsIntoService() {
        String lbArn = service.createLoadBalancer(
                REGION, "sample-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String tgArn = createTargetGroup("sample-tg");
        String listenerArn = service.createListener(
                REGION, lbArn, "HTTP", 80, null, List.of(HELD_CERTIFICATE),
                List.of(forwardAction(tgArn)), List.of(), Map.of()).getListenerArn();
        clearInvocations(dataPlane);
        service.certificateResolver = deletedOnTheSecondLook();

        AwsException e = assertThrows(AwsException.class, () -> service.modifyListener(
                REGION, listenerArn, "HTTPS", null, null, null, null, null));

        assertEquals("CertificateNotFound", e.getErrorCode());
        assertEquals("HTTP", service.describeListeners(REGION, lbArn, List.of(listenerArn))
                .getFirst().getProtocol());
    }

    /**
     * A resolver that finds the certificate once and not again, which is what a
     * {@code DeleteServerCertificate} landing between the check outside the guard and the one
     * under it looks like from inside the call.
     */
    private static CertificateReferenceResolver deletedOnTheSecondLook() {
        CertificateReferenceResolver resolver = mock(CertificateReferenceResolver.class);
        when(resolver.exists(anyString(), anyString())).thenReturn(true, false);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(resolver).runWhileHeld(any(), any());
        return resolver;
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
        verify(reloadedDataPlane).startListener(any(Listener.class), eq(REGION), anyList());

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
        verify(reloadedDataPlane).startListener(listenerCaptor.capture(), eq(REGION), anyList());
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
        verify(reloadedDataPlane).startListener(listenerCaptor.capture(), eq(REGION), anyList());
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

    private static ElbV2Service serviceWithStorage(StorageFactory storageFactory,
                                                   ElbV2DataPlane dataPlane,
                                                   ElbV2HealthChecker healthChecker) {
        Ec2Service ec2Service = mock(Ec2Service.class);
        stubAlbSubnets(ec2Service);
        return serviceWithStorage(storageFactory, dataPlane, healthChecker, ec2Service);
    }

    private static ElbV2Service serviceWithStorage(StorageFactory storageFactory,
                                                   ElbV2DataPlane dataPlane,
                                                   ElbV2HealthChecker healthChecker,
                                                   Ec2Service ec2Service) {
        ElbV2Service service = new ElbV2Service();
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
}
