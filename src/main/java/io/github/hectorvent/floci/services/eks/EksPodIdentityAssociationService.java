package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreatePodIdentityAssociationRequest;
import io.github.hectorvent.floci.services.eks.model.PodIdentityAssociation;
import io.github.hectorvent.floci.services.eks.model.PodIdentityAssociationSummary;
import io.github.hectorvent.floci.services.eks.model.UpdatePodIdentityAssociationRequest;
import io.github.hectorvent.floci.services.iam.IamService;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Manages EKS Pod Identity association metadata.
 * Credentials delivery to pods (webhook and link-local credential endpoint) is separate.
 */
@ApplicationScoped
public class EksPodIdentityAssociationService {

    private static final String ID_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";

    private final StorageBackend<String, StoredAssociation> associations;
    private final IamService iam;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public EksPodIdentityAssociationService(StorageFactory factory, IamService iam) {
        this(factory.create("eks", "eks-pod-identity-associations.json",
                new TypeReference<Map<String, StoredAssociation>>() {}), iam);
    }

    EksPodIdentityAssociationService(StorageBackend<String, StoredAssociation> associations, IamService iam) {
        this.associations = associations;
        this.iam = iam;
    }

    @RegisterForReflection
    public record StoredAssociation(PodIdentityAssociation association, String clientRequestToken) {}

    @RegisterForReflection
    public record Page(List<PodIdentityAssociationSummary> associations, String nextToken) {}

    public synchronized PodIdentityAssociation create(Cluster cluster, CreatePodIdentityAssociationRequest request) {
        requireActiveCluster(cluster);
        if (request == null) {
            throw invalid("Request body is required");
        }
        String namespace = request.namespace();
        if (namespace == null || namespace.isBlank()) {
            throw invalid("namespace is required");
        }
        if (namespace.length() > 63) {
            throw invalid("namespace must be at most 63 characters");
        }
        String serviceAccount = request.serviceAccount();
        if (serviceAccount == null || serviceAccount.isBlank()) {
            throw invalid("serviceAccount is required");
        }
        if (serviceAccount.length() > 63) {
            throw invalid("serviceAccount must be at most 63 characters");
        }
        String roleArn = request.roleArn();
        if (roleArn == null || roleArn.isBlank()) {
            throw invalid("roleArn is required");
        }
        validateRoleArn(cluster, roleArn, "roleArn");

        if (request.targetRoleArn() != null && !request.targetRoleArn().isBlank()) {
            validateTargetRoleArn(cluster, request.targetRoleArn());
        }

        Map<String, String> tags = request.tags() == null ? Map.of() : request.tags();
        validateTags(tags);

        String prefix = prefix(cluster);
        String token = request.clientRequestToken();
        List<StoredAssociation> clusterAssociations = associations.scan(key -> key.startsWith(prefix));

        if (token != null && !token.isBlank()) {
            for (StoredAssociation stored : clusterAssociations) {
                if (token.equals(stored.clientRequestToken())) {
                    PodIdentityAssociation previous = stored.association();
                    if (namespace.equals(previous.namespace())
                            && serviceAccount.equals(previous.serviceAccount())
                            && roleArn.equals(previous.roleArn())
                            && Objects.equals(request.targetRoleArn(), previous.targetRoleArn())
                            && Objects.equals(request.disableSessionTags(), previous.disableSessionTags())
                            && Objects.equals(request.policy(), previous.policy())
                            && tags.equals(previous.tags())) {
                        return previous;
                    }
                    throw invalid("clientRequestToken was already used with different parameters");
                }
            }
        }

        for (StoredAssociation stored : clusterAssociations) {
            PodIdentityAssociation existing = stored.association();
            if (namespace.equals(existing.namespace()) && serviceAccount.equals(existing.serviceAccount())) {
                throw new AwsException("ResourceInUseException", "Association already exists: " + existing.associationId(), 409);
            }
        }

        String associationId = generateAssociationId();
        String[] clusterArn = cluster.getArn().split(":", 6);
        String associationArn = "arn:" + clusterArn[1] + ":eks:" + clusterArn[3] + ":"
                + clusterArn[4] + ":podidentityassociation/" + cluster.getName() + "/" + associationId;
        double now = Instant.now().toEpochMilli() / 1000.0;
        String externalId = UUID.randomUUID().toString();

        PodIdentityAssociation association = new PodIdentityAssociation(
                cluster.getName(),
                namespace,
                serviceAccount,
                roleArn,
                associationArn,
                associationId,
                Map.copyOf(tags),
                now,
                now,
                null,
                request.targetRoleArn(),
                request.disableSessionTags(),
                externalId,
                request.policy()
        );
        associations.put(prefix + associationId, new StoredAssociation(association, token));
        return association;
    }

    public synchronized PodIdentityAssociation describe(Cluster cluster, String associationId) {
        requireActiveCluster(cluster);
        if (associationId == null || associationId.isBlank()) {
            throw invalid("associationId is required");
        }
        return associations.get(prefix(cluster) + associationId)
                .map(StoredAssociation::association)
                .orElseThrow(() -> notFound(associationId));
    }

    public synchronized PodIdentityAssociation update(Cluster cluster, String associationId,
                                                      UpdatePodIdentityAssociationRequest request) {
        requireActiveCluster(cluster);
        if (associationId == null || associationId.isBlank()) {
            throw invalid("associationId is required");
        }
        if (request == null) {
            throw invalid("Request body is required");
        }
        StoredAssociation stored = associations.get(prefix(cluster) + associationId)
                .orElseThrow(() -> notFound(associationId));
        PodIdentityAssociation current = stored.association();

        if (request.roleArn() == null && request.targetRoleArn() == null
                && request.disableSessionTags() == null && request.policy() == null) {
            throw invalid("At least one parameter must be specified in the update request");
        }

        String token = request.clientRequestToken();
        if (token != null && !token.isBlank() && token.equals(stored.clientRequestToken())) {
            boolean matches = (request.roleArn() == null || request.roleArn().equals(current.roleArn()))
                    && Objects.equals(request.targetRoleArn(), current.targetRoleArn())
                    && Objects.equals(request.disableSessionTags(), current.disableSessionTags())
                    && Objects.equals(request.policy(), current.policy());
            if (matches) {
                return current;
            }
            throw invalid("clientRequestToken was already used with different parameters");
        }

        String newRoleArn = current.roleArn();
        if (request.roleArn() != null) {
            validateRoleArn(cluster, request.roleArn(), "roleArn");
            newRoleArn = request.roleArn();
        }
        String newTargetRoleArn = current.targetRoleArn();
        if (request.targetRoleArn() != null) {
            validateTargetRoleArn(cluster, request.targetRoleArn());
            newTargetRoleArn = request.targetRoleArn();
        }
        Boolean newDisableSessionTags = request.disableSessionTags() != null
                ? request.disableSessionTags() : current.disableSessionTags();
        String newPolicy = request.policy() != null ? request.policy() : current.policy();

        double now = Instant.now().toEpochMilli() / 1000.0;
        PodIdentityAssociation updated = new PodIdentityAssociation(
                current.clusterName(),
                current.namespace(),
                current.serviceAccount(),
                newRoleArn,
                current.associationArn(),
                current.associationId(),
                current.tags(),
                current.createdAt(),
                now,
                current.ownerArn(),
                newTargetRoleArn,
                newDisableSessionTags,
                current.externalId(),
                newPolicy
        );
        associations.put(prefix(cluster) + associationId,
                new StoredAssociation(updated, token != null ? token : stored.clientRequestToken()));
        return updated;
    }

    public synchronized PodIdentityAssociation delete(Cluster cluster, String associationId) {
        PodIdentityAssociation existing = describe(cluster, associationId);
        associations.delete(prefix(cluster) + associationId);
        return existing;
    }

    public synchronized Page list(Cluster cluster, String namespace, String serviceAccount,
                                  Integer maxResults, String nextToken) {
        requireActiveCluster(cluster);
        int limit = maxResults == null ? 100 : maxResults;
        if (limit < 1 || limit > 100) {
            throw invalid("maxResults must be between 1 and 100");
        }
        String prefix = prefix(cluster);
        String after = "";
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(nextToken), StandardCharsets.UTF_8);
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
        List<PodIdentityAssociation> remaining = associations.scan(key -> key.startsWith(prefix)).stream()
                .map(StoredAssociation::association)
                .filter(a -> namespace == null || namespace.isBlank() || namespace.equals(a.namespace()))
                .filter(a -> serviceAccount == null || serviceAccount.isBlank() || serviceAccount.equals(a.serviceAccount()))
                .filter(a -> a.associationId().compareTo(cursor) > 0)
                .sorted(Comparator.comparing(PodIdentityAssociation::associationId))
                .toList();

        List<PodIdentityAssociationSummary> page = remaining.stream()
                .limit(limit)
                .map(PodIdentityAssociationSummary::from)
                .toList();

        String token = remaining.size() > limit
                ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                        (prefix + "\n" + page.getLast().associationId()).getBytes(StandardCharsets.UTF_8))
                : null;

        return new Page(page, token);
    }

    public synchronized void deleteClusterAssociations(Cluster cluster) {
        String prefix = prefix(cluster);
        for (StoredAssociation stored : associations.scan(key -> key.startsWith(prefix))) {
            associations.delete(prefix + stored.association().associationId());
        }
    }

    private static String prefix(Cluster cluster) {
        return cluster.getArn() + "/" + Objects.toString(cluster.getCreatedAt()) + "/";
    }

    private static void requireActiveCluster(Cluster cluster) {
        if (cluster == null) {
            throw new AwsException("ResourceNotFoundException", "Cluster not found", 404);
        }
        if (cluster.getStatus() != ClusterStatus.ACTIVE) {
            throw new AwsException("InvalidRequestException", "Cluster must be ACTIVE", 400);
        }
    }

    private void validateRoleArn(Cluster cluster, String roleArn, String fieldName) {
        String[] arn = roleArn.split(":", 6);
        String[] clusterArn = cluster.getArn().split(":", 6);
        if (arn.length != 6 || !"arn".equals(arn[0]) || !arn[1].equals(clusterArn[1])
                || !"iam".equals(arn[2]) || !arn[3].isEmpty() || !arn[4].matches("[0-9]{12}")
                || !arn[5].startsWith("role/") || arn[5].startsWith("role/aws-service-role/")
                || roleArn.endsWith("/")) {
            throw invalid(fieldName + " must identify an IAM role");
        }
        String roleName = roleArn.substring(roleArn.lastIndexOf('/') + 1);
        iam.findRole(arn[4], roleName)
                .filter(role -> roleArn.equals(role.getArn()))
                .orElseThrow(() -> invalid("IAM role does not exist"));
    }

    private static void validateTargetRoleArn(Cluster cluster, String targetRoleArn) {
        String[] arn = targetRoleArn.split(":", 6);
        String[] clusterArn = cluster.getArn().split(":", 6);
        if (arn.length != 6 || !"arn".equals(arn[0]) || !arn[1].equals(clusterArn[1])
                || !"iam".equals(arn[2]) || !arn[3].isEmpty() || !arn[4].matches("[0-9]{12}")
                || !arn[5].startsWith("role/") || targetRoleArn.endsWith("/")) {
            throw invalid("targetRoleArn must identify an IAM role");
        }
    }

    private static void validateTags(Map<String, String> tags) {
        if (tags.size() > 50 || tags.entrySet().stream().anyMatch(tag -> tag.getKey() == null
                || tag.getKey().isEmpty() || tag.getKey().length() > 128
                || tag.getKey().startsWith("aws:") || tag.getKey().startsWith("AWS:")
                || tag.getValue() == null || tag.getValue().length() > 256)) {
            throw invalid("Invalid tags");
        }
    }

    private String generateAssociationId() {
        StringBuilder sb = new StringBuilder("a-");
        for (int i = 0; i < 17; i++) {
            sb.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static AwsException notFound(String associationId) {
        return new AwsException("ResourceNotFoundException", "No association found for ID: " + associationId, 404);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
