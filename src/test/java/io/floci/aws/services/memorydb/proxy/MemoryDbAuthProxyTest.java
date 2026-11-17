package io.floci.aws.services.memorydb.proxy;

import io.floci.aws.testing.FakeRespServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class MemoryDbAuthProxyTest {

    private FakeRespServer backend;

    @BeforeEach
    void startBackend() throws IOException {
        backend = FakeRespServer.answeringOk();
    }

    @AfterEach
    void stopBackend() throws IOException {
        backend.close();
    }

    @Test
    void aClusterWithAnAclOfItsOwnRunsEachSessionUnderItsUsersAccessString() throws Exception {
        MemoryDbAuthProxy proxy = proxy(true, Map.of("reader", "on ~app:* -@all +get"));

        initialize(proxy, "reader");

        List<List<String>> commands = backend.commands();
        assertEquals(List.of("ACL", "SETUSER", "default", "reset", "off"), commands.get(2),
                "the backend's default user must not stay open to a client that sends AUTH again");
        List<String> rules = commands.get(3);
        assertEquals(List.of("ACL", "SETUSER", "reader", "reset", "on", "~app:*", "-@all", "+get"), rules.subList(0, 8));
        assertTrue(rules.contains("-config"));
        assertEquals(List.of("AUTH", "reader", rules.get(rules.indexOf("resetpass") + 1).substring(1)),
                commands.get(4));
    }

    @Test
    void aSessionWithoutAuthRunsAsTheAclsDefaultUser() throws Exception {
        MemoryDbAuthProxy proxy = proxy(true, Map.of("default", "on ~* &* +@all"));

        initialize(proxy, null);

        List<String> rules = backend.commands().get(3);
        assertTrue(rules.get(2).startsWith("floci-default-"), "default must not reopen the backend's own default user");
        assertEquals(List.of("reset", "on", "~*", "&*", "+@all"), rules.subList(3, 8));
    }

    @Test
    void aUserTheAclNoLongerHoldsIsRefused() {
        MemoryDbAuthProxy proxy = proxy(true, Map.of());

        assertThrows(IOException.class, () -> initialize(proxy, "gone"));
    }

    @Test
    void anOpenAccessClusterRelaysWithoutTouchingTheBackend() throws Exception {
        MemoryDbAuthProxy proxy = proxy(false, Map.of("default", "on ~* &* +@all"));
        Socket socket = mock(Socket.class);

        proxy.initializeBackend(socket, "default");

        verifyNoInteractions(socket);
    }

    private void initialize(MemoryDbAuthProxy proxy, String username) throws IOException {
        try (Socket socket = new Socket("localhost", backend.port())) {
            proxy.initializeBackend(socket, username);
        }
    }

    private MemoryDbAuthProxy proxy(boolean ownAcl, Map<String, String> accessStrings) {
        return new MemoryDbAuthProxy("cluster", true, "localhost", backend.port(),
                new MemoryDbAuthProxy.AuthValidator() {
                    @Override
                    public boolean authenticate(String username, String secret) {
                        return true;
                    }

                    @Override
                    public boolean enforcesAccessStrings() {
                        return ownAcl;
                    }

                    @Override
                    public String accessString(String username) {
                        return accessStrings.get(username);
                    }
                });
    }
}
