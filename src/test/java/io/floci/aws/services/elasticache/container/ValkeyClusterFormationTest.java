package io.floci.aws.services.elasticache.container;

import io.floci.aws.services.elasticache.model.Endpoint;
import io.floci.aws.testing.FakeRespServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValkeyClusterFormationTest {

    private final List<FakeRespServer> nodes = new ArrayList<>();

    @AfterEach
    void stopNodes() throws IOException {
        for (FakeRespServer node : nodes) {
            node.close();
        }
    }

    @Test
    void formingAClusterCreatesNoReplicationPasswordItsUnrestrictedSessionsCouldRead() {
        FakeRespServer primary = node("primary-id", false);
        FakeRespServer replica = node("replica-id", false);

        new ValkeyClusterFormation().form("grp", List.of(member(primary, true), member(replica, false)), 1);

        for (FakeRespServer node : List.of(primary, replica)) {
            assertTrue(node.commands().stream().noneMatch(command -> "ACL".equals(command.getFirst())
                    || "CONFIG".equals(command.getFirst())), "formation sent " + node.commands());
        }
    }

    @Test
    void everyNodeAcceptsTheReplicationUserAndSignsInWithIt() {
        FakeRespServer primary = node("primary-id", false);
        FakeRespServer replica = node("replica-id", false);

        new ValkeyClusterFormation().secureReplication("grp", List.of(endpoint(primary), endpoint(replica)));

        List<String> user = command(primary, "ACL", "SETUSER");
        assertTrue(user.get(2).startsWith("floci-replication-"));
        assertEquals(List.of("reset", "on"), user.subList(3, 5));
        assertTrue(user.get(5).startsWith(">"));
        assertEquals(List.of("+ping", "+sync", "+psync", "+replconf"), user.subList(6, 10));
        String password = user.get(5).substring(1);
        for (FakeRespServer node : List.of(primary, replica)) {
            assertEquals(user, command(node, "ACL", "SETUSER"), "every node must accept the same user");
            assertEquals(List.of("CONFIG", "SET", "masteruser", user.get(2)),
                    command(node, "CONFIG", "SET", "masteruser"));
            assertEquals(List.of("CONFIG", "SET", "masterauth", password),
                    command(node, "CONFIG", "SET", "masterauth"));
        }
    }

    @Test
    void aRefusedReplicationUserFailsWithoutTheServersReply() {
        FakeRespServer primary = node("primary-id", true);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> new ValkeyClusterFormation().secureReplication("grp", List.of(endpoint(primary))));

        String password = command(primary, "ACL", "SETUSER").get(5).substring(1);
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            assertFalse(String.valueOf(cause.getMessage()).contains(password),
                    "the reply quotes the password and must stay out of the error: " + cause.getMessage());
        }
    }

    /** A node that has met every other node and serves its slots, refusing ACL changes when told to. */
    private FakeRespServer node(String id, boolean refuseAcl) {
        try {
            FakeRespServer node = new FakeRespServer(command -> switch (String.join(" ", command.subList(0, 2))) {
                case "CLUSTER MYID" -> FakeRespServer.bulk(id);
                case "CLUSTER INFO" -> FakeRespServer.bulk("cluster_state:ok\r\ncluster_known_nodes:" + nodes.size());
                case "ACL SETUSER" -> refuseAcl
                        ? "-ERR Error in ACL SETUSER: " + String.join(" ", command) + "\r\n" : "+OK\r\n";
                default -> "+OK\r\n";
            });
            nodes.add(node);
            return node;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ValkeyClusterFormation.Node member(FakeRespServer node, boolean primary) {
        return new ValkeyClusterFormation.Node("localhost", node.port(), "127.0.0.1", 0, primary);
    }

    private static Endpoint endpoint(FakeRespServer node) {
        return new Endpoint("localhost", node.port());
    }

    private static List<String> command(FakeRespServer node, String... prefix) {
        return node.commands().get(indexOf(node, prefix));
    }

    private static int indexOf(FakeRespServer node, String... prefix) {
        List<List<String>> commands = node.commands();
        for (int i = 0; i < commands.size(); i++) {
            List<String> command = commands.get(i);
            if (command.size() >= prefix.length && command.subList(0, prefix.length).equals(List.of(prefix))) {
                return i;
            }
        }
        throw new AssertionError("no " + String.join(" ", prefix) + " in " + commands);
    }
}
