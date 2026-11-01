package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshiftserverless.model.RedshiftServerlessSnapshot;
import io.github.hectorvent.floci.services.redshiftserverless.model.ResourcePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedshiftServerlessResourcePolicyExpirationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings({"unchecked", "rawtypes"})
    void expirationRemovesLegacySnapshotAndPolicyWithoutAnApiReadFirst(boolean scopedSnapshot) {
        String account = "123456789012";
        String region = "us-east-1";
        String arn = "arn:aws:redshift-serverless:us-east-1:123456789012:snapshot/legacy-snap";
        InMemoryStorage<String, RedshiftServerlessSnapshot> rawSnapshots = new InMemoryStorage<>();
        InMemoryStorage<String, ResourcePolicy> rawPolicies = new InMemoryStorage<>();
        AccountAwareStorageBackend<RedshiftServerlessSnapshot> snapshots =
                new AccountAwareStorageBackend<>(rawSnapshots, null, account);
        AccountAwareStorageBackend<ResourcePolicy> policies = new AccountAwareStorageBackend<>(rawPolicies, null, account);
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(eq("redshiftserverless"), eq("redshiftserverless-snapshots.json"),
                any(TypeReference.class))).thenReturn((AccountAwareStorageBackend) snapshots);
        when(factory.create(eq("redshiftserverless"), eq("redshiftserverless-resourcepolicies.json"),
                any(TypeReference.class))).thenReturn((AccountAwareStorageBackend) policies);
        RedshiftServerlessService serverless = new RedshiftServerlessService(factory,
                new RegionResolver(region, account), mock(RedshiftServerlessEndpoints.class),
                mock(RedshiftServerlessRuntime.class), mock(EmulatorConfig.class));
        RedshiftServerlessResourcePolicyService service = new RedshiftServerlessResourcePolicyService(
                factory, serverless, new ObjectMapper());
        RedshiftServerlessSnapshot snapshot = new RedshiftServerlessSnapshot();
        snapshot.setSnapshotName("legacy-snap");
        snapshot.setSnapshotArn(arn);
        snapshot.setSnapshotCreateTime(Instant.EPOCH);
        snapshot.setRetentionPeriod(1);
        ResourcePolicy policy = new ResourcePolicy(arn, "{}");
        policy.setSnapshotCreateTime(Instant.EPOCH);
        rawSnapshots.put((scopedSnapshot ? account + "/" : "") + region + "::legacy-snap", snapshot);
        rawPolicies.put(region + "::" + arn, policy);

        serverless.expireSnapshots(Instant.EPOCH.plusSeconds(86400), service::deleteExpiredSnapshotPolicy);
        assertTrue(rawSnapshots.keys().isEmpty());
        assertTrue(rawPolicies.keys().isEmpty());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void expirationRemovesOnlyThePolicyOfThatAccountAndSnapshotGeneration() {
        String account = "123456789012";
        String otherAccount = "999999999999";
        String region = "us-east-1";
        String arn = "arn:aws:redshift-serverless:us-east-1:123456789012:snapshot/expiry-snap";
        String key = region + "::" + arn;
        Instant created = Instant.parse("2030-01-01T00:00:00Z");
        AccountAwareStorageBackend<ResourcePolicy> store = AccountAwareStorageBackend.inMemory(account);
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(eq("redshiftserverless"), eq("redshiftserverless-resourcepolicies.json"),
                any(TypeReference.class))).thenReturn((AccountAwareStorageBackend) store);
        RedshiftServerlessResourcePolicyService service = new RedshiftServerlessResourcePolicyService(
                factory, mock(RedshiftServerlessService.class), new ObjectMapper());
        RedshiftServerlessSnapshot snapshot = new RedshiftServerlessSnapshot();
        snapshot.setSnapshotArn(arn);
        snapshot.setSnapshotCreateTime(created);
        ResourcePolicy policy = new ResourcePolicy(arn, "{}");
        policy.setSnapshotCreateTime(created);
        store.putForAccount(account, key, policy);
        store.putForAccount(otherAccount, key, policy);
        AccountAwareStorageBackend.AccountEntry<RedshiftServerlessSnapshot> entry =
                new AccountAwareStorageBackend.AccountEntry<>(account, region + "::expiry-snap", snapshot);

        service.deleteExpiredSnapshotPolicy(entry);
        service.deleteExpiredSnapshotPolicy(entry);
        assertTrue(store.getForAccount(account, key).isEmpty());
        assertTrue(store.getForAccount(otherAccount, key).isPresent());

        ResourcePolicy replacement = new ResourcePolicy(arn, "{}");
        replacement.setSnapshotCreateTime(created.plusSeconds(1));
        store.putForAccount(account, key, replacement);
        service.deleteExpiredSnapshotPolicy(entry);
        assertTrue(store.getForAccount(account, key).isPresent());
    }
}
