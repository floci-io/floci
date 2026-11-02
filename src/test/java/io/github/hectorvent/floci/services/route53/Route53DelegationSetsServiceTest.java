package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.route53.model.DelegationSet;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import io.github.hectorvent.floci.testing.StorageFactories;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Route53DelegationSetsServiceTest {
    private static final String ACCOUNT = "741686000020";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void setsAndZoneLinksSurviveStorageReloadAndConfigurationChanges(String mode) {
        DelegationSet set;
        String zoneId;
        try (Fixture fixture = fixture(mode, "original")) {
            HostedZone zone = fixture.service().createHostedZone("saved.example.", "source", null, null).zone();
            zoneId = zone.getId();
            set = fixture.service().createReusableDelegationSet("saved", "/hostedzone/" + zoneId);
        }
        try (Fixture reloaded = fixture(mode, "changed")) {
            Route53Service service = reloaded.service();
            assertEquals(set, service.getReusableDelegationSet("/delegationset/" + set.id()));
            assertEquals(set, service.getDelegationSet(service.getHostedZone(zoneId)));
            assertError("DelegationSetInUse", () -> service.deleteReusableDelegationSet(set.id()));
            assertError("DelegationSetAlreadyCreated", () -> service.createReusableDelegationSet("saved", null));
            HostedZone next = service.createHostedZone("next.example.", "next", null, null, set.id()).zone();
            assertEquals(set, service.getDelegationSet(next));
            assertTrue(service.listResourceRecordSets(next.getId(), null, null, 100).stream()
                    .filter(record -> "SOA".equals(record.getType())).findFirst().orElseThrow()
                    .getRecords().getFirst().getValue().startsWith(set.nameServers().getFirst() + ". "));
            service.deleteHostedZone(next.getId());
            service.deleteHostedZone(zoneId);
            service.deleteReusableDelegationSet(set.id());
        }
        try (Fixture reloaded = fixture(mode, "changed")) {
            assertEquals(0, reloaded.service().getReusableDelegationSetCount());
            assertEquals(0, reloaded.service().getHostedZoneCount());
        }
    }

    @Test
    void aForeignReadCannotClaimAnOwnerlessLegacyZoneBeforePromotion() {
        RequestContext context = new RequestContext();
        context.setAccountId("741686000099");
        @SuppressWarnings("unchecked")
        Instance<RequestContext> instance = mock(Instance.class);
        when(instance.get()).thenReturn(context);
        InMemoryStorage<String, HostedZone> legacy = new InMemoryStorage<>();
        legacy.put("ZLEGACY", new HostedZone("ZLEGACY", "legacy.example.", "legacy", null, false));
        AccountAwareStorageBackend<HostedZone> zones = new AccountAwareStorageBackend<>(legacy, instance, ACCOUNT);
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                "route53-zones.json".equals(invocation.getArgument(1)) ? zones : AccountAwareStorageBackend.inMemory(ACCOUNT));
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenAnswer(ignored -> context.getAccountId());
        Route53Service service = new Route53Service(factory, config("original"), resolver, mock(Ec2Service.class));
        AwsException read = assertThrows(AwsException.class, () -> service.getHostedZone("ZLEGACY"));
        assertEquals("NoSuchHostedZone", read.getErrorCode());
        assertEquals(404, read.getHttpStatus());
        assertError("HostedZoneNotFound", () -> service.createReusableDelegationSet("foreign", "ZLEGACY"));
        context.setAccountId(ACCOUNT);
        service.createReusableDelegationSet("owner", "ZLEGACY");
        assertEquals(1, service.getReusableDelegationSetCount());
    }

    @Test
    void aForeignCallerCannotPromoteAnUnscopedLegacyZone() throws Exception {
        seedLegacyZone();
        try (Fixture fixture = fixture("persistent", "original", "741686000099")) {
            assertError("HostedZoneNotFound", () -> fixture.service().createReusableDelegationSet("foreign", "ZLEGACY"));
            assertEquals(0, fixture.service().getReusableDelegationSetCount());
        }
    }

    @Test
    void legacyPublicZonesParticipateInConflictsBeforeTheirFirstRead() throws Exception {
        seedLegacyZone();
        try (Fixture fixture = fixture("persistent", "original")) {
            DelegationSet set = fixture.service().createReusableDelegationSet("set", null);
            assertError("ConflictingDomainExists", () -> fixture.service()
                    .createHostedZone("child.legacy.example.", "child", null, null, set.id()));
        }
    }

    @Test
    void promotionAfterConfigurationChangePreservesTheOriginalZoneAssignment() {
        String zoneId;
        try (Fixture fixture = fixture("persistent", "original")) {
            zoneId = fixture.service().createHostedZone("original.example.", "original", null, null).zone().getId();
            ResourceRecordSet ns = fixture.service().listResourceRecordSets(zoneId, null, null, 100).stream()
                    .filter(record -> "NS".equals(record.getType())).findFirst().orElseThrow();
            ns.setRecords(List.of(new ResourceRecord("custom.nameserver.test.")));
            fixture.service().changeResourceRecordSets(zoneId, List.of(Map.of("action", "UPSERT", "rrs", ns)), null);
        }
        try (Fixture fixture = fixture("persistent", "changed")) {
            DelegationSet set = fixture.service().createReusableDelegationSet("promoted", zoneId);
            assertEquals(List.of("original-1.test", "original-2.test", "original-3.test", "original-4.test"),
                    set.nameServers());
        }
    }

    @Test
    void promotionRecoversAnOlderZonesNameserversFromItsPersistedApexRecords() throws Exception {
        seedLegacyZone();
        try (Fixture fixture = fixture("persistent", "changed")) {
            DelegationSet set = fixture.service().createReusableDelegationSet("promoted", "ZLEGACY");
            assertEquals(List.of("original-1.test", "original-2.test", "original-3.test", "original-4.test"),
                    set.nameServers());
        }
    }

    @Test
    void setQuotaAndMaximumPageSizeAreEnforcedAndDeletionFreesCapacity() {
        try (Fixture fixture = fixture("memory", "quota")) {
            Route53Service service = fixture.service();
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                ids.add(service.createReusableDelegationSet("ref-" + i, null).id());
            }
            assertError("LimitsExceeded", () -> service.createReusableDelegationSet("overflow", null));
            assertEquals(100, service.getReusableDelegationSetCount());
            assertEquals(100, service.pageReusableDelegationSets(null, "2147483648").items().size());
            String first = ids.stream().sorted().findFirst().orElseThrow();
            service.deleteReusableDelegationSet(first);
            assertEquals(99, service.pageReusableDelegationSets(first, "100").items().size());
            service.createReusableDelegationSet("replacement", null);
            assertEquals(100, service.getReusableDelegationSetCount());
        }
    }

    @Test
    void hostedZoneQuotaCountsOnlyZonesAssociatedWithThatSet() {
        try (Fixture fixture = fixture("memory", "quota")) {
            Route53Service service = fixture.service();
            DelegationSet set = service.createReusableDelegationSet("set", null);
            String first = null;
            for (int i = 0; i < 100; i++) {
                HostedZone zone = service.createHostedZone("zone-" + i + ".example.", "zone-" + i,
                        null, null, set.id()).zone();
                if (first == null) {
                    first = zone.getId();
                }
            }
            assertError("TooManyHostedZones", () ->
                    service.createHostedZone("overflow.example.", "overflow", null, null, set.id()));
            DelegationSet other = service.createReusableDelegationSet("other", null);
            service.createHostedZone("other.example.", "other", null, null, other.id());
            service.deleteHostedZone(first);
            service.createHostedZone("replacement.example.", "replacement", null, null, set.id());
            assertEquals(101, service.getHostedZoneCount());
        }
    }

    @Test
    void concurrentDuplicateReferencesCreateExactlyOneSet() throws Exception {
        try (Fixture fixture = fixture("memory", "concurrent");
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Route53Service service = fixture.service();
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                calls.add(() -> {
                    start.await();
                    return outcome(() -> service.createReusableDelegationSet("shared", null));
                });
            }
            List<Future<String>> futures = calls.stream().map(executor::submit).toList();
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            assertEquals(1, results.stream().filter("success"::equals).count());
            assertEquals(15, results.stream().filter("DelegationSetAlreadyCreated"::equals).count());
            assertEquals(1, service.getReusableDelegationSetCount());
        }
    }

    @Test
    void racingDeleteAndZoneCreationCannotLeaveADanglingSetReference() throws Exception {
        try (Fixture fixture = fixture("memory", "concurrent");
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Route53Service service = fixture.service();
            DelegationSet set = service.createReusableDelegationSet("racing", null);
            CountDownLatch start = new CountDownLatch(1);
            Future<String> create = executor.submit(() -> {
                start.await();
                return outcome(() -> service.createHostedZone("racing.example.", "racing", null, null, set.id()));
            });
            Future<String> delete = executor.submit(() -> {
                start.await();
                return outcome(() -> service.deleteReusableDelegationSet(set.id()));
            });
            start.countDown();
            String createResult = create.get(10, TimeUnit.SECONDS);
            String deleteResult = delete.get(10, TimeUnit.SECONDS);
            if ("success".equals(createResult)) {
                assertEquals("DelegationSetInUse", deleteResult);
                assertEquals(set, service.getReusableDelegationSet(set.id()));
                assertEquals(1, service.getHostedZoneCount());
            } else {
                assertEquals("NoSuchDelegationSet", createResult);
                assertEquals("success", deleteResult);
                assertEquals(0, service.getHostedZoneCount());
                assertEquals(0, service.getReusableDelegationSetCount());
            }
        }
    }

    private String outcome(Runnable action) {
        try {
            action.run();
            return "success";
        } catch (AwsException e) {
            return e.getErrorCode();
        }
    }

    private void assertError(String code, Runnable action) {
        AwsException error = assertThrows(AwsException.class, action::run);
        assertEquals(code, error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    private void seedLegacyZone() throws Exception {
        Files.writeString(directory.resolve("route53-zones.json"), """
                {"ZLEGACY":{"id":"ZLEGACY","name":"legacy.example.","callerReference":"legacy",
                "ownerAccountId":"741686000020","privateZone":false}}
                """);
        Files.writeString(directory.resolve("route53-records.json"), """
                {"ZLEGACY":[{"name":"legacy.example.","type":"NS","ttl":172800,"records":[
                {"value":"original-1.test."},{"value":"original-2.test."},
                {"value":"original-3.test."},{"value":"original-4.test."}]}]}
                """);
    }

    private Fixture fixture(String mode, String prefix) {
        return fixture(mode, prefix, ACCOUNT);
    }

    private Fixture fixture(String mode, String prefix, String callerAccount) {
        EmulatorConfig config = config(prefix);
        StorageFactory factory = StorageFactories.open(config, mode);
        return new Fixture(factory, new Route53Service(factory, config, new RegionResolver("us-east-1", callerAccount),
                mock(Ec2Service.class)));
    }

    private EmulatorConfig config(String prefix) {
        EmulatorConfig config = StorageFactories.config(directory, ACCOUNT);
        when(config.services().route53().defaultNameserver1()).thenReturn(prefix + "-1.test.");
        when(config.services().route53().defaultNameserver2()).thenReturn(prefix + "-2.test.");
        when(config.services().route53().defaultNameserver3()).thenReturn(prefix + "-3.test.");
        when(config.services().route53().defaultNameserver4()).thenReturn(prefix + "-4.test.");
        return config;
    }

    private record Fixture(StorageFactory factory, Route53Service service) implements AutoCloseable {
        @Override
        public void close() {
            factory.shutdownAll();
        }
    }
}
