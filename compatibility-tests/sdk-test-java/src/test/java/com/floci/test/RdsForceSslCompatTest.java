package com.floci.test;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.ApplyMethod;
import software.amazon.awssdk.services.rds.model.Parameter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RDS rds.force_ssl")
class RdsForceSslCompatTest {

    private static final String USERNAME = "admin";
    private static final String PASSWORD = "secret123";
    private static final String DATABASE = "app";

    @Test
    @DisplayName("rds.force_ssl=1 refuses a plaintext connection and accepts TLS")
    void forceSslRefusesPlaintextConnections() throws Exception {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Connects through the Floci proxy host with test credentials");
        String group = TestFixtures.uniqueName("force-ssl");
        String instanceId = TestFixtures.uniqueName("rds-force-ssl");
        try (RdsClient rds = TestFixtures.rdsClient()) {
            rds.createDBParameterGroup(r -> r.dbParameterGroupName(group)
                    .dbParameterGroupFamily("postgres16")
                    .description("force ssl"));
            try {
                rds.modifyDBParameterGroup(r -> r.dbParameterGroupName(group)
                        .parameters(Parameter.builder()
                                .parameterName("rds.force_ssl")
                                .parameterValue("1")
                                .applyMethod(ApplyMethod.IMMEDIATE)
                                .build()));

                int port = rds.createDBInstance(r -> r
                        .dbInstanceIdentifier(instanceId)
                        .dbInstanceClass("db.t3.micro")
                        .engine("postgres")
                        .masterUsername(USERNAME)
                        .masterUserPassword(PASSWORD)
                        .dbName(DATABASE)
                        .allocatedStorage(20)
                        .dbParameterGroupName(group)).dbInstance().endpoint().port();

                try {
                    try (Connection connection = TestFixtures.awaitJdbcConnection(url(port), properties("require"))) {
                        assertThat(connection.isValid(5)).isTrue();
                    }
                    assertThatThrownBy(() -> DriverManager.getConnection(url(port), properties("disable")).close())
                            .isInstanceOfSatisfying(SQLException.class,
                                    e -> assertThat(e.getSQLState()).isEqualTo("28000"))
                            .hasMessageContaining("no pg_hba.conf entry")
                            .hasMessageContaining("no encryption");
                } finally {
                    rds.deleteDBInstance(r -> r.dbInstanceIdentifier(instanceId).skipFinalSnapshot(true));
                }
            } finally {
                rds.deleteDBParameterGroup(r -> r.dbParameterGroupName(group));
            }
        }
    }

    private static String url(int port) {
        return "jdbc:postgresql://" + TestFixtures.proxyHost() + ":" + port + "/" + DATABASE;
    }

    private static Properties properties(String sslMode) {
        Properties properties = new Properties();
        properties.setProperty("user", USERNAME);
        properties.setProperty("password", PASSWORD);
        properties.setProperty("sslmode", sslMode);
        properties.setProperty("connectTimeout", "5");
        return properties;
    }
}
