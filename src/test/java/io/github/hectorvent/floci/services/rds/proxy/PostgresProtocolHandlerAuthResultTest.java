package io.github.hectorvent.floci.services.rds.proxy;

import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.services.rds.proxy.PasswordValidator.AuthResult;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresProtocolHandlerAuthResultTest {

    @Test
    void masterAlwaysUsesMasterBackendCredentials() {
        PostgresProtocolHandler.BackendLogin login = PostgresProtocolHandler.resolveBackendLogin(
                true, false, AuthResult.MASTER_EQUIVALENT,
                "master", "masterpass", "alice", "alicepass");

        assertEquals("master", login.user());
        assertEquals("masterpass", login.password());
    }

    @Test
    void iamUsesMasterBackendCredentials() {
        PostgresProtocolHandler.BackendLogin login = PostgresProtocolHandler.resolveBackendLogin(
                false, true, AuthResult.PASSTHROUGH,
                "master", "masterpass", "arn:role", "token");

        assertEquals("master", login.user());
        assertEquals("masterpass", login.password());
    }

    @Test
    void nonMasterPassthroughForwardsClientCredentials() {
        PostgresProtocolHandler.BackendLogin login = PostgresProtocolHandler.resolveBackendLogin(
                false, false, AuthResult.PASSTHROUGH,
                "master", "masterpass", "alice", "alicepass");

        assertEquals("alice", login.user());
        assertEquals("alicepass", login.password());
    }

    @Test
    void nonMasterMasterEquivalentUsesMasterBackendCredentials() {
        PostgresProtocolHandler.BackendLogin login = PostgresProtocolHandler.resolveBackendLogin(
                false, false, AuthResult.MASTER_EQUIVALENT,
                "master", "masterpass", "temp-user", "minted-pass");

        assertEquals("master", login.user());
        assertEquals("masterpass", login.password());
    }
}
