package io.github.hectorvent.floci.services.transfer;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.inject.Instance;
import io.github.hectorvent.floci.services.transfer.model.Server;
import io.github.hectorvent.floci.services.transfer.model.SshPublicKey;
import io.github.hectorvent.floci.services.transfer.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class TransferServiceTest {

    private AccountAwareStorageBackend<Server> serverStore;
    private AccountAwareStorageBackend<User> userStore;
    private InMemoryStorage<String, Server> serverDelegate;
    private AccountAwareStorageBackend<Map<String, String>> tagStore;
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
        tagStore = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), requestContextInstance, "111111111111");
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

        assertEquals(List.of(second.getServerId()), service.listServers(null, 100).stream()
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

        assertEquals(1, service.listServers(null, 100).size());
        assertEquals(1, service.listUsers("s-legacy", null, 100).size());
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
                new java.util.HashMap<>(Map.of("legacy", "tag")));

        service.tagResource(server.getArn(), Map.of("new", "tag"));

        assertEquals(Map.of("legacy", "tag", "resource", "tag", "new", "tag"),
                service.listTagsForResource(server.getArn()));
        assertTrue(tagStore.getForAccount("111111111111", "server/" + server.getServerId()).isEmpty());
        assertTrue(tagStore.getForAccount("111111111111", "us-east-1/server/" + server.getServerId()).isPresent());
    }
}
