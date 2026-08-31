package io.github.hectorvent.floci.services.aps;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.aps.model.PrometheusWorkspace;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ApsService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(ApsService.class);
    // AMP's ListWorkspacesRequest declares maxResults with a default of 100 and a maximum of 1000.
    private static final int DEFAULT_PAGE = 100;
    private static final int MAX_PAGE = 1000;

    private final StorageBackend<String, PrometheusWorkspace> storage;
    private final RegionResolver regionResolver;

    @Inject
    public ApsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.storage = storageFactory.create("aps", "aps-workspaces.json",
                new TypeReference<Map<String, PrometheusWorkspace>>() {});
        this.regionResolver = regionResolver;
    }

    public PrometheusWorkspace createWorkspace(String region, String alias, Map<String, String> tags,
                                               String kmsKeyArn) {
        String workspaceId = "ws-" + UUID.randomUUID();
        String arn = regionResolver.buildArn("aps", region, "workspace/" + workspaceId);

        PrometheusWorkspace workspace = new PrometheusWorkspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setAlias(stripAlias(alias));
        workspace.setArn(arn);
        // Real AMP answers the create 202 with status CREATING; the emulator provisions nothing,
        // so the workspace is ACTIVE from birth and the terraform/pulumi provider's create waiter
        // (Pending CREATING, Target ACTIVE) completes on its first DescribeWorkspace poll.
        workspace.setStatus("ACTIVE");
        workspace.setPrometheusEndpoint(
                "https://aps-workspaces." + region + ".amazonaws.com/workspaces/" + workspaceId + "/");
        workspace.setCreatedAt(Instant.now());
        workspace.setKmsKeyArn(kmsKeyArn);
        if (tags != null) {
            workspace.getTags().putAll(tags);
        }

        storage.put(key(region, workspaceId), workspace);
        LOG.infov("Created AMP workspace: {0} in {1}", workspaceId, region);
        return workspace;
    }

    public PrometheusWorkspace describeWorkspace(String region, String workspaceId) {
        return storage.get(key(region, workspaceId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Workspace not found: " + workspaceId, 404));
    }

    // The alias parameter is a prefix filter, not an exact match: the terraform provider's
    // aws_prometheus_workspaces data source exposes it as alias_prefix.
    public PaginatedResult<PrometheusWorkspace> listWorkspaces(String region, String aliasPrefix,
                                                               Integer maxResults, String nextToken) {
        String prefix = stripAlias(aliasPrefix);
        String regionPrefix = keyPrefix(region);
        List<PrometheusWorkspace> all = storage.scan(k -> k.startsWith(regionPrefix)).stream()
                .filter(w -> prefix == null || prefix.isEmpty()
                        || (w.getAlias() != null && w.getAlias().startsWith(prefix)))
                .toList();
        return Pagination.paginate(all, PrometheusWorkspace::getWorkspaceId, maxResults, nextToken,
                DEFAULT_PAGE, MAX_PAGE, "ValidationException");
    }

    public void deleteWorkspace(String region, String workspaceId) {
        describeWorkspace(region, workspaceId);
        storage.delete(key(region, workspaceId));
        LOG.infov("Deleted AMP workspace: {0} in {1}", workspaceId, region);
    }

    public void updateWorkspaceAlias(String region, String workspaceId, String alias) {
        PrometheusWorkspace workspace = describeWorkspace(region, workspaceId);
        workspace.setAlias(stripAlias(alias));
        storage.put(key(region, workspaceId), workspace);
    }

    // ── TagHandler: the shared /tags/{resourceArn} dispatcher routes aps ARNs here ──

    @Override
    public String serviceKey() {
        return "aps";
    }

    // AMP defines TagResource/UntagResource with a 200 response, not the dispatcher's default 204.
    @Override
    public int tagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public int untagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(workspaceByArn(region, arn).getTags());
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        // Per AMP's TagResource: keys must not begin with the reserved "aws:" prefix
        // (case-insensitive, matching the sibling checks in FisService and BatchService).
        for (String tagKey : tags.keySet()) {
            if (tagKey.regionMatches(true, 0, "aws:", 0, 4)) {
                throw new AwsException("ValidationException",
                        "Tag keys must not begin with aws:. Offending key: " + tagKey, 400);
            }
        }
        PrometheusWorkspace workspace = workspaceByArn(region, arn);
        workspace.getTags().putAll(tags);
        storage.put(key(region, workspace.getWorkspaceId()), workspace);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        PrometheusWorkspace workspace = workspaceByArn(region, arn);
        tagKeys.forEach(workspace.getTags()::remove);
        storage.put(key(region, workspace.getWorkspaceId()), workspace);
    }

    private PrometheusWorkspace workspaceByArn(String region, String arn) {
        // arn:aws:aps:<region>:<account>:workspace/<workspaceId>
        String resource;
        try {
            resource = AwsArnUtils.parse(arn).resource();
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Invalid resource ARN: " + arn, 400);
        }
        String prefix = "workspace/";
        if (!resource.startsWith(prefix) || resource.length() == prefix.length()) {
            throw new AwsException("ValidationException",
                    "Tags are only supported on AMP workspaces: " + arn, 400);
        }
        return describeWorkspace(region, resource.substring(prefix.length()));
    }

    // AMP is regional ("You can have one or more workspaces in each Region in your account"), so
    // the store is partitioned by request region, like CloudWatchLogsService's groupKey.
    private static String key(String region, String workspaceId) {
        return keyPrefix(region) + workspaceId;
    }

    private static String keyPrefix(String region) {
        return region + "::";
    }

    // AMP strips leading/trailing blanks from every alias it accepts, including the list filter.
    private static String stripAlias(String alias) {
        return alias == null ? null : alias.strip();
    }
}
