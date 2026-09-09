package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.WorkloadIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Workload identity registry for the AgentCore control plane. Exposed via the
 * RPC-style {@code /identities/*} operations and used to back each runtime's
 * associated workload identity.
 */
@ApplicationScoped
public class BedrockAgentCoreIdentityService {

    private static final String ARN_SERVICE = "bedrock-agentcore";
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{3,255}");
    private static final int MAX_PAGE = 20;

    private final StorageBackend<String, WorkloadIdentity> storage;
    private final RegionResolver regionResolver;

    @Inject
    public BedrockAgentCoreIdentityService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("bedrockagentcore", "bedrock-agentcore-identities.json",
                new TypeReference<Map<String, WorkloadIdentity>>() {}), regionResolver);
    }

    BedrockAgentCoreIdentityService(StorageBackend<String, WorkloadIdentity> storage, RegionResolver regionResolver) {
        this.storage = storage;
        this.regionResolver = regionResolver;
    }

    public WorkloadIdentity create(String name, List<String> allowedUrls, String region) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("ValidationException",
                    "name must match [A-Za-z0-9_.-]{3,255}", 400);
        }
        if (storage.get(key(region, name)).isPresent()) {
            throw new AwsException("ConflictException", "Workload identity already exists: " + name, 409);
        }
        Instant now = Instant.now();
        WorkloadIdentity identity = new WorkloadIdentity();
        identity.setName(name);
        identity.setWorkloadIdentityArn(regionResolver.buildArn(ARN_SERVICE, region,
                "workload-identity-directory/default/workload-identity/" + name));
        if (allowedUrls != null) {
            identity.setAllowedResourceOauth2ReturnUrls(allowedUrls);
        }
        identity.setCreatedTime(now);
        identity.setLastUpdatedTime(now);
        identity.setAccountId(regionResolver.getAccountId());
        storage.put(key(region, name), identity);
        return identity;
    }

    /** Auto-create used when a runtime is created; ignores conflicts by suffixing. */
    public WorkloadIdentity createForRuntime(String baseName, String region) {
        return create(baseName, null, region);
    }

    public WorkloadIdentity get(String name, String region) {
        return storage.get(key(region, name))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Workload identity not found: " + name, 404));
    }

    public WorkloadIdentity update(String name, List<String> allowedUrls, String region) {
        WorkloadIdentity identity = get(name, region);
        if (allowedUrls != null) {
            identity.setAllowedResourceOauth2ReturnUrls(allowedUrls);
        }
        identity.setLastUpdatedTime(Instant.now());
        storage.put(key(region, name), identity);
        return identity;
    }

    public void delete(String name, String region) {
        get(name, region);
        storage.delete(key(region, name));
    }

    public PaginatedResult<WorkloadIdentity> list(Integer maxResults, String nextToken, String region) {
        String prefix = "identity:" + region + ":";
        List<WorkloadIdentity> all = storage.scan(k -> k.startsWith(prefix));
        return Pagination.paginate(all, WorkloadIdentity::getName, maxResults, nextToken,
                MAX_PAGE, "ValidationException");
    }

    private static String key(String region, String name) {
        return "identity:" + region + ":" + name;
    }
}
