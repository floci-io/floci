package io.github.hectorvent.floci.services.elasticache.container;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Forms a Valkey cluster out of freshly started cluster-enabled nodes, the way
 * {@code valkey-cli --cluster create} does: distinct config epochs, MEET, slot
 * assignment on the primaries, then REPLICATE for the replicas.
 *
 * <p>Commands are issued from the JVM over each node's Floci-reachable endpoint;
 * the addresses handed to MEET are the nodes' Docker-network IPs, because that is
 * where the nodes reach <em>each other</em>.
 */
@ApplicationScoped
public class ValkeyClusterFormation {

    private static final Logger LOG = Logger.getLogger(ValkeyClusterFormation.class);

    private static final int BACKEND_PORT = 6379;
    private static final int TOTAL_SLOTS = 16384;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final long FORMATION_DEADLINE_MS = 60_000;
    private static final long RETRY_SLEEP_MS = 200;

    /**
     * One node to join into the cluster.
     *
     * @param endpointHost host Floci uses to reach the node
     * @param endpointPort port Floci uses to reach the node
     * @param networkIp    the node's Docker-network IP, dialled by its peers
     * @param nodeGroup    zero-based shard index
     * @param primary      whether this node holds the shard's slots
     */
    public record Node(String endpointHost, int endpointPort, String networkIp,
                       int nodeGroup, boolean primary) {}

    public void form(String groupId, List<Node> nodes, int numNodeGroups) {
        long deadline = System.currentTimeMillis() + FORMATION_DEADLINE_MS;
        List<RespClient> clients = new ArrayList<>(nodes.size());
        try {
            for (Node node : nodes) {
                clients.add(new RespClient(node.endpointHost(), node.endpointPort()));
            }

            List<String> nodeIds = new ArrayList<>(nodes.size());
            for (RespClient client : clients) {
                nodeIds.add(client.callString("CLUSTER", "MYID"));
            }

            for (int i = 0; i < clients.size(); i++) {
                try {
                    clients.get(i).callString("CLUSTER", "SET-CONFIG-EPOCH", String.valueOf(i + 1));
                } catch (RespError e) {
                    LOG.debugv("SET-CONFIG-EPOCH on node {0} of group {1}: {2}",
                            String.valueOf(i), groupId, e.getMessage());
                }
            }

            for (int i = 1; i < nodes.size(); i++) {
                clients.getFirst().callString("CLUSTER", "MEET",
                        nodes.get(i).networkIp(), String.valueOf(BACKEND_PORT));
            }

            for (int i = 0; i < nodes.size(); i++) {
                Node node = nodes.get(i);
                if (node.primary()) {
                    int[] range = slotRange(node.nodeGroup(), numNodeGroups);
                    clients.get(i).callString("CLUSTER", "ADDSLOTSRANGE",
                            String.valueOf(range[0]), String.valueOf(range[1]));
                }
            }

            awaitKnownNodes(groupId, clients, nodes.size(), deadline);

            for (int i = 0; i < nodes.size(); i++) {
                Node node = nodes.get(i);
                if (!node.primary()) {
                    String primaryId = nodeIds.get(primaryIndex(nodes, node.nodeGroup()));
                    replicateWithRetry(groupId, clients.get(i), primaryId, deadline);
                }
            }

            awaitClusterOk(groupId, clients, deadline);
            LOG.infov("Valkey cluster for group {0} formed: {1} shard(s), {2} node(s)",
                    groupId, String.valueOf(numNodeGroups), String.valueOf(nodes.size()));
        } catch (IOException e) {
            throw new RuntimeException("Cluster formation for group " + groupId + " failed: " + e.getMessage(), e);
        } finally {
            clients.forEach(RespClient::closeQuietly);
        }
    }

    /** The contiguous slot range served by the given shard, covering all 16384 slots overall. */
    public static int[] slotRange(int nodeGroup, int numNodeGroups) {
        int start = nodeGroup * TOTAL_SLOTS / numNodeGroups;
        int end = (nodeGroup + 1) * TOTAL_SLOTS / numNodeGroups - 1;
        return new int[] {start, end};
    }

    private static int primaryIndex(List<Node> nodes, int nodeGroup) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).primary() && nodes.get(i).nodeGroup() == nodeGroup) {
                return i;
            }
        }
        throw new IllegalStateException("No primary for node group " + nodeGroup);
    }

    private static void replicateWithRetry(String groupId, RespClient replica,
                                           String primaryId, long deadline) throws IOException {
        while (true) {
            try {
                replica.callString("CLUSTER", "REPLICATE", primaryId);
                return;
            } catch (RespError e) {
                // The replica may not have gossiped the primary yet — retry until the deadline.
                if (System.currentTimeMillis() >= deadline) {
                    throw new RuntimeException("Cluster formation for group " + groupId
                            + " timed out waiting to replicate " + primaryId + ": " + e.getMessage(), e);
                }
                sleep(groupId);
            }
        }
    }

    private static void awaitKnownNodes(String groupId, List<RespClient> clients,
                                        int expected, long deadline) throws IOException {
        while (!allMatch(clients, info -> parseInfoField(info, "cluster_known_nodes") >= expected)) {
            if (System.currentTimeMillis() >= deadline) {
                throw new RuntimeException("Cluster formation for group " + groupId
                        + " timed out waiting for all " + expected + " nodes to meet");
            }
            sleep(groupId);
        }
    }

    private static void awaitClusterOk(String groupId, List<RespClient> clients,
                                       long deadline) throws IOException {
        while (!allMatch(clients, info -> info.contains("cluster_state:ok"))) {
            if (System.currentTimeMillis() >= deadline) {
                throw new RuntimeException("Cluster formation for group " + groupId
                        + " timed out waiting for cluster_state:ok");
            }
            sleep(groupId);
        }
    }

    private static boolean allMatch(List<RespClient> clients,
                                    java.util.function.Predicate<String> infoPredicate) throws IOException {
        for (RespClient client : clients) {
            if (!infoPredicate.test(client.callString("CLUSTER", "INFO"))) {
                return false;
            }
        }
        return true;
    }

    private static long parseInfoField(String info, String field) {
        for (String line : info.split("\r?\n")) {
            if (line.startsWith(field + ":")) {
                try {
                    return Long.parseLong(line.substring(field.length() + 1).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static void sleep(String groupId) {
        try {
            Thread.sleep(RETRY_SLEEP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while forming cluster for group " + groupId, e);
        }
    }

    /** An {@code -ERR} reply from the server, distinct from transport failures. */
    static final class RespError extends IOException {
        RespError(String message) {
            super(message);
        }
    }

    /** Minimal RESP2 client: sends one command array, reads one reply. */
    static final class RespClient implements Closeable {

        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        RespClient(String host, int port) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        String callString(String... args) throws IOException {
            Object reply = call(args);
            return reply == null ? null : reply.toString();
        }

        Object call(String... args) throws IOException {
            StringBuilder header = new StringBuilder();
            header.append('*').append(args.length).append("\r\n");
            out.write(header.toString().getBytes(StandardCharsets.UTF_8));
            for (String arg : args) {
                byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
                out.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(bytes);
                out.write('\r');
                out.write('\n');
            }
            out.flush();
            return readReply();
        }

        private Object readReply() throws IOException {
            int type = in.read();
            if (type == -1) {
                throw new IOException("Connection closed while awaiting reply");
            }
            String line = readLine();
            return switch (type) {
                case '+' -> line;
                case '-' -> throw new RespError(line);
                case ':' -> Long.parseLong(line);
                case '$' -> readBulk(Integer.parseInt(line));
                case '*' -> readArray(Integer.parseInt(line));
                default -> throw new IOException("Unexpected RESP type: " + (char) type);
            };
        }

        private String readBulk(int length) throws IOException {
            if (length < 0) {
                return null;
            }
            byte[] data = in.readNBytes(length);
            if (data.length != length) {
                throw new IOException("Truncated bulk reply");
            }
            expectCrLf();
            return new String(data, StandardCharsets.UTF_8);
        }

        private List<Object> readArray(int count) throws IOException {
            if (count < 0) {
                return null;
            }
            List<Object> items = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                items.add(readReply());
            }
            return items;
        }

        private String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\r') {
                    int next = in.read();
                    if (next != '\n') {
                        throw new IOException("Malformed RESP line terminator");
                    }
                    return sb.toString();
                }
                sb.append((char) b);
            }
            throw new IOException("Connection closed mid-line");
        }

        private void expectCrLf() throws IOException {
            if (in.read() != '\r' || in.read() != '\n') {
                throw new IOException("Missing CRLF after bulk reply");
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }

        void closeQuietly() {
            try {
                close();
            } catch (IOException e) {
                LOG.debugv("Ignoring close failure for formation connection to {0}: {1}",
                        socket.getRemoteSocketAddress(), e.getMessage());
            }
        }
    }
}
