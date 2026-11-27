package io.floci.aws.services.rds.integration;

import io.floci.aws.services.dsql.ExternalPostgresServer;
import io.floci.aws.services.rds.model.Integration;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.spectrum.BackendSql;
import io.floci.aws.services.redshift.spectrum.ExternalStatement;
import io.floci.aws.services.redshift.spectrum.SpectrumSession;
import io.floci.aws.services.redshift.spectrum.SpectrumSqlException;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.opentest4j.TestAbortedException;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Retrying a failed zero-ETL destination checks the caller's privileges against a real PostgreSQL 16: each case
 * holds exactly one of the conditions the check combines, so dropping any of them flips a result here.
 */
@Tag("docker")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class RdsIntegrationRetryPermissionDockerNightlyIntegrationTest {

    private static final String SUPERUSER_PASSWORD = "floci-test";
    private static final String ROLE_PASSWORD = "retry-test";
    private static final String ACCOUNT = "000000000000";
    private static final String INTEGRATION_ID = "4b2f2f9c-7d1e-4f55-9a77-1a2b3c4d5e6f";

    private ExternalPostgresServer server;
    private RdsIntegrationService integrations;
    private RdsIntegrationLoader loader;
    private RdsIntegrationDatabases databases;
    private Integration integration;

    @BeforeAll
    void startServer() throws Exception {
        try {
            server = ExternalPostgresServer.start("floci-rds-zero-etl-retry", SUPERUSER_PASSWORD,
                    "POSTGRES_PASSWORD=" + SUPERUSER_PASSWORD);
        } catch (ExternalPostgresServer.DockerUnavailableException e) {
            throw new TestAbortedException(e.getMessage(), e);
        }
        ExternalPostgresServer.Superuser postgres = server.superuser();
        postgres.execute("CREATE ROLE retry_team NOLOGIN");
        postgres.execute("CREATE ROLE retry_owner LOGIN NOCREATEDB PASSWORD '" + ROLE_PASSWORD + "'");
        postgres.execute("CREATE ROLE retry_creator LOGIN CREATEDB PASSWORD '" + ROLE_PASSWORD + "'");
        postgres.execute("CREATE ROLE retry_member LOGIN CREATEDB PASSWORD '" + ROLE_PASSWORD
                + "' IN ROLE retry_team");
        postgres.execute("CREATE ROLE retry_super LOGIN SUPERUSER NOCREATEDB PASSWORD '" + ROLE_PASSWORD + "'");
        postgres.execute("CREATE DATABASE owned_by_owner OWNER retry_owner");
        postgres.execute("CREATE DATABASE owned_by_postgres");
        postgres.execute("CREATE DATABASE owned_by_team OWNER retry_team");
    }

    @AfterAll
    void removeServer() {
        if (server != null) {
            server.close();
        }
    }

    @BeforeEach
    void setUp() {
        integrations = mock(RdsIntegrationService.class);
        loader = mock(RdsIntegrationLoader.class);
        RedshiftService redshiftService = mock(RedshiftService.class);
        databases = new RdsIntegrationDatabases(integrations, loader, redshiftService,
                mock(RedshiftServerlessService.class));
        integration = new Integration();
        integration.setIntegrationArn("arn:aws:rds:us-east-1:000000000000:integration:" + INTEGRATION_ID);
        integration.setTargetArn("arn:aws:redshift:us-east-1:000000000000:cluster:orders-dw");
        integration.setDataFilter("include: orders.*.*");
        integration.setAccountId(ACCOUNT);
        integration.setRegion("us-east-1");
        when(integrations.findById(INTEGRATION_ID)).thenReturn(Optional.of(integration));
        when(integrations.addDestination(any(), anyString(), anyString(), anyBoolean())).thenAnswer(invocation -> {
            Integration updated = new Integration(integration);
            updated.getDestinations().removeIf(d -> invocation.getArgument(2).equals(d.getSourceDatabase()));
            updated.getDestinations().add(new Integration.Destination(invocation.getArgument(1),
                    invocation.getArgument(2), Integration.Destination.STATE_SYNCING, null, "load-1"));
            return updated;
        });
        when(redshiftService.clusterRegion(ACCOUNT, "orders-dw")).thenReturn(Optional.of("us-east-1"));
    }

    @Test
    void refusesAnOwnerWithoutCreatedb() throws Exception {
        assertRefused("retry_owner", "owned_by_owner");
    }

    @Test
    void refusesANonOwnerWithCreatedb() throws Exception {
        assertRefused("retry_creator", "owned_by_postgres");
    }

    @Test
    void allowsAMemberOfTheOwnerRoleWithCreatedb() throws Exception {
        assertAllowed("retry_member", "owned_by_team");
    }

    @Test
    void allowsASuperuserWithoutCreatedb() throws Exception {
        assertAllowed("retry_super", "owned_by_team");
    }

    private void assertRefused(String role, String databaseName) throws SQLException {
        try (JdbcBackend backend = connectAs(role)) {
            SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                    () -> retry(databaseName, backend));
            assertEquals("42501", error.sqlState(), error.getMessage());
            assertEquals("permission denied to create database", error.getMessage());
            assertThat(backend.statements, empty());
        }
        verify(integrations, never()).addDestination(any(), anyString(), anyString(), anyBoolean());
        verify(loader, never()).submit(any(), anyString(), anyString(), any());
    }

    private void assertAllowed(String role, String databaseName) throws SQLException {
        try (JdbcBackend backend = connectAs(role)) {
            retry(databaseName, backend);
            assertThat(backend.statements, empty());
        }
        verify(integrations).addDestination(integration, databaseName, "orders", false);
        verify(loader).submit(any(), eq("orders"), eq(databaseName), eq("load-1"));
    }

    private void retry(String databaseName, BackendSql backend) {
        integration.getDestinations().add(new Integration.Destination(databaseName, "orders",
                Integration.Destination.STATE_FAILED, null));
        databases.create(new ExternalStatement.CreateIntegrationDatabase(databaseName, INTEGRATION_ID, "orders"),
                new SpectrumSession(ACCOUNT, ACCOUNT + ":orders-dw", "dev", List.of(), false), backend);
    }

    private JdbcBackend connectAs(String role) throws SQLException {
        return new JdbcBackend(DriverManager.getConnection(server.jdbcUrl("postgres"), role, ROLE_PASSWORD));
    }

    /** Answers queries from the server as the connected role and records, without running, every statement. */
    private static final class JdbcBackend implements BackendSql, AutoCloseable {
        private final Connection connection;
        private final List<String> statements = new ArrayList<>();

        JdbcBackend(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Optional<String> queryString(String sql) {
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
                return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
            } catch (SQLException e) {
                throw new IllegalStateException(sql, e);
            }
        }

        @Override
        public void execute(String sql) {
            statements.add(sql);
        }

        @Override
        public long copyIn(String copySql, InputStream data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() throws SQLException {
            connection.close();
        }
    }
}
