package io.github.hectorvent.floci.services.lambdamicrovms;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.LambdaArnUtils;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    private static final Pattern CONNECTOR_VERSION = Pattern.compile("[1-9][0-9]{0,17}");
    /** The one AWS-managed base image the catalog serves. */
    private static final String MANAGED_IMAGE = "al2023-1";

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
        image.imageArn = AwsArnUtils.Arn.of("lambda", region, accountId, "microvm-image:" + name).toString();
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
        if (!namesManagedImage(identifier)) {
            throw new AwsException("ResourceNotFoundException",
                    "Managed MicroVM image not found: " + identifier, 404);
        }
        return List.of(managedImage(region));
    }

    /** True when {@code identifier} names the managed image, as {@link #listManagedImageVersions} reads it. */
    public static boolean namesManagedImage(String identifier) {
        return identifier.contains(MANAGED_IMAGE);
    }

    /** The managed image's ARN in {@code region}, owned by {@code aws} rather than the caller's account. */
    public static String managedImageArn(String region) {
        return AwsArnUtils.Arn.of("lambda", region, "aws", "microvm-image:" + MANAGED_IMAGE).toString();
    }

    private Map<String, Object> managedImage(String region) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("imageArn", managedImageArn(region));
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
        return findMicrovm(region, microvmId).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "MicroVM not found: " + microvmId, 404));
    }

    /** The MicroVM of the caller's account that {@code microvmId} names in {@code region}, looked up as every route does. */
    public Optional<Microvm> findMicrovm(String region, String microvmId) {
        return Optional.ofNullable(microvms.get(key(region, unwrap(microvmId))));
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
        public long latestVersion = 1;
        /** Oldest first. The latest one has the configuration above. */
        public List<NetworkConnectorVersion> versions = new ArrayList<>();
        public final Map<String, String> tags = new LinkedHashMap<>();
    }

    @RegisterForReflection
    public static final class NetworkConnectorVersion {
        public long version;
        public String state;
        public String stateReason;
        public String operatorRole;
        public List<String> subnetIds = List.of();
        public List<String> securityGroupIds = List.of();
        public String networkProtocol;
        public List<String> associatedComputeResourceTypes = List.of();
        public Instant lastModified;
    }

    /** A connector and the version an identifier names, the latest one when it names none. */
    public record ConnectorVersion(NetworkConnector connector, NetworkConnectorVersion version, boolean qualified) {

        public String versionArn() {
            return LambdaArnUtils.networkConnectorVersionArn(connector.arn, version.version);
        }
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
        connector.arn = AwsArnUtils.Arn.of("lambda", region, accountId, "network-connector:" + connector.id).toString();
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
        connector.versions.add(snapshot(connector, 1));
        connectors.put(key(region, connector.id), connector);
        // The create response is a snapshot taken before the instant settle,
        // so it reports PENDING while the stored connector is already ACTIVE.
        // It has to carry every member the stored one does, or a client reads
        // back less than it just sent.
        NetworkConnector response = responseOf(connector);
        response.state = "PENDING";
        return response;
    }

    private static NetworkConnector responseOf(NetworkConnector connector) {
        NetworkConnector response = new NetworkConnector();
        response.id = connector.id;
        response.name = connector.name;
        response.arn = connector.arn;
        response.state = connector.state;
        response.operatorRole = connector.operatorRole;
        response.subnetIds = connector.subnetIds;
        response.securityGroupIds = connector.securityGroupIds;
        response.networkProtocol = connector.networkProtocol;
        response.associatedComputeResourceTypes = connector.associatedComputeResourceTypes;
        response.lastModified = connector.lastModified;
        response.stateReason = connector.stateReason;
        response.lastUpdateStatus = connector.lastUpdateStatus;
        response.lastUpdateStatusReason = connector.lastUpdateStatusReason;
        response.latestVersion = connector.latestVersion;
        return response;
    }

    private static NetworkConnectorVersion snapshot(NetworkConnector connector, long version) {
        NetworkConnectorVersion out = new NetworkConnectorVersion();
        out.version = version;
        out.state = connector.state;
        out.stateReason = connector.stateReason;
        out.operatorRole = connector.operatorRole;
        out.subnetIds = connector.subnetIds;
        out.securityGroupIds = connector.securityGroupIds;
        out.networkProtocol = connector.networkProtocol;
        out.associatedComputeResourceTypes = connector.associatedComputeResourceTypes;
        out.lastModified = connector.lastModified;
        return out;
    }

    public NetworkConnector getConnector(String region, String identifier) {
        return getConnectorVersion(region, identifier).connector();
    }

    /**
     * The identifier is an id, a name or an ARN, each with an optional {@code :<version>}. AWS names
     * the full ARN, with the version asked for, when either is missing.
     */
    public synchronized ConnectorVersion getConnectorVersion(String region, String identifier) {
        ConnectorRef ref = ConnectorRef.parse(identifier);
        NetworkConnector connector = find(region, ref);
        long wanted = ref.version() == null ? connector.latestVersion : ref.version();
        NetworkConnectorVersion found = connector.versions.stream()
                .filter(v -> v.version == wanted)
                .findFirst()
                .orElseThrow(() -> connectorNotFound(LambdaArnUtils.networkConnectorVersionArn(connector.arn, wanted)));
        return new ConnectorVersion(connector, found, ref.version() != null);
    }

    /** Oldest first. */
    public synchronized List<NetworkConnectorVersion> listConnectorVersions(String region, String identifier) {
        ConnectorRef ref = ConnectorRef.parse(identifier);
        if (ref.version() != null) {
            throw new AwsException("InvalidParameterValueException",
                    "Identifier must not include a version qualifier", 400);
        }
        return List.copyOf(find(region, ref).versions);
    }

    /**
     * The version a Web Function revision names by its version-qualified ARN. Only the connector id names it,
     * not its name, and only in {@code region}.
     */
    public synchronized Optional<NetworkConnectorVersion> connectorVersion(String region, String connectorArn,
                                                                         long version) {
        Optional<NetworkConnector> connector = in(connectors, region)
                .filter(c -> connectorArn.equals(c.arn))
                .findFirst();
        connector.ifPresent(LambdaMicrovmsService::backfillVersions);
        return connector.flatMap(c -> c.versions.stream().filter(v -> v.version == version).findFirst());
    }

    private record ConnectorRef(String unqualified, boolean arn, String idOrName, Long version) {

        static ConnectorRef parse(String identifier) {
            boolean arn = AwsArnUtils.isArn(identifier);
            // network-connector:<id>, with an optional :<version>
            String[] resource = arn ? AwsArnUtils.parse(identifier).resource().split(":", -1) : null;
            String unqualified = identifier;
            Long version = null;
            int colon = identifier.lastIndexOf(':');
            if (colon >= 0 && (!arn || resource.length == 3)
                    && CONNECTOR_VERSION.matcher(identifier.substring(colon + 1)).matches()) {
                unqualified = identifier.substring(0, colon);
                version = Long.parseLong(identifier.substring(colon + 1));
            }
            String idOrName = arn ? unqualified.substring(unqualified.lastIndexOf(':') + 1) : unqualified;
            return new ConnectorRef(unqualified, arn, idOrName, version);
        }
    }

    private NetworkConnector find(String region, ConnectorRef ref) {
        NetworkConnector connector = connectors.get(key(region, ref.idOrName()));
        if (connector == null) {
            connector = in(connectors, region)
                    .filter(c -> ref.idOrName().equals(c.name))
                    .findFirst()
                    .orElseThrow(() -> {
                        String arn = ref.arn() ? ref.unqualified()
                                : AwsArnUtils.Arn.of("lambda", region, regionResolver.getAccountId(),
                                "network-connector:" + ref.idOrName()).toString();
                        return connectorNotFound(ref.version() == null ? arn
                                : LambdaArnUtils.networkConnectorVersionArn(arn, ref.version()));
                    });
        }
        backfillVersions(connector);
        return connector;
    }

    /** One stored before versions has only its latest version. */
    private static void backfillVersions(NetworkConnector connector) {
        if (connector.versions.isEmpty()) {
            connector.versions.add(snapshot(connector, connector.latestVersion));
        }
    }

    private static AwsException connectorNotFound(String arn) {
        return new AwsException("ResourceNotFoundException", "Network connector not found: " + arn, 404);
    }

    public List<NetworkConnector> listConnectors(String region) {
        return in(connectors, region)
                .sorted(Comparator.comparing(c -> c.id))
                .toList();
    }

    /**
     * UpdateNetworkConnector: every member is optional and a null one leaves the connector's value
     * as it is. As on AWS, an update that changes the configuration or the operator role makes the
     * next version, one that changes nothing keeps the version, and the compute resource types
     * cannot change. Updates hold the service lock, so a version names the one update that made it.
     */
    public synchronized NetworkConnector updateConnector(String region, String id,
                                                         List<String> subnetIds, List<String> securityGroupIds,
                                                         String operatorRole, String networkProtocol,
                                                         List<String> computeResourceTypes) {
        NetworkConnector connector = getConnector(region, id);
        if (computeResourceTypes != null && !computeResourceTypes.isEmpty()
                && !Set.copyOf(computeResourceTypes).equals(Set.copyOf(connector.associatedComputeResourceTypes))) {
            throw new AwsException("InvalidParameterValueException",
                    "AssociatedComputeResourceTypes cannot be changed on an existing network connector. Current: "
                    + connector.associatedComputeResourceTypes + ", requested: " + computeResourceTypes + ".", 400);
        }
        if (subnetIds != null && subnetIds.size() > MAX_CONNECTOR_SUBNETS) {
            throw new AwsException("InvalidParameterValueException",
                    "SubnetIds must contain between 1 and " + MAX_CONNECTOR_SUBNETS + " entries", 400);
        }
        List<String> newSubnets = subnetIds != null && !subnetIds.isEmpty()
                ? List.copyOf(subnetIds) : connector.subnetIds;
        List<String> newGroups = securityGroupIds != null ? List.copyOf(securityGroupIds) : connector.securityGroupIds;
        String newRole = operatorRole != null && !operatorRole.isBlank() ? operatorRole : connector.operatorRole;
        String newProtocol = networkProtocol != null && !networkProtocol.isBlank()
                ? networkProtocol : connector.networkProtocol;
        connector.lastModified = Instant.now();
        connector.lastUpdateStatus = "Successful";
        // AWS keeps the stored order when only the order differs.
        if (Set.copyOf(newSubnets).equals(Set.copyOf(connector.subnetIds))
                && Set.copyOf(newGroups).equals(Set.copyOf(connector.securityGroupIds))
                && Objects.equals(newRole, connector.operatorRole)
                && Objects.equals(newProtocol, connector.networkProtocol)) {
            connector.lastUpdateStatusReason = "No configuration changes detected";
            persist(connectors, region, connector.id, connector);
            return responseOf(connector);
        }
        connector.subnetIds = newSubnets;
        connector.securityGroupIds = newGroups;
        connector.operatorRole = newRole;
        connector.networkProtocol = newProtocol;
        connector.latestVersion++;
        connector.stateReason = "Update initiated";
        connector.lastUpdateStatusReason = "Update was successful";
        connector.versions.add(snapshot(connector, connector.latestVersion));
        persist(connectors, region, connector.id, connector);
        // Taken before the instant settle, so it reports the update in progress.
        NetworkConnector response = responseOf(connector);
        response.lastUpdateStatus = "InProgress";
        response.lastUpdateStatusReason = "Update in progress";
        return response;
    }

    /** Deletes the connector, or only the version the identifier names. Returns what was deleted. */
    public synchronized ConnectorVersion deleteConnector(String region, String identifier) {
        ConnectorVersion named = getConnectorVersion(region, identifier);
        NetworkConnector connector = named.connector();
        if (!named.qualified()) {
            connectors.remove(key(region, connector.id));
            return named;
        }
        if (named.version().version == connector.latestVersion) {
            throw new AwsException("InvalidParameterValueException",
                    "Cannot delete the latest version of a network connector.", 400);
        }
        connector.versions.remove(named.version());
        persist(connectors, region, connector.id, connector);
        return named;
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
