package io.github.hectorvent.floci.services.rds.container;

import org.junit.jupiter.api.Test;

import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdsContainerManagerTest {

    @Test
    void postgresInitSqlCreatesRdsIamRoleWhenMissing() {
        String sql = RdsContainerManager.postgresIamRoleInitSql();

        assertTrue(sql.contains("pg_roles"));
        assertTrue(sql.contains("rolname = 'rds_iam'"));
        assertTrue(sql.contains("CREATE ROLE rds_iam"));
    }

    @Test
    void postgres18UsesParentDataMount() {
        assertEquals("/var/lib/postgresql",
                RdsContainerManager.engineDefaultDataPath(DatabaseEngine.POSTGRES, "postgres:18.4-alpine"));
        assertEquals("/var/lib/postgresql",
                RdsContainerManager.engineDefaultDataPath(DatabaseEngine.POSTGRES, "registry.example.com/postgres:18-alpine"));
        assertEquals("/var/lib/postgresql",
                RdsContainerManager.engineDefaultDataPath(DatabaseEngine.POSTGRES,
                        "postgres:18.4-alpine@sha256:1234567890abcdef"));
        assertEquals("/var/lib/postgresql",
                RdsContainerManager.engineDefaultDataPath(DatabaseEngine.POSTGRES,
                        "localhost:5000/postgres:18.4-alpine"));
    }

    @Test
    void olderPostgresUsesLegacyDataMount() {
        assertEquals("/var/lib/postgresql/data",
                RdsContainerManager.engineDefaultDataPath(DatabaseEngine.POSTGRES, "postgres:16-alpine"));
        assertEquals("/var/lib/postgresql/data",
                RdsContainerManager.engineDefaultDataPath(DatabaseEngine.POSTGRES, "postgres:17.6"));
        assertEquals("/var/lib/postgresql/data",
                RdsContainerManager.engineDefaultDataPath(DatabaseEngine.POSTGRES, "postgres:latest"));
        assertEquals("/var/lib/postgresql/data",
                RdsContainerManager.engineDefaultDataPath(DatabaseEngine.POSTGRES, "localhost:5000/postgres"));
    }
}
