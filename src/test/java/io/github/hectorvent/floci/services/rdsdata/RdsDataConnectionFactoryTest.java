package io.github.hectorvent.floci.services.rdsdata;

import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RdsDataConnectionFactoryTest {

    @Test
    void buildsMysqlUrl() {
        assertEquals(
                "jdbc:mysql://127.0.0.1:3306/app?useSSL=false&allowPublicKeyRetrieval=true",
                RdsDataConnectionFactory.buildUrl(DatabaseEngine.MYSQL, "127.0.0.1", 3306, "app"));
    }

    @Test
    void buildsMariadbUrlUsingMysqlDriver() {
        assertEquals(
                "jdbc:mysql://127.0.0.1:3306/app?useSSL=false&allowPublicKeyRetrieval=true",
                RdsDataConnectionFactory.buildUrl(DatabaseEngine.MARIADB, "127.0.0.1", 3306, "app"));
    }

    @Test
    void buildsPostgresUrl() {
        assertEquals(
                "jdbc:postgresql://127.0.0.1:5432/app?sslmode=disable",
                RdsDataConnectionFactory.buildUrl(DatabaseEngine.POSTGRES, "127.0.0.1", 5432, "app"));
    }
}
