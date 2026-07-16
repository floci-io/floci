package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntime;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntimeVersion;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.ListResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockAgentCoreControlServiceTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private BedrockAgentCoreControlService service;

    @BeforeEach
    void setUp() {
        service = new BedrockAgentCoreControlService(
                new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"));
    }

    private ObjectNode artifact() {
        ObjectNode node = mapper.createObjectNode();
        node.putObject("containerConfiguration").put("containerUri", "123456789012.dkr.ecr/x:latest");
        return node;
    }

    private ObjectNode network() {
        ObjectNode node = mapper.createObjectNode();
        node.put("networkMode", "PUBLIC");
        return node;
    }

    private AgentRuntime create(String name) {
        return service.createAgentRuntime(name, artifact(), network(),
                "arn:aws:iam::000000000000:role/agent", "desc", null, null, null, REGION);
    }

    @Test
    void createGeneratesIdArnAndVersion() {
        AgentRuntime rt = create("myAgent");
        assertTrue(rt.getAgentRuntimeId().matches("myAgent-[a-zA-Z0-9]{10}"), rt.getAgentRuntimeId());
        assertEquals(1, rt.getLatestVersion());
        assertEquals("READY", rt.getStatus());
        assertTrue(rt.getWorkloadIdentityArn().contains(":bedrock-agentcore:"));
        assertTrue(service.arn(rt, "1", REGION)
                .matches("arn:aws:bedrock-agentcore:us-east-1:000000000000:agent/[0-9a-f-]{36}:1"),
                service.arn(rt, "1", REGION));
    }

    @Test
    void createRejectsInvalidName() {
        AwsException e = assertThrows(AwsException.class, () -> create("1bad-name"));
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void createRejectsMissingRequiredFields() {
        assertThrows(AwsException.class, () -> service.createAgentRuntime("ok", null, network(),
                "arn:aws:iam::000000000000:role/agent", null, null, null, null, REGION));
    }

    @Test
    void updateBumpsVersionAndKeepsHistory() {
        AgentRuntime rt = create("myAgent");
        String id = rt.getAgentRuntimeId();

        AgentRuntime updated = service.updateAgentRuntime(id, artifact(), network(),
                "arn:aws:iam::000000000000:role/agent2", "v2", null, null, null, REGION);
        assertEquals(2, updated.getLatestVersion());
        assertEquals(2, updated.getVersions().size());
        assertTrue(service.resolveVersion(updated, "1").getRoleArn().endsWith("role/agent"));
        assertTrue(service.resolveVersion(updated, "2").getRoleArn().endsWith("role/agent2"));
        assertEquals("2", service.resolveVersion(updated, null).getVersion());
    }

    @Test
    void getUnknownThrows404() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.getAgentRuntime("missing-abcdefghij", REGION));
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void resolveUnknownVersionThrows404() {
        AgentRuntime rt = create("myAgent");
        AwsException e = assertThrows(AwsException.class, () -> service.resolveVersion(rt, "9"));
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void listReturnsCreatedRuntimes() {
        create("agentA");
        create("agentB");
        ListResult<AgentRuntime> result = service.listAgentRuntimes(0, null, REGION);
        assertEquals(2, result.items().size());
        assertNull(result.nextToken());
    }

    @Test
    void listPaginatesWithToken() {
        create("agentA");
        create("agentB");
        create("agentC");
        ListResult<AgentRuntime> page1 = service.listAgentRuntimes(2, null, REGION);
        assertEquals(2, page1.items().size());
        assertTrue(page1.nextToken() != null);
        ListResult<AgentRuntime> page2 = service.listAgentRuntimes(2, page1.nextToken(), REGION);
        assertEquals(1, page2.items().size());
        assertNull(page2.nextToken());
    }

    @Test
    void listVersionsPaginatesPastNineVersionsWithoutCycling() {
        // Regression for the numeric-sort / lexicographic-cursor pagination bug (issue #7).
        AgentRuntime rt = create("myAgent");
        String id = rt.getAgentRuntimeId();
        for (int i = 0; i < 11; i++) {
            service.updateAgentRuntime(id, artifact(), network(),
                    "arn:aws:iam::000000000000:role/agent", "d", null, null, null, REGION);
        }
        // 12 versions ("1".."12"). Page with maxResults=5 and follow the cursor.
        Set<String> seen = new HashSet<>();
        String token = null;
        int pages = 0;
        do {
            ListResult<AgentRuntimeVersion> page = service.listAgentRuntimeVersions(id, 5, token, REGION);
            page.items().forEach(v -> seen.add(v.getVersion()));
            token = page.nextToken();
            assertTrue(++pages <= 5, "pagination did not terminate (cycled)");
        } while (token != null);
        assertEquals(12, seen.size());
        assertTrue(seen.contains("10") && seen.contains("11") && seen.contains("12"));
    }

    @Test
    void versionSnapshotCapturesAuthorizerAndProtocolConfig() {
        // Regression for the version-config leak (issue #8).
        ObjectNode authA = mapper.createObjectNode();
        authA.putObject("customJWTAuthorizer").put("discoveryUrl", "A");
        ObjectNode authB = mapper.createObjectNode();
        authB.putObject("customJWTAuthorizer").put("discoveryUrl", "B");

        AgentRuntime rt = service.createAgentRuntime("myAgent", artifact(), network(),
                "arn:aws:iam::000000000000:role/agent", "d", null, authA, null, REGION);
        String id = rt.getAgentRuntimeId();
        service.updateAgentRuntime(id, artifact(), network(),
                "arn:aws:iam::000000000000:role/agent", "d", null, authB, null, REGION);

        AgentRuntime fresh = service.getAgentRuntime(id, REGION);
        assertEquals("A", service.resolveVersion(fresh, "1").getAuthorizerConfiguration()
                .path("customJWTAuthorizer").path("discoveryUrl").asText());
        assertEquals("B", service.resolveVersion(fresh, "2").getAuthorizerConfiguration()
                .path("customJWTAuthorizer").path("discoveryUrl").asText());
    }

    @Test
    void listRejectsOutOfRangeMaxResults() {
        create("agentA");
        assertEquals(400, assertThrows(AwsException.class,
                () -> service.listAgentRuntimes(101, null, REGION)).getHttpStatus());
        assertEquals(400, assertThrows(AwsException.class,
                () -> service.listAgentRuntimes(-1, null, REGION)).getHttpStatus());
    }

    @Test
    void listRejectsMalformedNextToken() {
        assertEquals(400, assertThrows(AwsException.class,
                () -> service.listAgentRuntimes(0, "!!!not-base64!!!", REGION)).getHttpStatus());
    }

    @Test
    void deleteRemovesAndReportsDeleting() {
        AgentRuntime rt = create("myAgent");
        String id = rt.getAgentRuntimeId();
        AgentRuntime deleted = service.deleteAgentRuntime(id, REGION);
        assertEquals("DELETING", deleted.getStatus());
        assertThrows(AwsException.class, () -> service.getAgentRuntime(id, REGION));
    }
}
