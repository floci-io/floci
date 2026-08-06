package io.github.hectorvent.floci.services.lightsail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class LightsailService {

    private static final String SERVICE = "lightsail";
    private static final String RESOURCE_INSTANCE = "Instance";
    private static final String RESOURCE_DISK = "Disk";
    private static final String RESOURCE_STATIC_IP = "StaticIp";
    private static final String RESOURCE_KEY_PAIR = "KeyPair";

    private final StorageBackend<String, ObjectNode> resourceStore;
    private final StorageBackend<String, ObjectNode> operationStore;
    private final ObjectMapper mapper;
    private final RegionResolver regionResolver;

    @Inject
    public LightsailService(StorageFactory storageFactory, ObjectMapper mapper, RegionResolver regionResolver) {
        this.resourceStore = storageFactory.create(SERVICE, "lightsail-resources.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.operationStore = storageFactory.create(SERVICE, "lightsail-operations.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.regionResolver = regionResolver;
    }

    public ObjectNode createInstances(String region, JsonNode request) {
        requireArray(request, "instanceNames");
        requireText(request, "availabilityZone");
        String blueprintId = requireText(request, "blueprintId");
        String bundleId = requireText(request, "bundleId");
        String availabilityZone = request.path("availabilityZone").asText();
        ArrayNode operations = mapper.createArrayNode();

        for (JsonNode nameNode : request.path("instanceNames")) {
            String name = nameNode.asText(null);
            requireName(name, "instanceNames");
            ensureNotExists(region, RESOURCE_INSTANCE, name);

            ObjectNode instance = baseResource(region, availabilityZone, RESOURCE_INSTANCE, name);
            instance.put("blueprintId", blueprintId);
            instance.put("blueprintName", blueprintName(blueprintId));
            instance.put("bundleId", bundleId);
            instance.set("addOns", mapper.createArrayNode());
            instance.put("isStaticIp", false);
            instance.put("privateIpAddress", privateIpFor(name));
            instance.put("publicIpAddress", publicIpFor(name));
            instance.set("ipv6Addresses", mapper.createArrayNode());
            instance.put("ipAddressType", request.path("ipAddressType").asText("ipv4"));
            instance.set("hardware", hardware(bundleId));
            instance.set("networking", defaultNetworking());
            instance.set("state", instanceState("running"));
            instance.put("username", defaultUsername(blueprintId));
            if (request.hasNonNull("keyPairName")) {
                instance.put("sshKeyName", request.path("keyPairName").asText());
            }
            instance.set("tags", tags(request.path("tags")));
            instance.set("metadataOptions", mapper.createObjectNode()
                    .put("state", "applied")
                    .put("httpTokens", "optional")
                    .put("httpEndpoint", "enabled")
                    .put("httpPutResponseHopLimit", 1));

            resourceStore.put(resourceKey(region, RESOURCE_INSTANCE, name), instance);
            operations.add(operation(region, RESOURCE_INSTANCE, name, "CreateInstance"));
        }

        return mapper.createObjectNode().set("operations", operations);
    }

    public ObjectNode getInstances(String region) {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode instances = response.putArray("instances");
        listResources(region, RESOURCE_INSTANCE).forEach(instances::add);
        return response;
    }

    public ObjectNode getInstance(String region, String name) {
        return mapper.createObjectNode().set("instance", requireResource(region, RESOURCE_INSTANCE, name));
    }

    public ObjectNode deleteInstance(String region, String name) {
        requireResource(region, RESOURCE_INSTANCE, name);
        resourceStore.delete(resourceKey(region, RESOURCE_INSTANCE, name));
        detachStaticIpsFrom(name);
        detachDisksFrom(name);
        return operations(region, RESOURCE_INSTANCE, name, "DeleteInstance");
    }

    public ObjectNode startInstance(String region, String name) {
        ObjectNode instance = requireResource(region, RESOURCE_INSTANCE, name);
        instance.set("state", instanceState("running"));
        resourceStore.put(resourceKey(region, RESOURCE_INSTANCE, name), instance);
        return operations(region, RESOURCE_INSTANCE, name, "StartInstance");
    }

    public ObjectNode stopInstance(String region, String name) {
        ObjectNode instance = requireResource(region, RESOURCE_INSTANCE, name);
        instance.set("state", instanceState("stopped"));
        resourceStore.put(resourceKey(region, RESOURCE_INSTANCE, name), instance);
        return operations(region, RESOURCE_INSTANCE, name, "StopInstance");
    }

    public ObjectNode rebootInstance(String region, String name) {
        ObjectNode instance = requireResource(region, RESOURCE_INSTANCE, name);
        instance.set("state", instanceState("running"));
        resourceStore.put(resourceKey(region, RESOURCE_INSTANCE, name), instance);
        return operations(region, RESOURCE_INSTANCE, name, "RebootInstance");
    }

    public ObjectNode getInstanceState(String region, String name) {
        ObjectNode instance = requireResource(region, RESOURCE_INSTANCE, name);
        return mapper.createObjectNode().set("state", instance.path("state").deepCopy());
    }

    public ObjectNode getInstancePortStates(String region, String name) {
        ObjectNode instance = requireResource(region, RESOURCE_INSTANCE, name);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode states = response.putArray("portStates");
        for (JsonNode port : instance.path("networking").path("ports")) {
            ObjectNode state = port.deepCopy();
            state.put("state", "open");
            states.add(state);
        }
        return response;
    }

    public ObjectNode putInstancePorts(String region, String name, JsonNode request, boolean merge) {
        ObjectNode instance = requireResource(region, RESOURCE_INSTANCE, name);
        ArrayNode ports = mapper.createArrayNode();
        if (merge) {
            instance.path("networking").path("ports").forEach(port -> ports.add(port.deepCopy()));
        }
        JsonNode portInfos = request.path("portInfos");
        if (portInfos.isMissingNode() && request.has("portInfo")) {
            portInfos = mapper.createArrayNode().add(request.path("portInfo"));
        }
        if (portInfos.isMissingNode() || !portInfos.isArray()) {
            throw new AwsException("InvalidInputException", "portInfo or portInfos is required", 400);
        }
        for (JsonNode portInfo : portInfos) {
            ports.add(portInfo.deepCopy());
        }
        ((ObjectNode) instance.path("networking")).set("ports", ports);
        resourceStore.put(resourceKey(region, RESOURCE_INSTANCE, name), instance);
        return mapper.createObjectNode().set("operation", operation(region, RESOURCE_INSTANCE, name,
                merge ? "OpenInstancePublicPorts" : "PutInstancePublicPorts"));
    }

    public ObjectNode closeInstancePorts(String region, String name, JsonNode request) {
        ObjectNode instance = requireResource(region, RESOURCE_INSTANCE, name);
        JsonNode target = request.path("portInfo");
        if (target.isMissingNode()) {
            throw new AwsException("InvalidInputException", "portInfo is required", 400);
        }
        ArrayNode remaining = mapper.createArrayNode();
        for (JsonNode port : instance.path("networking").path("ports")) {
            if (!samePort(port, target)) {
                remaining.add(port.deepCopy());
            }
        }
        ((ObjectNode) instance.path("networking")).set("ports", remaining);
        resourceStore.put(resourceKey(region, RESOURCE_INSTANCE, name), instance);
        return mapper.createObjectNode().set("operation", operation(region, RESOURCE_INSTANCE, name,
                "CloseInstancePublicPorts"));
    }

    public ObjectNode createDisk(String region, JsonNode request) {
        String name = requireText(request, "diskName");
        String availabilityZone = requireText(request, "availabilityZone");
        ensureNotExists(region, RESOURCE_DISK, name);

        ObjectNode disk = baseResource(region, availabilityZone, RESOURCE_DISK, name);
        disk.set("addOns", mapper.createArrayNode());
        disk.put("sizeInGb", request.path("sizeInGb").asInt(8));
        disk.put("isSystemDisk", false);
        disk.put("iops", Math.max(100, request.path("sizeInGb").asInt(8) * 3));
        disk.put("path", "/dev/xvdf");
        disk.put("state", "available");
        disk.put("isAttached", false);
        disk.put("attachmentState", "detached");
        disk.put("gbInUse", 0);
        disk.put("autoMountStatus", "NotMounted");
        disk.set("tags", tags(request.path("tags")));
        resourceStore.put(resourceKey(region, RESOURCE_DISK, name), disk);
        return operations(region, RESOURCE_DISK, name, "CreateDisk");
    }

    public ObjectNode getDisks(String region) {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode disks = response.putArray("disks");
        listResources(region, RESOURCE_DISK).forEach(disks::add);
        return response;
    }

    public ObjectNode getDisk(String region, String name) {
        return mapper.createObjectNode().set("disk", requireResource(region, RESOURCE_DISK, name));
    }

    public ObjectNode attachDisk(String region, JsonNode request) {
        String diskName = requireText(request, "diskName");
        String instanceName = requireText(request, "instanceName");
        ObjectNode disk = requireResource(region, RESOURCE_DISK, diskName);
        requireResource(region, RESOURCE_INSTANCE, instanceName);
        if (disk.path("isAttached").asBoolean(false)) {
            throw new AwsException("InvalidInputException",
                    "Disk " + diskName + " is already attached to instance "
                            + disk.path("attachedTo").asText() + ".",
                    400);
        }
        disk.put("attachedTo", instanceName);
        disk.put("isAttached", true);
        disk.put("attachmentState", "attached");
        disk.put("state", "in-use");
        disk.put("path", request.path("diskPath").asText("/dev/xvdf"));
        resourceStore.put(resourceKey(region, RESOURCE_DISK, diskName), disk);
        return operations(region, RESOURCE_DISK, diskName, "AttachDisk");
    }

    public ObjectNode detachDisk(String region, String name) {
        ObjectNode disk = requireResource(region, RESOURCE_DISK, name);
        if (!disk.path("isAttached").asBoolean(false)) {
            throw new AwsException("InvalidInputException",
                    "Disk " + name + " is not attached to any instance.",
                    400);
        }
        disk.remove("attachedTo");
        disk.put("isAttached", false);
        disk.put("attachmentState", "detached");
        disk.put("state", "available");
        resourceStore.put(resourceKey(region, RESOURCE_DISK, name), disk);
        return operations(region, RESOURCE_DISK, name, "DetachDisk");
    }

    public ObjectNode deleteDisk(String region, String name) {
        ObjectNode disk = requireResource(region, RESOURCE_DISK, name);
        if (disk.path("isAttached").asBoolean(false)) {
            throw new AwsException("InvalidInputException",
                    "Disk " + name + " is attached to instance " + disk.path("attachedTo").asText()
                            + " and cannot be deleted. Detach the disk first and then try again.",
                    400);
        }
        resourceStore.delete(resourceKey(region, RESOURCE_DISK, name));
        return operations(region, RESOURCE_DISK, name, "DeleteDisk");
    }

    public ObjectNode allocateStaticIp(String region, String name) {
        requireName(name, "staticIpName");
        ensureNotExists(region, RESOURCE_STATIC_IP, name);
        ObjectNode ip = baseResource(region, region + "a", RESOURCE_STATIC_IP, name);
        ip.put("ipAddress", publicIpFor(name));
        ip.put("isAttached", false);
        resourceStore.put(resourceKey(region, RESOURCE_STATIC_IP, name), ip);
        return operations(region, RESOURCE_STATIC_IP, name, "AllocateStaticIp");
    }

    public ObjectNode getStaticIps(String region) {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode ips = response.putArray("staticIps");
        listResources(region, RESOURCE_STATIC_IP).forEach(ips::add);
        return response;
    }

    public ObjectNode getStaticIp(String region, String name) {
        return mapper.createObjectNode().set("staticIp", requireResource(region, RESOURCE_STATIC_IP, name));
    }

    public ObjectNode attachStaticIp(String region, JsonNode request) {
        String staticIpName = requireText(request, "staticIpName");
        String instanceName = requireText(request, "instanceName");
        ObjectNode ip = requireResource(region, RESOURCE_STATIC_IP, staticIpName);
        ObjectNode instance = requireResource(region, RESOURCE_INSTANCE, instanceName);
        if (ip.path("isAttached").asBoolean(false)) {
            throw new AwsException("InvalidInputException",
                    "StaticIp " + staticIpName + " is already attached to instance "
                            + ip.path("attachedTo").asText() + ".",
                    400);
        }
        ip.put("attachedTo", instanceName);
        ip.put("isAttached", true);
        instance.put("publicIpAddress", ip.path("ipAddress").asText());
        instance.put("isStaticIp", true);
        resourceStore.put(resourceKey(region, RESOURCE_STATIC_IP, staticIpName), ip);
        resourceStore.put(resourceKey(region, RESOURCE_INSTANCE, instanceName), instance);
        return operations(region, RESOURCE_STATIC_IP, staticIpName, "AttachStaticIp");
    }

    public ObjectNode detachStaticIp(String region, String name) {
        ObjectNode ip = requireResource(region, RESOURCE_STATIC_IP, name);
        String instanceName = ip.path("attachedTo").asText(null);
        if (instanceName != null) {
            resourceStore.get(resourceKey(region, RESOURCE_INSTANCE, instanceName)).ifPresent(instance -> {
                instance.put("isStaticIp", false);
                instance.put("publicIpAddress", publicIpFor(instanceName));
                resourceStore.put(resourceKey(region, RESOURCE_INSTANCE, instanceName), instance);
            });
        }
        ip.remove("attachedTo");
        ip.put("isAttached", false);
        resourceStore.put(resourceKey(region, RESOURCE_STATIC_IP, name), ip);
        return operations(region, RESOURCE_STATIC_IP, name, "DetachStaticIp");
    }

    public ObjectNode releaseStaticIp(String region, String name) {
        ObjectNode ip = requireResource(region, RESOURCE_STATIC_IP, name);
        String instanceName = ip.path("attachedTo").asText(null);
        if (instanceName != null) {
            resourceStore.get(resourceKey(region, RESOURCE_INSTANCE, instanceName)).ifPresent(instance -> {
                instance.put("isStaticIp", false);
                instance.put("publicIpAddress", publicIpFor(instanceName));
                resourceStore.put(resourceKey(region, RESOURCE_INSTANCE, instanceName), instance);
            });
        }
        resourceStore.delete(resourceKey(region, RESOURCE_STATIC_IP, name));
        return operations(region, RESOURCE_STATIC_IP, name, "ReleaseStaticIp");
    }

    public ObjectNode createKeyPair(String region, JsonNode request) {
        String name = requireText(request, "keyPairName");
        ensureNotExists(region, RESOURCE_KEY_PAIR, name);
        ObjectNode keyPair = keyPair(region, name, request.path("publicKeyBase64").asText(null), tags(request.path("tags")));
        resourceStore.put(resourceKey(region, RESOURCE_KEY_PAIR, name), keyPair);

        ObjectNode response = mapper.createObjectNode();
        response.set("keyPair", keyPair.deepCopy());
        response.put("publicKeyBase64", keyPair.path("publicKeyBase64").asText());
        response.put("privateKeyBase64", privateKeyBase64(name));
        response.set("operation", operation(region, RESOURCE_KEY_PAIR, name, "CreateKeyPair"));
        return response;
    }

    public ObjectNode importKeyPair(String region, JsonNode request) {
        String name = requireText(request, "keyPairName");
        String publicKey = requireText(request, "publicKeyBase64");
        ensureNotExists(region, RESOURCE_KEY_PAIR, name);
        ObjectNode keyPair = keyPair(region, name, publicKey, mapper.createArrayNode());
        resourceStore.put(resourceKey(region, RESOURCE_KEY_PAIR, name), keyPair);
        return mapper.createObjectNode().set("operation", operation(region, RESOURCE_KEY_PAIR, name, "ImportKeyPair"));
    }

    public ObjectNode downloadDefaultKeyPair(String region) {
        String name = "LightsailDefaultKeyPair";
        resourceStore.get(resourceKey(region, RESOURCE_KEY_PAIR, name))
                .orElseGet(() -> {
                    ObjectNode created = keyPair(region, name, null, mapper.createArrayNode());
                    resourceStore.put(resourceKey(region, RESOURCE_KEY_PAIR, name), created);
                    return created;
                });
        ObjectNode response = mapper.createObjectNode();
        response.put("publicKeyBase64", publicKeyBase64(name));
        response.put("privateKeyBase64", privateKeyBase64(name));
        response.put("createdAt", epochSeconds(Instant.now()));
        return response;
    }

    public ObjectNode getKeyPairs(String region) {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode keyPairs = response.putArray("keyPairs");
        listResources(region, RESOURCE_KEY_PAIR).forEach(keyPair -> {
            ObjectNode copy = keyPair.deepCopy();
            copy.remove(List.of("publicKeyBase64", "privateKeyBase64"));
            keyPairs.add(copy);
        });
        return response;
    }

    public ObjectNode getKeyPair(String region, String name) {
        ObjectNode keyPair = requireResource(region, RESOURCE_KEY_PAIR, name).deepCopy();
        keyPair.remove(List.of("publicKeyBase64", "privateKeyBase64"));
        return mapper.createObjectNode().set("keyPair", keyPair);
    }

    public ObjectNode deleteKeyPair(String region, String name) {
        requireResource(region, RESOURCE_KEY_PAIR, name);
        resourceStore.delete(resourceKey(region, RESOURCE_KEY_PAIR, name));
        return mapper.createObjectNode().set("operation", operation(region, RESOURCE_KEY_PAIR, name, "DeleteKeyPair"));
    }

    public ObjectNode getRegions(JsonNode request) {
        boolean includeAzs = request.path("includeAvailabilityZones").asBoolean(false);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode regions = response.putArray("regions");
        regions.add(regionNode("us-east-1", "Virginia", "United States", includeAzs));
        regions.add(regionNode("us-west-2", "Oregon", "United States", includeAzs));
        regions.add(regionNode("eu-central-1", "Frankfurt", "Europe", includeAzs));
        regions.add(regionNode("eu-west-1", "Ireland", "Europe", includeAzs));
        return response;
    }

    public ObjectNode getBlueprints() {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode blueprints = response.putArray("blueprints");
        blueprints.add(blueprint("ubuntu_22_04", "Ubuntu", "linux", "Ubuntu 22.04 LTS"));
        blueprints.add(blueprint("ubuntu_24_04", "Ubuntu", "linux", "Ubuntu 24.04 LTS"));
        blueprints.add(blueprint("amazon_linux_2023", "Amazon Linux", "linux", "Amazon Linux 2023"));
        blueprints.add(blueprint("wordpress", "WordPress", "wordpress", "WordPress"));
        return response;
    }

    public ObjectNode getBundles() {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode bundles = response.putArray("bundles");
        bundles.add(bundle("nano_3_0", "Nano", 1, 0.5, 20, 1024, 3.50));
        bundles.add(bundle("micro_3_0", "Micro", 2, 1.0, 40, 2048, 5.00));
        bundles.add(bundle("small_3_0", "Small", 2, 2.0, 60, 3072, 10.00));
        bundles.add(bundle("medium_3_0", "Medium", 2, 4.0, 80, 4096, 20.00));
        return response;
    }

    public ObjectNode tagResource(String region, JsonNode request) {
        String name = textOrNull(request, "resourceName");
        String arn = textOrNull(request, "resourceArn");
        ObjectNode resource = requireAnyResource(region, name, arn);
        mergeTags((ArrayNode) resource.withArray("tags"), tags(request.path("tags")));
        resourceStore.put(resourceKey(region, resource.path("resourceType").asText(), resource.path("name").asText()), resource);
        return operations(region, resource.path("resourceType").asText(), resource.path("name").asText(), "TagResource");
    }

    public ObjectNode untagResource(String region, JsonNode request) {
        String name = textOrNull(request, "resourceName");
        String arn = textOrNull(request, "resourceArn");
        ObjectNode resource = requireAnyResource(region, name, arn);
        JsonNode tagKeys = request.path("tagKeys");
        if (!tagKeys.isArray()) {
            throw new AwsException("InvalidInputException", "tagKeys is required", 400);
        }
        ArrayNode tags = mapper.createArrayNode();
        for (JsonNode tag : resource.path("tags")) {
            if (!containsText(tagKeys, tag.path("key").asText())) {
                tags.add(tag.deepCopy());
            }
        }
        resource.set("tags", tags);
        resourceStore.put(resourceKey(region, resource.path("resourceType").asText(), resource.path("name").asText()), resource);
        return operations(region, resource.path("resourceType").asText(), resource.path("name").asText(), "UntagResource");
    }

    public ObjectNode getActiveNames(String region) {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode names = response.putArray("activeNames");
        listResources(region, null).stream()
                .map(resource -> resource.path("name").asText())
                .sorted()
                .forEach(names::add);
        return response;
    }

    public ObjectNode getOperations(String region) {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode operations = response.putArray("operations");
        listOperations(region).forEach(operations::add);
        return response;
    }

    public ObjectNode getOperationsForResource(String region, String resourceName) {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode operations = response.putArray("operations");
        listOperations(region).stream()
                .filter(operation -> resourceName.equals(operation.path("resourceName").asText()))
                .forEach(operations::add);
        return response;
    }

    public ObjectNode getOperation(String region, String operationId) {
        return mapper.createObjectNode().set("operation", operationStore.get(operationKey(region, operationId))
                .orElseThrow(() -> new AwsException("NotFoundException", "Operation " + operationId + " was not found", 400)));
    }

    public ObjectNode emptyList(String fieldName) {
        ObjectNode response = mapper.createObjectNode();
        response.set(fieldName, mapper.createArrayNode());
        return response;
    }

    public ObjectNode isVpcPeered() {
        return mapper.createObjectNode().put("isPeered", false);
    }

    private ObjectNode baseResource(String region, String availabilityZone, String resourceType, String name) {
        Instant now = Instant.now();
        ObjectNode resource = mapper.createObjectNode();
        resource.put("name", name);
        resource.put("arn", arn(region, resourceType, name));
        resource.put("supportCode", region + "/" + name);
        resource.put("createdAt", epochSeconds(now));
        resource.set("location", location(region, availabilityZone));
        resource.put("resourceType", resourceType);
        return resource;
    }

    private ObjectNode operation(String region, String resourceType, String resourceName, String operationType) {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        ObjectNode operation = mapper.createObjectNode();
        operation.put("id", id);
        operation.put("resourceName", resourceName);
        operation.put("resourceType", resourceType);
        operation.put("createdAt", epochSeconds(now));
        operation.set("location", location(region, region + "a"));
        operation.put("isTerminal", true);
        operation.put("operationDetails", operationType + " completed locally");
        operation.put("operationType", operationType);
        operation.put("status", "Succeeded");
        operation.put("statusChangedAt", epochSeconds(now));
        operationStore.put(operationKey(region, id), operation);
        return operation;
    }

    private ObjectNode operations(String region, String resourceType, String resourceName, String operationType) {
        ObjectNode response = mapper.createObjectNode();
        response.putArray("operations").add(operation(region, resourceType, resourceName, operationType));
        return response;
    }

    private ObjectNode requireResource(String region, String resourceType, String name) {
        requireName(name, "resourceName");
        return resourceStore.get(resourceKey(region, resourceType, name))
                .orElseThrow(() -> new AwsException("NotFoundException",
                        resourceType + " " + name + " was not found", 400));
    }

    private ObjectNode requireAnyResource(String region, String name, String arn) {
        if (arn != null && !arn.isBlank()) {
            return listResources(region, null).stream()
                    .filter(resource -> arn.equals(resource.path("arn").asText()))
                    .findFirst()
                    .orElseThrow(() -> new AwsException("NotFoundException", "Resource " + arn + " was not found", 400));
        }
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidInputException", "resourceName is required", 400);
        }
        return listResources(region, null).stream()
                .filter(resource -> name.equals(resource.path("name").asText()))
                .findFirst()
                .orElseThrow(() -> new AwsException("NotFoundException", "Resource " + name + " was not found", 400));
    }

    private List<ObjectNode> listResources(String region, String resourceType) {
        String prefix = region + ":";
        return resourceStore.scan(key -> key.startsWith(prefix)
                        && (resourceType == null || key.startsWith(resourceKeyPrefix(region, resourceType))))
                .stream()
                .map(ObjectNode::deepCopy)
                .sorted(Comparator.comparing(node -> node.path("name").asText()))
                .toList();
    }

    private List<ObjectNode> listOperations(String region) {
        String prefix = region + ":";
        return operationStore.scan(key -> key.startsWith(prefix)).stream()
                .map(ObjectNode::deepCopy)
                .sorted(Comparator.comparing(node -> node.path("createdAt").asDouble()))
                .toList();
    }

    private void ensureNotExists(String region, String resourceType, String name) {
        if (resourceStore.get(resourceKey(region, resourceType, name)).isPresent()) {
            throw new AwsException("InvalidInputException", resourceType + " " + name + " already exists", 400);
        }
    }

    private void detachStaticIpsFrom(String instanceName) {
        for (String key : new ArrayList<>(resourceStore.keys())) {
            if (key.contains(":" + RESOURCE_STATIC_IP + ":")) {
                resourceStore.get(key).ifPresent(ip -> {
                    if (instanceName.equals(ip.path("attachedTo").asText(null))) {
                        ip.remove("attachedTo");
                        ip.put("isAttached", false);
                        resourceStore.put(key, ip);
                    }
                });
            }
        }
    }

    private void detachDisksFrom(String instanceName) {
        for (String key : new ArrayList<>(resourceStore.keys())) {
            if (key.contains(":" + RESOURCE_DISK + ":")) {
                resourceStore.get(key).ifPresent(disk -> {
                    if (instanceName.equals(disk.path("attachedTo").asText(null))) {
                        disk.remove("attachedTo");
                        disk.put("isAttached", false);
                        disk.put("attachmentState", "detached");
                        disk.put("state", "available");
                        resourceStore.put(key, disk);
                    }
                });
            }
        }
    }

    private ObjectNode keyPair(String region, String name, String publicKeyBase64, ArrayNode tags) {
        ObjectNode keyPair = baseResource(region, region + "a", RESOURCE_KEY_PAIR, name);
        keyPair.set("tags", tags);
        keyPair.put("fingerprint", Base64.getEncoder().encodeToString(("fingerprint:" + name).getBytes(StandardCharsets.UTF_8)));
        keyPair.put("publicKeyBase64", publicKeyBase64 == null ? publicKeyBase64(name) : publicKeyBase64);
        return keyPair;
    }

    private ObjectNode regionNode(String name, String displayName, String continentCode, boolean includeAzs) {
        ObjectNode region = mapper.createObjectNode();
        region.put("continentCode", continentCode);
        region.put("description", displayName);
        region.put("displayName", displayName);
        region.put("name", name);
        if (includeAzs) {
            ArrayNode zones = region.putArray("availabilityZones");
            zones.add(availabilityZone(name + "a", name));
            zones.add(availabilityZone(name + "b", name));
            region.set("relationalDatabaseAvailabilityZones", zones.deepCopy());
        }
        return region;
    }

    private ObjectNode availabilityZone(String zoneName, String regionName) {
        return mapper.createObjectNode()
                .put("zoneName", zoneName)
                .put("state", "available")
                .put("regionName", regionName);
    }

    private ObjectNode blueprint(String id, String name, String group, String description) {
        return mapper.createObjectNode()
                .put("blueprintId", id)
                .put("name", name)
                .put("group", group)
                .put("type", "os")
                .put("description", description)
                .put("isActive", true)
                .put("minPower", 0)
                .put("version", "latest")
                .put("versionCode", "1")
                .put("productUrl", "https://aws.amazon.com/lightsail/")
                .put("platform", "LINUX_UNIX")
                .put("appCategory", "LfR");
    }

    private ObjectNode bundle(String id, String name, int cpu, double ram, int disk, int transfer, double price) {
        ObjectNode bundle = mapper.createObjectNode();
        bundle.put("price", price);
        bundle.put("cpuCount", cpu);
        bundle.put("diskSizeInGb", disk);
        bundle.put("bundleId", id);
        bundle.put("instanceType", "lightsail." + id);
        bundle.put("isActive", true);
        bundle.put("name", name);
        bundle.put("power", cpu * 100);
        bundle.put("ramSizeInGb", ram);
        bundle.put("transferPerMonthInGb", transfer);
        bundle.putArray("supportedPlatforms").add("LINUX_UNIX").add("WINDOWS");
        bundle.putArray("supportedAppCategories").add("LfR");
        bundle.put("publicIpv4AddressCount", 1);
        return bundle;
    }

    private ObjectNode hardware(String bundleId) {
        ObjectNode hardware = mapper.createObjectNode();
        int cpu = switch (bundleId) {
            case "nano_3_0" -> 1;
            default -> 2;
        };
        double ram = switch (bundleId) {
            case "nano_3_0" -> 0.5;
            case "micro_3_0" -> 1.0;
            case "small_3_0" -> 2.0;
            case "medium_3_0" -> 4.0;
            default -> 1.0;
        };
        hardware.put("cpuCount", cpu);
        hardware.put("disks", mapper.createArrayNode());
        hardware.put("ramSizeInGb", ram);
        return hardware;
    }

    private ObjectNode defaultNetworking() {
        ObjectNode networking = mapper.createObjectNode();
        ArrayNode ports = networking.putArray("ports");
        ObjectNode ssh = mapper.createObjectNode();
        ssh.put("fromPort", 22);
        ssh.put("toPort", 22);
        ssh.put("protocol", "tcp");
        ssh.putArray("cidrs").add("0.0.0.0/0");
        ssh.putArray("ipv6Cidrs");
        ssh.putArray("cidrListAliases");
        ports.add(ssh);
        networking.put("monthlyTransfer", mapper.createObjectNode()
                .put("gbPerMonthAllocated", 1024));
        return networking;
    }

    private ObjectNode instanceState(String state) {
        ObjectNode node = mapper.createObjectNode();
        node.put("name", state);
        node.put("code", "running".equals(state) ? 16 : 80);
        return node;
    }

    private ObjectNode location(String region, String availabilityZone) {
        return mapper.createObjectNode()
                .put("availabilityZone", availabilityZone)
                .put("regionName", region);
    }

    private ArrayNode tags(JsonNode tagsNode) {
        ArrayNode tags = mapper.createArrayNode();
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String key = textOrNull(tag, "key");
                if (key == null) {
                    key = textOrNull(tag, "Key");
                }
                if (key != null) {
                    ObjectNode tagNode = mapper.createObjectNode();
                    tagNode.put("key", key);
                    String value = textOrNull(tag, "value");
                    if (value == null) {
                        value = textOrNull(tag, "Value");
                    }
                    if (value != null) {
                        tagNode.put("value", value);
                    }
                    tags.add(tagNode);
                }
            }
        }
        return tags;
    }

    private void mergeTags(ArrayNode existing, ArrayNode updates) {
        for (JsonNode update : updates) {
            String key = update.path("key").asText();
            boolean replaced = false;
            for (int i = 0; i < existing.size(); i++) {
                if (key.equals(existing.get(i).path("key").asText())) {
                    existing.set(i, update.deepCopy());
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                existing.add(update.deepCopy());
            }
        }
    }

    private boolean samePort(JsonNode left, JsonNode right) {
        return left.path("fromPort").asInt(-1) == right.path("fromPort").asInt(-2)
                && left.path("toPort").asInt(-1) == right.path("toPort").asInt(-2)
                && left.path("protocol").asText("").equals(right.path("protocol").asText(""));
    }

    private boolean containsText(JsonNode array, String value) {
        for (JsonNode node : array) {
            if (value.equals(node.asText())) {
                return true;
            }
        }
        return false;
    }

    private String arn(String region, String resourceType, String name) {
        return regionResolver.buildArn("lightsail", region, resourceType + "/" + name);
    }

    private static String requireText(JsonNode request, String field) {
        String value = request.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidInputException", field + " is required", 400);
        }
        return value;
    }

    private static void requireArray(JsonNode request, String field) {
        if (!request.has(field) || !request.path(field).isArray() || request.path(field).isEmpty()) {
            throw new AwsException("InvalidInputException", field + " is required", 400);
        }
    }

    private static void requireName(String name, String field) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidInputException", field + " is required", 400);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String resourceKey(String region, String resourceType, String name) {
        return resourceKeyPrefix(region, resourceType) + name;
    }

    private static String resourceKeyPrefix(String region, String resourceType) {
        return region + ":" + resourceType + ":";
    }

    private static String operationKey(String region, String operationId) {
        return region + ":" + operationId;
    }

    private static double epochSeconds(Instant instant) {
        return instant.toEpochMilli() / 1000.0;
    }

    private static String privateIpFor(String name) {
        int value = Math.floorMod(name.hashCode(), 200) + 20;
        return "10.0.0." + value;
    }

    private static String publicIpFor(String name) {
        int value = Math.floorMod(name.hashCode(), 200) + 20;
        return "203.0.113." + value;
    }

    private static String blueprintName(String blueprintId) {
        return switch (blueprintId) {
            case "ubuntu_24_04" -> "Ubuntu 24.04 LTS";
            case "ubuntu_22_04" -> "Ubuntu 22.04 LTS";
            case "amazon_linux_2023" -> "Amazon Linux 2023";
            case "wordpress" -> "WordPress";
            default -> blueprintId;
        };
    }

    private static String defaultUsername(String blueprintId) {
        return blueprintId != null && blueprintId.startsWith("amazon_linux") ? "ec2-user" : "ubuntu";
    }

    private static String publicKeyBase64(String name) {
        return Base64.getEncoder().encodeToString(("ssh-rsa FLOCI-" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static String privateKeyBase64(String name) {
        return Base64.getEncoder().encodeToString(("-----BEGIN PRIVATE KEY-----\nfloci-" + name
                + "\n-----END PRIVATE KEY-----\n").getBytes(StandardCharsets.UTF_8));
    }
}
