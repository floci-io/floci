package io.floci.aws.services.dsql.dataplane;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DsqlDatabasesTest {

    @Test
    void dockerBackendDescribesTodaysDatabase() {
        DsqlDatabases.Backend backend = new DsqlDatabases.Backend("h", 1, "postgres", "dbowner", null,
                RoleNames.IDENTITY, BackendCredentials.TRUST);

        assertEquals("jdbc:postgresql://h:1/postgres", backend.jdbcUrl());
        assertEquals("admin", RoleNames.IDENTITY.toBackend("admin"));
        assertEquals("admin", RoleNames.IDENTITY.toLogical("admin"));
        assertTrue(RoleNames.IDENTITY.isIdentity());
    }

    @Test
    void aTrustedDatabaseWantsNoPassword() {
        assertNull(BackendCredentials.TRUST.passwordFor("x"));
    }

    @Test
    void anUnknownBackendFailsNamingTheSetting() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().dsql().backend()).thenReturn("kubernetes");
        DockerDsqlBackend docker = new DockerDsqlBackend(mock(ContainerBuilder.class),
                mock(ContainerLifecycleManager.class), mock(ContainerDetector.class), config);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new DsqlDatabases(docker, new ExternalDsqlBackend(config), config));

        assertEquals("floci.services.dsql.backend must be docker or external, not 'kubernetes'", refused.getMessage());
    }
}
