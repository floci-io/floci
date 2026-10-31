package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import io.github.hectorvent.floci.services.redshiftserverless.model.RedshiftServerlessSnapshot;
import io.github.hectorvent.floci.services.redshiftserverless.model.SnapshotCopyConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedshiftServerlessSnapshotCopyServiceTest {
    private static final String REGION = "us-east-1";
    private static final String DESTINATION = "us-west-2";
    private static final String ACCOUNT_ID = "123456789012";

    private static final String OTHER_ACCOUNT_ID = "999999999999";

    private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();
    private RedshiftServerlessService serverless;
    private RedshiftServerlessSnapshotCopyService copies;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        for (String file : List.of("namespaces", "workgroups", "snapshots", "snapshotcopyconfigurations")) {
            AccountAwareStorageBackend<?> store = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
            stores.put(file, store);
            when(storageFactory.create(eq("redshiftserverless"), eq("redshiftserverless-" + file + ".json"),
                    any(TypeReference.class))).thenReturn((AccountAwareStorageBackend) store);
        }
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        when(regionResolver.buildArn(eq("redshift-serverless"), any(String.class), any(String.class)))
                .thenAnswer(invocation -> "arn:aws:redshift-serverless:"
                        + invocation.getArgument(1, String.class) + ":" + ACCOUNT_ID + ":"
                        + invocation.getArgument(2, String.class));
        RedshiftServerlessEndpoints endpoints = mock(RedshiftServerlessEndpoints.class);
        when(endpoints.allocate()).thenReturn(new Endpoint("localhost", 7100));
        RedshiftServerlessRuntime runtime = mock(RedshiftServerlessRuntime.class);
        when(runtime.generatePassword()).thenReturn("Generated123");
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(System.getProperty("java.io.tmpdir"));
        serverless = new RedshiftServerlessService(storageFactory, regionResolver, endpoints, runtime, config);
        copies = new RedshiftServerlessSnapshotCopyService(storageFactory, regionResolver, serverless);
    }

    @Test
    void createSnapshotCopyConfigurationStampsCreatedAt() {
        serverless.createNamespace("copy-ns", "admin", "dev", null, null, List.of(), List.of(), Map.of(), REGION);
        Instant before = Instant.now();

        SnapshotCopyConfiguration configuration =
                copies.createSnapshotCopyConfiguration("copy-ns", DESTINATION, null, null, REGION);

        Instant after = Instant.now();
        assertNotNull(configuration.getCreatedAt());
        assertFalse(configuration.getCreatedAt().isBefore(before));
        assertFalse(configuration.getCreatedAt().isAfter(after));
    }

    @Test
    void configurationCopyConstructorCopiesCreatedAt() {
        SnapshotCopyConfiguration original = new SnapshotCopyConfiguration();
        Instant created = Instant.parse("2026-10-30T00:00:00Z");
        original.setCreatedAt(created);

        assertEquals(created, new SnapshotCopyConfiguration(original).getCreatedAt());
    }

    @Test
    void snapshotCopyConstructorCopiesTheCopyBookkeeping() {
        RedshiftServerlessSnapshot original = new RedshiftServerlessSnapshot();
        original.setCopiedFromSnapshotArn("arn:aws:redshift-serverless:us-east-1:123456789012:snapshot/s");
        original.setCopiedToRegions(new LinkedHashSet<>(Set.of(DESTINATION)));

        RedshiftServerlessSnapshot copy = new RedshiftServerlessSnapshot(original);
        copy.getCopiedToRegions().add("eu-west-1");

        assertEquals(original.getCopiedFromSnapshotArn(), copy.getCopiedFromSnapshotArn());
        assertEquals(Set.of(DESTINATION), original.getCopiedToRegions());
        assertNotSame(original.getCopiedToRegions(), copy.getCopiedToRegions());
    }

    @Test
    void activeRulesListsConfigurationsOfEveryAccount() {
        serverless.createNamespace("copy-ns", "admin", "dev", null, null, List.of(), List.of(), Map.of(), REGION);
        copies.createSnapshotCopyConfiguration("copy-ns", DESTINATION, "kms-1", 7, REGION);
        seedForeignConfiguration("other-ns", "us-west-1", "eu-west-1");

        List<RedshiftServerlessSnapshotCopyService.CopyRule> rules = copies.activeRules();

        assertEquals(2, rules.size());
        RedshiftServerlessSnapshotCopyService.CopyRule own = ruleOf(rules, ACCOUNT_ID);
        assertEquals(REGION, own.sourceRegion());
        assertEquals("copy-ns", own.namespaceName());
        assertEquals(DESTINATION, own.destinationRegion());
        assertEquals("kms-1", own.destinationKmsKeyId());
        assertEquals(7, own.retentionPeriod());
        assertNotNull(own.createdAt());
        RedshiftServerlessSnapshotCopyService.CopyRule foreign = ruleOf(rules, OTHER_ACCOUNT_ID);
        assertEquals("us-west-1", foreign.sourceRegion());
        assertEquals("other-ns", foreign.namespaceName());
        assertEquals("eu-west-1", foreign.destinationRegion());
    }

    @Test
    void activeRulesDropsTheRuleOfARecreatedNamespace() {
        Namespace namespace = serverless.createNamespace("copy-ns", "admin", "dev", null, null, List.of(),
                List.of(), Map.of(), REGION);
        copies.createSnapshotCopyConfiguration("copy-ns", DESTINATION, null, null, REGION);
        Namespace recreated = new Namespace(namespace);
        recreated.setNamespaceId("a-different-namespace-id");
        namespaceStore().put(REGION + "::copy-ns", recreated);

        assertTrue(copies.activeRules().isEmpty());
    }

    @Test
    void activeRulesCarryTheIdOfTheirNamespace() {
        Namespace namespace = serverless.createNamespace("copy-ns", "admin", "dev", null, null, List.of(),
                List.of(), Map.of(), REGION);
        copies.createSnapshotCopyConfiguration("copy-ns", DESTINATION, null, null, REGION);

        assertEquals(namespace.getNamespaceId(), copies.activeRules().get(0).namespaceId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void activeRulesBackfillTheCreationTimeOfAConfigurationThatHasNone() {
        serverless.createNamespace("copy-ns", "admin", "dev", null, null, List.of(), List.of(), Map.of(), REGION);
        SnapshotCopyConfiguration created =
                copies.createSnapshotCopyConfiguration("copy-ns", DESTINATION, null, null, REGION);
        SnapshotCopyConfiguration older = new SnapshotCopyConfiguration(created);
        older.setCreatedAt(null);
        ((AccountAwareStorageBackend<SnapshotCopyConfiguration>) stores.get("snapshotcopyconfigurations"))
                .put(REGION + "::" + created.getSnapshotCopyConfigurationId(), older);
        Instant before = Instant.now();

        Instant first = copies.activeRules().get(0).createdAt();

        assertNotNull(first);
        assertFalse(first.isBefore(before));
        assertEquals(first, copies.activeRules().get(0).createdAt());
    }

    @Test
    void activeRulesIgnoresDeletedConfigurations() {
        serverless.createNamespace("copy-ns", "admin", "dev", null, null, List.of(), List.of(), Map.of(), REGION);
        SnapshotCopyConfiguration configuration =
                copies.createSnapshotCopyConfiguration("copy-ns", DESTINATION, null, null, REGION);
        copies.deleteSnapshotCopyConfiguration(configuration.getSnapshotCopyConfigurationId(), REGION);

        assertTrue(copies.activeRules().isEmpty());
    }

    @Test
    void rejectsAnUnknownDestinationRegion() {
        serverless.createNamespace("copy-ns", "admin", "dev", null, null, List.of(), List.of(), Map.of(), REGION);

        AwsException failure = assertThrows(AwsException.class,
                () -> copies.createSnapshotCopyConfiguration("copy-ns", "mars-north-1", null, null, REGION));

        assertTrue(failure.getMessage().contains("destinationRegion"), failure.getMessage());
        assertTrue(copies.activeRules().isEmpty());
    }

    @Test
    void rejectsADestinationInAnotherPartition() {
        serverless.createNamespace("copy-ns", "admin", "dev", null, null, List.of(), List.of(), Map.of(), REGION);

        AwsException failure = assertThrows(AwsException.class,
                () -> copies.createSnapshotCopyConfiguration("copy-ns", "cn-north-1", null, null, REGION));

        assertTrue(failure.getMessage().contains("partition"), failure.getMessage());
        assertTrue(copies.activeRules().isEmpty());
    }

    @SuppressWarnings("unchecked")
    private AccountAwareStorageBackend<Namespace> namespaceStore() {
        return (AccountAwareStorageBackend<Namespace>) stores.get("namespaces");
    }

    @SuppressWarnings("unchecked")
    private void seedForeignConfiguration(String namespaceName, String region, String destination) {
        Namespace namespace = new Namespace();
        namespace.setNamespaceName(namespaceName);
        namespace.setNamespaceId("foreign-namespace-id");
        namespaceStore().putForAccount(OTHER_ACCOUNT_ID, region + "::" + namespaceName, namespace);
        SnapshotCopyConfiguration configuration = new SnapshotCopyConfiguration();
        configuration.setSnapshotCopyConfigurationId("foreign-configuration");
        configuration.setNamespaceName(namespaceName);
        configuration.setOwnerId("foreign-namespace-id");
        configuration.setDestinationRegion(destination);
        configuration.setCreatedAt(Instant.parse("2026-10-30T00:00:00Z"));
        ((AccountAwareStorageBackend<SnapshotCopyConfiguration>) stores.get("snapshotcopyconfigurations"))
                .putForAccount(OTHER_ACCOUNT_ID, region + "::foreign-configuration", configuration);
    }

    private static RedshiftServerlessSnapshotCopyService.CopyRule ruleOf(
            List<RedshiftServerlessSnapshotCopyService.CopyRule> rules, String accountId) {
        return rules.stream().filter(rule -> rule.accountId().equals(accountId)).findFirst().orElseThrow();
    }

    @Test
    void snapshotNeverExposesANullCopiedToRegions() {
        RedshiftServerlessSnapshot snapshot = new RedshiftServerlessSnapshot();
        assertTrue(snapshot.getCopiedToRegions().isEmpty());
        snapshot.setCopiedToRegions(null);
        assertTrue(snapshot.getCopiedToRegions().isEmpty());
    }
}
