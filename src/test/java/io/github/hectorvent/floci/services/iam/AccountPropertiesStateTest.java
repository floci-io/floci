package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.iam.model.OutboundWebIdentityFederation;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the wire tests cannot reach for the account-level singletons: that they survive a restart,
 * that one account cannot see another's, and that two racing enables cannot both succeed.
 */
class AccountPropertiesStateTest {

    private static final String ACCOUNT_A = "000000000000";
    private static final String ACCOUNT_B = "111111111111";

    @SuppressWarnings("unchecked")
    private static Instance<RequestContext> requestContextFor(AtomicReference<String> accountId) {
        RequestContext rc = mock(RequestContext.class);
        when(rc.getAccountId()).thenAnswer(invocation -> accountId.get());
        Instance<RequestContext> inst = mock(Instance.class);
        when(inst.get()).thenReturn(rc);
        return inst;
    }

    private static IamService newService(
            StorageBackend<String, String> accountProperties,
            StorageBackend<String, String> stsPreferences,
            StorageBackend<String, OutboundWebIdentityFederation> federation,
            String defaultAccount) {
        return new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                accountProperties, stsPreferences, federation,
                new RegionResolver("us-east-1", defaultAccount), false, null);
    }

    private static IamService inMemory(String defaultAccount) {
        return newService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), defaultAccount);
    }

    /**
     * All three singletons survive a restart. The issuer matters most: a relying party has pinned
     * it, so losing it on restart would break verification with no way to notice from the API.
     */
    @Test
    void thePropertiesTheTokenVersionAndTheIssuerSurviveARestart(@TempDir Path dir) {
        Path propertiesFile = dir.resolve("iam-account-properties.json");
        Path stsFile = dir.resolve("iam-sts-preferences.json");
        Path federationFile = dir.resolve("iam-outbound-federation.json");

        PersistentStorage<String, String> properties =
                new PersistentStorage<>(propertiesFile, new TypeReference<>() {});
        PersistentStorage<String, String> sts =
                new PersistentStorage<>(stsFile, new TypeReference<>() {});
        PersistentStorage<String, OutboundWebIdentityFederation> federation =
                new PersistentStorage<>(federationFile, new TypeReference<>() {});
        properties.load();
        sts.load();
        federation.load();

        IamService before = newService(properties, sts, federation, ACCOUNT_A);
        before.putAccountProperties(Map.of("RoleManager/Enabled", "true"));
        before.setSecurityTokenServicePreferences("v2Token");
        String issuer = before.enableOutboundWebIdentityFederation();

        PersistentStorage<String, String> properties2 =
                new PersistentStorage<>(propertiesFile, new TypeReference<>() {});
        PersistentStorage<String, String> sts2 =
                new PersistentStorage<>(stsFile, new TypeReference<>() {});
        PersistentStorage<String, OutboundWebIdentityFederation> federation2 =
                new PersistentStorage<>(federationFile, new TypeReference<>() {});
        properties2.load();
        sts2.load();
        federation2.load();
        IamService after = newService(properties2, sts2, federation2, ACCOUNT_A);

        assertEquals(Map.of("RoleManager/Enabled", "true"), after.getAccountProperties());
        assertEquals("v2Token", after.getGlobalEndpointTokenVersion());
        assertEquals(issuer,
                after.getOutboundWebIdentityFederationInfo().getIssuerIdentifier());
        assertTrue(after.getOutboundWebIdentityFederationInfo().isEnabled());
    }

    @Test
    void oneAccountCannotSeeAnothersProperties() {
        AtomicReference<String> account = new AtomicReference<>(ACCOUNT_A);
        Instance<RequestContext> context = requestContextFor(account);
        AccountAwareStorageBackend<String> properties = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), context, ACCOUNT_A);
        AccountAwareStorageBackend<String> sts = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), context, ACCOUNT_A);
        AccountAwareStorageBackend<OutboundWebIdentityFederation> federation =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), context, ACCOUNT_A);

        IamService inA = newService(properties, sts, federation, ACCOUNT_A);
        IamService inB = newService(properties, sts, federation, ACCOUNT_B);

        inA.putAccountProperties(Map.of("RoleManager/Mode", "a"));
        inA.setSecurityTokenServicePreferences("v2Token");
        String issuerA = inA.enableOutboundWebIdentityFederation();

        account.set(ACCOUNT_B);
        assertTrue(inB.getAccountProperties().isEmpty(), "B must not see A's properties");
        assertEquals("v1Token", inB.getGlobalEndpointTokenVersion(),
                "B falls back to the default rather than reading A's preference");
        assertThrows(AwsException.class, inB::getOutboundWebIdentityFederationInfo,
                "B's federation is off, whatever A did");

        // And B enabling mints its own issuer rather than inheriting A's.
        String issuerB = inB.enableOutboundWebIdentityFederation();
        assertNotEquals(issuerA, issuerB);

        account.set(ACCOUNT_A);
        assertEquals(Map.of("RoleManager/Mode", "a"), inA.getAccountProperties());
        assertEquals(issuerA,
                inA.getOutboundWebIdentityFederationInfo().getIssuerIdentifier());
    }

    /**
     * Enabling is a check-then-act on one switch, so two racing enables must not both mint an
     * issuer and both report success. Asserted by counting the winners, because an invariant
     * phrased as "the stored issuer is one of the two" passes with the lock removed too.
     */
    @Test
    void racingEnablesProduceOneWinner() throws Exception {
        for (int trial = 0; trial < 60; trial++) {
            IamService service = inMemory(ACCOUNT_A);
            int attempts = 8;
            ExecutorService pool = Executors.newFixedThreadPool(attempts);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger enabled = new AtomicInteger();
            try {
                for (int i = 0; i < attempts; i++) {
                    pool.submit(() -> {
                        start.await();
                        try {
                            service.enableOutboundWebIdentityFederation();
                            enabled.incrementAndGet();
                        } catch (AwsException expected) {
                            // FeatureEnabled for the losers, which is the point.
                        }
                        return null;
                    });
                }
                start.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "trial did not finish");
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, enabled.get(),
                    "trial " + trial + ": exactly one enable should have succeeded");
        }
    }

    /** The issuer is minted once: a disable and re-enable returns the same URL. */
    @Test
    void theIssuerIsStableAcrossADisableAndReEnable() {
        IamService service = inMemory(ACCOUNT_A);
        String first = service.enableOutboundWebIdentityFederation();
        service.disableOutboundWebIdentityFederation();
        assertThrows(AwsException.class, service::getOutboundWebIdentityFederationInfo);
        assertEquals(first, service.enableOutboundWebIdentityFederation());
    }

    private static IamService inRegion(String region) {
        return new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(region, ACCOUNT_A), false, null);
    }

    /**
     * The issuer host comes from the request's partition rather than the commercial literal the
     * API Reference's example happens to show. This is the test the mistake would survive
     * otherwise: hardcoding {@code api.aws} passes everything else.
     */
    @Test
    void theIssuerHostFollowsThePartition() {
        String commercial = inRegion("us-east-1").enableOutboundWebIdentityFederation();
        String chinese = inRegion("cn-north-1").enableOutboundWebIdentityFederation();

        assertTrue(commercial.endsWith(".tokens.sts.global.api.aws"), commercial);
        assertTrue(chinese.startsWith("https://"), chinese);
        assertNotEquals(commercial.substring(commercial.indexOf(".tokens")),
                chinese.substring(chinese.indexOf(".tokens")),
                "the suffix must differ between partitions, but China gave " + chinese);
    }

    /** A request mixing namespaces is rejected whole, so nothing from it is written. */
    @Test
    void aMixedNamespaceRequestWritesNothing() {
        IamService service = inMemory(ACCOUNT_A);
        service.putAccountProperties(Map.of("RoleManager/Kept", "yes"));

        Map<String, String> mixed = new LinkedHashMap<>();
        mixed.put("RoleManager/A", "1");
        mixed.put("Other/B", "2");
        assertThrows(AwsException.class, () -> service.putAccountProperties(mixed));

        assertEquals(Map.of("RoleManager/Kept", "yes"), service.getAccountProperties(),
                "neither key of the rejected request may have landed");
    }
}
