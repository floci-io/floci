package io.github.hectorvent.floci.services.identitystore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.identitystore.model.Group;
import io.github.hectorvent.floci.services.identitystore.model.Membership;
import io.github.hectorvent.floci.services.identitystore.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentityStorePersistenceTest {
    private static final String STORE = "d-1234567890";
    private static final String GLOBAL_PARTITION = "000000000000";

    private final ObjectMapper mapper = new ObjectMapper();

    private StorageFactory newFactory(Path dir, String mode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(GLOBAL_PARTITION);
        when(config.storage().persistentPath()).thenReturn(dir.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(3_600_000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn(mode);
        when(access.storageFlushInterval(anyString())).thenReturn(3_600_000L);
        return new StorageFactory(config, access);
    }

    private ObjectNode idRequest(String idField, String id) {
        ObjectNode request = mapper.createObjectNode();
        request.put("IdentityStoreId", STORE);
        request.put(idField, id);
        return request;
    }

    private record Seeded(User user, Group group, Membership membership) {}

    private Seeded seed(IdentityStoreService service) {
        ObjectNode userRequest = mapper.createObjectNode();
        userRequest.put("IdentityStoreId", STORE);
        userRequest.put("UserName", "alice");
        userRequest.put("DisplayName", "Alice");
        userRequest.putObject("Name").put("GivenName", "Alice").put("FamilyName", "Liddell");
        User user = service.createUser(userRequest);

        ObjectNode groupRequest = mapper.createObjectNode();
        groupRequest.put("IdentityStoreId", STORE);
        groupRequest.put("DisplayName", "Admins");
        groupRequest.put("Description", "Administrators");
        Group group = service.createGroup(groupRequest);

        ObjectNode membershipRequest = mapper.createObjectNode();
        membershipRequest.put("IdentityStoreId", STORE);
        membershipRequest.put("GroupId", group.groupId());
        membershipRequest.putObject("MemberId").put("UserId", user.userId());
        Membership membership = service.createMembership(membershipRequest);
        return new Seeded(user, group, membership);
    }

    private void assertReloaded(IdentityStoreService reloaded, Seeded seeded) {
        User user = reloaded.describeUser(idRequest("UserId", seeded.user().userId()));
        assertNotNull(user.attributes());
        assertEquals("alice", user.userName());
        assertEquals("Alice", user.displayName());
        assertEquals("Liddell", user.attributes().path("Name").path("FamilyName").asText());
        assertEquals(seeded.user().createdAt(), user.createdAt());
        assertEquals(1, reloaded.listUsers(idRequest("IdentityStoreId", STORE)).items().size());

        Group group = reloaded.describeGroup(idRequest("GroupId", seeded.group().groupId()));
        assertEquals("Admins", group.displayName());
        assertEquals("Administrators", group.description());

        assertEquals(seeded.membership().membershipId(),
                reloaded.listGroupMemberships(idRequest("GroupId", seeded.group().groupId()))
                        .items().get(0).membershipId());
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path source : files.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void usersGroupsAndMembershipsSurviveRestart(String mode, @TempDir Path dir) {
        StorageFactory firstFactory = newFactory(dir, mode);
        IdentityStoreService first = new IdentityStoreService(firstFactory, mapper);
        Seeded seeded = seed(first);

        firstFactory.shutdownAll();

        StorageFactory reopenedFactory = newFactory(dir, mode);
        IdentityStoreService reopened = new IdentityStoreService(reopenedFactory, mapper);
        assertReloaded(reopened, seeded);
        reopenedFactory.shutdownAll();
    }

    @Test
    void usersGroupsAndMembershipsSurviveWalReplayAfterCrash(@TempDir Path dirA, @TempDir Path dirB)
            throws IOException {
        StorageFactory crashedFactory = newFactory(dirA, "wal");
        StorageFactory recoveredFactory = null;
        try {
            IdentityStoreService crashed = new IdentityStoreService(crashedFactory, mapper);
            Seeded seeded = seed(crashed);

            copyTree(dirA, dirB);

            List<Path> wals;
            try (Stream<Path> files = Files.list(dirB)) {
                wals = files.filter(path -> path.getFileName().toString().endsWith(".wal")).toList();
            }
            assertEquals(3, wals.size());
            for (Path wal : wals) {
                assertTrue(Files.size(wal) > 0, wal + " must hold the journaled records");
            }
            try (Stream<Path> files = Files.list(dirB)) {
                for (Path snapshot : files.filter(path -> path.getFileName().toString().endsWith("-snapshot.json")).toList()) {
                    assertFalse(Files.readString(snapshot).contains("alice"),
                            snapshot + " must not hold the data, or the test would not prove a replay");
                }
            }

            recoveredFactory = newFactory(dirB, "wal");
            IdentityStoreService recovered = new IdentityStoreService(recoveredFactory, mapper);
            assertReloaded(recovered, seeded);
        } finally {
            crashedFactory.shutdownAll();
            if (recoveredFactory != null) {
                recoveredFactory.shutdownAll();
            }
        }
    }
}
