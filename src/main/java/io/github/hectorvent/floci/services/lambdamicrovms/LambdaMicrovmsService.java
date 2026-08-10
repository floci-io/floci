package io.github.hectorvent.floci.services.lambdamicrovms;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * In-memory state for AWS Lambda MicroVMs ("Lambda Microvms" apiVersion
 * 2025-09-09) and network connectors ("Lambda Core" apiVersion 2026-04-30).
 *
 * <p>Scope is CloudFormation-sufficient: images with a version/build lifecycle
 * that converges immediately, basic MicroVM CRUD, and network connectors.
 * Tokens, per-VM endpoints, idle timers, and drift behaviors are out of scope
 * — the m80 emulator owns full fidelity; this module exists so
 * {@code AWS::Lambda::MicrovmImage} and {@code AWS::Lambda::NetworkConnector}
 * provision through the CloudFormation engine and SDK clients can exercise the
 * basic control plane locally.</p>
 *
 * <p>State enums mirror the service model: image {@code CREATING → CREATED},
 * version/build {@code SUCCESSFUL}, VM {@code PENDING → RUNNING →
 * TERMINATING → TERMINATED}, connector {@code PENDING → ACTIVE}. Mutations
 * return the transient state and settle instantly, so a follow-up read
 * observes the resting state.</p>
 */
@ApplicationScoped
public class LambdaMicrovmsService {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9-_]{1,64}");
    private static final int MAX_CONNECTOR_SUBNETS = 16;

    private final RegionResolver regionResolver;
    private final StorageFactory storageFactory;

    /**
     * Keyed {@code region/id}. The account is not part of the key here: the backend prefixes every
     * key with the caller's account and strips it again on read, which is the same account the
     * {@link RegionResolver} resolves, so two accounts sharing a region still cannot see each
     * other's images, MicroVMs or connectors.
     */
    private Map<String, MicrovmImage> images = new ConcurrentHashMap<>();
    private Map<String, Microvm> microvms = new ConcurrentHashMap<>();
    private Map<String, NetworkConnector> connectors = new ConcurrentHashMap<>();

    @Inject
    public LambdaMicrovmsService(RegionResolver regionResolver, StorageFactory storageFactory) {
        this.regionResolver = regionResolver;
        this.storageFactory = storageFactory;
    }

    @PostConstruct
    void init() {
        initializeStorage();
    }

    /**
     * MicroVMs are delivered as part of the lambda service, so they share its storage key and
     * therefore its configured mode and flush interval rather than declaring their own.
     */
    void initializeStorage() {
        if (storageFactory == null) {
            return; // keeps non-CDI unit tests working
        }
        this.images = new StorageBackedMap<>(storageFactory.create("lambda",
                "lambda-microvm-images.json", new TypeReference<Map<String, MicrovmImage>>() {}));
        this.microvms = new StorageBackedMap<>(storageFactory.create("lambda",
                "lambda-microvms.json", new TypeReference<Map<String, Microvm>>() {}));
        this.connectors = new StorageBackedMap<>(storageFactory.create("lambda",
                "lambda-network-connectors.json", new TypeReference<Map<String, NetworkConnector>>() {}));
    }

    // ---------------------------------------------------------------- images

    @RegisterForReflection
    public static final class MicrovmImage {
        public String name;
        public String imageArn;
        public String state;
        public String description;
        public String baseImageArn;
        public String baseImageVersion;
        public String buildRoleArn;
        public String codeArtifactUri;
        public String latestActiveImageVersion;
        public Instant createdAt;
        public Instant updatedAt;
        public final Map<String, String> tags = new LinkedHashMap<>();
        public final List<MicrovmImageVersion> versions = new ArrayList<>();
    }

    @RegisterForReflection
    public static final class MicrovmImageVersion {
        public String imageVersion;
        public String state;
        public String status;
        public Instant createdAt;
        public final List<MicrovmBuild> builds = new ArrayList<>();
    }

    @RegisterForReflection
    public static final class MicrovmBuild {
        public String buildId;
        public String buildState;
        public Instant createdAt;
        /** Graviton generation. The service builds for more than one. */
        public String chipsetGeneration;
    }

    public MicrovmImage createImage(String region, String accountId, String name,
                                    String baseImageArn, String buildRoleArn,
                                    String codeArtifactUri, String description) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("ValidationException",
                    "name must match [a-zA-Z0-9-_]{1,64}", 400);
        }
        if (baseImageArn == null || buildRoleArn == null || codeArtifactUri == null) {
            throw new AwsException("ValidationException",
                    "baseImageArn, buildRoleArn and codeArtifact.uri are required", 400);
        }
        MicrovmImage existing = images.get(key(region, name));
        if (existing != null && existing.versions.stream()
                .anyMatch(v -> "SUCCESSFUL".equals(v.state))) {
            // A rebuild of an existing image mints a new version.
            addVersion(existing);
            persist(images, region, name, existing);
            return existing;
        }
        MicrovmImage image = new MicrovmImage();
        image.name = name;
        image.imageArn = "arn:aws:lambda:" + region + ":" + accountId + ":microvm-image:" + name;
        image.state = "CREATING";
        image.description = description;
        image.baseImageArn = baseImageArn;
        image.baseImageVersion = "1.0";
        image.buildRoleArn = buildRoleArn;
        image.codeArtifactUri = codeArtifactUri;
        image.createdAt = Instant.now();
        image.updatedAt = image.createdAt;
        addVersion(image);
        images.put(key(region, name), image);
        return image;
    }

    /** The Graviton generations every version is built for, newest first. */
    private static final List<String> CHIPSET_GENERATIONS = List.of("4", "3");

    /** Adds a converged version+builds and settles the image to CREATED. */
    private MicrovmImage addVersion(MicrovmImage image) {
        MicrovmImageVersion version = new MicrovmImageVersion();
        version.imageVersion = (image.versions.size() + 1) + ".0";
        version.state = "SUCCESSFUL";
        version.status = "ACTIVE";
        version.createdAt = Instant.now();
        // A version is built once per Graviton generation the service targets,
        // newest first. Recorded live: two builds per version, not one.
        for (String generation : CHIPSET_GENERATIONS) {
            MicrovmBuild build = new MicrovmBuild();
            build.buildId = UUID.randomUUID().toString();
            build.buildState = "SUCCESSFUL";
            build.createdAt = version.createdAt;
            build.chipsetGeneration = generation;
            version.builds.add(build);
        }
        image.versions.add(version);
        image.latestActiveImageVersion = version.imageVersion;
        image.state = "CREATED";
        image.updatedAt = Instant.now();
        return image;
    }


    /**
     * The live service identifies images and connectors by ARN in URI paths
     * (a bare name gets 400 "Invalid ARN format"; recorded 2026-07-29).
     * Accept both: unwrap an ARN to its trailing resource id, pass bare
     * ids/names through — CloudFormation physical ids stay name-based.
     */
    private static String unwrap(String identifier) {
        if (identifier != null && identifier.startsWith("arn:")) {
            return identifier.substring(identifier.lastIndexOf(':') + 1);
        }
        return identifier;
    }

    public MicrovmImage getImage(String region, String name) {
        MicrovmImage image = images.get(key(region, unwrap(name)));
        if (image == null) {
            throw new AwsException("ResourceNotFoundException",
                    "MicroVM image not found: " + name, 404);
        }
        return image;
    }

    public List<MicrovmImage> listImages(String region) {
        return in(images, region)
                .sorted(Comparator.comparing(i -> i.name))
                .toList();
    }

    public MicrovmImage updateImage(String region, String name, String baseImageArn,
                                    String buildRoleArn, String codeArtifactUri, String description) {
        MicrovmImage image = getImage(region, name);
        if (baseImageArn == null || buildRoleArn == null || codeArtifactUri == null) {
            // Recorded live: the PUT is a full replace, not a patch.
            throw new AwsException("ValidationException",
                    "validation errors detected: baseImageArn, buildRoleArn and codeArtifact must not be null", 400);
        }
        image.baseImageArn = baseImageArn;
        image.buildRoleArn = buildRoleArn;
        image.codeArtifactUri = codeArtifactUri;
        image.description = description;
        // A full-replace update rebuilds: it mints a new version and walks the
        // image to UPDATED rather than back to CREATED. Recorded live, and a
        // consumer polling for UPDATED after a PUT never converges without it.
        addVersion(image);
        image.state = "UPDATED";
        image.updatedAt = Instant.now();
        persist(images, region, image.name, image);
        return image;
    }

    public void deleteImage(String region, String name) {
        MicrovmImage image = getImage(region, name);
        boolean inUse = in(microvms, region)
                .anyMatch(vm -> image.imageArn.equals(vm.imageArn)
                        && !"TERMINATED".equals(vm.state));
        if (inUse) {
            // Recorded live: 400, exact message.
            throw new AwsException("ValidationException",
                    "Cannot delete microvm image with running microvms.", 400);
        }
        image.state = "DELETED";
        images.remove(key(region, unwrap(name)));
    }

    public MicrovmImageVersion getVersion(String region, String name, String imageVersion) {
        MicrovmImage image = getImage(region, name);
        return image.versions.stream()
                .filter(v -> v.imageVersion.equals(imageVersion))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "MicroVM image version not found: " + name + "/" + imageVersion, 404));
    }

    public MicrovmImageVersion updateVersionStatus(String region, String name,
                                                   String imageVersion, String status) {
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw new AwsException("ValidationException",
                    "status must be ACTIVE or INACTIVE", 400);
        }
        MicrovmImage image = getImage(region, name);
        MicrovmImageVersion version = getVersion(region, name, imageVersion);
        version.status = status;
        persist(images, region, image.name, image);
        return version;
    }

    public void deleteVersion(String region, String name, String imageVersion) {
        MicrovmImage image = getImage(region, name);
        MicrovmImageVersion version = getVersion(region, name, imageVersion);
        image.versions.remove(version);
        if (imageVersion.equals(image.latestActiveImageVersion)) {
            image.latestActiveImageVersion = image.versions.isEmpty()
                    ? null
                    : image.versions.get(image.versions.size() - 1).imageVersion;
        }
        persist(images, region, image.name, image);
    }

    // -------------------------------------------------------- managed images

    /** The AWS-managed base image catalog, seeded with the one documented entry. */
    public List<Map<String, Object>> listManagedImages(String region) {
        return List.of(managedImage(region));
    }

    public List<Map<String, Object>> listManagedImageVersions(String region, String identifier) {
        if (!identifier.contains("al2023-1")) {
            throw new AwsException("ResourceNotFoundException",
                    "Managed MicroVM image not found: " + identifier, 404);
        }
        return List.of(managedImage(region));
    }

    private Map<String, Object> managedImage(String region) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("imageArn", "arn:aws:lambda:" + region + ":aws:microvm-image:al2023-1");
        entry.put("createdAt", 1781833144.754d);
        entry.put("updatedAt", 1784165932.388d);
        return entry;
    }

    // ------------------------------------------------------------------- vms

    @RegisterForReflection
    public static final class Microvm {
        public String microvmId;
        public String state;
        public String imageArn;
        public String imageVersion;
        public String endpoint;
        public String stateReason;
        public Instant startedAt;
        public Instant terminatedAt;
    }

    public Microvm runMicrovm(String region, String accountId, String imageIdentifier) {
        if (imageIdentifier == null || imageIdentifier.isBlank()) {
            throw new AwsException("ValidationException", "imageIdentifier is required", 400);
        }
        MicrovmImage image = images.get(key(region, unwrap(imageIdentifier)));
        if (image == null) {
            throw new AwsException("ResourceNotFoundException",
                    "MicroVM image not found: " + imageIdentifier, 404);
        }
        Microvm vm = new Microvm();
        vm.microvmId = "microvm-" + UUID.randomUUID();
        vm.imageArn = image.imageArn;
        vm.imageVersion = image.latestActiveImageVersion;
        vm.endpoint = UUID.randomUUID() + ".lambda-microvm." + region + ".on.aws";
        vm.startedAt = Instant.now();
        // Settle instantly: the response carries PENDING, the next read RUNNING.
        vm.state = "RUNNING";
        microvms.put(key(region, vm.microvmId), vm);
        Microvm response = new Microvm();
        response.microvmId = vm.microvmId;
        response.state = "PENDING";
        response.imageArn = vm.imageArn;
        response.imageVersion = vm.imageVersion;
        response.endpoint = vm.endpoint;
        response.startedAt = vm.startedAt;
        return response;
    }

    public Microvm getMicrovm(String region, String microvmId) {
        Microvm vm = microvms.get(key(region, unwrap(microvmId)));
        if (vm == null) {
            throw new AwsException("ResourceNotFoundException",
                    "MicroVM not found: " + microvmId, 404);
        }
        return vm;
    }

    public List<Microvm> listMicrovms(String region) {
        return in(microvms, region)
                .sorted(Comparator.comparing(vm -> vm.microvmId))
                .toList();
    }

    public void terminateMicrovm(String region, String microvmId) {
        Microvm vm = getMicrovm(region, microvmId);
        if ("TERMINATED".equals(vm.state)) {
            // Recorded live: terminal-state mutations are 400 ValidationException.
            throw new AwsException("ValidationException",
                    "The MicroVM " + vm.microvmId + " has been terminated and its state cannot be changed.", 400);
        }
        vm.state = "TERMINATED";
        vm.stateReason = "Success.";
        vm.terminatedAt = Instant.now();
        persist(microvms, region, vm.microvmId, vm);
    }

    // ------------------------------------------------------------ connectors

    @RegisterForReflection
    public static final class NetworkConnector {
        public String id;
        public String name;
        public String arn;
        public String state;
        public String operatorRole;
        public List<String> subnetIds = List.of();
        public List<String> securityGroupIds = List.of();
        // Both arrive on the request and were previously validated and then
        // dropped, so a client could not read back what it had just set.
        public String networkProtocol;
        public List<String> associatedComputeResourceTypes = List.of();
        public Instant lastModified;
        public String stateReason;
        public String lastUpdateStatus;
        public String lastUpdateStatusReason;
        public final Map<String, String> tags = new LinkedHashMap<>();
    }

    public NetworkConnector createConnector(String region, String accountId, String name,
                                            List<String> subnetIds, List<String> securityGroupIds,
                                            String operatorRole, String clientToken,
                                            List<String> computeResourceTypes, String networkProtocol) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "Name is required", 400);
        }
        // The three checks below are recorded live behavior; the Smithy model
        // marks all three members optional.
        if (clientToken == null || clientToken.isBlank()) {
            throw new AwsException("InvalidParameterValueException",
                    "ClientToken is a required field", 400);
        }
        if (computeResourceTypes == null || computeResourceTypes.isEmpty()) {
            throw new AwsException("InvalidParameterValueException",
                    "AssociatedComputeResourceTypes is required for VPC_EGRESS connector type", 400);
        }
        if (networkProtocol == null || networkProtocol.isBlank()) {
            throw new AwsException("InvalidParameterValueException",
                    "NetworkProtocol cannot be null or empty for VPC_EGRESS connector", 400);
        }
        if (operatorRole == null || operatorRole.isBlank()) {
            throw new AwsException("InvalidParameterValueException",
                    "NetworkConnectorOperatorRole is required for VPC_EGRESS connector type", 400);
        }
        if (subnetIds == null || subnetIds.isEmpty() || subnetIds.size() > MAX_CONNECTOR_SUBNETS) {
            throw new AwsException("InvalidParameterValueException",
                    "SubnetIds must contain between 1 and " + MAX_CONNECTOR_SUBNETS + " entries", 400);
        }
        boolean duplicate = in(connectors, region)
                .anyMatch(c -> name.equals(c.name));
        if (duplicate) {
            throw new AwsException("ResourceConflictException",
                    "A network connector with this name already exists: " + name, 409);
        }
        NetworkConnector connector = new NetworkConnector();
        connector.id = "nc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        connector.name = name;
        connector.arn = "arn:aws:lambda:" + region + ":" + accountId + ":network-connector:" + connector.id;
        connector.state = "PENDING";
        connector.operatorRole = operatorRole;
        connector.subnetIds = List.copyOf(subnetIds);
        connector.securityGroupIds = securityGroupIds == null ? List.of() : List.copyOf(securityGroupIds);
        connector.networkProtocol = networkProtocol;
        connector.associatedComputeResourceTypes =
                computeResourceTypes == null ? List.of() : List.copyOf(computeResourceTypes);
        connector.lastModified = Instant.now();
        // Recorded on the first read of a freshly created connector.
        connector.stateReason = "Initial creation";
        // Settle instantly, as with VMs.
        connector.state = "ACTIVE";
        connectors.put(key(region, connector.id), connector);
        // The create response is a snapshot taken before the instant settle,
        // so it reports PENDING while the stored connector is already ACTIVE.
        // It has to carry every member the stored one does, or a client reads
        // back less than it just sent.
        NetworkConnector response = new NetworkConnector();
        response.id = connector.id;
        response.name = connector.name;
        response.arn = connector.arn;
        response.state = "PENDING";
        response.operatorRole = connector.operatorRole;
        response.subnetIds = connector.subnetIds;
        response.securityGroupIds = connector.securityGroupIds;
        response.networkProtocol = connector.networkProtocol;
        response.associatedComputeResourceTypes = connector.associatedComputeResourceTypes;
        response.lastModified = connector.lastModified;
        response.stateReason = connector.stateReason;
        return response;
    }

    public NetworkConnector getConnector(String region, String id) {
        NetworkConnector connector = connectors.get(key(region, unwrap(id)));
        if (connector == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Network connector not found: " + id, 404);
        }
        return connector;
    }

    public List<NetworkConnector> listConnectors(String region) {
        return in(connectors, region)
                .sorted(Comparator.comparing(c -> c.id))
                .toList();
    }

    public NetworkConnector updateConnector(String region, String id,
                                            List<String> subnetIds, List<String> securityGroupIds) {
        NetworkConnector connector = getConnector(region, id);
        if (subnetIds != null && !subnetIds.isEmpty()) {
            if (subnetIds.size() > MAX_CONNECTOR_SUBNETS) {
                throw new AwsException("InvalidParameterValueException",
                        "SubnetIds must contain between 1 and " + MAX_CONNECTOR_SUBNETS + " entries", 400);
            }
            connector.subnetIds = List.copyOf(subnetIds);
            connector.lastModified = Instant.now();
            connector.lastUpdateStatus = "Successful";
            connector.lastUpdateStatusReason = "No configuration changes detected";
        }
        if (securityGroupIds != null) {
            connector.securityGroupIds = List.copyOf(securityGroupIds);
        }
        persist(connectors, region, connector.id, connector);
        return connector;
    }

    public void deleteConnector(String region, String id) {
        getConnector(region, id);
        connectors.remove(key(region, unwrap(id)));
    }

    // ------------------------------------------------------------------ tags

    /** True when the ARN names a MicroVM-family resource this service owns. */
    public static boolean ownsArn(String arn) {
        return arn != null && (arn.contains(":microvm-image:")
                || arn.contains(":microvm:")
                || arn.contains(":network-connector:"));
    }

    public Map<String, String> listTags(String region, String arn) {
        return taggable(region, arn);
    }

    public void tagResource(String region, String arn, Map<String, String> tags) {
        mutateTags(region, arn, target -> target.putAll(tags));
    }

    public void untagResource(String region, String arn, List<String> tagKeys) {
        mutateTags(region, arn, target -> {
            if (tagKeys != null) {
                tagKeys.forEach(target::remove);
            }
        });
    }

    /**
     * Applies a tag mutation and writes the owning resource back. The tag map is a field of the
     * stored object, so mutating it alone would never reach the backend.
     */
    private void mutateTags(String region, String arn, Consumer<Map<String, String>> mutation) {
        if (arn.contains(":microvm-image:")) {
            MicrovmImage image = getImage(region, arn.substring(arn.lastIndexOf(':') + 1));
            mutation.accept(image.tags);
            persist(images, region, image.name, image);
            return;
        }
        if (arn.contains(":network-connector:")) {
            NetworkConnector connector = getConnector(region, arn.substring(arn.lastIndexOf(':') + 1));
            mutation.accept(connector.tags);
            persist(connectors, region, connector.id, connector);
            return;
        }
        throw new AwsException("ResourceNotFoundException",
                "Resource not found: " + arn, 404);
    }

    private Map<String, String> taggable(String region, String arn) {
        if (arn.contains(":microvm-image:")) {
            String name = arn.substring(arn.lastIndexOf(':') + 1);
            return getImage(region, name).tags;
        }
        if (arn.contains(":network-connector:")) {
            String id = arn.substring(arn.lastIndexOf(':') + 1);
            return getConnector(region, id).tags;
        }
        throw new AwsException("ResourceNotFoundException",
                "Resource not found: " + arn, 404);
    }

    // ----------------------------------------------------------------- state

    /**
     * The stored key for a resource. The account half is added by the backend, so callers only
     * supply the region, and a listing filters on the same prefix the backend hands back.
     */
    private static String key(String region, String id) {
        return region + "/" + id;
    }

    private static <V> Stream<V> in(Map<String, V> store, String region) {
        String prefix = region + "/";
        return store.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue);
    }

    /**
     * Re-persists an entity whose fields were mutated in place. {@link StorageBackedMap} only marks
     * the backend dirty on put and remove, so a mutation applied to a value from get or a listing
     * would be dropped by the periodic flush and lost on restart.
     */
    private static <V> void persist(Map<String, V> store, String region, String id, V value) {
        store.put(key(region, id), value);
    }
}
