package io.floci.aws.services.dsql.dataplane;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
