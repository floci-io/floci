package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.ec2.Ec2InstanceTypeCatalog.CatalogInstanceType;
import io.floci.aws.services.eks.EksAutoModeNodes.Launch;
import io.floci.aws.services.eks.EksAutoModeNodes.NodePool;
import io.floci.aws.services.eks.EksAutoModeNodes.PendingPod;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The NodeClaim Auto Mode keeps for each node, shaped as AWS shows it. */
class EksAutoModeNodeClaimsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Named after the pool with five characters, as Karpenter's generateName gives them, stable for an instance. */
    @Test
    void aClaimIsNamedAfterItsPoolStablyForItsInstance() {
        String name = EksAutoModeNodeClaims.name("general-purpose", "i-0123456789abcdef0");

        assertTrue(name.matches("general-purpose-[bcdfghjklmnpqrstvwxz2456789]{5}"), name);
        assertEquals(name, EksAutoModeNodeClaims.name("general-purpose", "i-0123456789abcdef0"));
        assertNotEquals(name, EksAutoModeNodeClaims.name("general-purpose", "i-0123456789abcdef1"));
        // Another attempt derives another name, for when the first is taken.
        String second = EksAutoModeNodeClaims.name("general-purpose", "i-0123456789abcdef0", 1);
        assertTrue(second.matches("general-purpose-[bcdfghjklmnpqrstvwxz2456789]{5}"), second);
        assertNotEquals(name, second);
    }

    /**
     * Owned by its NodePool, held by Karpenter's finalizer, labelled as its node, with the pool's requirements and
     * the chosen type, pool and NodeClass added, its pods' requests, and the pool's expireAfter and
     * terminationGracePeriod.
     */
    @Test
    void aClaimCarriesItsPoolsOwnershipRequirementsAndItsPodsRequests() throws Exception {
        String poolItem = """
                {"metadata": {"name": "general-purpose", "uid": "b3a94e41"},
                 "spec": {"template": {"spec": {"expireAfter": "336h", "terminationGracePeriod": "24h0m0s",
                   "requirements": [{"key": "kubernetes.io/arch", "operator": "In", "values": ["amd64"]}]}}}}""";
        CatalogInstanceType large = new CatalogInstanceType();
        large.instanceType = "c6a.large";
        large.vcpu = 2;
        large.memoryMib = 4096;
        NodePool pool = new NodePool("general-purpose", "default", List.of(), List.of(), Map.of(), 0,
                Duration.ofSeconds(30), List.of());
        Launch launch = new Launch(pool, large, "us-east-1a", Map.of("karpenter.sh/nodepool", "general-purpose",
                "topology.kubernetes.io/zone", "us-east-1a"), List.of(
                new PendingPod("default", "a", 100, 64L << 20, Map.of(), List.of(), List.of()),
                new PendingPod("default", "b", 250, 32L << 20, Map.of(), List.of(), List.of())));

        JsonNode claim = MAPPER.readTree(EksAutoModeNodeClaims.claim(launch, "general-purpose-kw46r",
                MAPPER.readTree(poolItem),
                launch.labels()));

        assertEquals("karpenter.sh/v1", claim.path("apiVersion").asText());
        assertEquals("NodeClaim", claim.path("kind").asText());
        JsonNode metadata = claim.path("metadata");
        assertEquals("general-purpose-kw46r", metadata.path("name").asText());
        assertEquals("[\"karpenter.sh/termination\"]", metadata.path("finalizers").toString());
        assertEquals("NodePool", metadata.path("ownerReferences").get(0).path("kind").asText());
        assertEquals("b3a94e41", metadata.path("ownerReferences").get(0).path("uid").asText());
        assertTrue(metadata.path("ownerReferences").get(0).path("blockOwnerDeletion").asBoolean());
        assertEquals("default", metadata.path("labels").path("eks.amazonaws.com/nodeclass").asText());
        assertEquals("eks", metadata.path("labels").path("app.kubernetes.io/managed-by").asText());
        assertEquals("us-east-1a", metadata.path("labels").path("topology.kubernetes.io/zone").asText());
        JsonNode spec = claim.path("spec");
        assertEquals("336h", spec.path("expireAfter").asText());
        assertEquals("24h0m0s", spec.path("terminationGracePeriod").asText());
        assertEquals("default", spec.path("nodeClassRef").path("name").asText());
        assertEquals("eks.amazonaws.com", spec.path("nodeClassRef").path("group").asText());
        assertEquals(List.of("kubernetes.io/arch", "node.kubernetes.io/instance-type", "karpenter.sh/nodepool",
                "eks.amazonaws.com/nodeclass"), spec.path("requirements").findValuesAsText("key"));
        assertEquals("c6a.large", spec.path("requirements").get(1).path("values").get(0).asText());
        assertEquals("350m", spec.path("resources").path("requests").path("cpu").asText());
        assertEquals(String.valueOf(96L << 20), spec.path("resources").path("requests").path("memory").asText());
        assertEquals("2", spec.path("resources").path("requests").path("pods").asText());
    }

    /** A pool without expireAfter or terminationGracePeriod gives Auto Mode's 336h and 24h. */
    @Test
    void aPoolWithoutExpireAfterOrGracePeriodGivesAutoModesDefaults() throws Exception {
        CatalogInstanceType large = new CatalogInstanceType();
        large.instanceType = "c6a.large";
        NodePool pool = new NodePool("batch", "default", List.of(), List.of(), Map.of(), 0, null, List.of());

        JsonNode claim = MAPPER.readTree(EksAutoModeNodeClaims.claim(new Launch(pool, large, null, Map.of(),
                List.of()), "batch-kw46r", MAPPER.readTree("{\"metadata\": {\"name\": \"batch\"}}"), Map.of()));

        assertEquals("336h", claim.path("spec").path("expireAfter").asText());
        assertEquals("24h0m0s", claim.path("spec").path("terminationGracePeriod").asText());
        assertFalse(claim.path("metadata").has("ownerReferences"));
        // A claim made again whose node's pods aren't known requests nothing rather than zero.
        assertFalse(MAPPER.readTree(EksAutoModeNodeClaims.claim(new Launch(pool, large, null, Map.of(), List.of()),
                "batch-kw46r", MAPPER.readTree("{\"metadata\": {\"name\": \"batch\"}}"), Map.of(), null))
                .path("spec").has("resources"));
    }

    /** Launched first; once its node joins, registered, initialized, and ready as the node is. */
    @Test
    void theStatusSaysLaunchedThenJoinedWithTheNodesReadiness() throws Exception {
        JsonNode launched = MAPPER.readTree(EksAutoModeNodeClaims.launchedStatus("aws:///us-east-1a/i-1",
                "ami-1", "2026-11-26T00:00:00Z")).path("status");
        assertEquals("aws:///us-east-1a/i-1", launched.path("providerID").asText());
        assertEquals("ami-1", launched.path("imageID").asText());
        assertEquals("True", condition(launched, "Launched"));
        assertEquals("Unknown", condition(launched, "Registered"));
        assertEquals("Unknown", condition(launched, "Ready"));

        JsonNode node = MAPPER.readTree("""
                {"metadata": {"name": "ip-10-0-1-10.ec2.internal"},
                 "status": {"capacity": {"cpu": "2"}, "allocatable": {"cpu": "1780m"},
                            "conditions": [{"type": "Ready", "status": "True"}]}}""");
        JsonNode joined = MAPPER.readTree(EksAutoModeNodeClaims.joinedStatus("aws:///us-east-1a/i-1", "ami-1",
                node, "2026-11-26T00:00:10Z")).path("status");
        assertEquals("ip-10-0-1-10.ec2.internal", joined.path("nodeName").asText());
        assertEquals("1780m", joined.path("allocatable").path("cpu").asText());
        assertEquals("2", joined.path("capacity").path("cpu").asText());
        assertEquals("True", condition(joined, "Registered"));
        assertEquals("True", condition(joined, "Initialized"));
        assertEquals("True", condition(joined, "Ready"));

        JsonNode claim = MAPPER.createObjectNode().set("status", joined);
        assertTrue(EksAutoModeNodeClaims.saysJoined(claim, node));
        assertFalse(EksAutoModeNodeClaims.saysJoined(claim, MAPPER.readTree("""
                {"metadata": {"name": "ip-10-0-1-10.ec2.internal"},
                 "status": {"capacity": {"cpu": "2"}, "allocatable": {"cpu": "1780m"},
                            "conditions": [{"type": "Ready", "status": "False"}]}}""")));
        assertFalse(EksAutoModeNodeClaims.saysJoined(claim, MAPPER.readTree("""
                {"metadata": {"name": "another"},
                 "status": {"capacity": {"cpu": "2"}, "allocatable": {"cpu": "1780m"},
                            "conditions": [{"type": "Ready", "status": "True"}]}}""")));
        // A resource the node reports after it was ready, as a device plugin adds one, is written again.
        assertFalse(EksAutoModeNodeClaims.saysJoined(claim, MAPPER.readTree("""
                {"metadata": {"name": "ip-10-0-1-10.ec2.internal"},
                 "status": {"capacity": {"cpu": "2", "nvidia.com/gpu": "1"},
                            "allocatable": {"cpu": "1780m", "nvidia.com/gpu": "1"},
                            "conditions": [{"type": "Ready", "status": "True"}]}}""")));
    }

    /** Only the claims Auto Mode manages are its own; ending one takes Karpenter's finalizer off and keeps others. */
    @Test
    void onlyManagedClaimsAreOwnedAndEndingOneKeepsOtherFinalizers() throws Exception {
        assertTrue(EksAutoModeNodeClaims.managed(MAPPER.readTree(
                "{\"metadata\": {\"labels\": {\"app.kubernetes.io/managed-by\": \"eks\"}}}")));
        assertFalse(EksAutoModeNodeClaims.managed(MAPPER.readTree("{\"metadata\": {\"labels\": {}}}")));

        assertEquals("{\"metadata\":{\"finalizers\":null}}", EksAutoModeNodeClaims.withoutFinalizer(
                MAPPER.readTree("{\"metadata\": {\"finalizers\": [\"karpenter.sh/termination\"]}}")));
        assertEquals("{\"metadata\":{\"finalizers\":[\"example.com/audit\"]}}",
                EksAutoModeNodeClaims.withoutFinalizer(MAPPER.readTree("{\"metadata\": {\"finalizers\": "
                        + "[\"karpenter.sh/termination\", \"example.com/audit\"]}}")));
    }

    private static String condition(JsonNode status, String type) {
        for (JsonNode condition : status.path("conditions")) {
            if (type.equals(condition.path("type").asText())) {
                return condition.path("status").asText();
            }
        }
        return null;
    }
}
