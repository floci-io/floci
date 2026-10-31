package io.github.hectorvent.floci.services.transfer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.transfer.model.Server;
import io.github.hectorvent.floci.services.transfer.model.SshPublicKey;
import io.github.hectorvent.floci.services.transfer.model.User;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

class TransferServiceTest {

    private AccountAwareStorageBackend<Server> serverStore;
    private AccountAwareStorageBackend<User> userStore;
    private InMemoryStorage<String, Server> serverDelegate;
    private AccountAwareStorageBackend<Map<String, String>> tagStore;
    private InMemoryStorage<String, Map<String, String>> tagDelegate;
    private TransferService service;
    private RegionResolver regionResolver;
    private RequestContext requestContext;

    @BeforeEach
    void setUp() {
        requestContext = new RequestContext();
        requestContext.setAccountId("111111111111");
        requestContext.setRegion("us-east-1");
        @SuppressWarnings("unchecked")
        Instance<RequestContext> requestContextInstance = Mockito.mock(Instance.class);
        when(requestContextInstance.get()).thenReturn(requestContext);
        serverDelegate = new InMemoryStorage<>();
        serverStore = new AccountAwareStorageBackend<>(serverDelegate, requestContextInstance,
                "111111111111");
        userStore = new AccountAwareStorageBackend<>(new InMemoryStorage<>(), requestContextInstance,
                "111111111111");
        tagDelegate = new InMemoryStorage<>();
        tagStore = new AccountAwareStorageBackend<>(
                tagDelegate, requestContextInstance, "111111111111");
        tagStore = Mockito.spy(tagStore);
        StorageFactory factory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                AccountAwareStorageBackend<?> store = switch (fileName) {
                    case "transfer-servers.json" -> serverStore;
                    case "transfer-users.json" -> userStore;
                    case "transfer-tags.json" -> tagStore;
                    default -> throw new IllegalArgumentException(fileName);
                };
                @SuppressWarnings("unchecked")
                AccountAwareStorageBackend<V> typed = (AccountAwareStorageBackend<V>) store;
                return typed;
            }
        };
        regionResolver = Mockito.mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenAnswer(invocation -> requestContext.getAccountId());
        when(regionResolver.getRegion()).thenAnswer(invocation -> requestContext.getRegion());
        when(regionResolver.buildArn(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> AwsArnUtils.Arn.of(invocation.getArgument(0),
                        invocation.getArgument(1), requestContext.getAccountId(), invocation.getArgument(2)).toString());
        service = new TransferService(factory, null, regionResolver);
    }

    @Test
    void exactFinalPagesDoNotReturnAnotherToken() {
        Server server = service.createServer("us-east-1", null, null, null, null,
                null, null, null, null, null);
        service.createUser(server.getServerId(), "us-east-1", "alice", "role", null,
                null, null, null);
        ObjectMapper mapper = new ObjectMapper();
        TransferHandler handler = new TransferHandler(service, mapper);
        ObjectNode serverRequest = mapper.createObjectNode().put("MaxResults", 1);
        Response serversResponse = handler.handle("ListServers", serverRequest, "us-east-1");
        ObjectNode userRequest = mapper.createObjectNode().put("ServerId", server.getServerId())
                .put("MaxResults", 1);
        Response usersResponse = handler.handle("ListUsers", userRequest, "us-east-1");

        JsonNode servers = (JsonNode) serversResponse.getEntity();
        JsonNode users = (JsonNode) usersResponse.getEntity();
        assertAll(
                () -> assertEquals(1, servers.path("Servers").size()),
                () -> assertFalse(servers.has("NextToken"), "ListServers has no remaining page"),
                () -> assertEquals(1, users.path("Users").size()),
                () -> assertFalse(users.has("NextToken"), "ListUsers has no remaining page"));
    }

    @Test
    void listActionsReturnTokensOnlyWhileMoreItemsRemain() {
        Server firstServer = service.createServer("us-east-1", null, null, null, null,
                null, null, null, null, null);
        service.createServer("us-east-1", null, null, null, null,
                null, null, null, null, null);
        service.createUser(firstServer.getServerId(), "us-east-1", "alice", "role", null,
                null, null, null);
        service.createUser(firstServer.getServerId(), "us-east-1", "bob", "role", null,
                null, null, null);
        ObjectMapper mapper = new ObjectMapper();
        TransferHandler handler = new TransferHandler(service, mapper);

        ObjectNode serverRequest = mapper.createObjectNode().put("MaxResults", 1);
        JsonNode firstServers = (JsonNode) handler.handle("ListServers", serverRequest, "us-east-1").getEntity();
        assertTrue(firstServers.has("NextToken"));
        serverRequest.put("NextToken", firstServers.path("NextToken").asText());
        JsonNode lastServers = (JsonNode) handler.handle("ListServers", serverRequest, "us-east-1").getEntity();

        ObjectNode userRequest = mapper.createObjectNode().put("ServerId", firstServer.getServerId())
                .put("MaxResults", 1);
        JsonNode firstUsers = (JsonNode) handler.handle("ListUsers", userRequest, "us-east-1").getEntity();
        assertTrue(firstUsers.has("NextToken"));
        userRequest.put("NextToken", firstUsers.path("NextToken").asText());
        JsonNode lastUsers = (JsonNode) handler.handle("ListUsers", userRequest, "us-east-1").getEntity();

        assertAll(
                () -> assertEquals(1, firstServers.path("Servers").size()),
                () -> assertEquals(1, lastServers.path("Servers").size()),
                () -> assertFalse(lastServers.has("NextToken")),
                () -> assertEquals(1, firstUsers.path("Users").size()),
                () -> assertEquals("alice", firstUsers.path("Users").get(0).path("UserName").asText()),
                () -> assertEquals(1, lastUsers.path("Users").size()),
                () -> assertEquals("bob", lastUsers.path("Users").get(0).path("UserName").asText()),
                () -> assertFalse(lastUsers.has("NextToken")));
    }

    @Test
    void listActionsRejectPageSizesOutsideAwsRange() {
        ObjectMapper mapper = new ObjectMapper();
        TransferHandler handler = new TransferHandler(service, mapper);
        assertAll(
                () -> assertEquals(400, handler.handle("ListServers",
                        mapper.createObjectNode().put("MaxResults", 0), "us-east-1").getStatus()),
                () -> assertEquals(400, handler.handle("ListUsers",
                        mapper.createObjectNode().put("MaxResults", 1001), "us-east-1").getStatus()));
    }

    @Test
    void listActionsRejectUnknownNextTokens() {
        Server server = service.createServer("us-east-1", null, null, null, null,
                null, null, null, null, null);
        service.createUser(server.getServerId(), "us-east-1", "alice", "role", null,
                null, null, null);
        ObjectMapper mapper = new ObjectMapper();
        TransferHandler handler = new TransferHandler(service, mapper);

        Response servers = handler.handle("ListServers",
                mapper.createObjectNode().put("NextToken", "unknown-token"), "us-east-1");
        Response users = handler.handle("ListUsers", mapper.createObjectNode()
                .put("ServerId", server.getServerId()).put("NextToken", "unknown-token"), "us-east-1");

        assertAll(
                () -> assertEquals(400, servers.getStatus()),
                () -> assertEquals("InvalidNextTokenException;Sender",
                        servers.getHeaderString("x-amzn-query-error")),
                () -> assertEquals(400, users.getStatus()),
                () -> assertEquals("InvalidNextTokenException;Sender",
                        users.getHeaderString("x-amzn-query-error")));
    }

    @Test
    void listActionsRejectTokensFromOtherScopes() {
        Server firstServer = service.createServer("us-east-1", null, null, null, null,
                null, null, null, null, null);
        Server secondServer = service.createServer("us-east-1", null, null, null, null,
                null, null, null, null, null);
        service.createUser(firstServer.getServerId(), "us-east-1", "alice", "role", null,
                null, null, null);
        service.createUser(firstServer.getServerId(), "us-east-1", "bob", "role", null,
                null, null, null);
        ObjectMapper mapper = new ObjectMapper();
        TransferHandler handler = new TransferHandler(service, mapper);

        String serverToken = ((JsonNode) handler.handle("ListServers",
                mapper.createObjectNode().put("MaxResults", 1), "us-east-1").getEntity())
                .path("NextToken").asText();
        String userToken = ((JsonNode) handler.handle("ListUsers", mapper.createObjectNode()
                .put("ServerId", firstServer.getServerId()).put("MaxResults", 1), "us-east-1")
                .getEntity()).path("NextToken").asText();

        Response serverTokenOnUsers = handler.handle("ListUsers", mapper.createObjectNode()
                .put("ServerId", firstServer.getServerId()).put("NextToken", serverToken), "us-east-1");
        Response userTokenOnOtherServer = handler.handle("ListUsers", mapper.createObjectNode()
                .put("ServerId", secondServer.getServerId()).put("NextToken", userToken), "us-east-1");
        Response alteredServerToken = handler.handle("ListServers", mapper.createObjectNode()
                .put("NextToken", serverToken + "x"), "us-east-1");

        assertAll(
                () -> assertEquals(400, serverTokenOnUsers.getStatus()),
                () -> assertEquals(400, userTokenOnOtherServer.getStatus()),
                () -> assertEquals(400, alteredServerToken.getStatus()));
    }

    @Test
    void listUsersTokenResumesAfterItsCursorIsDeleted() {
        Server server = service.createServer("us-east-1", null, null, null, null,
                null, null, null, null, null);
        for (String name : List.of("alice", "bob", "carol")) {
            service.createUser(server.getServerId(), "us-east-1", name, "role", null,
                    null, null, null);
        }
        ObjectMapper mapper = new ObjectMapper();
        TransferHandler handler = new TransferHandler(service, mapper);
        ObjectNode request = mapper.createObjectNode().put("ServerId", server.getServerId())
                .put("MaxResults", 1);
        String token = ((JsonNode) handler.handle("ListUsers", request, "us-east-1").getEntity())
                .path("NextToken").asText();
        service.deleteUser(server.getServerId(), "alice");
        request.put("NextToken", token);

        JsonNode next = (JsonNode) handler.handle("ListUsers", request, "us-east-1").getEntity();
        assertEquals("bob", next.path("Users").get(0).path("UserName").asText());
        assertTrue(next.has("NextToken"));
    }

    @Test
    void failedTagWriteDoesNotLeaveCreatedServerBehind() {
        doThrow(new IllegalStateException("tag write failed"))
                .when(tagStore).putForAccount(anyString(), anyString(), any());

        assertThrows(IllegalStateException.class, () -> service.createServer("us-east-1", null, null,
                null, null, null, null, null, null, Map.of("stage", "test")));

        assertTrue(serverDelegate.keys().isEmpty());
        assertTrue(tagDelegate.keys().isEmpty());
    }

    @Test
    void serversAndUsersAreIsolatedByAccountAndRegion() {
        Server first = service.createServer("us-east-1", null, null, null, null,
                null, null, null, null, null);
        User firstUser = service.createUser(first.getServerId(), "us-east-1", "alice", "role", null,
                null, null, null);

        requestContext.setAccountId("222222222222");
        requestContext.setRegion("eu-west-1");
        Server second = service.createServer("eu-west-1", null, null, null, null,
                null, null, null, null, null);
        User secondUser = service.createUser(second.getServerId(), "eu-west-1", "alice", "role", null,
                null, null, null);

        assertEquals(List.of(second.getServerId()), service.listServers(null, 100).items().stream()
                .map(Server::getServerId).toList());
        assertEquals(secondUser.getArn(), service.getUser(second.getServerId(), "alice").getArn());
        assertThrows(AwsException.class, () -> service.getServer(first.getServerId()));
        assertThrows(AwsException.class, () -> service.getUser(first.getServerId(), "alice"));

        requestContext.setAccountId("111111111111");
        requestContext.setRegion("us-east-1");
        assertEquals(firstUser.getArn(), service.getUser(first.getServerId(), "alice").getArn());
    }

    @Test
    void deletingServerCascadesOnlyWithinItsScope() {
        Server first = service.createServer("us-east-1", null, null, null, null,
                null, null, null, null, null);
        service.createUser(first.getServerId(), "us-east-1", "alice", "role", null,
                null, null, null);
        service.importSshPublicKey(first.getServerId(), "alice", "ssh-rsa AAAA");

        requestContext.setAccountId("222222222222");
        requestContext.setRegion("eu-west-1");
        Server second = service.createServer("eu-west-1", null, null, null, null,
                null, null, null, null, null);
        service.createUser(second.getServerId(), "eu-west-1", "alice", "role", null,
                null, null, null);

        service.deleteServer(second.getServerId());
        requestContext.setAccountId("111111111111");
        requestContext.setRegion("us-east-1");
        service.importSshPublicKey(first.getServerId(), "alice", "ssh-rsa BBBB");
        assertEquals(2, service.getUser(first.getServerId(), "alice").getSshPublicKeys().size(),
                "the first scope must remain available");
        assertThrows(AwsException.class, () -> service.getUser(second.getServerId(), "alice"));
    }

    @Test
    void taggingForeignArnIsRejected() {
        Server first = service.createServer("us-east-1", null, null, null, null,
                null, null, null, null, null);
        service.tagResource(first.getArn(), Map.of("owner", "first"));

        requestContext.setAccountId("222222222222");
        requestContext.setRegion("eu-west-1");
        AwsException error = assertThrows(AwsException.class,
                () -> service.tagResource(first.getArn(), Map.of("owner", "foreign")));

        assertEquals(404, error.getHttpStatus());
        requestContext.setAccountId("111111111111");
        requestContext.setRegion("us-east-1");
        assertEquals(Map.of("owner", "first"), service.listTagsForResource(first.getArn()));
    }

    @Test
    void legacyServerIsMigratedOnlyWhenArnMatchesScope() {
        Server legacy = new Server();
        legacy.setServerId("s-legacy");
        legacy.setArn("arn:aws:transfer:us-east-1:111111111111:server/s-legacy");
        serverDelegate.put("s-legacy", legacy);

        assertEquals("s-legacy", service.getServer("s-legacy").getServerId());

        when(regionResolver.getAccountId()).thenReturn("222222222222");
        when(regionResolver.getRegion()).thenReturn("eu-west-1");
        assertThrows(AwsException.class, () -> service.getServer("s-legacy"));
    }

    @Test
    void listMigratesAccountPrefixedLegacyServerAndUserRecords() {
        Server server = new Server();
        server.setServerId("s-legacy");
        server.setArn("arn:aws:transfer:us-east-1:111111111111:server/s-legacy");
        serverStore.putForAccount("111111111111", "s-legacy", server);

        User user = new User();
        user.setUserName("alice");
        user.setArn("arn:aws:transfer:us-east-1:111111111111:user/s-legacy/alice");
        userStore.putForAccount("111111111111", "s-legacy/alice", user);

        assertEquals(1, service.listServers(null, 100).items().size());
        assertEquals(1, service.listUsers("s-legacy", null, 100).items().size());
        assertTrue(serverStore.getForAccount("111111111111", "s-legacy").isEmpty());
        assertTrue(userStore.getForAccount("111111111111", "s-legacy/alice").isEmpty());
        assertTrue(serverStore.getForAccount("111111111111", "us-east-1/s-legacy").isPresent());
        assertTrue(userStore.getForAccount("111111111111", "us-east-1/s-legacy/alice").isPresent());
        assertEquals(1, service.countUsers("s-legacy"));
    }

    @Test
    void legacyTagsAreMigratedWithoutDroppingResourceTags() {
        Server server = service.createServer("us-east-1", null, null, null, null,
                null, null, null, null, Map.of("resource", "tag"));
        tagStore.putForAccount("111111111111", "server/" + server.getServerId(),
                new HashMap<>(Map.of("legacy", "tag")));

        service.tagResource(server.getArn(), Map.of("new", "tag"));

        assertEquals(Map.of("legacy", "tag", "resource", "tag", "new", "tag"),
                service.listTagsForResource(server.getArn()));
        assertTrue(tagStore.getForAccount("111111111111", "server/" + server.getServerId()).isEmpty());
        assertTrue(tagStore.getForAccount("111111111111", "us-east-1/server/" + server.getServerId()).isPresent());
    }

    @Test
    void fullyUnscopedLegacyTagsAreMigratedToTheScopedKey() {
        Server server = service.createServer("us-east-1", null, null, null, null,
                null, null, null, null, null);
        String legacyKey = "server/" + server.getServerId();
        tagDelegate.put(legacyKey, Map.of("legacy", "tag"));

        assertEquals(Map.of("legacy", "tag"), service.listTagsForResource(server.getArn()));
        assertTrue(tagDelegate.get(legacyKey).isEmpty());
        assertEquals(Map.of("legacy", "tag"),
                tagStore.getForAccount("111111111111", "us-east-1/" + legacyKey).orElseThrow());
    }

    @Test
    void scopedTagsWinOverFullyUnscopedLegacyDuplicates() {
        Server server = service.createServer("us-east-1", null, null, null, null,
                null, null, null, null, null);
        String legacyKey = "server/" + server.getServerId();
        tagDelegate.put(legacyKey, Map.of("legacy", "tag"));
        tagStore.putForAccount("111111111111", "us-east-1/" + legacyKey,
                Map.of("current", "tag"));

        assertEquals(Map.of("current", "tag"), service.listTagsForResource(server.getArn()));
        assertTrue(tagDelegate.get(legacyKey).isEmpty());
    }

    @Test
    void updateServerPreservesOmittedDetailsAndClearsExplicitEmptyMaps() {
        Server server = service.createServer("us-east-1", null, null, "VPC",
                Map.of("VpcId", "vpc-123"), "API_GATEWAY",
                Map.of("Url", "https://idp.example.com"), null, null, null);

        Server preserved = service.updateServer(server.getServerId(), null, null, null,
                null, null, null);

        assertEquals(Map.of("VpcId", "vpc-123"), preserved.getEndpointDetails());
        assertEquals(Map.of("Url", "https://idp.example.com"), preserved.getIdentityProviderDetails());

        Server cleared = service.updateServer(server.getServerId(), null, null, Map.of(),
                Map.of(), null, null);

        assertTrue(cleared.getEndpointDetails().isEmpty());
        assertTrue(cleared.getIdentityProviderDetails().isEmpty());
    }
}
