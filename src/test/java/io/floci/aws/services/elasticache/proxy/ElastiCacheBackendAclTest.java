package io.floci.aws.services.elasticache.proxy;

import io.floci.aws.services.elasticache.model.AuthMode;
import io.floci.aws.testing.FakeRespServer;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.InOrder;

import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ResourceLock(ParallelLocks.PORTS)
class ElastiCacheBackendAclTest {

    private FakeRespServer server;
    private List<List<String>> commands;
    private volatile boolean refuseNextCommands;
    private ElastiCacheBackendAcl acl;

    @BeforeEach
    void startBackend() throws IOException {
        server = new FakeRespServer(command -> refuseNextCommands ? "-ERR refused\r\n" : "+OK\r\n");
        commands = server.commands();
        acl = new ElastiCacheBackendAcl();
    }

    @AfterEach
    void stopBackend() throws IOException {
        server.close();
    }

    @Test
    void reapplyInstallsTheNewRulesOnTheBackendUserThatLoginCreated() throws Exception {
        login("app", "on ~app:* -@all +get");
        int afterLogin = commands.size();
        String sessionPassword = passwordOf("app");

        acl.reapply("localhost", server.port(), "app", "on ~app:* -@all +get +set");

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

        acl.reapply("localhost", server.port(), "default", "on ~allowed:* +get");

        assertEquals(backendUser, commands.get(afterLogin + 1).get(2));
    }

    @Test
    void aSessionThatSignsInAgainGetsItsNewUsersCredentialsOverAConnectionOfTheirOwn() throws Exception {
        login("app", "on ~app:* -@all +get");
        int connections = server.connections();
        int afterLogin = commands.size();

        List<String> credentials = acl.credentials("localhost", server.port(), "reader",
                () -> "on ~reports:* -@all +get");

        assertEquals(connections + 1, server.connections());
        assertEquals(List.of("reader", passwordOf("reader")), credentials);
        List<List<String>> sent = commands.subList(afterLogin, commands.size());
        assertTrue(sent.get(0).get(1).startsWith("floci-admin-"), "the rules are installed as the administrator");
        assertEquals(List.of("ACL", "SETUSER", "reader", "reset", "on", "~reports:*", "-@all", "+get"),
                sent.get(1).subList(0, 8));
        assertEquals(List.of("AUTH", "reader", passwordOf("reader")), sent.get(2));
    }

    @Test
    void theDefaultUserSignsInAgainAsItsPrivateBackendUser() throws Exception {
        login("app", "on ~app:* -@all +get");

        List<String> credentials = acl.credentials("localhost", server.port(), "default", () -> "on ~* +@all");

        assertTrue(credentials.get(0).startsWith("floci-default-"), "never the backend's own default user");
        assertEquals(passwordOf(credentials.get(0)), credentials.get(1));
    }

    @Test
    void aUserWhoseRulesTheBackendRefusesGetsNoCredentials() throws Exception {
        login("app", "on ~app:* -@all +get");
        refuseNextCommands = true;

        assertThrows(IOException.class, () -> acl.credentials("localhost", server.port(), "reader",
                () -> "on ~reports:* -@all +get"));
    }

    @Test
    void aCacheWithUsersSwitchesASessionToTheBackendUserOfItsNewUser() throws Exception {
        ElastiCacheAuthProxy.PasswordValidator validator = mock(ElastiCacheAuthProxy.PasswordValidator.class);
        when(validator.hasMembers()).thenReturn(true);
        when(validator.accessString("reader")).thenReturn("on ~reports:* -@all +get");
        ElastiCacheAuthProxy proxy = new ElastiCacheAuthProxy("grp-0001", AuthMode.PASSWORD, "localhost",
                server.port(), validator, mock(SigV4Validator.class));

        List<String> credentials = proxy.backendCredentials("reader");

        assertEquals("reader", credentials.get(0));
        assertEquals(List.of("ACL", "SETUSER", "reader", "reset", "on", "~reports:*", "-@all", "+get"),
                setUserOf("reader").subList(0, 8));
        assertEquals(passwordOf("reader"), credentials.get(1));
    }

    @Test
    void aCacheWithoutUsersSwitchesASessionToTheBackendsOwnDefaultUser() throws Exception {
        ElastiCacheAuthProxy.PasswordValidator validator = mock(ElastiCacheAuthProxy.PasswordValidator.class);
        ElastiCacheAuthProxy proxy = new ElastiCacheAuthProxy("grp-0001", AuthMode.PASSWORD, "localhost",
                server.port(), validator, mock(SigV4Validator.class));

        List<String> credentials = proxy.backendCredentials(null);

        assertEquals("default", credentials.get(0), "an auth-token cache's backend has no users of its own");
        assertEquals(List.of(), commands);
    }

    @Test
    void aClusterNodeReadiesItsReplicationBeforeASessionSwitchesUser() throws Exception {
        ElastiCacheAuthProxy.PasswordValidator validator = clusterValidator(true);
        ElastiCacheAuthProxy proxy = clusterNodeProxy(validator);

        proxy.backendCredentials("app");

        InOrder order = inOrder(validator);
        order.verify(validator).prepareClusterReplication();
        order.verify(validator, atLeastOnce()).accessString("app");
    }

    @Test
    void nothingIsSentBeforeTheBackendIsSetUp() throws Exception {
        acl.reapply("localhost", server.port(), "app", "on ~* +@all");

        assertEquals(0, server.connections());
    }

    @Test
    void nothingIsSentForAUserNobodyHasAuthenticatedAs() throws Exception {
        login("app", "on ~* +@all");
        int connections = server.connections();

        acl.reapply("localhost", server.port(), "other", "on ~* +@all");

        assertEquals(connections, server.connections());
    }

    @Test
    void selectorsAreRefusedWithoutTouchingTheBackend() throws Exception {
        login("app", "on ~* +@all");
        int connections = server.connections();

        assertThrows(IOException.class,
                () -> acl.reapply("localhost", server.port(), "app", "on ~* -@all (+get ~*)"));

        assertEquals(connections, server.connections());
    }

    @Test
    void backendRefusalIsReportedWithoutExposingItsResponse() throws Exception {
        login("app", "on ~* +@all");
        refuseNextCommands = true;

        IOException failure = assertThrows(IOException.class,
                () -> acl.reapply("localhost", server.port(), "app", "on ~* +@all"));

        assertEquals("Could not establish the backend cache user's permissions", failure.getMessage());
    }

    @Test
    void rulesAreReadUnderTheLockSoStaleRulesCannotOverwriteNewerRules() throws Exception {
        login("app", "on ~app:* -@all +get");
        int afterLogin = commands.size();

        AtomicReference<String> currentRules = new AtomicReference<>("on ~app:* -@all +get +set");
        acl.reapply("localhost", server.port(), "app", currentRules::get);

        currentRules.set("on ~app:* -@all +get");
        acl.reapply("localhost", server.port(), "app", currentRules::get);

        List<List<String>> sent = commands.subList(afterLogin, commands.size());
        assertEquals(4, sent.size());
        assertTrue(sent.get(1).contains("+set"));
        assertFalse(sent.get(3).contains("+set"));
    }

    @Test
    void disabledUserAccessStringIsReappliedToBackend() throws Exception {
        login("app", "on ~app:* -@all +get");
        int afterLogin = commands.size();

        acl.reapply("localhost", server.port(), "app", "off -@all");

        List<List<String>> sent = commands.subList(afterLogin, commands.size());
        assertEquals(2, sent.size());
        assertEquals("ACL", sent.get(1).get(0));
        assertEquals("SETUSER", sent.get(1).get(1));
        assertEquals("app", sent.get(1).get(2));
        assertEquals("reset", sent.get(1).get(3));
        assertEquals("off", sent.get(1).get(4));
        assertEquals("-@all", sent.get(1).get(5));
    }

    @Test
    void sessionsOnAClusterNodeCannotChangeTheClusterTopology() throws Exception {
        acl = new ElastiCacheBackendAcl(() -> true);

        login("ops", "on ~* &* +@all");

        // The CLUSTER subcommands ElastiCache lists among its restricted commands.
        List<String> rules = setUserOf("ops");
        for (String subcommand : List.of("addslots", "addslotsrange", "bumpepoch", "delslots", "delslotsrange",
                "failover", "flushslots", "forget", "links", "meet", "replicate", "reset", "saveconfig",
                "set-config-epoch", "setslot")) {
            assertTrue(rules.contains("-cluster|" + subcommand), "cluster " + subcommand + " must be denied");
        }
        assertTrue(rules.indexOf("-cluster|meet") > rules.indexOf("+@all"),
                "the restrictions must follow the user's own rules so +@all cannot grant them back");
    }

    @Test
    void aChangedAccessStringOnAClusterNodeKeepsTheClusterRestrictions() throws Exception {
        acl = new ElastiCacheBackendAcl(() -> true);
        login("ops", "on ~* &* +@all");
        int afterLogin = commands.size();

        acl.reapply("localhost", server.port(), "ops", "on ~* &* +@all -flushall");

        assertTrue(commands.get(afterLogin + 1).contains("-cluster|failover"));
    }

    @Test
    void sessionsOnAStandaloneBackendGetNoSubcommandRulesAnOlderEngineWouldRefuse() throws Exception {
        login("ops", "on ~* &* +@all");

        assertTrue(setUserOf("ops").stream().noneMatch(rule -> rule.startsWith("-cluster|")));
    }

    @Test
    void aClusterModeCacheInstallsItsUserOnTheNodeItsProxyServes() throws Exception {
        ElastiCacheAuthProxy.PasswordValidator validator = clusterValidator(true);
        ElastiCacheAuthProxy proxy = clusterNodeProxy(validator);

        initialize(proxy, "app");
        initialize(proxy, "app");

        List<String> rules = setUserOf("app");
        assertEquals(List.of("ACL", "SETUSER", "app", "reset", "on", "~app:*", "-@all", "+get"), rules.subList(0, 8));
        assertTrue(rules.contains("-cluster|failover"));
        assertEquals(List.of("ACL", "SETUSER", "default", "reset", "off"), commands.get(2));
        assertEquals(List.of("AUTH", "app", passwordOf("app")), commands.getLast());
        InOrder order = inOrder(validator);
        order.verify(validator).prepareClusterReplication();
        order.verify(validator, atLeastOnce()).accessString("app");
        verify(validator, times(1)).prepareClusterReplication();
    }

    @Test
    void aClusterWhoseReplicationIsNotReadyRefusesTheSessionBeforeTurningDefaultOff() {
        ElastiCacheAuthProxy.PasswordValidator validator = clusterValidator(true);
        doThrow(new IllegalStateException("a node refused the replication credentials"))
                .when(validator).prepareClusterReplication();
        ElastiCacheAuthProxy proxy = clusterNodeProxy(validator);

        assertThrows(IOException.class, () -> initialize(proxy, "app"));

        assertEquals(List.of(), commands, "the node's default user must stay on for its replication");
    }

    @Test
    void aClusterModeCacheWithoutUsersNeverGetsAReplicationPassword() throws Exception {
        ElastiCacheAuthProxy.PasswordValidator validator = clusterValidator(false);
        ElastiCacheAuthProxy proxy = clusterNodeProxy(validator);

        initialize(proxy, null);

        verify(validator, never()).prepareClusterReplication();
        assertEquals(List.of(), commands);
    }

    private static ElastiCacheAuthProxy.PasswordValidator clusterValidator(boolean members) {
        ElastiCacheAuthProxy.PasswordValidator validator = mock(ElastiCacheAuthProxy.PasswordValidator.class);
        when(validator.hasMembers()).thenReturn(members);
        when(validator.clusterMode()).thenReturn(true);
        when(validator.accessString("app")).thenReturn("on ~app:* -@all +get");
        return validator;
    }

    private ElastiCacheAuthProxy clusterNodeProxy(ElastiCacheAuthProxy.PasswordValidator validator) {
        return new ElastiCacheAuthProxy("grp-0001-001", AuthMode.PASSWORD, "localhost", server.port(), validator,
                mock(SigV4Validator.class));
    }

    private void initialize(ElastiCacheAuthProxy proxy, String username) throws IOException {
        try (Socket backend = new Socket("localhost", server.port())) {
            proxy.initializeBackend(backend, username);
        }
    }

    private List<String> setUserOf(String backendUser) {
        return commands.stream()
                .filter(c -> c.size() > 2 && "SETUSER".equals(c.get(1)) && backendUser.equals(c.get(2)))
                .reduce((first, second) -> second).orElseThrow();
    }

    private void login(String username, String accessString) throws IOException {
        try (Socket backend = new Socket("localhost", server.port())) {
            acl.authenticate(backend, username, accessString);
        }
    }

    private String passwordOf(String backendUser) {
        List<String> rules = setUserOf(backendUser);
        return rules.get(rules.indexOf("resetpass") + 1).substring(1);
    }
}
