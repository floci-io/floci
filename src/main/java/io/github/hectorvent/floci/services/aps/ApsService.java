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
import io.github.hectorvent.floci.services.aps.model.RuleGroupsNamespace;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class ApsService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(ApsService.class);
    // AMP's ListWorkspacesRequest declares maxResults with a default of 100 and a maximum of 1000.
    private static final int DEFAULT_PAGE = 100;
    private static final int MAX_PAGE = 1000;
    private static final int MAX_NAMESPACE_NAME_LENGTH = 128;
    private static final Pattern NAMESPACE_NAME = Pattern.compile(".*[0-9A-Za-z][-.0-9A-Z_a-z]*.*");

    private final StorageBackend<String, PrometheusWorkspace> storage;
    private final StorageBackend<String, RuleGroupsNamespace> namespaceStorage;
    private final RegionResolver regionResolver;

    @Inject
    public ApsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.storage = storageFactory.create("aps", "aps-workspaces.json",
                new TypeReference<Map<String, PrometheusWorkspace>>() {});
        this.namespaceStorage = storageFactory.create("aps", "aps-rule-groups-namespaces.json",
                new TypeReference<Map<String, RuleGroupsNamespace>>() {});
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
        String namespacePrefix = namespaceKeyPrefix(region, workspaceId);
        for (RuleGroupsNamespace namespace : namespaceStorage.scan(k -> k.startsWith(namespacePrefix))) {
            namespaceStorage.delete(namespaceKey(region, workspaceId, namespace.getName()));
        }
        storage.delete(key(region, workspaceId));
        LOG.infov("Deleted AMP workspace: {0} in {1}", workspaceId, region);
    }

    public void updateWorkspaceAlias(String region, String workspaceId, String alias) {
        PrometheusWorkspace workspace = describeWorkspace(region, workspaceId);
        workspace.setAlias(stripAlias(alias));
        storage.put(key(region, workspaceId), workspace);
    }

    public RuleGroupsNamespace createRuleGroupsNamespace(String region, String workspaceId, String name,
                                                         String encodedData, Map<String, String> tags) {
        describeWorkspace(region, workspaceId);
        requireNamespaceName(name);
        requireNamespaceData(encodedData);
        if (namespaceStorage.get(namespaceKey(region, workspaceId, name)).isPresent()) {
            throw new AwsException("ConflictException",
                    "Rule groups namespace already exists: " + name, 409);
        }

        RuleGroupsNamespace namespace = new RuleGroupsNamespace();
        namespace.setName(name);
        namespace.setWorkspaceId(workspaceId);
        namespace.setArn(regionResolver.buildArn("aps", region,
                "rulegroupsnamespace/" + workspaceId + "/" + name));
        namespace.setStatus("ACTIVE");
        namespace.setEncodedData(encodedData);
        Instant now = Instant.now();
        namespace.setCreatedAt(now);
        namespace.setModifiedAt(now);
        if (tags != null) {
            namespace.getTags().putAll(tags);
        }

        namespaceStorage.put(namespaceKey(region, workspaceId, name), namespace);
        LOG.infov("Created AMP rule groups namespace: {0} in workspace {1}", name, workspaceId);
        return namespace;
    }

    public RuleGroupsNamespace describeRuleGroupsNamespace(String region, String workspaceId, String name) {
        describeWorkspace(region, workspaceId);
        return namespaceStorage.get(namespaceKey(region, workspaceId, name))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Rule groups namespace not found: " + name, 404));
    }

    public PaginatedResult<RuleGroupsNamespace> listRuleGroupsNamespaces(String region, String workspaceId,
                                                                         String namePrefix, Integer maxResults,
                                                                         String nextToken) {
        describeWorkspace(region, workspaceId);
        String prefix = namespaceKeyPrefix(region, workspaceId);
        List<RuleGroupsNamespace> all = namespaceStorage.scan(k -> k.startsWith(prefix)).stream()
                .filter(n -> namePrefix == null || namePrefix.isEmpty()
                        || (n.getName() != null && n.getName().startsWith(namePrefix)))
                .toList();
        return Pagination.paginate(all, RuleGroupsNamespace::getName, maxResults, nextToken,
                DEFAULT_PAGE, MAX_PAGE, "ValidationException");
    }

    public RuleGroupsNamespace putRuleGroupsNamespace(String region, String workspaceId, String name,
                                                      String encodedData) {
        requireNamespaceData(encodedData);
        RuleGroupsNamespace namespace = describeRuleGroupsNamespace(region, workspaceId, name);
        namespace.setEncodedData(encodedData);
        namespace.setModifiedAt(Instant.now());
        namespaceStorage.put(namespaceKey(region, workspaceId, name), namespace);
        return namespace;
    }

    public void deleteRuleGroupsNamespace(String region, String workspaceId, String name) {
        describeRuleGroupsNamespace(region, workspaceId, name);
        namespaceStorage.delete(namespaceKey(region, workspaceId, name));
        LOG.infov("Deleted AMP rule groups namespace: {0} in workspace {1}", name, workspaceId);
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
        return Map.copyOf(taggableByArn(region, arn).tags());
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
        Taggable taggable = taggableByArn(region, arn);
        taggable.tags().putAll(tags);
        taggable.persist().run();
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        Taggable taggable = taggableByArn(region, arn);
        tagKeys.forEach(taggable.tags()::remove);
        taggable.persist().run();
    }

    private record Taggable(Map<String, String> tags, Runnable persist) {
    }

    private Taggable taggableByArn(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Invalid resource ARN: " + arn, 400);
        }
        if (!"aps".equals(parsed.service()) || !region.equals(parsed.region())
                || !regionResolver.getAccountId().equals(parsed.accountId())) {
            throw new AwsException("ValidationException",
                    "The resource ARN does not belong to this AMP account and region: " + arn, 400);
        }
        String resource = parsed.resource();
        String workspacePrefix = "workspace/";
        if (resource.startsWith(workspacePrefix) && resource.length() > workspacePrefix.length()) {
            PrometheusWorkspace workspace =
                    describeWorkspace(region, resource.substring(workspacePrefix.length()));
            return new Taggable(workspace.getTags(),
                    () -> storage.put(key(region, workspace.getWorkspaceId()), workspace));
        }
        String namespacePrefix = "rulegroupsnamespace/";
        if (resource.startsWith(namespacePrefix)) {
            String[] parts = resource.substring(namespacePrefix.length()).split("/", 2);
            if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                RuleGroupsNamespace namespace = describeRuleGroupsNamespace(region, parts[0], parts[1]);
                return new Taggable(namespace.getTags(),
                        () -> namespaceStorage.put(namespaceKey(region, parts[0], parts[1]), namespace));
            }
        }
        throw new AwsException("ValidationException",
                "Tags are only supported on AMP workspaces and rule groups namespaces: " + arn, 400);
    }

    // AMP is regional ("You can have one or more workspaces in each Region in your account"), so
    // the store is partitioned by request region, like CloudWatchLogsService's groupKey.
    private static String key(String region, String workspaceId) {
        return keyPrefix(region) + workspaceId;
    }

    private static String keyPrefix(String region) {
        return region + "::";
    }

    private static String namespaceKey(String region, String workspaceId, String name) {
        return namespaceKeyPrefix(region, workspaceId) + name;
    }

    private static String namespaceKeyPrefix(String region, String workspaceId) {
        return keyPrefix(region) + workspaceId + "::";
    }

    private static void requireNamespaceName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAMESPACE_NAME_LENGTH
                || !NAMESPACE_NAME.matcher(name).matches() || name.indexOf('/') >= 0) {
            throw new AwsException("ValidationException",
                    "name must be 1 to " + MAX_NAMESPACE_NAME_LENGTH
                            + " characters matching " + NAMESPACE_NAME.pattern()
                            + " and must not contain '/'.", 400);
        }
    }

    private static void requireNamespaceData(String encodedData) {
        if (encodedData == null || encodedData.isEmpty()) {
            throw new AwsException("ValidationException", "data must not be empty.", 400);
        }
    }

    // AMP strips leading/trailing blanks from every alias it accepts, including the list filter.
    private static String stripAlias(String alias) {
        return alias == null ? null : alias.strip();
    }
}
