package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegionFacts;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elb.model.ClassicHealthCheck;
import io.github.hectorvent.floci.services.elb.model.ClassicListener;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ElbClassicServiceTest {

    private static final String REGION = "us-west-2";
    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "222222222222";

    @Mock
    Ec2Service ec2Service;

    @Mock
    ElbClassicHealthChecker healthChecker;

    private ElbClassicService service;

    @BeforeEach
    void setUp() {
        service = new ElbClassicService(ec2Service, healthChecker, null, null);
        lenient().when(ec2Service.describeSubnets(anyString(), anyList(), any()))
                .thenAnswer(invocation -> {
                    List<String> ids = invocation.getArgument(1);
                    return ids.stream().map(id -> {
                        Subnet subnet = new Subnet();
                        subnet.setSubnetId(id);
                        subnet.setVpcId("vpc-1");
                        subnet.setAvailabilityZone(REGION + id.substring(id.length() - 1));
                        return subnet;
                    }).toList();
                });
    }

    private static ClassicListener httpListener() {
        ClassicListener listener = new ClassicListener();
        listener.setProtocol("HTTP");
        listener.setLoadBalancerPort(80);
        listener.setInstanceProtocol("HTTP");
        listener.setInstancePort(8080);
        return listener;
    }

    private ClassicLoadBalancer create(String name) {
        return service.createLoadBalancer(REGION, name, List.of(httpListener()),
                List.of(), List.of("subnet-a"), List.of("sg-1"), null, Map.of());
    }

    @Test
    void omittedInstanceProtocolMatchesExplicitDefaultOnListenerRetry() {
        ClassicListener listener = httpListener();
        listener.setInstanceProtocol(null);
        ClassicLoadBalancer lb = service.createLoadBalancer(REGION, "default-listener",
                List.of(listener), List.of(), List.of("subnet-a"), List.of(), null, Map.of());
        assertEquals("HTTP", lb.getListeners().getFirst().getInstanceProtocol());
        service.createLoadBalancerListeners(REGION, "default-listener", List.of(httpListener()));
        assertEquals(1, lb.getListeners().size());

        ClassicListener tcp = new ClassicListener();
        tcp.setProtocol("TCP");
        tcp.setLoadBalancerPort(443);
        tcp.setInstancePort(9090);
        service.createLoadBalancerListeners(REGION, "default-listener", List.of(tcp));
        assertEquals("TCP", lb.getListeners().getLast().getInstanceProtocol());
        ClassicListener explicit = new ClassicListener();
        explicit.setProtocol("TCP");
        explicit.setLoadBalancerPort(443);
        explicit.setInstancePort(9090);
        explicit.setInstanceProtocol("TCP");
        service.createLoadBalancerListeners(REGION, "default-listener", List.of(explicit));
        assertEquals(2, lb.getListeners().size());
    }

    @Test
    void createGivesADnsNameAndTheAwsDefaultHealthCheck() {
        ClassicLoadBalancer lb = create("my-elb");

        assertTrue(lb.getDnsName().startsWith("my-elb-"));
        assertEquals("internet-facing", lb.getScheme());
        assertEquals("vpc-1", lb.getVpcId());
        assertEquals(List.of(REGION + "a"), lb.getAvailabilityZones());
        // AWS's documented defaults for a load balancer that has never been configured.
        assertEquals("TCP:80", lb.getHealthCheck().getTarget());
        assertEquals(30, lb.getHealthCheck().getInterval());
        assertEquals(10, lb.getHealthCheck().getHealthyThreshold());
        verify(healthChecker).startMonitoring(lb);
    }

    @Test
    void createRejectsAMissingNameWithTheClassicParameterName() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createLoadBalancer(REGION, null, List.of(httpListener()),
                        List.of(), List.of(), List.of(), null, Map.of()));
        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().contains("LoadBalancerName"), e.getMessage());
    }

    @Test
    void createRejectsAListenerMissingARequiredMember() {
        ClassicListener incomplete = new ClassicListener();
        incomplete.setProtocol("HTTP");
        incomplete.setLoadBalancerPort(80);
        AwsException e = assertThrows(AwsException.class, () ->
                service.createLoadBalancer(REGION, "my-elb", List.of(incomplete),
                        List.of(), List.of(), List.of(), null, Map.of()));
        assertEquals("ValidationError", e.getErrorCode());
    }

    @Test
    void createRejectsAnEmptyListenerListNamingTheMissingMember() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createLoadBalancer(REGION, "my-elb", List.of(),
                        List.of(), List.of("subnet-a"), List.of("sg-1"), null, Map.of()));
        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().contains("listener"), e.getMessage());
    }

    /**
     * CreateLoadBalancer's Errors table documents InvalidConfigurationRequest at HTTP 409, not
     * 400 — a client that retries on 409 but fails hard on 400 depends on the difference.
     */
    @Test
    void subnetsSpanningTwoVpcsAreRejectedWithTheDocumentedStatus() {
        when(ec2Service.describeSubnets(anyString(), anyList(), any()))
                .thenAnswer(invocation -> {
                    List<String> ids = invocation.getArgument(1);
                    return ids.stream().map(id -> {
                        Subnet subnet = new Subnet();
                        subnet.setSubnetId(id);
                        subnet.setVpcId("vpc-" + id.charAt(id.length() - 1));
                        subnet.setAvailabilityZone(REGION + id.charAt(id.length() - 1));
                        return subnet;
                    }).toList();
                });

        AwsException e = assertThrows(AwsException.class, () ->
                service.createLoadBalancer(REGION, "my-elb", List.of(httpListener()),
                        List.of(), List.of("subnet-a", "subnet-b"), List.of("sg-1"), null, Map.of()));
        assertEquals("InvalidConfigurationRequest", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
    }

    @Test
    void createRejectsADuplicateName() {
        create("my-elb");
        AwsException e = assertThrows(AwsException.class, () -> create("my-elb"));
        assertEquals("DuplicateLoadBalancerName", e.getErrorCode());
    }

    /**
     * The name check and the write have to be one step. Checked before the write and nothing
     * else, two creates of one name both pass the check and both commit, and the caller of the
     * first is told it succeeded while holding a load balancer that was replaced.
     */
    @Test
    void twoConcurrentCreatesOfOneNameLeaveOnlyOneWinner() throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            String name = "concurrent-elb-" + attempt;
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<?>> creates = List.of(
                        pool.submit(() -> {
                            start.await();
                            // Zones rather than subnets, and no security group, so that nothing
                            // in here reaches a mock from two threads at once.
                            return service.createLoadBalancer(REGION, name,
                                    List.of(httpListener()), List.of(REGION + "a"), List.of(),
                                    List.of(), null, Map.of());
                        }),
                        pool.submit(() -> {
                            start.await();
                            return service.createLoadBalancer(REGION, name,
                                    List.of(httpListener()), List.of(REGION + "a"), List.of(),
                                    List.of(), null, Map.of());
                        }));
                start.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "attempt did not finish");

                int won = 0;
                int refused = 0;
                for (Future<?> create : creates) {
                    try {
                        create.get();
                        won++;
                    } catch (ExecutionException e) {
                        assertTrue(e.getCause() instanceof AwsException aws
                                        && "DuplicateLoadBalancerName".equals(aws.getErrorCode()),
                                "the loser must be refused by name, got " + e.getCause());
                        refused++;
                    }
                }
                assertEquals(1, won, "exactly one create must succeed for attempt " + attempt);
                assertEquals(1, refused, "exactly one create must be refused for attempt " + attempt);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /**
     * IAM's {@code DeleteServerCertificate} walks these listener lists while deciding whether the
     * certificate it is about to remove is still in use. It holds its own lock for that and not
     * this service's, by design, so listener writes run alongside the walk and the list has to
     * tolerate them: on a plain list the walk ends in ConcurrentModificationException, which no
     * mapper covers, and the delete answers an unmapped 500.
     */
    @Test
    void theCertificateScanSurvivesListenerWritesOnAnotherThread() throws Exception {
        create("scanned-elb");
        ClassicListener https = new ClassicListener();
        https.setProtocol("HTTPS");
        https.setLoadBalancerPort(443);
        https.setInstanceProtocol("HTTP");
        https.setInstancePort(80);
        https.setSslCertificateId("arn:aws:iam::000000000000:server-certificate/scanned");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean writing = new AtomicBoolean(true);
        try {
            Future<?> writer = pool.submit(() -> {
                start.await();
                try {
                    for (int i = 0; i < 400; i++) {
                        service.createLoadBalancerListeners(REGION, "scanned-elb", List.of(https));
                        service.deleteLoadBalancerListeners(REGION, "scanned-elb", List.of(443));
                    }
                } finally {
                    writing.set(false);
                }
                return null;
            });
            Future<Integer> scanner = pool.submit(() -> {
                start.await();
                int scans = 0;
                while (writing.get()) {
                    service.serverCertificateReferences();
                    scans++;
                }
                return scans;
            });
            start.countDown();

            writer.get(30, TimeUnit.SECONDS);
            assertTrue(scanner.get(30, TimeUnit.SECONDS) > 0, "the scan never ran");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void loadBalancersAreScopedByRegion() {
        create("my-elb");
        assertTrue(service.hasLoadBalancer(REGION, "my-elb"));
        assertTrue(service.describeLoadBalancers("eu-west-1", null).isEmpty());
    }

    @Test
    void createPopulatesTheSourceSecurityGroupFromTheFirstAttachedGroup() {
        SecurityGroup sourceGroup = new SecurityGroup();
        sourceGroup.setGroupId("sg-1");
        sourceGroup.setGroupName("elb-sg");
        sourceGroup.setOwnerId("333333333333");
        when(ec2Service.describeSecurityGroups(
                REGION, List.of("sg-1"), List.of(), Map.of())).thenReturn(List.of(sourceGroup));
        ClassicLoadBalancer created = create("my-elb");

        assertEquals("333333333333", created.getSourceSecurityGroupOwnerAlias());
        assertEquals("elb-sg", created.getSourceSecurityGroupName());
    }

    @Test
    void configureHealthCheckStoresAndRearmsTheChecker() {
        ClassicLoadBalancer lb = create("my-elb");
        ClassicHealthCheck hc = new ClassicHealthCheck();
        hc.setTarget("HTTP:8080/health");
        hc.setInterval(10);
        hc.setTimeout(3);
        hc.setHealthyThreshold(2);
        hc.setUnhealthyThreshold(2);

        service.configureHealthCheck(REGION, "my-elb", hc);

        assertEquals("HTTP:8080/health", lb.getHealthCheck().getTarget());
        verify(healthChecker).healthCheckChanged(lb);
    }

    @Test
    void configureHealthCheckEnforcesTheRangesTheModelDeclares() {
        create("my-elb");
        ClassicHealthCheck hc = new ClassicHealthCheck();
        hc.setTarget("HTTP:8080/");
        hc.setInterval(301);
        hc.setTimeout(3);
        hc.setHealthyThreshold(2);
        hc.setUnhealthyThreshold(2);

        AwsException e = assertThrows(AwsException.class,
                () -> service.configureHealthCheck(REGION, "my-elb", hc));
        assertEquals("ValidationError", e.getErrorCode());
    }

    @Test
    void registerAndDeregisterInstancesAreIdempotent() {
        ClassicLoadBalancer lb = create("my-elb");
        service.registerInstances(REGION, "my-elb", List.of("i-1"));
        assertEquals(List.of("i-1"), service.registerInstances(REGION, "my-elb", List.of("i-1")));
        assertEquals(List.of(), service.deregisterInstances(REGION, "my-elb", List.of("i-1")));
        verify(healthChecker).deregisterInstances(lb, List.of("i-1"));
    }

    @Test
    void describeInstanceHealthRejectsAnInstanceThatIsNotRegistered() {
        create("my-elb");
        AwsException e = assertThrows(AwsException.class,
                () -> service.describeInstanceHealth(REGION, "my-elb", List.of("i-unknown")));
        assertEquals("InvalidInstance", e.getErrorCode());
    }

    @Test
    void modifyAttributesLeavesUnsentMembersAtTheirDefaults() {
        create("my-elb");
        service.modifyLoadBalancerAttributes(REGION, "my-elb",
                new ElbClassicService.AttributeUpdate(true, null, null, null, null,
                        true, 300, null, Map.of()));

        ClassicLoadBalancer lb = service.requireLoadBalancer(REGION, "my-elb");
        assertTrue(lb.getAttributes().isCrossZoneLoadBalancingEnabled());
        assertTrue(lb.getAttributes().isConnectionDrainingEnabled());
        assertEquals(300, lb.getAttributes().getConnectionDrainingTimeout());
        assertEquals(60, lb.getAttributes().getIdleTimeout());
        assertNull(lb.getAttributes().getAccessLogS3BucketName());
    }

    @Test
    void deleteIsSilentForALoadBalancerThatDoesNotExist() {
        service.deleteLoadBalancer(REGION, "never-existed");
        assertThrows(AwsException.class, () -> service.requireLoadBalancer(REGION, "never-existed"));
    }

    @Test
    void addingAConflictingListenerOnAPortInUseIsRejected() {
        create("my-elb");
        ClassicListener conflicting = new ClassicListener();
        conflicting.setProtocol("TCP");
        conflicting.setLoadBalancerPort(80);
        conflicting.setInstancePort(9999);

        AwsException e = assertThrows(AwsException.class, () ->
                service.createLoadBalancerListeners(REGION, "my-elb", List.of(conflicting)));
        assertEquals("DuplicateListener", e.getErrorCode());
    }

    @Test
    void repeatingAnIdenticalListenerIsAccepted() {
        ClassicLoadBalancer lb = create("my-elb");
        service.createLoadBalancerListeners(REGION, "my-elb", List.of(httpListener()));
        assertEquals(1, lb.getListeners().size());
    }

    /** A balancer stored before the zone was looked up per region carries the old fixed zone. */
    @Test
    void aRestoredBalancerTakesTheHostedZoneOfItsRegion() {
        create("stored-elb").setCanonicalHostedZoneNameId("Z35SXDOTRQ7X7K");
        assertEquals("Z35SXDOTRQ7X7K",
                service.describeLoadBalancers(REGION, List.of("stored-elb")).getFirst().getCanonicalHostedZoneNameId());

        service.restorePersistedRuntime();

        assertEquals(AwsRegionFacts.classicElbHostedZoneId(REGION).orElseThrow(),
                service.describeLoadBalancers(REGION, List.of("stored-elb")).getFirst().getCanonicalHostedZoneNameId());
    }

    /**
     * Startup runs outside any request, where the storage-backed map sees only the default
     * account. Every other account's balancers have to come back too, as their own account's.
     */
    @Test
    void restoreBringsBackTheBalancersOfEveryAccount() {
        AccountAwareStorageBackend<Map<String, ClassicLoadBalancer>> storage =
                AccountAwareStorageBackend.inMemory(DEFAULT_ACCOUNT);
        StorageFactory factory = mock(StorageFactory.class);
        doReturn(storage).when(factory).create(eq("elb"), eq("elb-load-balancers.json"), any());
        ClassicLoadBalancer own = storedBalancer("web", List.of("i-own"));
        ClassicLoadBalancer foreign = storedBalancer("web", List.of("i-foreign"));
        // Read back from disk, a region map is whatever map the JSON reader built.
        storage.putForAccount(DEFAULT_ACCOUNT, REGION, new LinkedHashMap<>(Map.of("web", own)));
        storage.putForAccount(OTHER_ACCOUNT, REGION, new LinkedHashMap<>(Map.of("web", foreign)));

        service = new ElbClassicService(ec2Service, healthChecker, factory, null);
        service.initializeStorage();
        service.restorePersistedRuntime();

        verify(healthChecker).startMonitoring(own);
        verify(healthChecker).registerInstances(own, List.of("i-own"));
        verify(healthChecker).startMonitoring(foreign);
        verify(healthChecker).registerInstances(foreign, List.of("i-foreign"));
        String zone = AwsRegionFacts.classicElbHostedZoneId(REGION).orElseThrow();
        assertEquals(zone, own.getCanonicalHostedZoneNameId());
        assertEquals(zone, foreign.getCanonicalHostedZoneNameId());
        // A record stored before balancers carried their account takes it from where it is stored.
        assertEquals(DEFAULT_ACCOUNT, own.getAccountId());
        assertEquals(OTHER_ACCOUNT, foreign.getAccountId());
        assertInstanceOf(ConcurrentHashMap.class, storage.getForAccount(DEFAULT_ACCOUNT, REGION).orElseThrow());
        assertInstanceOf(ConcurrentHashMap.class, storage.getForAccount(OTHER_ACCOUNT, REGION).orElseThrow());
    }

    /**
     * The default account id is configurable and need not be 12 digits, while a scan of every
     * account recognizes only 12-digit prefixes. The default account's balancers must still keep
     * their keys and zone, and be restored once.
     */
    @Test
    void restoreKeepsTheKeysOfADefaultAccountWhoseIdIsNotTwelveDigits() {
        AccountAwareStorageBackend<Map<String, ClassicLoadBalancer>> storage =
                AccountAwareStorageBackend.inMemory("local");
        StorageFactory factory = mock(StorageFactory.class);
        doReturn(storage).when(factory).create(eq("elb"), eq("elb-load-balancers.json"), any());
        ClassicLoadBalancer own = storedBalancer("web", List.of("i-own"));
        ClassicLoadBalancer foreign = storedBalancer("web", List.of("i-foreign"));
        storage.putForAccount("local", REGION, new LinkedHashMap<>(Map.of("web", own)));
        storage.putForAccount(OTHER_ACCOUNT, REGION, new LinkedHashMap<>(Map.of("web", foreign)));

        service = new ElbClassicService(ec2Service, healthChecker, factory, null);
        service.initializeStorage();
        service.restorePersistedRuntime();

        assertEquals(Set.of(REGION), storage.keysForAccount("local"));
        assertEquals(Set.of(REGION), storage.keysForAccount(OTHER_ACCOUNT));
        verify(healthChecker, times(1)).startMonitoring(own);
        verify(healthChecker, times(1)).startMonitoring(foreign);
        assertEquals(AwsRegionFacts.classicElbHostedZoneId(REGION).orElseThrow(), own.getCanonicalHostedZoneNameId());
        assertEquals("local", own.getAccountId());
        assertInstanceOf(ConcurrentHashMap.class, storage.getForAccount("local", REGION).orElseThrow());
    }

    /**
     * The service is created on its first use, which can come inside another account's request, and
     * an account id need not be 12 digits: the default one is configurable, and AssumeRole takes the
     * account from the role ARN. Every region map still has to come back under its own key.
     */
    @Test
    void restoreReadsEachRegionMapUnderTheAccountOfItsKey() {
        RequestContext request = new RequestContext();
        request.setAccountId(OTHER_ACCOUNT);
        @SuppressWarnings("unchecked")
        Instance<RequestContext> requests = mock(Instance.class);
        // Lenient: the restore should not consult the request at all, but anything that does sees
        // another account's.
        lenient().when(requests.get()).thenReturn(request);
        AccountAwareStorageBackend<Map<String, ClassicLoadBalancer>> storage =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), requests, "local");
        StorageFactory factory = mock(StorageFactory.class);
        doReturn(storage).when(factory).create(eq("elb"), eq("elb-load-balancers.json"), any());
        Map<String, ClassicLoadBalancer> byAccount = new LinkedHashMap<>();
        for (String account : List.of("local", OTHER_ACCOUNT, "dev")) {
            ClassicLoadBalancer lb = storedBalancer("web", List.of("i-" + account));
            byAccount.put(account, lb);
            storage.putForAccount(account, REGION, new LinkedHashMap<>(Map.of("web", lb)));
        }

        service = new ElbClassicService(ec2Service, healthChecker, factory, null);
        service.initializeStorage();
        service.restorePersistedRuntime();

        String zone = AwsRegionFacts.classicElbHostedZoneId(REGION).orElseThrow();
        for (Map.Entry<String, ClassicLoadBalancer> entry : byAccount.entrySet()) {
            String account = entry.getKey();
            ClassicLoadBalancer lb = entry.getValue();
            assertEquals(Set.of(REGION), storage.keysForAccount(account), account);
            assertInstanceOf(ConcurrentHashMap.class, storage.getForAccount(account, REGION).orElseThrow(), account);
            assertSame(lb, storage.getForAccount(account, REGION).orElseThrow().get("web"), account);
            verify(healthChecker, times(1)).startMonitoring(lb);
            assertEquals(zone, lb.getCanonicalHostedZoneNameId(), account);
            assertEquals(account, lb.getAccountId());
        }
    }

    private static ClassicLoadBalancer storedBalancer(String name, List<String> instanceIds) {
        ClassicLoadBalancer lb = new ClassicLoadBalancer();
        lb.setLoadBalancerName(name);
        lb.setRegion(REGION);
        lb.setCanonicalHostedZoneNameId("Z35SXDOTRQ7X7K");
        lb.setInstanceIds(new ArrayList<>(instanceIds));
        return lb;
    }
}
