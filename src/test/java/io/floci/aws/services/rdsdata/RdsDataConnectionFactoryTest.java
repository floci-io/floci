package io.floci.aws.services.rdsdata;

import com.mysql.cj.conf.ConnectionUrl;
import io.floci.aws.services.rds.model.DatabaseEngine;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.postgresql.Driver;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
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

    // Catches the connection-property injection: a database name going into the URL as written let
    // `app?allowLoadLocalInfile=true` add a driver property. Each driver must read back the exact name
    // and nothing else.
    @Test
    void everyDriverReadsBackTheExactDatabaseNameAndNoAddedProperty() throws Exception {
        for (String database : List.of("app?allowLoadLocalInfile=true&autoDeserialize=true", "app&sslmode=require",
                "my db", "a+b", "a%b", "other/app", "a#b", "user@app", "app;x=1", "app:1", "a\\b", "caf\u00e9")) {
            ConnectionUrl mysql = ConnectionUrl.getConnectionUrlInstance(
                    RdsDataConnectionFactory.buildUrl(DatabaseEngine.MYSQL, "127.0.0.1", 3306, database), new Properties());
            assertEquals(database, mysql.getDatabase(), "MySQL " + database);
            assertNull(mysql.getMainHost().getHostProperties().get("allowLoadLocalInfile"), "MySQL " + database);
            assertEquals("false", mysql.getMainHost().getHostProperties().get("useSSL"), "MySQL " + database);

            Properties postgres = Driver.parseURL(
                    RdsDataConnectionFactory.buildUrl(DatabaseEngine.POSTGRES, "127.0.0.1", 5432, database),
                    new Properties());
            assertEquals(database, postgres.getProperty("PGDBNAME"), "PostgreSQL " + database);
            assertEquals("disable", postgres.getProperty("sslmode"), "PostgreSQL " + database);
        }
    }
}
