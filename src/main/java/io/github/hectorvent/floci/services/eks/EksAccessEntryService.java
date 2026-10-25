package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eks.model.AccessEntry;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateAccessEntryRequest;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Access-entry management metadata; Kubernetes authorization is handled separately. */
@ApplicationScoped
public class EksAccessEntryService {
    private final StorageBackend<String, StoredEntry> entries;
    private final IamService iam;

    @Inject
    public EksAccessEntryService(StorageFactory factory, IamService iam) {
        this(factory.create("eks", "eks-access-entries.json", new TypeReference<Map<String, StoredEntry>>() {}), iam);
    }

    EksAccessEntryService(StorageBackend<String, StoredEntry> entries, IamService iam) {
        this.entries = entries;
        this.iam = iam;
    }

    @RegisterForReflection
    public record StoredEntry(AccessEntry entry, String principalId, String clientRequestToken) {}

    @RegisterForReflection
    public record Page(List<String> accessEntries, String nextToken) {}

    public synchronized AccessEntry create(Cluster cluster, CreateAccessEntryRequest request) {
        requireApiAccess(cluster);
        if (request == null || request.principalArn() == null) {
            throw invalid("principalArn is required");
        }
        String type = request.type() == null ? "STANDARD" : request.type();
        if (!"STANDARD".equals(type) && !"EC2_LINUX".equals(type)) {
            throw invalid("Floci currently supports STANDARD and EC2_LINUX access entries");
        }
        boolean node = "EC2_LINUX".equals(type);
        if (node && (request.username() != null || request.kubernetesGroups() != null)) {
            throw invalid("EC2_LINUX entries cannot specify username or kubernetesGroups");
        }
        String principal = request.principalArn();
        AwsArnUtils.Arn arn = AwsArnUtils.isArn(principal) ? AwsArnUtils.parse(principal) : null;
        AwsArnUtils.Arn clusterArn = AwsArnUtils.parse(cluster.getArn());
        if (arn == null || !arn.partition().equals(clusterArn.partition())
                || !"iam".equals(arn.service()) || !arn.region().isEmpty() || !arn.accountId().matches("[0-9]{12}")
                || !(arn.resource().startsWith("role/") || (!node && arn.resource().startsWith("user/")))
                || arn.resource().startsWith("role/aws-service-role/")
                || principal.endsWith("/") || (node && !arn.accountId().equals(clusterArn.accountId()))) {
            throw invalid("principalArn must identify an IAM principal; EC2_LINUX requires a role in the cluster account");
        }
        String name = principal.substring(principal.lastIndexOf('/') + 1);
        boolean role = arn.resource().startsWith("role/");
        String principalId = role
                ? iam.findRole(arn.accountId(), name).filter(value -> principal.equals(value.getArn()))
                        .map(IamRole::getRoleId).orElseThrow(() -> invalid("IAM role does not exist"))
                : iam.findUser(arn.accountId(), name).filter(value -> principal.equals(value.getArn()))
                        .map(IamUser::getUserId).orElseThrow(() -> invalid("IAM user does not exist"));
        String username = node ? "system:node:{{EC2PrivateDNSName}}" : request.username();
        if (username != null && !node && (username.isBlank()
                || username.matches("^(system|eks|aws|amazon|iam):.*"))) {
            throw invalid("Invalid username");
        }
        if (username != null && !node) {
            for (String placeholder : List.of("{{SessionName}}", "{{SessionNameRaw}}")) {
                int position = username.indexOf(placeholder);
                if (position >= 0 && !username.substring(0, position).contains(":")) {
                    throw invalid("A session-name placeholder must be preceded by a colon");
                }
            }
        }
        if (username == null) {
            username = role ? AwsArnUtils.Arn.global(arn.partition(), "sts", arn.accountId(),
                    "assumed-role/" + name + "/{{SessionName}}").toString() : principal;
        }
        List<String> groups = node ? List.of("system:nodes")
                : request.kubernetesGroups() == null ? List.of() : request.kubernetesGroups();
        if (groups.stream().anyMatch(group -> group == null || group.isBlank())) {
            throw invalid("kubernetesGroups must contain nonempty strings");
        }
        Map<String, String> tags = request.tags() == null ? Map.of() : request.tags();
        if (tags.size() > 50 || tags.entrySet().stream().anyMatch(tag -> tag.getKey() == null
                || tag.getKey().isEmpty() || tag.getKey().length() > 128 || tag.getValue() == null
                || tag.getValue().length() > 256)) {
            throw invalid("Invalid access entry tags");
        }
        String prefix = prefix(cluster);
        String token = request.clientRequestToken();
        if (token != null) {
            for (StoredEntry stored : entries.scan(key -> key.startsWith(prefix))) {
                if (token.equals(stored.clientRequestToken())) {
                    AccessEntry previous = stored.entry();
                    if (principal.equals(previous.principalArn()) && type.equals(previous.type())
                            && username.equals(previous.username()) && groups.equals(previous.kubernetesGroups())
                            && tags.equals(previous.tags())) {
                        return previous;
                    }
                    throw invalid("clientRequestToken was already used with different parameters");
                }
            }
        }
        String key = prefix + principal;
        if (entries.get(key).isPresent()) {
            throw new AwsException("ResourceInUseException", "Access entry already exists", 409);
        }
        double now = Instant.now().toEpochMilli() / 1000.0;
        String entryArn = new AwsArnUtils.Arn(clusterArn.partition(), "eks", clusterArn.region(), clusterArn.accountId(),
                "access-entry/" + cluster.getName() + "/" + (role ? "role" : "user") + "/" + arn.accountId() + "/" + name
                        + "/" + UUID.randomUUID()).toString();
        AccessEntry entry = new AccessEntry(entryArn, cluster.getName(), principal,
                type, username, List.copyOf(groups), Map.copyOf(tags), now, now);
        entries.put(key, new StoredEntry(entry, principalId, token));
        return entry;
    }

    synchronized Optional<AccessEntry> workerEntry(Cluster cluster, String accountId, String principal, String roleId) {
        requireApiAccess(cluster);
        String key = prefix(cluster) + principal;
        Optional<StoredEntry> stored = entries instanceof AccountAwareStorageBackend<StoredEntry> aware
                ? aware.getForAccount(accountId, key) : entries.get(key);
        return stored.filter(value -> roleId != null && roleId.equals(value.principalId()))
                .map(StoredEntry::entry).filter(entry -> "EC2_LINUX".equals(entry.type()));
    }

    public synchronized AccessEntry describe(Cluster cluster, String principal) {
        requireApiAccess(cluster);
        return entries.get(prefix(cluster) + principal).map(StoredEntry::entry)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Access entry not found", 404));
    }

    public synchronized void delete(Cluster cluster, String principal) {
        describe(cluster, principal);
        entries.delete(prefix(cluster) + principal);
    }

    public synchronized Page list(Cluster cluster, Integer maxResults, String nextToken) {
        requireApiAccess(cluster);
        int limit = maxResults == null ? 100 : maxResults;
        if (limit < 1 || limit > 100) {
            throw invalid("maxResults must be between 1 and 100");
        }
        String prefix = prefix(cluster);
        String after = "";
        if (nextToken != null) {
            try {
                String decoded = Base64Url.decode(nextToken);
                if (!decoded.startsWith(prefix + "\n")) {
                    throw invalid("nextToken does not belong to this cluster");
                }
                after = decoded.substring(prefix.length() + 1);
                if (after.isBlank()) {
                    throw invalid("Invalid nextToken");
                }
            } catch (IllegalArgumentException exception) {
                throw invalid("Invalid nextToken");
            }
        }
        String cursor = after;
        List<String> remaining = entries.scan(key -> key.startsWith(prefix)).stream()
                .map(stored -> stored.entry().principalArn()).filter(principal -> principal.compareTo(cursor) > 0)
                .sorted(Comparator.naturalOrder()).toList();
        List<String> page = remaining.stream().limit(limit).toList();
        String token = remaining.size() > limit ? Base64Url.encode(prefix + "\n" + page.getLast()) : null;
        return new Page(page, token);
    }

    public synchronized void deleteClusterEntries(Cluster cluster) {
        String prefix = prefix(cluster);
        for (StoredEntry stored : entries.scan(key -> key.startsWith(prefix))) {
            entries.delete(prefix + stored.entry().principalArn());
        }
    }

    private static String prefix(Cluster cluster) {
        // A recreated cluster must not inherit access entries or pagination tokens from its predecessor.
        return cluster.getArn() + "/" + Objects.toString(cluster.getCreatedAt()) + "/";
    }

    private static void requireApiAccess(Cluster cluster) {
        if (cluster.getAccessConfig() == null || "CONFIG_MAP".equals(cluster.getAccessConfig().authenticationMode())) {
            throw new AwsException("InvalidRequestException", "Cluster authentication mode must be API or API_AND_CONFIG_MAP", 400);
        }
        if (cluster.getStatus() != ClusterStatus.ACTIVE) {
            throw new AwsException("InvalidRequestException", "Cluster must be ACTIVE", 400);
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
