package io.floci.aws.services.rds.integration;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.rds.model.Integration;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.spectrum.BackendSql;
import io.floci.aws.services.redshift.spectrum.ExternalStatement;
import io.floci.aws.services.redshift.spectrum.SpectrumSession;
import io.floci.aws.services.redshift.spectrum.SpectrumSqlException;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code CREATE DATABASE ... FROM INTEGRATION} against an RDS integration: the database is created and loaded
 * only on the integration's own target, from a source database its filter includes.
 */
class RdsIntegrationDatabasesTest {

    private static final String ACCOUNT = "000000000000";
    private static final String INTEGRATION_ID = "4b2f2f9c-7d1e-4f55-9a77-1a2b3c4d5e6f";
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
        integrations = mock(RdsIntegrationService.class);
        loader = mock(RdsIntegrationLoader.class);
        redshiftService = mock(RedshiftService.class);
        serverless = mock(RedshiftServerlessService.class);
        databases = new RdsIntegrationDatabases(integrations, loader, redshiftService, serverless);
        backend = new RecordingBackend();
        integration = new Integration();
        integration.setIntegrationArn("arn:aws:rds:us-east-1:000000000000:integration:" + INTEGRATION_ID);
        integration.setTargetArn("arn:aws:redshift:us-east-1:000000000000:cluster:orders-dw");
        integration.setDataFilter("include: orders.*.*");
        integration.setAccountId(ACCOUNT);
        integration.setRegion("us-east-1");
        when(integrations.findById(anyString())).thenReturn(Optional.empty());
        when(integrations.findById(INTEGRATION_ID)).thenReturn(Optional.of(integration));
        when(integrations.addDestination(any(), anyString(), anyString(), anyBoolean())).thenReturn(integration);
        when(redshiftService.clusterRegion(ACCOUNT, "orders-dw")).thenReturn(Optional.of("us-east-1"));
    }

    private static SpectrumSession session(String clusterIdentifier) {
        return new SpectrumSession(ACCOUNT, ACCOUNT + ":" + clusterIdentifier, "dev", List.of(), false);
    }

    private static ExternalStatement.CreateIntegrationDatabase statement(String sourceDatabase) {
        return new ExternalStatement.CreateIntegrationDatabase("orders_dw", INTEGRATION_ID, sourceDatabase);
    }

    private void assertState(String sqlState, Runnable call) {
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class, call::run);
        assertEquals(sqlState, error.sqlState(), error.getMessage());
    }

    @Test
    void createsTheDatabaseAndStartsTheLoad() {
        databases.create(statement("orders"), session("orders-dw"), backend);

        assertThat(backend.statements, contains("CREATE DATABASE \"orders_dw\""));
        verify(integrations).addDestination(integration, "orders_dw", "orders", false);
        verify(loader).submit(integration, "orders", "orders_dw");
    }

    @Test
    void acceptsTheWorkgroupOfANamespaceTarget() {
        integration.setTargetArn(NAMESPACE_ARN);
        when(serverless.getNamespaceWorkgroupTarget(NAMESPACE_ARN, "us-east-1")).thenReturn(
                new RedshiftServerlessService.WorkgroupTarget("arn", "orders-wg", "localhost", 5439, "dev",
                        "admin", "secret", "admin", List.of(), null));

        databases.create(statement("orders"), session("serverless_us-east-1_orders-wg"), backend);

        verify(loader).submit(integration, "orders", "orders_dw");
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
                Integration.Destination.STATE_SYNCED, null));

        databases.create(statement("orders"), session("orders-dw"), backend);

        verify(integrations).addDestination(integration, "orders_dw", "orders", true);
    }

    @Test
    void keepsTheDestinationOfADatabaseThatStillExists() {
        integration.getDestinations().add(new Integration.Destination("orders_dw", "orders",
                Integration.Destination.STATE_SYNCED, null));
        backend.existingDatabase = true;

        databases.create(statement("orders"), session("orders-dw"), backend);

        verify(integrations).addDestination(integration, "orders_dw", "orders", false);
    }

    @Test
    void mapsAnIntegrationDeletedMeanwhileToUndefinedObject() {
        when(integrations.addDestination(any(), anyString(), anyString(), anyBoolean())).thenThrow(
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
        when(integrations.addDestination(any(), anyString(), anyString(), anyBoolean())).thenThrow(
                new AwsException("IntegrationAlreadyExistsFault", "already", 400));
        assertState("42P04", () -> databases.create(statement("orders"), session("orders-dw"), backend));
        assertThat(backend.statements, empty());
    }

    @Test
    void forgetsTheDestinationWhenTheDatabaseCannotBeCreated() {
        RecordingBackend failing = new RecordingBackend();
        failing.failure = new SpectrumSqlException("42P04", "database \"orders_dw\" already exists");

        assertState("42P04", () -> databases.create(statement("orders"), session("orders-dw"), failing));

        verify(integrations).removeDestination(integration, "orders");
        verify(loader, never()).submit(any(), anyString(), anyString());
    }

    @Test
    void reusesExistingDatabaseWhenRetryingAFailedLoadWithTheSameName() {
        integration.getDestinations().add(new Integration.Destination("orders_dw", "orders",
                Integration.Destination.STATE_FAILED, null));
        backend.existingDatabase = true;

        databases.create(statement("orders"), session("orders-dw"), backend);

        assertThat(backend.statements, empty());
        verify(integrations).addDestination(integration, "orders_dw", "orders", false);
        verify(loader).submit(integration, "orders", "orders_dw");
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
                new ExternalStatement.CreateIntegrationDatabase("orders_dw_v2", INTEGRATION_ID, "orders"),
                session("orders-dw"), failing));

        verify(integrations).restoreDestination(integration, "orders", failed, List.of(error));
        verify(integrations, never()).removeDestination(any(), anyString());
        verify(loader, never()).submit(any(), anyString(), anyString());
    }

    private static final class RecordingBackend implements BackendSql {
        private final List<String> statements = new ArrayList<>();
        private RuntimeException failure;
        private boolean existingDatabase;

        @Override
        public Optional<String> queryString(String sql) {
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
