package io.floci.aws.services.aps;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsEndpoints;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.core.common.TagHandler;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.aps.model.PrometheusWorkspace;
import io.floci.aws.services.aps.model.RuleGroupsNamespace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@ApplicationScoped
public class ApsService implements TagHandler, StoredResourceArns {

    private static final Logger LOG = Logger.getLogger(ApsService.class);
    // AMP's ListWorkspacesRequest declares maxResults with a default of 100 and a maximum of 1000.
    private static final int DEFAULT_PAGE = 100;
    private static final int MAX_PAGE = 1000;
    private static final int MAX_NAMESPACE_NAME_LENGTH = 128;
    private static final Pattern NAMESPACE_NAME = Pattern.compile(".*[0-9A-Za-z][-.0-9A-Z_a-z]*.*");
    private static final Pattern CLIENT_TOKEN = Pattern.compile("[!-~]{1,64}");
    private static final String WORKSPACE_PREFIX = "workspace/";
    private static final String NAMESPACE_PREFIX = "rulegroupsnamespace/";

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
        return createWorkspace(region, alias, tags, kmsKeyArn, null);
    }

    // ponytail: serialize token claims; use per-token locks if workspace-create throughput matters.
    public synchronized PrometheusWorkspace createWorkspace(String region, String alias, Map<String, String> tags,
                                                            String kmsKeyArn, String clientToken) {
        if (clientToken != null && !CLIENT_TOKEN.matcher(clientToken).matches()) {
            throw new AwsException("ValidationException",
                    "clientToken must contain 1 to 64 printable ASCII characters without spaces.", 400);
        }
        Map<String, String> requestedTags = tags != null ? tags : Map.of();
        if (clientToken != null) {
            PrometheusWorkspace receipt = storage.get(workspaceRequestKey(region, clientToken)).orElse(null);
            if (receipt != null) {
                if (!Objects.equals(stripAlias(alias), receipt.getAlias())
                        || !Objects.equals(kmsKeyArn, receipt.getKmsKeyArn())
                        || !requestedTags.equals(receipt.getTags())) {
                    throw new AwsException("ConflictException",
                            "clientToken has already been used with different workspace parameters.", 409);
                }
                return copyWorkspace(receipt);
            }
        }
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
                "https://" + AwsEndpoints.host("aps-workspaces", region) + "/workspaces/" + workspaceId + "/");
        workspace.setCreatedAt(Instant.now());
        workspace.setKmsKeyArn(kmsKeyArn);
        workspace.setTags(tags);

        PrometheusWorkspace response = clientToken != null ? copyWorkspace(workspace) : workspace;
        if (clientToken != null) {
            // One commit keeps the receipt and resource together in snapshots and WAL replay.
            storage.putAllAtomically(Map.of(key(region, workspaceId), workspace,
                    workspaceRequestKey(region, clientToken), response));
        } else {
            storage.put(key(region, workspaceId), workspace);
        }
        LOG.infov("Created AMP workspace: {0} in {1}", workspaceId, region);
        return clientToken != null ? copyWorkspace(response) : workspace;
    }

    private static PrometheusWorkspace copyWorkspace(PrometheusWorkspace source) {
        PrometheusWorkspace copy = new PrometheusWorkspace();
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setAlias(source.getAlias());
        copy.setArn(source.getArn());
        copy.setStatus(source.getStatus());
        copy.setPrometheusEndpoint(source.getPrometheusEndpoint());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setKmsKeyArn(source.getKmsKeyArn());
        copy.setTags(source.getTags());
        return copy;
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

    public synchronized void deleteWorkspace(String region, String workspaceId) {
        describeWorkspace(region, workspaceId);
        String namespacePrefix = namespaceKeyPrefix(region, workspaceId);
        for (RuleGroupsNamespace namespace : namespaceStorage.scan(k -> k.startsWith(namespacePrefix))) {
            namespaceStorage.delete(namespaceKey(region, workspaceId, namespace.getName()));
        }
        storage.delete(key(region, workspaceId));
        LOG.infov("Deleted AMP workspace: {0} in {1}", workspaceId, region);
    }

    public synchronized void updateWorkspaceAlias(String region, String workspaceId, String alias) {
        PrometheusWorkspace workspace = describeWorkspace(region, workspaceId);
        workspace.setAlias(stripAlias(alias));
        storage.put(key(region, workspaceId), workspace);
    }

    public synchronized RuleGroupsNamespace createRuleGroupsNamespace(String region, String workspaceId, String name,
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
        namespace.setTags(tags);

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

    public synchronized RuleGroupsNamespace putRuleGroupsNamespace(String region, String workspaceId, String name,
                                                                   String encodedData) {
        requireNamespaceData(encodedData);
        RuleGroupsNamespace namespace = describeRuleGroupsNamespace(region, workspaceId, name);
        namespace.setEncodedData(encodedData);
        namespace.setModifiedAt(Instant.now());
        namespaceStorage.put(namespaceKey(region, workspaceId, name), namespace);
        return namespace;
    }

    public synchronized void deleteRuleGroupsNamespace(String region, String workspaceId, String name) {
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
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        // Per AMP's TagResource: keys must not begin with the reserved "aws:" prefix
        // (case-insensitive, matching the sibling checks in FisService and BatchService).
        for (String tagKey : tags.keySet()) {
            if (tagKey.regionMatches(true, 0, "aws:", 0, 4)) {
                throw new AwsException("ValidationException",
                        "Tag keys must not begin with aws:. Offending key: " + tagKey, 400);
            }
        }
        Taggable taggable = taggableByArn(region, arn);
        Map<String, String> tagged = new HashMap<>(taggable.tags());
        tagged.putAll(tags);
        taggable.store().accept(tagged);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Taggable taggable = taggableByArn(region, arn);
        Map<String, String> untagged = new HashMap<>(taggable.tags());
        tagKeys.forEach(untagged::remove);
        taggable.store().accept(untagged);
    }

    /**
     * A resource's tags and how to store a new set of them. A tag change stores a whole new map in the resource's
     * volatile field, never changing a stored one, so a reader of the tags ({@link #storedTags}), which takes no lock,
     * sees one whole set; it does so under the service's lock, so two changes cannot lose each other's tags.
     */
    private record Taggable(Map<String, String> tags, Consumer<Map<String, String>> store) {
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
        List<String> ids = resourceIds(parsed.resource());
        if (ids.size() == 1) {
            PrometheusWorkspace workspace = describeWorkspace(region, ids.get(0));
            return new Taggable(workspace.getTags(), tags -> {
                workspace.setTags(tags);
                storage.put(key(region, workspace.getWorkspaceId()), workspace);
            });
        }
        if (ids.size() == 2) {
            RuleGroupsNamespace namespace = describeRuleGroupsNamespace(region, ids.get(0), ids.get(1));
            return new Taggable(namespace.getTags(), tags -> {
                namespace.setTags(tags);
                namespaceStorage.put(namespaceKey(region, ids.get(0), ids.get(1)), namespace);
            });
        }
        throw new AwsException("ValidationException",
                "Tags are only supported on AMP workspaces and rule groups namespaces: " + arn, 400);
    }

    // ── StoredResourceArns: the tags IAM gives a resource-tag condition ──

    @Override
    public String namespace() {
        return "aps";
    }

    /**
     * A request names a workspace by the id AMP minted and a namespace by its workspace and name, which the table
     * fills into the ARN itself, so there is nothing to find by name.
     */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * The tags of the workspace or the rule groups namespace whose ARN is {@code arn}, read in place under its key in
     * the Region the ARN names, in the request's account, where the handlers read: an ARN of another account names
     * none. A tag change stores a whole new map in a volatile field, so the read, which takes no lock, is of one
     * whole set.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "aps")) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (!parsed.accountId().equals(regionResolver.getAccountId())) {
            return Map.of();
        }
        List<String> ids = resourceIds(parsed.resource());
        if (ids.size() == 1) {
            return AccountAwareStorageBackend.readInAccount(storage, parsed.accountId(),
                            key(parsed.region(), ids.get(0)))
                    .filter(workspace -> arn.equals(workspace.getArn()))
                    .<Map<String, String>>map(workspace -> new LinkedHashMap<>(workspace.getTags()))
                    .orElse(Map.of());
        }
        if (ids.size() != 2) {
            return Map.of();
        }
        return AccountAwareStorageBackend.readInAccount(namespaceStorage, parsed.accountId(),
                        namespaceKey(parsed.region(), ids.get(0), ids.get(1)))
                .filter(namespace -> arn.equals(namespace.getArn()))
                .<Map<String, String>>map(namespace -> new LinkedHashMap<>(namespace.getTags()))
                .orElse(Map.of());
    }

    /**
     * The ids an AMP ARN's resource names: a workspace's id, or a rule groups namespace's workspace id and name; none
     * for anything else. Tag calls and IAM's tag lookup read an ARN alike.
     */
    private static List<String> resourceIds(String resource) {
        if (resource.startsWith(WORKSPACE_PREFIX) && resource.length() > WORKSPACE_PREFIX.length()) {
            return List.of(resource.substring(WORKSPACE_PREFIX.length()));
        }
        if (resource.startsWith(NAMESPACE_PREFIX)) {
            String[] parts = resource.substring(NAMESPACE_PREFIX.length()).split("/", 2);
            if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                return List.of(parts[0], parts[1]);
            }
        }
        return List.of();
    }

    // AMP is regional ("You can have one or more workspaces in each Region in your account"), so
    // the store is partitioned by request region, like CloudWatchLogsService's groupKey.
    private static String key(String region, String workspaceId) {
        return keyPrefix(region) + workspaceId;
    }

    private static String keyPrefix(String region) {
        return region + "::";
    }

    private static String workspaceRequestKey(String region, String clientToken) {
        // Request regions cannot contain ':', keeping receipts outside every workspace prefix.
        return ":CreateWorkspace::" + region + "::" + clientToken;
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
