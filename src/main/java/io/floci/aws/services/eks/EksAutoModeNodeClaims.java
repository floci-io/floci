package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.Digests;
import io.floci.aws.services.eks.EksAutoModeNodes.Launch;
import io.floci.aws.services.eks.EksAutoModeNodes.PendingPod;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The {@code NodeClaim} (karpenter.sh/v1) Auto Mode keeps for each node it launches, shaped as AWS shows it
 * (measured on an Auto Mode cluster in us-east-1): named after its pool with a five-character suffix, owned by its
 * NodePool, held by the {@code karpenter.sh/termination} finalizer, labelled as its node is, and with the pool's
 * requirements, its pods' requests, the pool's {@code expireAfter} and {@code terminationGracePeriod} in its spec. Its
 * status says it was launched, then, once its node has joined, registered, initialized and ready.
 */
final class EksAutoModeNodeClaims {

    static final String FINALIZER = "karpenter.sh/termination";
    static final String NODECLASS_GROUP = "eks.amazonaws.com"; // partition-literal: a Kubernetes API group
    static final String NODECLASS_LABEL = NODECLASS_GROUP + "/nodeclass";
    /** What Auto Mode puts in {@code expireAfter} when the pool doesn't say: its built-in pools' 14 days. */
    static final String DEFAULT_EXPIRE_AFTER = "336h";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUFFIX_ALPHABET = "bcdfghjklmnpqrstvwxz2456789";

    private EksAutoModeNodeClaims() {
    }

    /**
     * The NodeClaim's name for an instance of {@code pool}: the pool's name and five characters, as Karpenter's
     * {@code generateName} gives them, here derived from the instance ID so each pass finds the same one.
     */
    static String name(String pool, String instanceId) {
        byte[] hash = Digests.sha256(instanceId.getBytes(StandardCharsets.UTF_8));
        StringBuilder suffix = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            suffix.append(SUFFIX_ALPHABET.charAt(Math.floorMod(hash[i], SUFFIX_ALPHABET.length())));
        }
        return pool + "-" + suffix;
    }

    /**
     * The NodeClaim of a launch, as it is applied: metadata and spec. {@code poolItem} is the NodePool as
     * {@code kubectl get nodepools} lists it, whose UID owns the claim and whose template gives its requirements.
     */
    static String claim(Launch launch, String instanceId, JsonNode poolItem, Map<String, String> labels) {
        ObjectNode claim = MAPPER.createObjectNode();
        claim.put("apiVersion", "karpenter.sh/v1");
        claim.put("kind", "NodeClaim");
        ObjectNode metadata = claim.putObject("metadata");
        metadata.put("name", name(launch.pool().name(), instanceId));
        ObjectNode claimLabels = metadata.putObject("labels");
        labels.forEach(claimLabels::put);
        claimLabels.put(NODECLASS_LABEL, launch.pool().nodeClass());
        claimLabels.put("app.kubernetes.io/managed-by", "eks");
        metadata.putArray("finalizers").add(FINALIZER);
        String uid = poolItem.path("metadata").path("uid").asText("");
        if (!uid.isEmpty()) {
            ObjectNode owner = metadata.putArray("ownerReferences").addObject();
            owner.put("apiVersion", "karpenter.sh/v1");
            owner.put("kind", "NodePool");
            owner.put("name", launch.pool().name());
            owner.put("uid", uid);
            owner.put("blockOwnerDeletion", true);
        }
        JsonNode template = poolItem.path("spec").path("template").path("spec");
        ObjectNode spec = claim.putObject("spec");
        spec.put("expireAfter", template.path("expireAfter").asText(DEFAULT_EXPIRE_AFTER));
        if (template.hasNonNull("terminationGracePeriod")) {
            spec.put("terminationGracePeriod", template.path("terminationGracePeriod").asText());
        }
        ObjectNode classRef = spec.putObject("nodeClassRef");
        classRef.put("group", NODECLASS_GROUP);
        classRef.put("kind", "NodeClass");
        classRef.put("name", launch.pool().nodeClass());
        ArrayNode requirements = spec.putArray("requirements");
        template.path("requirements").forEach(requirements::add);
        // What Karpenter adds to the pool's: the type it chose (AWS lists every type that would do), the pool and
        // the NodeClass.
        requirement(requirements, "node.kubernetes.io/instance-type", launch.instanceType().instanceType);
        requirement(requirements, EksAutoModeNodes.NODEPOOL_LABEL, launch.pool().name());
        requirement(requirements, NODECLASS_LABEL, launch.pool().nodeClass());
        long cpu = launch.pods().stream().mapToLong(PendingPod::cpuMillis).sum();
        long memory = launch.pods().stream().mapToLong(PendingPod::memoryBytes).sum();
        ObjectNode requests = spec.putObject("resources").putObject("requests");
        requests.put("cpu", cpu + "m");
        requests.put("memory", String.valueOf(memory));
        requests.put("pods", String.valueOf(launch.pods().size()));
        return claim.toString();
    }

    private static void requirement(ArrayNode requirements, String key, String value) {
        ObjectNode requirement = requirements.addObject();
        requirement.put("key", key);
        requirement.put("operator", "In");
        requirement.putArray("values").add(value);
    }

    /** The status a NodeClaim gets once its instance is launched. */
    static String launchedStatus(String providerId, String imageId, String now) {
        ObjectNode status = MAPPER.createObjectNode().putObject("status");
        status.put("providerID", providerId);
        status.put("imageID", imageId);
        ArrayNode conditions = status.putArray("conditions");
        condition(conditions, "Launched", "True", now);
        condition(conditions, "Registered", "Unknown", now);
        condition(conditions, "Initialized", "Unknown", now);
        condition(conditions, "Ready", "Unknown", now);
        return wrap(status);
    }

    /**
     * The status a NodeClaim gets once its node has joined: the node's name, capacity and allocatable, and
     * registered, initialized and ready, Ready following the node's own Ready condition.
     */
    static String joinedStatus(String providerId, String imageId, JsonNode node, String now) {
        ObjectNode status = MAPPER.createObjectNode().putObject("status");
        status.put("providerID", providerId);
        status.put("imageID", imageId);
        status.put("nodeName", node.path("metadata").path("name").asText());
        JsonNode nodeStatus = node.path("status");
        if (nodeStatus.has("capacity")) {
            status.set("capacity", nodeStatus.path("capacity"));
        }
        if (nodeStatus.has("allocatable")) {
            status.set("allocatable", nodeStatus.path("allocatable"));
        }
        String ready = "Unknown";
        for (JsonNode condition : nodeStatus.path("conditions")) {
            if ("Ready".equals(condition.path("type").asText())) {
                ready = condition.path("status").asText("Unknown");
            }
        }
        ArrayNode conditions = status.putArray("conditions");
        condition(conditions, "Launched", "True", now);
        condition(conditions, "Registered", "True", now);
        condition(conditions, "Initialized", "True", now);
        condition(conditions, "Ready", ready, now);
        return wrap(status);
    }

    /** Whether a NodeClaim's status already says its node {@code nodeName} joined, with {@code ready} as its Ready. */
    static boolean saysJoined(JsonNode claim, String nodeName, String ready) {
        if (!nodeName.equals(claim.path("status").path("nodeName").asText())) {
            return false;
        }
        for (JsonNode condition : claim.path("status").path("conditions")) {
            if ("Ready".equals(condition.path("type").asText())) {
                return ready.equals(condition.path("status").asText());
            }
        }
        return false;
    }

    /** A node's Ready condition status, {@code Unknown} when it has none. */
    static String nodeReady(JsonNode node) {
        for (JsonNode condition : node.path("status").path("conditions")) {
            if ("Ready".equals(condition.path("type").asText())) {
                return condition.path("status").asText("Unknown");
            }
        }
        return "Unknown";
    }

    private static void condition(ArrayNode conditions, String type, String status, String now) {
        ObjectNode condition = conditions.addObject();
        condition.put("type", type);
        condition.put("status", status);
        condition.put("reason", "True".equals(status) ? type : "AwaitingReconciliation");
        condition.put("message", "");
        condition.put("lastTransitionTime", now);
    }

    private static String wrap(ObjectNode status) {
        ObjectNode patch = MAPPER.createObjectNode();
        patch.set("status", status);
        return patch.toString();
    }
}
