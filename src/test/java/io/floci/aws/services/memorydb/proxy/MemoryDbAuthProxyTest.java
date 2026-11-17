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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void aSessionOfAClusterWithAnAclOfItsOwnSignsInAgainAsItsNewUsersBackendUser() throws Exception {
        MemoryDbAuthProxy proxy = proxy(true, Map.of("reader", "on ~app:* -@all +get"));

        List<String> credentials = proxy.backendCredentials("reader");

        List<String> rules = backend.commands().get(3);
        assertEquals(List.of("ACL", "SETUSER", "reader", "reset", "on", "~app:*", "-@all", "+get"), rules.subList(0, 8));
        assertEquals(List.of("reader", rules.get(rules.indexOf("resetpass") + 1).substring(1)), credentials);
    }

    @Test
    void aSessionOfAnOpenAccessClusterSignsInAgainAsTheBackendsOwnDefaultUser() throws Exception {
        MemoryDbAuthProxy proxy = proxy(false, Map.of());

        assertEquals("default", proxy.backendCredentials("reader").get(0));
        assertEquals(List.of(), backend.commands());
    }

    @Test
    void credentialsAreCheckedAfterSignInUnlessTheClusterIsOpenAccess() {
        assertTrue(proxy(true, Map.of()).checksCredentials());
        MemoryDbAuthProxy.AuthValidator passwordless = (username, secret) -> true;
        assertFalse(new MemoryDbAuthProxy("open", false, "localhost", backend.port(), passwordless)
                .checksCredentials());
        MemoryDbAuthProxy ownAclWithoutPasswords = new MemoryDbAuthProxy("own", false, "localhost", backend.port(),
                new MemoryDbAuthProxy.AuthValidator() {
                    @Override
                    public boolean authenticate(String username, String secret) {
                        return true;
                    }

                    @Override
                    public boolean enforcesAccessStrings() {
                        return true;
                    }
                });
        assertTrue(ownAclWithoutPasswords.checksCredentials(),
                "a user of an ACL without passwords can still sign in again as another user");
    }

    @Test
    void theAclDecidesWhichUsersSignInWithIam() {
        MemoryDbAuthProxy proxy = new MemoryDbAuthProxy("cluster", true, "localhost", backend.port(),
                new MemoryDbAuthProxy.AuthValidator() {
                    @Override
                    public boolean authenticate(String username, String secret) {
                        return true;
                    }

                    @Override
                    public boolean signsInWithIam(String username) {
                        return "iam-user".equals(username);
                    }
                });

        assertTrue(proxy.signsInWithIam("iam-user"));
        assertFalse(proxy.signsInWithIam("reader"));
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
