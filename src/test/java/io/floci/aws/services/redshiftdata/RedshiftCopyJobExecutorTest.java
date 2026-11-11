package io.floci.aws.services.redshiftdata;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.RedshiftZeroEtlEndpoints;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.redshift.model.Endpoint;
import io.floci.aws.services.redshift.model.Integration;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService.WorkgroupTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedshiftCopyJobExecutorTest {
    private final RedshiftService service = mock(RedshiftService.class);
    private final RedshiftZeroEtlEndpoints endpoints = mock(RedshiftZeroEtlEndpoints.class);
    private final RedshiftDataConnectionFactory connections = mock(RedshiftDataConnectionFactory.class);
    private final RedshiftCopyJobExecutor executor = new RedshiftCopyJobExecutor(service, endpoints, connections);

    @Test
    void missingClusterOrNamespaceDoesNotPreventIntegrationDeletion() {
        Integration cluster = integration("arn:aws:redshift:us-east-1:123456789012:cluster:target");
        when(service.describeClustersForAccount("123456789012", "target"))
                .thenThrow(new AwsException("ClusterNotFound", "Gone", 404));
        assertFalse(executor.runAutomatic(cluster));
        assertDoesNotThrow(() -> executor.disable(cluster));
        Integration namespace = integration("arn:aws:redshift-serverless:us-east-1:123456789012:namespace/target");
        doThrow(new AwsException("ResourceNotFoundException", "Gone", 404))
                .when(endpoints).targetWorkgroup("123456789012", namespace.getTargetArn());
        assertFalse(executor.runAutomatic(namespace));
        assertDoesNotThrow(() -> executor.disable(namespace));
        doThrow(new AwsException("ValidationException",
                "Workgroup runtime is not available for Data API execution.", 400))
                .when(endpoints).targetWorkgroup("123456789012", namespace.getTargetArn());
        assertTrue(executor.runAutomatic(namespace));
        assertDoesNotThrow(() -> executor.disable(namespace));
        verifyNoInteractions(connections);
    }

    @Test
    void pausedClusterSkipsLoadsAndRefusesDeletionWithoutLosingIntegrationState() {
        Integration integration = integration("arn:aws:redshift:us-east-1:123456789012:cluster:target");
        Cluster cluster = new Cluster();
        cluster.setClusterStatus("paused");
        when(service.describeClustersForAccount("123456789012", "target")).thenReturn(List.of(cluster));
        assertTrue(executor.runAutomatic(integration));
        AwsException error = assertThrows(AwsException.class, () -> executor.disable(integration));
        assertEquals("InvalidClusterState", error.getErrorCode());
        verifyNoInteractions(connections);
    }

    @Test
    void serverlessCleanupUsesTheWorkgroupProxyAndMatchesOnlyTheSourceBucket() throws Exception {
        Integration integration = integration("arn:aws:redshift-serverless:us-east-1:123456789012:namespace/target");
        Endpoint endpoint = new Endpoint();
        endpoint.setPort(15439);
        WorkgroupTarget target = new WorkgroupTarget("workgroup-arn", "workgroup", "backend", 5432, "analytics",
                "master", "password", "admin", List.of(), null, endpoint);
        when(endpoints.targetWorkgroup("123456789012", integration.getTargetArn())).thenReturn(target);
        Connection database = mock(Connection.class);
        PreparedStatement update = mock(PreparedStatement.class);
        openDatabase(database);
        when(database.prepareStatement(anyString())).thenReturn(update);
        executor.disable(integration);
        verify(connections, times(2)).open(argThat(value -> value.host().equals("127.0.0.1")
                && value.port() == 15439 && value.database().equals("analytics")));
        verify(update).setString(1, "source-bucket");
        verify(update).executeUpdate();
        verify(database).close();
    }

    @ParameterizedTest
    @CsvSource({
            "Connection lost,         08006, true",
            "Target table is missing, 42P01, false"
    })
    void failedJobRollsBackAndRetryIsRequestedOnlyForTransientFailures(String message, String sqlState,
                                                                       boolean retryExpected) throws Exception {
        Integration integration = integration("arn:aws:redshift:us-east-1:123456789012:cluster:target");
        Cluster cluster = new Cluster();
        cluster.setClusterStatus("available");
        cluster.setProxyPort(15439);
        when(service.describeClustersForAccount("123456789012", "target")).thenReturn(List.of(cluster));
        Connection database = mock(Connection.class);
        openDatabase(database);
        PreparedStatement findJobs = mock(PreparedStatement.class);
        PreparedStatement lock = mock(PreparedStatement.class);
        Statement run = mock(Statement.class);
        ResultSet jobs = mock(ResultSet.class);
        ResultSet locked = mock(ResultSet.class);
        when(database.prepareStatement(startsWith("SELECT job_name"))).thenReturn(findJobs);
        when(database.prepareStatement(contains("FOR UPDATE"))).thenReturn(lock);
        when(database.createStatement()).thenReturn(run);
        when(findJobs.executeQuery()).thenReturn(jobs);
        when(jobs.next()).thenReturn(true, true, false);
        when(jobs.getString(1)).thenReturn("failed", "healthy");
        when(lock.executeQuery()).thenReturn(locked);
        when(locked.next()).thenReturn(true);
        when(run.execute("COPY JOB RUN \"failed\"")).thenThrow(new SQLException(message, sqlState));

        assertEquals(retryExpected, executor.runAutomatic(integration));

        verify(database).rollback();
        verify(run).execute("COPY JOB RUN \"healthy\"");
        verify(database).commit();
    }

    private void openDatabase(Connection database) throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet databases = mock(ResultSet.class);
        when(connections.open(any())).thenReturn(connection, database);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(databases);
        when(databases.next()).thenReturn(true, false);
        when(databases.getString(1)).thenReturn("analytics");
    }

    private static Integration integration(String target) {
        Integration integration = new Integration();
        integration.setAccountId("123456789012");
        integration.setTargetArn(target);
        integration.setTargetClusterIdentifier("target");
        integration.setSourceArn("arn:aws:s3:::source-bucket");
        return integration;
    }
}
