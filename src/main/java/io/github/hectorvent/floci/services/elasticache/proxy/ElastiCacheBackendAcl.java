package io.github.hectorvent.floci.services.elasticache.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Keeps frontend passwords and IAM tokens out of the backend's private ACL credentials. */
final class ElastiCacheBackendAcl {
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int COMMAND_TIMEOUT_MILLIS = 10000;

    private final String administrator = "floci-admin-" + UUID.randomUUID();
    private final String administratorPassword = UUID.randomUUID().toString();
    private final Map<String, String> passwords = new LinkedHashMap<>();
    private volatile boolean initialized;

    boolean initialized() {
        return initialized;
    }

    synchronized void authenticate(Socket backend, String username, Supplier<String> accessStringSupplier)
            throws IOException {
        String accessString = accessStringSupplier.get();
        if (accessString == null) {
            throw new IOException("Cache user is no longer associated with this resource");
        }
        List<String> accessRules = accessRules(accessString);
        backend.setSoTimeout(COMMAND_TIMEOUT_MILLIS);
        if (!initialized) {
            command(backend, List.of("ACL", "SETUSER", administrator, "reset", "on",
                    ">" + administratorPassword, "~*", "&*", "+@all"));
            command(backend, List.of("AUTH", administrator, administratorPassword));
            command(backend, List.of("ACL", "SETUSER", "default", "reset", "off"));
            initialized = true;
        } else {
            command(backend, List.of("AUTH", administrator, administratorPassword));
        }
        String backendName = backendName(username);
        String password = passwords.computeIfAbsent(backendName, ignored -> UUID.randomUUID().toString());
        command(backend, userRules(backendName, accessRules, password));
        command(backend, List.of("AUTH", backendName, password));
        backend.setSoTimeout(0);
    }

    void authenticate(Socket backend, String username, String accessString) throws IOException {
        authenticate(backend, username, () -> accessString);
    }

    /**
     * Installs a changed access string on the backend user that sessions of {@code username} already run
     * as, so it applies to their open connections and not only to the next login. Nothing is done while
     * the backend has not been set up, or for a user nobody has authenticated as yet: the next login
     * installs the current rules.
     */
    synchronized void reapply(String backendHost, int backendPort, String username,
                              Supplier<String> accessStringSupplier) throws IOException {
        if (!initialized) {
            return;
        }
        String backendName = backendName(username);
        String password = passwords.get(backendName);
        if (password == null) {
            return;
        }
        String accessString = accessStringSupplier.get();
        if (accessString == null) {
            return;
        }
        List<String> accessRules = accessRules(accessString);
        try (Socket backend = new Socket()) {
            backend.connect(new InetSocketAddress(backendHost, backendPort), CONNECT_TIMEOUT_MILLIS);
            backend.setSoTimeout(COMMAND_TIMEOUT_MILLIS);
            command(backend, List.of("AUTH", administrator, administratorPassword));
            command(backend, userRules(backendName, accessRules, password));
        }
    }

    void reapply(String backendHost, int backendPort, String username, String accessString) throws IOException {
        reapply(backendHost, backendPort, username, () -> accessString);
    }

    private String backendName(String username) {
        return "default".equals(username) ? "floci-default-" + administrator : username;
    }

    private static List<String> accessRules(String accessString) throws IOException {
        List<String> accessRules = List.of(accessString.trim().split("\\s+"));
        if (accessRules.stream().anyMatch(rule -> rule.startsWith("("))) {
            // Selectors can grant permissions independently of the root command restrictions below.
            throw new IOException("Cache ACL selectors are not supported yet");
        }
        return accessRules;
    }

    private static List<String> userRules(String backendName, List<String> accessRules, String password) {
        List<String> rules = new ArrayList<>(List.of("ACL", "SETUSER", backendName, "reset"));
        rules.addAll(accessRules);
        // Authentication rules are owned by the proxy. A supplied nopass or password token
        // must never make an alternate backend identity publicly accessible.
        rules.addAll(List.of("resetpass", ">" + password, "-acl", "-config", "-debug", "-module",
                "-replicaof", "-slaveof", "-shutdown", "-monitor", "-slowlog", "-sync", "-psync",
                "-save", "-bgsave", "-bgrewriteaof", "-migrate"));
        return rules;
    }

    private static void command(Socket socket, List<String> arguments) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(("*" + arguments.size() + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String argument : arguments) {
            byte[] bytes = argument.getBytes(StandardCharsets.UTF_8);
            out.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        out.flush();
        InputStream in = socket.getInputStream();
        StringBuilder response = new StringBuilder();
        int value;
        while ((value = in.read()) != -1 && value != '\r' && response.length() < 4096) {
            response.append((char) value);
        }
        if (value != '\r' || in.read() != '\n' || !"+OK".contentEquals(response)) {
            // Never include server responses, access strings or credentials in this error.
            throw new IOException("Could not establish the backend cache user's permissions");
        }
    }
}
