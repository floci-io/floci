package io.floci.aws.services.rdsdata;

import io.floci.aws.services.rds.model.DatabaseEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    // Catches: a database name that adds or overrides connection properties, or
    // points the URL somewhere else, reaching the driver as part of the URL.
    @Test
    void aDatabaseNameThatWouldChangeTheUrlIsRefusedForEveryEngine() {
        for (DatabaseEngine engine : List.of(DatabaseEngine.MYSQL, DatabaseEngine.MARIADB, DatabaseEngine.POSTGRES)) {
            for (String database : List.of("app?allowLoadLocalInfile=true", "app&sslmode=require", "app#x",
                    "other/app", "app\\x", "user@app", "app;x=1", "app%3Fx", "app:1", "my app", "app\n")) {
                assertThrows(IllegalArgumentException.class,
                        () -> RdsDataConnectionFactory.buildUrl(engine, "127.0.0.1", 5432, database),
                        engine + " " + database);
            }
        }
    }

    @Test
    void anOrdinaryDatabaseNameKeepsItsUrl() {
        assertEquals("jdbc:postgresql://127.0.0.1:5432/orders-db_2$?sslmode=disable",
                RdsDataConnectionFactory.buildUrl(DatabaseEngine.POSTGRES, "127.0.0.1", 5432, "orders-db_2$"));
    }
}
