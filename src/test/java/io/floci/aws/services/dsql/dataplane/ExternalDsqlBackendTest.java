package io.floci.aws.services.dsql.dataplane;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.services.dsql.model.DsqlCluster;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExternalDsqlBackendTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void externalModeNeverTouchesDocker() {
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        ContainerDetector detector = mock(ContainerDetector.class);
        EmulatorConfig config = unreachableExternalServer();
        DsqlDatabases databases = new DsqlDatabases(new DockerDsqlBackend(builder, lifecycle, detector, config),
                new ExternalDsqlBackend(config), config);
        DsqlCluster cluster = cluster();

        assertThrows(SQLException.class, () -> databases.start(cluster));
        assertNotNull(cluster.getBackendSecret(), "the secret is set on the cluster for the caller to persist");
        assertThrows(SQLException.class, () -> databases.rolesToDrop(cluster));
        assertThrows(RuntimeException.class, () -> databases.stop(cluster, true));
        databases.stop(cluster, false);

        verifyNoInteractions(builder, lifecycle, detector);
    }

    @Test
    void derivedPasswordIsStablePerRole() {
        String admin = ExternalDsqlBackend.derive(SECRET, "dabc_admin");

        assertEquals(admin, ExternalDsqlBackend.derive(SECRET, "dabc_admin"));
        assertNotEquals(admin, ExternalDsqlBackend.derive(SECRET, "dabc_app"));
        assertNotEquals(admin, ExternalDsqlBackend.derive(
                Base64.getEncoder().encodeToString(new byte[] {1}), "dabc_admin"));
        assertTrue(admin.matches("[A-Za-z0-9_-]{43}"), admin);
        assertFalse(admin.contains("="));
    }

    @Test
    void aSecretIsGeneratedOnceAndKept() {
        DsqlCluster cluster = cluster();
        cluster.setBackendSecret(SECRET);

        assertThrows(SQLException.class, () -> new ExternalDsqlBackend(unreachableExternalServer()).start(cluster));

        assertEquals(SECRET, cluster.getBackendSecret());
    }

    private static EmulatorConfig unreachableExternalServer() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().dsql().backend()).thenReturn("external");
        when(config.services().dsql().external().host()).thenReturn(Optional.of("127.0.0.1"));
        // Nothing listens on port 1, so every connection is refused at once.
        when(config.services().dsql().external().port()).thenReturn(1);
        when(config.services().dsql().external().database()).thenReturn("postgres");
        when(config.services().dsql().external().username()).thenReturn("postgres");
        when(config.services().dsql().external().password()).thenReturn(Optional.empty());
        return config;
    }

    private static DsqlCluster cluster() {
        DsqlCluster cluster = new DsqlCluster();
        cluster.setIdentifier("abcdefghijklmnopqrstuvwxyz");
        return cluster;
    }
}
