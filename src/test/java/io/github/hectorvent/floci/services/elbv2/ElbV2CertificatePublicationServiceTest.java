package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.CertificateReferenceResolver;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import io.github.hectorvent.floci.testutil.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.LogRecord;

import static io.github.hectorvent.floci.services.elbv2.ElbV2PublicationTestSupport.allowPublications;
import static io.github.hectorvent.floci.services.elbv2.ElbV2PublicationTestSupport.applyPublication;
import static io.github.hectorvent.floci.services.elbv2.ElbV2ServiceTest.serviceWithStorage;
import static io.github.hectorvent.floci.services.elbv2.ElbV2ServiceTest.sharedStorageFactory;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ElbV2CertificatePublicationServiceTest {
    private static final String REGION = "us-west-2";
    private static final List<String> ALB_SUBNETS = List.of("subnet-a", "subnet-b");
    private static final String HELD_CERTIFICATE = "arn:aws:iam::000000000000:server-certificate/held";
    private static final String OTHER_CERTIFICATE = "arn:aws:iam::000000000000:server-certificate/other";

    @Mock
    ElbV2DataPlane dataPlane;

    @Mock
    ElbV2HealthChecker healthChecker;

    private ElbV2Service service;

    @BeforeEach
    void setUp() {
        service = serviceWithStorage(null, dataPlane, healthChecker);
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "modify", "add"})
    void unavailableAcmCannotBePublishedWithoutALiveTlsRuntime(String operation) {
        String lb = service.createLoadBalancer(REGION, "pending-acm", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        Listener existing = service.createListener(REGION, lb, "HTTPS", 9443, null,
                List.of(HELD_CERTIFICATE), List.of(), List.of(), Map.of());
        String pending = "arn:aws:acm:us-west-2:000000000000:certificate/pending";
        CertificateReferenceResolver resolver = mock(CertificateReferenceResolver.class);
        AtomicBoolean issued = new AtomicBoolean();
        when(resolver.exists(anyString(), anyString())).thenReturn(true);
        when(resolver.isUsableAcmCertificate(REGION, pending)).thenAnswer(call -> issued.get());
        lenient().doAnswer(call -> call.getArgument(1, Supplier.class).get()).when(resolver).supplyWhileHeld(any(), any());
        service.certificateResolver = resolver;
        Runnable request = () -> {
            if ("create".equals(operation)) {
                service.createListener(REGION, lb, "HTTPS", 9444, null, List.of(pending), List.of(), List.of(), Map.of());
            } else if ("modify".equals(operation)) {
                service.modifyListener(REGION, existing.getListenerArn(), null, null, null, List.of(pending), null, null);
            } else {
                service.addListenerCertificates(REGION, existing.getListenerArn(), List.of(pending));
            }
        };
        AwsException failure = assertThrows(AwsException.class, request::run);
        assertEquals("CertificateNotFound", failure.getErrorCode());
        assertEquals(1, service.describeListeners(REGION, lb, List.of()).size());
        assertEquals(List.of(HELD_CERTIFICATE), existing.getCertificates());
        issued.set(true);
        assertDoesNotThrow(request::run);
        assertEquals("create".equals(operation) ? 2 : 1, service.describeListeners(REGION, lb, List.of()).size());
        assertEquals("add".equals(operation) ? List.of(HELD_CERTIFICATE, pending)
                : "modify".equals(operation) ? List.of(pending) : List.of(HELD_CERTIFICATE), existing.getCertificates());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void portOnlyChangesRetryAProtocolConflictFromAnObsoleteSnapshot(boolean atPublication) {
        LoadBalancer lb = service.createLoadBalancer(REGION, "protocol-retry", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of());
        Listener listener = service.createListener(REGION, lb.getLoadBalancerArn(), "HTTPS", 9443, null,
                List.of(HELD_CERTIFICATE), List.of(), List.of(), Map.of());
        AtomicBoolean first = new AtomicBoolean(true);
        Runnable conflict = () -> {
            if (first.compareAndSet(true, false)) {
                service.modifyListener(REGION, listener.getListenerArn(), "HTTP", null, null, null, null, null);
                throw new ElbV2DataPlane.IncompatibleListenerProtocolException(9090);
            }
        };
        if (atPublication) {
            doAnswer(call -> {
                Listener prospective = call.getArgument(0);
                if (Integer.valueOf(9090).equals(prospective.getPort())) {
                    conflict.run();
                }
                return applyPublication(call);
            }).when(dataPlane).publishListener(any(), eq(REGION), any(), any(), any(), any());
        } else {
            doAnswer(call -> {
                conflict.run();
                return null;
            }).when(dataPlane).requireCompatibleListenerProtocol(listener.getListenerArn(), 9090, "HTTPS");
        }
        Listener moved = assertDoesNotThrow(() -> service.modifyListener(REGION, listener.getListenerArn(),
                null, 9090, null, null, null, null));
        assertEquals("HTTP", moved.getProtocol());
        assertEquals(9090, moved.getPort());
        assertEquals(List.of(HELD_CERTIFICATE), moved.getCertificates());
        assertFalse(first.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unchangedProtocolConflictsRemainTerminal(boolean atPublication) {
        LoadBalancer lb = service.createLoadBalancer(REGION, "protocol-refusal", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of());
        Listener listener = service.createListener(REGION, lb.getLoadBalancerArn(), "HTTPS", 9443, null,
                List.of(HELD_CERTIFICATE), List.of(), List.of(), Map.of());
        AtomicReference<Integer> refusals = new AtomicReference<>(0);
        if (atPublication) {
            doAnswer(call -> {
                refusals.updateAndGet(count -> count + 1);
                throw new ElbV2DataPlane.IncompatibleListenerProtocolException(9090);
            }).when(dataPlane).publishListener(any(), eq(REGION), any(), any(), any(), any());
        } else {
            doAnswer(call -> {
                refusals.updateAndGet(count -> count + 1);
                throw new ElbV2DataPlane.IncompatibleListenerProtocolException(9090);
            }).when(dataPlane).requireCompatibleListenerProtocol(listener.getListenerArn(), 9090, "HTTPS");
        }
        AwsException refusal = assertThrows(AwsException.class, () -> service.modifyListener(REGION,
                listener.getListenerArn(), null, 9090, null, null, null, null));
        assertEquals("InvalidConfigurationRequest", refusal.getErrorCode());
        assertEquals(1, refusals.get());
        assertEquals(9443, listener.getPort());
        assertEquals("HTTPS", listener.getProtocol());
    }

    @Test
    void deletedParentRefusesAListenerWaitingForItsPublicationFence() throws Exception {
        LoadBalancer lb = service.createLoadBalancer(REGION, "parent-fence", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of());
        CountDownLatch publicationEntered = new CountDownLatch(1);
        AtomicReference<Thread> publisher = new AtomicReference<>();
        doAnswer(call -> {
            publisher.set(Thread.currentThread());
            publicationEntered.countDown();
            return applyPublication(call);
        }).when(dataPlane).publishListener(any(Listener.class), eq(REGION), any(), any(), any(), any());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Listener> creation;
            synchronized (lb) {
                creation = worker.submit(() -> service.createListener(REGION, lb.getLoadBalancerArn(), "HTTP", 9090,
                        null, List.of(), List.of(), List.of(), Map.of()));
                assertTrue(publicationEntered.await(5, TimeUnit.SECONDS));
                long owner = Thread.currentThread().threadId();
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(publisher.get().threadId());
                    assertNotNull(info);
                    assertEquals(Thread.State.BLOCKED, info.getThreadState());
                    assertEquals(owner, info.getLockOwnerId());
                });
                service.deleteLoadBalancer(REGION, lb.getLoadBalancerArn());
            }
            ExecutionException refused = assertThrows(ExecutionException.class, () -> creation.get(5, TimeUnit.SECONDS));
            assertEquals("LoadBalancerNotFound", ((AwsException) refused.getCause()).getErrorCode());
            AwsException missing = assertThrows(AwsException.class,
                    () -> service.describeListeners(REGION, lb.getLoadBalancerArn(), List.of()));
            assertEquals("LoadBalancerNotFound", missing.getErrorCode());
            assertTrue(service.describeListeners(REGION, null, List.of()).isEmpty());
            assertTrue(service.describeRules(REGION, null, List.of()).isEmpty());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
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
        verify(dataPlane, never()).removeListener(anyString(), any(), any());
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oneBrokenSavedListenerDoesNotPreventHealthyRestoration(boolean domainFailure) {
        StorageFactory factory = sharedStorageFactory();
        ElbV2Service first = serviceWithStorage(factory, mock(ElbV2DataPlane.class), mock(ElbV2HealthChecker.class));
        String lb = first.createLoadBalancer(REGION, "restore-isolation", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        Listener broken = first.createListener(REGION, lb, "HTTP", 9080, null,
                List.of(), List.of(), List.of(), Map.of());
        Listener healthy = first.createListener(REGION, lb, "HTTP", 9081, null,
                List.of(), List.of(), List.of(), Map.of());
        ElbV2DataPlane runtime = mock(ElbV2DataPlane.class);
        ElbV2Service reloaded = serviceWithStorage(factory, runtime, mock(ElbV2HealthChecker.class));
        AtomicBoolean firstRestore = new AtomicBoolean(true);
        doAnswer(call -> {
            if (firstRestore.getAndSet(false)) {
                if (domainFailure) {
                    throw new AwsException("CertificateNotFound", "controlled missing stored material", 400);
                }
                throw new IllegalStateException("controlled unusable stored material");
            }
            return applyPublication(call);
        }).when(runtime).publishListener(any(), eq(REGION), any(), any(), any(), any());
        List<LogRecord> warnings = LogCapture.capture(ElbV2Service.class,
                () -> assertDoesNotThrow(reloaded::restorePersistedRuntime));
        String reason = domainFailure ? "controlled missing stored material" : "controlled unusable stored material";
        assertTrue(warnings.stream().anyMatch(record -> record.getParameters() != null
                && record.getParameters().length == 3
                && reason.equals(record.getParameters()[2])), "restore warning must explain why the listener was skipped");
        ArgumentCaptor<Listener> restored = ArgumentCaptor.captor();
        verify(runtime, times(2)).publishListener(restored.capture(), eq(REGION), any(), any(), any(), any());
        assertEquals(List.of(broken.getListenerArn(), healthy.getListenerArn()).stream().sorted().toList(),
                restored.getAllValues().stream().map(Listener::getListenerArn).sorted().toList());
        assertEquals(2, reloaded.describeListeners(REGION, lb, List.of()).size());
        allowPublications(runtime);
        assertDoesNotThrow(reloaded::restorePersistedRuntime);
        assertEquals(2, reloaded.describeListeners(REGION, lb, List.of()).size());
    }

    /**
     * A resolver that finds the certificate once and not again, which is what a
     * {@code DeleteServerCertificate} landing between the check outside the guard and the one
     * under it looks like from inside the call.
     */
    private static CertificateReferenceResolver deletedOnTheSecondLook() {
        CertificateReferenceResolver resolver = mock(CertificateReferenceResolver.class);
        AtomicBoolean held = new AtomicBoolean();
        when(resolver.exists(anyString(), anyString())).thenAnswer(call -> !held.get());
        doAnswer(invocation -> {
            held.set(true);
            try {
                return invocation.getArgument(1, Supplier.class).get();
            } finally {
                held.set(false);
            }
        }).when(resolver).supplyWhileHeld(any(), any());
        return resolver;
    }

    private String createTargetGroup(String name) {
        return service.createTargetGroup(
                REGION, name, "HTTP", "HTTP1", 9999, "vpc-a", "instance",
                "HTTP", "traffic-port", true, "/v1/ready", 30, 5, 5, 2, "200",
                "ipv4", Map.of()).getTargetGroupArn();
    }

    private static Action forwardAction(String targetGroupArn) {
        Action action = new Action();
        action.setType("forward");
        action.setTargetGroupArn(targetGroupArn);
        return action;
    }
}
