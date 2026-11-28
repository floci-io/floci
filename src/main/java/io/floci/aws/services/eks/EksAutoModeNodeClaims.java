package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.Digests;
import io.floci.aws.services.eks.EksAutoModeNodes.Launch;
import io.floci.aws.services.eks.EksAutoModeNodes.PendingPod;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code NodeClaim} (karpenter.sh/v1) Auto Mode keeps for each node it launches, shaped as AWS shows it
 * (measured on an Auto Mode cluster in us-east-1): named after its pool with a five-character suffix, owned by its
 * NodePool, held by the {@code karpenter.sh/termination} finalizer, labelled as its node is, and with the pool's
 * requirements, its pods' requests, the pool's {@code expireAfter} and {@code terminationGracePeriod} (336h and 24h
 * when it doesn't say) in its spec. Its
 * status says it was launched, then, once its node has joined, registered, initialized and ready.
 */
final class EksAutoModeNodeClaims {

    static final String FINALIZER = "karpenter.sh/termination";
    static final String NODECLASS_GROUP = "eks.amazonaws.com"; // partition-literal: a Kubernetes API group
    static final String NODECLASS_LABEL = NODECLASS_GROUP + "/nodeclass";
    /** What Auto Mode puts in {@code expireAfter} when the pool doesn't say: its built-in pools' 14 days. */
    static final String DEFAULT_EXPIRE_AFTER = "336h";
    /** What Auto Mode puts in {@code terminationGracePeriod} when the pool doesn't say: 24 hours. */
    static final String DEFAULT_TERMINATION_GRACE_PERIOD = "24h0m0s";
    static final String MANAGED_BY = "app.kubernetes.io/managed-by";
    static final String MANAGED_BY_VALUE = "eks";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUFFIX_ALPHABET = "bcdfghjklmnpqrstvwxz2456789";

    private EksAutoModeNodeClaims() {
    }

    /**
     * The NodeClaim's name for an instance of {@code pool}: the pool's name and five characters, as Karpenter's
     * {@code generateName} gives them, here derived from the instance ID.
     */
    static String name(String pool, String instanceId) {
        return name(pool, instanceId, 0);
    }

    /**
     * As above; {@code attempt} past 0 derives another name, for when the first is another instance's already. The
     * name chosen is kept in the instance's {@code karpenter.sh/nodeclaim} tag.
     */
    static String name(String pool, String instanceId, int attempt) {
        String seed = attempt == 0 ? instanceId : instanceId + "#" + attempt;
        byte[] hash = Digests.sha256(seed.getBytes(StandardCharsets.UTF_8));
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
    static String claim(Launch launch, String name, JsonNode poolItem, Map<String, String> labels) {
        return claim(launch, name, poolItem, labels, requests(launch));
    }

    /** What a launch's pods request: CPU millicores, memory bytes, and how many they are. */
    static long[] requests(Launch launch) {
        return new long[]{launch.pods().stream().mapToLong(PendingPod::cpuMillis).sum(),
            launch.pods().stream().mapToLong(PendingPod::memoryBytes).sum(), launch.pods().size()};
    }

    /**
     * As above, with the {@code requests} (CPU millicores, memory bytes, pods) its spec gives, or none when
     * {@code null}, as for a claim made again whose node's pods aren't known.
     */
    static String claim(Launch launch, String name, JsonNode poolItem, Map<String, String> labels,
                        long[] requests) {
        ObjectNode claim = MAPPER.createObjectNode();
        claim.put("apiVersion", "karpenter.sh/v1");
        claim.put("kind", "NodeClaim");
        ObjectNode metadata = claim.putObject("metadata");
        metadata.put("name", name);
        ObjectNode claimLabels = metadata.putObject("labels");
        labels.forEach(claimLabels::put);
        claimLabels.put(NODECLASS_LABEL, launch.pool().nodeClass());
        claimLabels.put(MANAGED_BY, MANAGED_BY_VALUE);
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
        spec.put("terminationGracePeriod", template.path("terminationGracePeriod")
                .asText(DEFAULT_TERMINATION_GRACE_PERIOD));
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
        if (requests != null) {
            ObjectNode resources = spec.putObject("resources").putObject("requests");
            resources.put("cpu", requests[0] + "m");
            resources.put("memory", String.valueOf(requests[1]));
            resources.put("pods", String.valueOf(requests[2]));
        }
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
        ArrayNode conditions = status.putArray("conditions");
        condition(conditions, "Launched", "True", now);
        condition(conditions, "Registered", "True", now);
        condition(conditions, "Initialized", "True", now);
        condition(conditions, "Ready", nodeReady(node), now);
        return wrap(status);
    }

    /**
     * Whether a NodeClaim's status already says {@code node} joined: its name, capacity and allocatable, and its
     * Ready as the node's.
     */
    static boolean saysJoined(JsonNode claim, JsonNode node) {
        JsonNode status = claim.path("status");
        JsonNode nodeStatus = node.path("status");
        if (!node.path("metadata").path("name").asText().equals(status.path("nodeName").asText())
                || !nodeStatus.path("capacity").equals(status.path("capacity"))
                || !nodeStatus.path("allocatable").equals(status.path("allocatable"))) {
            return false;
        }
        return nodeReady(node).equals(EksAutoModeNodes.conditionStatus(claim, "Ready"));
    }

    /**
     * Whether a NodeClaim has lived its {@code expireAfter}, counted from its creation, as Karpenter expires a claim
     * (deleting it, whatever the pool's disruption budgets). One whose {@code expireAfter} is {@code Never}, missing
     * or unreadable doesn't expire.
     */
    static boolean expired(JsonNode claim, Instant now) {
        String after = claim.path("spec").path("expireAfter").asText("");
        String created = claim.path("metadata").path("creationTimestamp").asText("");
        if (after.isEmpty() || "Never".equals(after) || created.isEmpty()) {
            return false;
        }
        try {
            return !Instant.parse(created).plus(Duration.parse("PT" + after.toUpperCase(Locale.ROOT))).isAfter(now);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** Whether Auto Mode manages a NodeClaim, as its {@code app.kubernetes.io/managed-by: eks} label says. */
    static boolean managed(JsonNode claim) {
        return MANAGED_BY_VALUE.equals(claim.path("metadata").path("labels").path(MANAGED_BY).asText());
    }

    /** The merge patch that takes Karpenter's finalizer off a NodeClaim and keeps its others. */
    static String withoutFinalizer(JsonNode claim) {
        ObjectNode patch = MAPPER.createObjectNode();
        ArrayNode kept = MAPPER.createArrayNode();
        claim.path("metadata").path("finalizers").forEach(f -> {
            if (!FINALIZER.equals(f.asText())) {
                kept.add(f);
            }
        });
        if (kept.isEmpty()) {
            patch.putObject("metadata").putNull("finalizers");
        } else {
            patch.putObject("metadata").set("finalizers", kept);
        }
        return patch.toString();
    }

    /** A node's Ready condition status, {@code Unknown} when it has none. */
    static String nodeReady(JsonNode node) {
        return Objects.requireNonNullElse(EksAutoModeNodes.conditionStatus(node, "Ready"), "Unknown");
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
