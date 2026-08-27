package io.github.hectorvent.floci.services.aps;

import io.github.hectorvent.floci.config.EmulatorConfig;
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
    // AMP's ListWorkspacesRequest declares maxResults with a maximum of 1000.
    private static final int MAX_PAGE = 1000;

    private final StorageBackend<String, PrometheusWorkspace> storage;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;

    @Inject
    public ApsService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver) {
        this.storage = storageFactory.create("aps", "aps-workspaces.json",
                new TypeReference<Map<String, PrometheusWorkspace>>() {});
        this.config = config;
        this.regionResolver = regionResolver;
    }

    public PrometheusWorkspace createWorkspace(String alias, Map<String, String> tags, String kmsKeyArn) {
        String workspaceId = "ws-" + UUID.randomUUID();
        String region = config.defaultRegion();
        String arn = AwsArnUtils.Arn.of("aps", region, regionResolver.getAccountId(),
                "workspace/" + workspaceId).toString();

        PrometheusWorkspace workspace = new PrometheusWorkspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setAlias(alias);
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

        storage.put(workspaceId, workspace);
        LOG.infov("Created AMP workspace: {0}", workspaceId);
        return workspace;
    }

    public PrometheusWorkspace describeWorkspace(String workspaceId) {
        return storage.get(workspaceId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Workspace not found: " + workspaceId, 404));
    }

    // The alias parameter is a prefix filter, not an exact match: the terraform provider's
    // aws_prometheus_workspaces data source exposes it as alias_prefix.
    public PaginatedResult<PrometheusWorkspace> listWorkspaces(String aliasPrefix, Integer maxResults, String nextToken) {
        List<PrometheusWorkspace> all = storage.scan(k -> true).stream()
                .filter(w -> aliasPrefix == null || aliasPrefix.isEmpty()
                        || (w.getAlias() != null && w.getAlias().startsWith(aliasPrefix)))
                .toList();
        return Pagination.paginate(all, PrometheusWorkspace::getWorkspaceId, maxResults, nextToken,
                MAX_PAGE, "ValidationException");
    }

    public PrometheusWorkspace deleteWorkspace(String workspaceId) {
        PrometheusWorkspace workspace = describeWorkspace(workspaceId);
        workspace.setStatus("DELETING");
        storage.delete(workspaceId);
        LOG.infov("Deleted AMP workspace: {0}", workspaceId);
        return workspace;
    }

    public PrometheusWorkspace updateWorkspaceAlias(String workspaceId, String alias) {
        PrometheusWorkspace workspace = describeWorkspace(workspaceId);
        workspace.setAlias(alias);
        storage.put(workspaceId, workspace);
        return workspace;
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
        return Map.copyOf(workspaceByArn(arn).getTags());
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        PrometheusWorkspace workspace = workspaceByArn(arn);
        workspace.getTags().putAll(tags);
        storage.put(workspace.getWorkspaceId(), workspace);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        PrometheusWorkspace workspace = workspaceByArn(arn);
        tagKeys.forEach(workspace.getTags()::remove);
        storage.put(workspace.getWorkspaceId(), workspace);
    }

    private PrometheusWorkspace workspaceByArn(String arn) {
        // arn:aws:aps:<region>:<account>:workspace/<workspaceId>
        int slash = arn.lastIndexOf('/');
        if (slash < 0 || slash == arn.length() - 1) {
            throw new AwsException("ValidationException", "Invalid AMP resource ARN: " + arn, 400);
        }
        return describeWorkspace(arn.substring(slash + 1));
    }
}
