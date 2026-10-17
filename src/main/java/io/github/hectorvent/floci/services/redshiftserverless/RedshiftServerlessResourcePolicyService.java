package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshiftserverless.model.RedshiftServerlessSnapshot;
import io.github.hectorvent.floci.services.redshiftserverless.model.ResourcePolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resource policies for Redshift Serverless snapshots. A policy is stored and echoed, not
 * evaluated: no principal named in it gains access.
 *
 * <p>A policy belongs to a snapshot but lives in its own store, so deleting the snapshot needs no
 * hook here. Each read checks that the snapshot is still there and drops the policy otherwise.
 */
@ApplicationScoped
public class RedshiftServerlessResourcePolicyService implements Resettable {

    private final AccountAwareStorageBackend<ResourcePolicy> policies;
    private final RedshiftServerlessService serverless;
    private final ObjectReader strictPolicyReader;

    @Inject
    public RedshiftServerlessResourcePolicyService(StorageFactory storageFactory,
                                                   RedshiftServerlessService serverless,
                                                   ObjectMapper objectMapper) {
        this.policies = storageFactory.create("redshiftserverless", "redshiftserverless-resourcepolicies.json",
                new TypeReference<Map<String, ResourcePolicy>>() {});
        this.serverless = serverless;
        this.strictPolicyReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** Replaces the policy on the snapshot {@code resourceArn} names. */
    public synchronized ResourcePolicy putResourcePolicy(String resourceArn, String policy, String region) {
        requireJsonObject(policy);
        RedshiftServerlessSnapshot snapshot = findSnapshot(resourceArn, region)
                .orElseThrow(() -> ServerlessSupport.notFound("The resource " + resourceArn + " was not found."));
        ResourcePolicy stored = new ResourcePolicy(resourceArn, policy);
        stored.setSnapshotCreateTime(snapshot.getSnapshotCreateTime());
        policies.put(storageKey(region, resourceArn), stored);
        return new ResourcePolicy(resourceArn, policy);
    }

    public synchronized ResourcePolicy getResourcePolicy(String resourceArn, String region) {
        ResourcePolicy stored = require(resourceArn, region);
        return new ResourcePolicy(stored.getResourceArn(), stored.getPolicy());
    }

    public synchronized void deleteResourcePolicy(String resourceArn, String region) {
        require(resourceArn, region);
        policies.delete(storageKey(region, resourceArn));
    }

    @Override
    public void clear() {
        policies.clear();
    }

    private ResourcePolicy require(String resourceArn, String region) {
        serverless.snapshotNameFromArn(resourceArn, region, "resourceArn");
        ResourcePolicy stored = policies.get(storageKey(region, resourceArn)).orElse(null);
        if (stored != null && !isOnSameSnapshot(stored, region)) {
            policies.delete(storageKey(region, resourceArn));
            stored = null;
        }
        if (stored == null) {
            throw ServerlessSupport.notFound("The resource " + resourceArn + " has no resource policy.");
        }
        return stored;
    }

    /**
     * A snapshot ARN carries only the snapshot's name, so a snapshot recreated under the same name
     * is told apart by its creation time: a policy left by its predecessor is not this snapshot's.
     */
    private boolean isOnSameSnapshot(ResourcePolicy stored, String region) {
        return findSnapshot(stored.getResourceArn(), region)
                .map(snapshot -> Objects.equals(snapshot.getSnapshotCreateTime(), stored.getSnapshotCreateTime()))
                .orElse(false);
    }

    private Optional<RedshiftServerlessSnapshot> findSnapshot(String resourceArn, String region) {
        return serverless.findSnapshot(serverless.snapshotNameFromArn(resourceArn, region, "resourceArn"), region);
    }

    private void requireJsonObject(String policy) {
        try {
            JsonNode parsed = strictPolicyReader.readTree(policy);
            if (parsed == null || !parsed.isObject()) {
                throw ServerlessSupport.validation("policy must be a JSON policy document.");
            }
        } catch (IOException e) {
            throw ServerlessSupport.validation("policy must be a JSON policy document.");
        }
    }

    private static String storageKey(String region, String resourceArn) {
        return region + "::" + resourceArn;
    }
}
