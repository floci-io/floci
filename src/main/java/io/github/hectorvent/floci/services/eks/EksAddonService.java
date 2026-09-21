package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eks.model.Addon;
import io.github.hectorvent.floci.services.eks.model.AddonHealth;
import io.github.hectorvent.floci.services.eks.model.AddonInfo;
import io.github.hectorvent.floci.services.eks.model.AddonPodIdentityAssociation;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateAddonRequest;
import io.github.hectorvent.floci.services.eks.model.CreatePodIdentityAssociationRequest;
import io.github.hectorvent.floci.services.eks.model.PodIdentityAssociation;
import io.github.hectorvent.floci.services.eks.model.Update;
import io.github.hectorvent.floci.services.eks.model.UpdateAddonRequest;
import io.github.hectorvent.floci.services.eks.model.UpdateParam;
import io.github.hectorvent.floci.services.iam.IamService;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@ApplicationScoped
public class EksAddonService {

    private static final Logger LOG = Logger.getLogger(EksAddonService.class);
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 100;

    private final StorageBackend<String, StoredAddon> storage;
    private final StorageBackend<String, StoredUpdate> updatesStorage;
    private final EksAddonCatalog catalog;
    private final IamService iam;
    private final EksPodIdentityAssociationService podIdentityAssociations;

    @Inject
    public EksAddonService(StorageFactory storageFactory, EksAddonCatalog catalog,
                           IamService iam, EksPodIdentityAssociationService podIdentityAssociations) {
        this(storageFactory.create("eks", "eks-addons.json",
                new TypeReference<Map<String, StoredAddon>>() {}),
                storageFactory.create("eks", "eks-updates.json",
                new TypeReference<Map<String, StoredUpdate>>() {}),
                catalog, iam, podIdentityAssociations);
    }

    EksAddonService(StorageBackend<String, StoredAddon> storage,
                    StorageBackend<String, StoredUpdate> updatesStorage,
                    EksAddonCatalog catalog,
                    IamService iam,
                    EksPodIdentityAssociationService podIdentityAssociations) {
        this.storage = storage;
        this.updatesStorage = updatesStorage;
        this.catalog = catalog;
        this.iam = iam;
        this.podIdentityAssociations = podIdentityAssociations;
    }

    @RegisterForReflection
    public record StoredAddon(Addon addon, String clientRequestToken) {}

    @RegisterForReflection
    public record StoredUpdate(Update update, String addonName) {}

    @RegisterForReflection
    public record AddonNamesPage(List<String> addons, String nextToken) {}

    @RegisterForReflection
    public record AddonVersionsPage(List<AddonInfo> addons, String nextToken) {}

    public synchronized Addon create(Cluster cluster, CreateAddonRequest request) {
        requireActiveCluster(cluster);
        if (request == null || request.addonName() == null || request.addonName().isBlank()) {
            throw new AwsException("InvalidParameterException", "addonName is required", 400);
        }

        String addonName = request.addonName().trim();
        if (!catalog.isKnownAddon(addonName)) {
            throw new AwsException("InvalidParameterException",
                    "Addon name '" + addonName + "' is not valid", 400);
        }

        String storageKey = prefix(cluster) + addonName.toLowerCase(Locale.ROOT);
        Optional<StoredAddon> existing = storage.get(storageKey);
        if (existing.isPresent()) {
            StoredAddon stored = existing.get();
            if (request.clientRequestToken() != null && !request.clientRequestToken().isBlank()
                    && request.clientRequestToken().equals(stored.clientRequestToken())) {
                if (parametersMatch(stored.addon(), request)) {
                    return stored.addon();
                }
                throw new AwsException("InvalidParameterException",
                        "clientRequestToken was already used with different parameters", 400);
            }
            throw new AwsException("ResourceInUseException", "Addon already exists: " + addonName, 409);
        }

        String version = (request.addonVersion() != null && !request.addonVersion().isBlank())
                ? request.addonVersion().trim()
                : catalog.resolveDefaultVersion(addonName, cluster.getVersion()).orElse(null);
        if (version == null || !catalog.isVersionSupported(addonName, version, cluster.getVersion())) {
            throw new AwsException("InvalidParameterException",
                    "Addon version specified is not supported", 400);
        }

        if (request.serviceAccountRoleArn() != null && !request.serviceAccountRoleArn().isBlank()) {
            validateRoleArn(cluster, request.serviceAccountRoleArn(), "serviceAccountRoleArn");
        }

        if (request.resolveConflicts() != null && !request.resolveConflicts().isBlank()) {
            validateResolveConflicts(request.resolveConflicts());
        }

        Map<String, String> tags = request.tags() != null ? request.tags() : Map.of();
        validateTags(tags);

        List<String> associationArns = createOrLinkPodIdentityAssociations(cluster, addonName, request.podIdentityAssociations());

        double now = Instant.now().toEpochMilli() / 1000.0;
        String addonArn = generateAddonArn(cluster, addonName);
        Optional<AddonInfo> addonInfo = catalog.findAddon(addonName);
        String owner = addonInfo.map(AddonInfo::owner).orElse("aws");
        String publisher = addonInfo.map(AddonInfo::publisher).orElse("eks");

        Addon addon = new Addon(
                addonArn,
                addonName,
                version,
                cluster.getName(),
                "ACTIVE",
                new AddonHealth(List.of()),
                now,
                now,
                request.serviceAccountRoleArn(),
                request.configurationValues(),
                Map.copyOf(tags),
                List.copyOf(associationArns),
                owner,
                publisher
        );

        storage.put(storageKey, new StoredAddon(addon, request.clientRequestToken()));
        LOG.infov("Created EKS addon {0} on cluster {1} with version {2}", addonName, cluster.getName(), version);
        return addon;
    }

    public Addon describe(Cluster cluster, String addonName) {
        requireActiveCluster(cluster);
        if (addonName == null || addonName.isBlank()) {
            throw new AwsException("InvalidParameterException", "addonName is required", 400);
        }

        String storageKey = prefix(cluster) + addonName.trim().toLowerCase(Locale.ROOT);
        StoredAddon stored = storage.get(storageKey)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No addon: " + addonName + " found for cluster: " + cluster.getName(), 404));
        return stored.addon();
    }

    public AddonNamesPage list(Cluster cluster, Integer maxResults, String nextToken) {
        requireActiveCluster(cluster);
        String p = prefix(cluster);
        List<String> addonNames = storage.scan(key -> key.startsWith(p)).stream()
                .map(stored -> stored.addon().addonName())
                .sorted()
                .toList();

        PaginatedResult<String> result = Pagination.paginate(
                addonNames,
                Function.identity(),
                maxResults,
                nextToken,
                DEFAULT_PAGE_SIZE,
                MAX_PAGE_SIZE,
                "InvalidParameterException"
        );
        return new AddonNamesPage(result.items(), result.nextToken());
    }

    public synchronized Update update(Cluster cluster, String addonName, UpdateAddonRequest request) {
        requireActiveCluster(cluster);
        if (addonName == null || addonName.isBlank()) {
            throw new AwsException("InvalidParameterException", "addonName is required", 400);
        }

        String storageKey = prefix(cluster) + addonName.trim().toLowerCase(Locale.ROOT);
        StoredAddon stored = storage.get(storageKey)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No addon: " + addonName + " found for cluster: " + cluster.getName(), 404));

        Addon current = stored.addon();
        String newVersion = current.addonVersion();
        List<UpdateParam> params = new ArrayList<>();

        if (request != null) {
            if (request.addonVersion() != null && !request.addonVersion().isBlank()) {
                String requestedVersion = request.addonVersion().trim();
                if (!catalog.isVersionSupported(addonName, requestedVersion, cluster.getVersion())) {
                    throw new AwsException("InvalidParameterException",
                            "Addon version specified is not supported", 400);
                }
                newVersion = requestedVersion;
                params.add(new UpdateParam("Version", newVersion));
            }

            if (request.serviceAccountRoleArn() != null) {
                if (!request.serviceAccountRoleArn().isBlank()) {
                    validateRoleArn(cluster, request.serviceAccountRoleArn(), "serviceAccountRoleArn");
                }
                params.add(new UpdateParam("ServiceAccountRoleArn", request.serviceAccountRoleArn()));
            }

            if (request.resolveConflicts() != null && !request.resolveConflicts().isBlank()) {
                validateResolveConflicts(request.resolveConflicts());
                params.add(new UpdateParam("ResolveConflicts", request.resolveConflicts()));
            }

            if (request.configurationValues() != null) {
                params.add(new UpdateParam("ConfigurationValues", request.configurationValues()));
            }
        }

        String updatedRoleArn = request != null && request.serviceAccountRoleArn() != null
                ? (request.serviceAccountRoleArn().isBlank() ? null : request.serviceAccountRoleArn())
                : current.serviceAccountRoleArn();
        String updatedConfig = request != null && request.configurationValues() != null
                ? request.configurationValues()
                : current.configurationValues();

        List<String> updatedAssociations = current.podIdentityAssociations();
        if (request != null && request.podIdentityAssociations() != null) {
            if (podIdentityAssociations != null && current.podIdentityAssociations() != null) {
                for (String arn : current.podIdentityAssociations()) {
                    deleteAssociationSilently(cluster, arn);
                }
            }
            updatedAssociations = createOrLinkPodIdentityAssociations(cluster, addonName, request.podIdentityAssociations());
            params.add(new UpdateParam("PodIdentityAssociations", request.podIdentityAssociations().toString()));
        }

        double now = Instant.now().toEpochMilli() / 1000.0;
        Addon updatedAddon = new Addon(
                current.addonArn(),
                current.addonName(),
                newVersion,
                current.clusterName(),
                "ACTIVE",
                current.health(),
                current.createdAt(),
                now,
                updatedRoleArn,
                updatedConfig,
                current.tags(),
                updatedAssociations,
                current.owner(),
                current.publisher()
        );

        String token = request != null && request.clientRequestToken() != null
                ? request.clientRequestToken() : stored.clientRequestToken();
        storage.put(storageKey, new StoredAddon(updatedAddon, token));
        LOG.infov("Updated EKS addon {0} on cluster {1}", addonName, cluster.getName());

        Update update = new Update(
                UUID.randomUUID().toString(),
                "Successful",
                "AddonUpdate",
                params,
                now,
                List.of()
        );
        updatesStorage.put(prefix(cluster) + update.id(), new StoredUpdate(update, addonName));
        return update;
    }

    public synchronized Addon delete(Cluster cluster, String addonName, boolean preserve) {
        requireActiveCluster(cluster);
        if (addonName == null || addonName.isBlank()) {
            throw new AwsException("InvalidParameterException", "addonName is required", 400);
        }

        String storageKey = prefix(cluster) + addonName.trim().toLowerCase(Locale.ROOT);
        StoredAddon stored = storage.get(storageKey)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No addon: " + addonName + " found for cluster: " + cluster.getName(), 404));

        storage.delete(storageKey);
        if (preserve) {
            LOG.infov("Deleted EKS addon {0} on cluster {1} (preserve=true)", addonName, cluster.getName());
        } else {
            if (podIdentityAssociations != null && stored.addon().podIdentityAssociations() != null) {
                for (String arn : stored.addon().podIdentityAssociations()) {
                    deleteAssociationSilently(cluster, arn);
                }
            }
            LOG.infov("Deleted EKS addon {0} on cluster {1} (preserve=false)", addonName, cluster.getName());
        }

        Addon current = stored.addon();
        return new Addon(
                current.addonArn(),
                current.addonName(),
                current.addonVersion(),
                current.clusterName(),
                "DELETING",
                current.health(),
                current.createdAt(),
                Instant.now().toEpochMilli() / 1000.0,
                current.serviceAccountRoleArn(),
                current.configurationValues(),
                current.tags(),
                current.podIdentityAssociations(),
                current.owner(),
                current.publisher()
        );
    }

    public synchronized void deleteClusterAddons(Cluster cluster) {
        if (cluster != null) {
            String p = prefix(cluster);
            storage.keys().stream().filter(k -> k.startsWith(p)).toList().forEach(storage::delete);
            updatesStorage.keys().stream().filter(k -> k.startsWith(p)).toList().forEach(updatesStorage::delete);
            LOG.infov("Purged all addons and updates for cluster {0}", cluster.getName());
        }
    }

    public Update describeUpdate(Cluster cluster, String updateId, String addonName) {
        requireActiveCluster(cluster);
        if (updateId == null || updateId.isBlank()) {
            throw new AwsException("InvalidParameterException", "updateId is required", 400);
        }
        StoredUpdate stored = updatesStorage.get(prefix(cluster) + updateId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No update: " + updateId + " found for cluster: " + cluster.getName(), 404));
        if (addonName != null && !addonName.isBlank()
                && stored.addonName() != null && !stored.addonName().equalsIgnoreCase(addonName.trim())) {
            throw new AwsException("ResourceNotFoundException",
                    "No update: " + updateId + " found for addon: " + addonName, 404);
        }
        return stored.update();
    }

    public AddonVersionsPage describeAddonVersions(String addonName, String kubernetesVersion,
                                                  Integer maxResults, String nextToken,
                                                  List<String> publishers, List<String> types, List<String> owners) {
        List<AddonInfo> filtered = catalog.describeVersions(addonName, kubernetesVersion, publishers, types, owners);
        PaginatedResult<AddonInfo> result = Pagination.paginate(
                filtered,
                AddonInfo::addonName,
                maxResults,
                nextToken,
                DEFAULT_PAGE_SIZE,
                MAX_PAGE_SIZE,
                "InvalidParameterException"
        );
        return new AddonVersionsPage(result.items(), result.nextToken());
    }

    private List<String> createOrLinkPodIdentityAssociations(Cluster cluster,
                                                             String addonName,
                                                             List<AddonPodIdentityAssociation> associations) {
        if (associations == null || associations.isEmpty()) {
            return List.of();
        }
        List<String> arns = new ArrayList<>();
        String namespace = resolveAddonNamespace(addonName);
        for (AddonPodIdentityAssociation assoc : associations) {
            if (assoc.roleArn() == null || assoc.roleArn().isBlank()
                    || assoc.serviceAccount() == null || assoc.serviceAccount().isBlank()) {
                throw new AwsException("InvalidParameterException",
                        "roleArn and serviceAccount are required for podIdentityAssociations", 400);
            }
            validateRoleArn(cluster, assoc.roleArn(), "roleArn");

            if (podIdentityAssociations != null) {
                CreatePodIdentityAssociationRequest req = new CreatePodIdentityAssociationRequest(
                        cluster.getName(),
                        namespace,
                        assoc.serviceAccount(),
                        assoc.roleArn(),
                        null,
                        Map.of(),
                        null,
                        null,
                        null
                );
                PodIdentityAssociation created = podIdentityAssociations.create(cluster, req);
                arns.add(created.associationArn());
            } else {
                String[] clusterArn = cluster.getArn().split(":", 6);
                String assocId = "a-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
                String arn = "arn:" + clusterArn[1] + ":eks:" + clusterArn[3] + ":"
                        + clusterArn[4] + ":podidentityassociation/" + cluster.getName() + "/" + assocId;
                arns.add(arn);
            }
        }
        return arns;
    }

    private void deleteAssociationSilently(Cluster cluster, String associationArn) {
        if (associationArn == null || associationArn.isBlank() || podIdentityAssociations == null) {
            return;
        }
        String associationId = associationArn.substring(associationArn.lastIndexOf('/') + 1);
        try {
            podIdentityAssociations.delete(cluster, associationId);
        } catch (AwsException e) {
            LOG.debugf("Ignored error deleting pod identity association %s: %s", associationId, e.getMessage());
        }
    }

    private static String resolveAddonNamespace(String addonName) {
        return "kube-system";
    }

    private static String prefix(Cluster cluster) {
        return cluster.getArn() + "/" + Objects.toString(cluster.getCreatedAt()) + "/";
    }

    private static String generateAddonArn(Cluster cluster, String addonName) {
        String[] clusterArn = cluster.getArn().split(":", 6);
        return "arn:" + clusterArn[1] + ":eks:" + clusterArn[3] + ":"
                + clusterArn[4] + ":addon/" + cluster.getName() + "/" + addonName + "/" + UUID.randomUUID();
    }

    private static void requireActiveCluster(Cluster cluster) {
        if (cluster == null) {
            throw new AwsException("ResourceNotFoundException", "Cluster not found", 404);
        }
        if (cluster.getStatus() != ClusterStatus.ACTIVE) {
            throw new AwsException("InvalidRequestException",
                    "Cluster must be ACTIVE, but was " + cluster.getStatus(), 400);
        }
    }

    private void validateRoleArn(Cluster cluster, String roleArn, String fieldName) {
        if (roleArn == null || roleArn.isBlank()) {
            throw new AwsException("InvalidParameterException", fieldName + " is required", 400);
        }
        String[] arn = roleArn.split(":", 6);
        String[] clusterArn = cluster.getArn().split(":", 6);
        if (arn.length != 6 || !"arn".equals(arn[0]) || !arn[1].equals(clusterArn[1])
                || !"iam".equals(arn[2]) || !arn[3].isEmpty() || !arn[4].matches("[0-9]{12}")
                || !arn[5].startsWith("role/") || arn[5].startsWith("role/aws-service-role/")
                || roleArn.endsWith("/")) {
            throw new AwsException("InvalidParameterException", fieldName + " must identify an IAM role", 400);
        }
        if (iam != null) {
            String roleName = roleArn.substring(roleArn.lastIndexOf('/') + 1);
            iam.findRole(arn[4], roleName)
                    .filter(role -> roleArn.equals(role.getArn()))
                    .orElseThrow(() -> new AwsException("InvalidParameterException", "Role not found: " + roleArn, 400));
        }
    }

    private static void validateResolveConflicts(String resolveConflicts) {
        String upper = resolveConflicts.toUpperCase(Locale.ROOT);
        if (!"OVERWRITE".equals(upper) && !"NONE".equals(upper) && !"PRESERVE".equals(upper)) {
            throw new AwsException("InvalidParameterException",
                    "Unsupported resolveConflicts value: " + resolveConflicts, 400);
        }
    }

    private static void validateTags(Map<String, String> tags) {
        if (tags == null) {
            return;
        }
        if (tags.size() > 50) {
            throw new AwsException("InvalidParameterException", "Too many tags: maximum is 50", 400);
        }
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || key.length() > 128) {
                throw new AwsException("InvalidParameterException", "Invalid tag key: " + key, 400);
            }
            if (value == null || value.length() > 256) {
                throw new AwsException("InvalidParameterException", "Invalid tag value: " + value, 400);
            }
        }
    }

    private static boolean parametersMatch(Addon existing, CreateAddonRequest request) {
        Map<String, String> existingTags = existing.tags() != null ? existing.tags() : Map.of();
        Map<String, String> requestTags = request.tags() != null ? request.tags() : Map.of();
        return Objects.equals(existing.serviceAccountRoleArn(), request.serviceAccountRoleArn())
                && Objects.equals(existing.configurationValues(), request.configurationValues())
                && (request.addonVersion() == null || request.addonVersion().isBlank()
                        || Objects.equals(existing.addonVersion(), request.addonVersion()))
                && Objects.equals(existingTags, requestTags);
    }
}
