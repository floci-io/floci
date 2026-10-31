package io.github.hectorvent.floci.services.elasticache.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElastiCacheBackendAclTest {

    private static final int WAIT_MILLIS = 5000;

    private ServerSocket server;
    private final List<List<String>> commands = new CopyOnWriteArrayList<>();
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();
    private volatile boolean refuseNextCommands;
    private ElastiCacheBackendAcl acl;

    @BeforeEach
    void startBackend() throws IOException {
        server = new ServerSocket(0);
        acl = new ElastiCacheBackendAcl();
        Thread acceptor = new Thread(this::acceptConnections, "fake-backend");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stopBackend() throws IOException {
        server.close();
        for (Socket socket : accepted) {
            socket.close();
        }
    }

    @Test
    void reapplyInstallsTheNewRulesOnTheBackendUserThatLoginCreated() throws Exception {
        login("app", "on ~app:* -@all +get");
        int afterLogin = commands.size();
        String sessionPassword = passwordOf("app");

        acl.reapply("localhost", server.getLocalPort(), "app", "on ~app:* -@all +get +set");

        List<List<String>> sent = commands.subList(afterLogin, commands.size());
        assertEquals(2, sent.size());
        assertEquals("AUTH", sent.get(0).get(0));
        assertTrue(sent.get(0).get(1).startsWith("floci-admin-"));
        assertEquals(List.of("ACL", "SETUSER", "app", "reset", "on", "~app:*", "-@all", "+get", "+set", "resetpass",
                ">" + sessionPassword), sent.get(1).subList(0, 11));
        assertTrue(sent.get(1).contains("-acl"));
        assertTrue(sent.get(1).contains("-save"));
    }

    @Test
    void defaultUserIsReappliedToTheSameBackendUserLoginUsed() throws Exception {
        login("default", "on ~* +@all");
        String backendUser = commands.stream()
                .filter(c -> c.size() > 2 && "SETUSER".equals(c.get(1)) && c.get(2).startsWith("floci-default-"))
                .findFirst().orElseThrow().get(2);
        int afterLogin = commands.size();

        acl.reapply("localhost", server.getLocalPort(), "default", "on ~allowed:* +get");

        assertEquals(backendUser, commands.get(afterLogin + 1).get(2));
    }

    @Test
    void nothingIsSentBeforeTheBackendIsSetUp() throws Exception {
        acl.reapply("localhost", server.getLocalPort(), "app", "on ~* +@all");

        assertEquals(0, accepted.size());
    }

    @Test
    void nothingIsSentForAUserNobodyHasAuthenticatedAs() throws Exception {
        login("app", "on ~* +@all");
        int connections = accepted.size();

        acl.reapply("localhost", server.getLocalPort(), "other", "on ~* +@all");

        assertEquals(connections, accepted.size());
    }

    @Test
    void selectorsAreRefusedWithoutTouchingTheBackend() throws Exception {
        login("app", "on ~* +@all");
        int connections = accepted.size();

        assertThrows(IOException.class,
                () -> acl.reapply("localhost", server.getLocalPort(), "app", "on ~* -@all (+get ~*)"));

        assertEquals(connections, accepted.size());
    }

    @Test
    void backendRefusalIsReportedWithoutExposingItsResponse() throws Exception {
        login("app", "on ~* +@all");
        refuseNextCommands = true;

        IOException failure = assertThrows(IOException.class,
                () -> acl.reapply("localhost", server.getLocalPort(), "app", "on ~* +@all"));

        assertEquals("Could not establish the backend cache user's permissions", failure.getMessage());
    }

    @Test
    void rulesAreReadUnderTheLockSoStaleRulesCannotOverwriteNewerRules() throws Exception {
        login("app", "on ~app:* -@all +get");
        int afterLogin = commands.size();

        AtomicReference<String> currentRules = new AtomicReference<>("on ~app:* -@all +get +set");
        acl.reapply("localhost", server.getLocalPort(), "app", currentRules::get);

        currentRules.set("on ~app:* -@all +get");
        acl.reapply("localhost", server.getLocalPort(), "app", currentRules::get);

        List<List<String>> sent = commands.subList(afterLogin, commands.size());
        assertEquals(4, sent.size());
        assertTrue(sent.get(1).contains("+set"));
        assertFalse(sent.get(3).contains("+set"));
    }

    @Test
    void disabledUserAccessStringIsReappliedToBackend() throws Exception {
        login("app", "on ~app:* -@all +get");
        int afterLogin = commands.size();

        acl.reapply("localhost", server.getLocalPort(), "app", "off -@all");

        List<List<String>> sent = commands.subList(afterLogin, commands.size());
        assertEquals(2, sent.size());
        assertEquals("ACL", sent.get(1).get(0));
        assertEquals("SETUSER", sent.get(1).get(1));
        assertEquals("app", sent.get(1).get(2));
        assertEquals("reset", sent.get(1).get(3));
        assertEquals("off", sent.get(1).get(4));
        assertEquals("-@all", sent.get(1).get(5));
    }

    private void login(String username, String accessString) throws IOException {
        try (Socket backend = new Socket("localhost", server.getLocalPort())) {
            acl.authenticate(backend, username, accessString);
        }
    }

    private String passwordOf(String backendUser) {
        return commands.stream()
                .filter(c -> c.size() > 2 && "SETUSER".equals(c.get(1)) && backendUser.equals(c.get(2)))
                .map(c -> c.get(c.indexOf("resetpass") + 1).substring(1))
                .reduce((first, second) -> second).orElseThrow();
    }

    private void acceptConnections() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                accepted.add(socket);
                Thread handler = new Thread(() -> serve(socket), "fake-backend-connection");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            while (true) {
                List<String> command = readCommand(in);
                if (command == null) {
                    return;
                }
                commands.add(command);
                out.write((refuseNextCommands ? "-ERR refused\r\n" : "+OK\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } catch (IOException expected) {
            // The test closed the connection, which is how every fake connection ends.
        }
    }

    private static List<String> readCommand(InputStream in) throws IOException {
        String header = readLine(in);
        if (header == null) {
            return null;
        }
        int count = Integer.parseInt(header.substring(1));
        List<String> arguments = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int length = Integer.parseInt(readLine(in).substring(1));
            byte[] bytes = in.readNBytes(length);
            readLine(in);
            arguments.add(new String(bytes, StandardCharsets.UTF_8));
        }
        return arguments;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = in.read()) != -1) {
            if (value == '\n') {
                return line.toString();
            }
            if (value != '\r') {
                line.append((char) value);
            }
        }
        return null;
    }
}
