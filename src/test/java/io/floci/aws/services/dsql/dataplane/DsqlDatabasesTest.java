package io.floci.aws.services.dsql.dataplane;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void backendDescriptionHidesTheSuperuserPassword() {
        DsqlDatabases.Backend backend = new DsqlDatabases.Backend("h", 1, "postgres", "floci", "s3cr3t-Pa55",
                RoleNames.IDENTITY, BackendCredentials.TRUST);

        assertFalse(backend.toString().contains("s3cr3t-Pa55"), backend.toString());
        assertTrue(backend.toString().contains("superuserPassword=***"), backend.toString());
        DsqlEndpoint.Route route = new DsqlEndpoint.Route("c.dsql", "arn", "us-east-1", "000000000000", backend);
        assertFalse(route.toString().contains("s3cr3t-Pa55"), route.toString());
    }

    @Test
    void aTrustedDatabaseWantsNoPassword() {
        assertNull(BackendCredentials.TRUST.passwordFor("x"));
    }
}
