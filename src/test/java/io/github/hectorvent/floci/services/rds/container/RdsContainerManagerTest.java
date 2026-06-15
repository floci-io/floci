package io.github.hectorvent.floci.services.rds.container;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RdsContainerManagerTest {

    @Test
    void postgresInitSqlCreatesRdsIamRoleWhenMissing() {
        String sql = RdsContainerManager.postgresIamRoleInitSql();

        assertTrue(sql.contains("pg_roles"));
        assertTrue(sql.contains("rolname = 'rds_iam'"));
        assertTrue(sql.contains("CREATE ROLE rds_iam"));
    }
}
