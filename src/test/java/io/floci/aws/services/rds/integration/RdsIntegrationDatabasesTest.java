package io.floci.aws.services.rds.integration;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.rds.model.Integration;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.spectrum.BackendSql;
import io.floci.aws.services.redshift.spectrum.ExternalStatement;
import io.floci.aws.services.redshift.spectrum.SpectrumSqlException;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.floci.aws.services.rds.integration.RdsIntegrationDatabasesFixture.ACCOUNT;
import static io.floci.aws.services.rds.integration.RdsIntegrationDatabasesFixture.session;
import static io.floci.aws.services.rds.integration.RdsIntegrationDatabasesFixture.statement;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code CREATE DATABASE ... FROM INTEGRATION} against an RDS integration: the database is created and loaded
 * only on the integration's own target, from a source database its filter includes.
 */
class RdsIntegrationDatabasesTest {

    private static final String NAMESPACE_ARN = "arn:aws:redshift-serverless:us-east-1:000000000000:"
            + "namespace/1a2b3c4d-0000-0000-0000-000000000000";

    private RdsIntegrationService integrations;
    private RdsIntegrationLoader loader;
    private RedshiftService redshiftService;
    private RedshiftServerlessService serverless;
    private RdsIntegrationDatabases databases;
    private RecordingBackend backend;
    private Integration integration;

    @BeforeEach
    void setUp() {
        RdsIntegrationDatabasesFixture fixture = new RdsIntegrationDatabasesFixture();
        integrations = fixture.integrations;
        loader = fixture.loader;
        redshiftService = fixture.redshiftService;
        serverless = fixture.serverless;
        databases = fixture.databases;
        integration = fixture.integration;
        backend = new RecordingBackend();
    }

    private void assertState(String sqlState, Runnable call) {
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class, call::run);
        assertEquals(sqlState, error.sqlState(), error.getMessage());
    }

    @Test
    void createsTheDatabaseAndStartsTheLoad() {
        databases.create(statement("orders"), session("orders-dw"), backend);

        assertThat(backend.statements, contains("CREATE DATABASE \"orders_dw\""));
        verify(integrations).reserveDestination(integration, "orders_dw", "orders", null, null);
        verify(integrations).startDestinationSync(any(), eq("orders"), eq("load-1"));
        verify(loader).submit(integration, "orders", "orders_dw", "load-1");
    }

    @Test
    void acceptsTheWorkgroupOfANamespaceTarget() {
        integration.setTargetArn(NAMESPACE_ARN);
        when(serverless.getNamespaceWorkgroupTarget(NAMESPACE_ARN, "us-east-1")).thenReturn(
                new RedshiftServerlessService.WorkgroupTarget("arn", "orders-wg", "localhost", 5439, "dev",
                        "admin", "secret", "admin", List.of(), null));

        databases.create(statement("orders"), session("serverless_us-east-1_orders-wg"), backend);

        verify(loader).submit(integration, "orders", "orders_dw", "load-1");
    }

    @Test
    void refusesAnotherClustersSession() {
        assertState("42704", () -> databases.create(statement("orders"), session("other-dw"), backend));
        assertThat(backend.statements, empty());
    }

    @Test
    void refusesASameNamedClusterInAnotherRegion() {
        when(redshiftService.clusterRegion(ACCOUNT, "orders-dw")).thenReturn(Optional.of("eu-west-1"));
        assertState("42704", () -> databases.create(statement("orders"), session("orders-dw"), backend));
    }

    @Test
    void replacesTheDestinationOfADroppedDatabase() {
        integration.getDestinations().add(new Integration.Destination("orders_dw", "orders",
                Integration.Destination.STATE_SYNCED, null, "old-load"));

        databases.create(statement("orders"), session("orders-dw"), backend);

        verify(integrations).reserveDestination(integration, "orders_dw", "orders", "orders_dw", "old-load");
    }

    @Test
    void keepsTheDestinationOfADatabaseThatStillExists() {
        integration.getDestinations().add(new Integration.Destination("orders_dw", "orders",
                Integration.Destination.STATE_SYNCED, null, "old-load"));
        backend.existingDatabase = true;

        databases.create(statement("orders"), session("orders-dw"), backend);

        verify(integrations).reserveDestination(integration, "orders_dw", "orders", null, null);
    }

    @Test
    void doesNotTreatACreatingDestinationAsDropped() {
        integration.getDestinations().add(new Integration.Destination("orders_dw", "orders",
                Integration.Destination.STATE_CREATING, null, "in-flight-load"));
        when(integrations.reserveDestination(any(), anyString(), anyString(), isNull(), isNull())).thenThrow(
                new AwsException("IntegrationAlreadyExistsFault", "already", 400));

        assertState("42P04", () -> databases.create(
                statement("orders_dw2", "orders"),
                session("orders-dw"), backend));
        verify(integrations).reserveDestination(integration, "orders_dw2", "orders", null, null);
        assertThat(backend.statements, empty());
    }

    @Test
    void mapsAnIntegrationDeletedMeanwhileToUndefinedObject() {
        when(integrations.reserveDestination(any(), anyString(), anyString(), any(), any())).thenThrow(
                new AwsException("IntegrationNotFoundFault", "gone", 404));
        assertState("42704", () -> databases.create(statement("orders"), session("orders-dw"), backend));
    }

    @Test
    void refusesAnUnknownIntegration() {
        assertState("42704", () -> databases.create(
                new ExternalStatement.CreateIntegrationDatabase("dw", "missing", "orders"), session("orders-dw"),
                backend));
    }

    @Test
    void refusesADynamoDbIntegrationAsUnsupported() {
        when(redshiftService.integrationExists(ACCOUNT, "dynamo")).thenReturn(true);
        assertState("0A000", () -> databases.create(
                new ExternalStatement.CreateIntegrationDatabase("dw", "dynamo", "orders"), session("orders-dw"),
                backend));
    }

    @Test
    void requiresTheSourceDatabase() {
        assertState("42601", () -> databases.create(statement(null), session("orders-dw"), backend));
    }

    @Test
    void refusesASourceDatabaseTheFilterLeavesOut() {
        assertState("22023", () -> databases.create(statement("billing"), session("orders-dw"), backend));
    }

    @Test
    void mapsADuplicateSourceDatabaseToDuplicateDatabase() {
        when(integrations.reserveDestination(any(), anyString(), anyString(), any(), any())).thenThrow(
                new AwsException("IntegrationAlreadyExistsFault", "already", 400));
        assertState("42P04", () -> databases.create(statement("orders"), session("orders-dw"), backend));
        assertThat(backend.statements, empty());
    }

    @Test
    void forgetsTheDestinationWhenTheDatabaseCannotBeCreated() {
        RecordingBackend failing = new RecordingBackend();
        failing.failure = new SpectrumSqlException("42P04", "database \"orders_dw\" already exists");

        assertState("42P04", () -> databases.create(statement("orders"), session("orders-dw"), failing));

        verify(integrations).removeDestination(integration, "orders", "load-1");
        verify(loader, never()).submit(any(), anyString(), anyString(), any());
    }

    @Test
    void reusesExistingDatabaseWhenRetryingAFailedLoadWithTheSameName() {
        integration.getDestinations().add(new Integration.Destination("orders_dw", "orders",
                Integration.Destination.STATE_FAILED, null));
        backend.existingDatabase = true;

        databases.create(statement("orders"), session("orders-dw"), backend);

        assertThat(backend.statements, empty());
        verify(integrations).addDestination(integration, "orders_dw", "orders", false);
        verify(loader).submit(any(), eq("orders"), eq("orders_dw"), eq("load-1"));
    }

    @Test
    void refusesRetryingAFailedLoadWhenCallerLacksPermission() {
        integration.getDestinations().add(new Integration.Destination("orders_dw", "orders",
                Integration.Destination.STATE_FAILED, null));
        backend.existingDatabase = true;
        backend.canRetryDatabase = false;

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> databases.create(statement("orders"), session("orders-dw"), backend));
        assertEquals("42501", error.sqlState(), error.getMessage());
        assertEquals("permission denied to create database", error.getMessage());

        assertThat(backend.statements, empty());
        verify(integrations, never()).addDestination(any(), anyString(), anyString(), anyBoolean());
        verify(loader, never()).submit(any(), anyString(), anyString(), any());
    }

    @Test
    void restoresPreviousFailedDestinationAndErrorsWhenRetryDatabaseCreationFails() {
        Integration.Destination failed = new Integration.Destination("orders_dw", "orders",
                Integration.Destination.STATE_FAILED, null);
        Integration.IntegrationError error = new Integration.IntegrationError(
                RdsIntegrationService.INITIAL_LOAD_FAILED,
                "The initial load of source database orders into orders_dw failed: source is down");
        integration.getDestinations().add(failed);
        integration.getErrors().add(error);
        RecordingBackend failing = new RecordingBackend();
        failing.existingDatabase = true;
        failing.failure = new SpectrumSqlException("42P04", "database \"orders_dw_v2\" already exists");

        assertState("42P04", () -> databases.create(
                statement("orders_dw_v2", "orders"),
                session("orders-dw"), failing));

        verify(integrations).restoreDestination(integration, "orders", "load-1", failed, List.of(error));
        verify(integrations, never()).removeDestination(any(), anyString(), any());
        verify(loader, never()).submit(any(), anyString(), anyString(), any());
    }

    private static final class RecordingBackend implements BackendSql {
        private final List<String> statements = new ArrayList<>();
        private RuntimeException failure;
        private boolean existingDatabase;
        private boolean canRetryDatabase = true;

        @Override
        public Optional<String> queryString(String sql) {
            if (sql.contains("pg_catalog.pg_roles")) {
                return canRetryDatabase ? Optional.of("1") : Optional.empty();
            }
            return existingDatabase ? Optional.of("1") : Optional.empty();
        }

        @Override
        public void execute(String sql) {
            if (failure != null) {
                throw failure;
            }
            statements.add(sql);
        }

        @Override
        public long copyIn(String copySql, InputStream data) {
            throw new UnsupportedOperationException();
        }
    }
}
