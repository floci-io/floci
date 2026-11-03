package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.firehose.model.RedshiftDestination;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessService;
import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessService.WorkgroupTarget;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FirehoseRedshiftDeliveryTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private RedshiftService redshift;
    private RedshiftServerlessService serverless;
    private SecretsManagerService secrets;
    private ServiceRoleAuthorizer roleAuthorizer;
    private IamService iamService;
    private final List<String[]> executed = new ArrayList<>();
    private FirehoseRedshiftDelivery delivery;

    @BeforeEach
    void setUp() {
        redshift = mock(RedshiftService.class);
        secrets = mock(SecretsManagerService.class);
        Cluster cluster = mock(Cluster.class);
        when(cluster.getEndpoint()).thenReturn(new Endpoint("host.docker.internal", 41234));
        when(cluster.getMasterUsername()).thenReturn("admin");
        when(redshift.describeClustersForAccount("000000000000", "c1")).thenReturn(List.of(cluster));
        serverless = mock(RedshiftServerlessService.class);
        roleAuthorizer = mock(ServiceRoleAuthorizer.class);
        iamService = mock(IamService.class);
        delivery = new FirehoseRedshiftDelivery(redshift, serverless, secrets, roleAuthorizer, iamService, mapper,
                (url, user, password, sql) -> executed.add(new String[] {url, user, password, sql}));
    }

    private RedshiftDestination serverlessDestination() throws Exception {
        RedshiftDestination destination = destination();
        destination.setClusterJdbcUrl(
                "jdbc:redshift://wg1.000000000000.us-east-1.redshift-serverless.amazonaws.com:5439/analytics");
        destination.setUsername(null);
        return destination;
    }

    private void workgroup(String database) {
        when(serverless.getWorkgroupTarget("wg1", "us-east-1")).thenReturn(new WorkgroupTarget(
                "arn:aws:redshift-serverless:us-east-1:000000000000:workgroup/id1", "wg1", "host.docker.internal",
                5432, database, "workgroupadmin", "ignored", "workgroupadmin", List.of(), null,
                new Endpoint("localhost", 41300)));
    }

    @Test
    void serverlessUrlLoadsIntoTheWorkgroupItsDatabaseAndItsAdminUser() throws Exception {
        workgroup("analytics");

        delivery.copy("000000000000", "us-east-1", serverlessDestination(), "stage", "k1");

        assertEquals(1, executed.size());
        assertEquals("jdbc:postgresql://127.0.0.1:41300/analytics?preferQueryMode=simple",
                executed.get(0)[0]);
        assertEquals("workgroupadmin", executed.get(0)[1]);
        assertEquals("Secret123", executed.get(0)[2]);
        verifyNoInteractions(redshift);
    }

    @Test
    void serverlessDestinationKeepsAnExplicitUsername() throws Exception {
        workgroup("analytics");
        RedshiftDestination destination = serverlessDestination();
        destination.setUsername("loader");

        delivery.copy("000000000000", "us-east-1", destination, "stage", "k1");

        assertEquals("loader", executed.get(0)[1]);
    }

    @Test
    void serverlessUrlNamingAnotherDatabaseFailsTheDelivery() throws Exception {
        workgroup("dev");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> delivery.copy("000000000000", "us-east-1", serverlessDestination(), "stage", "k1"));

        assertTrue(e.getMessage().contains("analytics") && e.getMessage().contains("dev"), e.getMessage());
        assertTrue(executed.isEmpty());
    }

    @Test
    void unknownWorkgroupSurfacesAsAwsException() throws Exception {
        when(serverless.getWorkgroupTarget("wg1", "us-east-1"))
                .thenThrow(new AwsException("ResourceNotFoundException", "Workgroup wg1 not found", 400));

        assertThrows(AwsException.class,
                () -> delivery.copy("000000000000", "us-east-1", serverlessDestination(), "stage", "k"));
        assertTrue(executed.isEmpty());
    }

    @Test
    void workgroupWithoutARuntimeSurfacesAsAwsException() throws Exception {
        when(serverless.getWorkgroupTarget("wg1", "us-east-1")).thenThrow(
                new AwsException("ValidationException", "Workgroup runtime is not available", 400));

        assertThrows(AwsException.class,
                () -> delivery.copy("000000000000", "us-east-1", serverlessDestination(), "stage", "k"));
    }

    @Test
    void serverlessSecretSignsInWithItsStoredCredentials() throws Exception {
        workgroup("analytics");
        RedshiftDestination destination = serverlessDestination();
        RedshiftDestination.SecretsManagerConfiguration cfg = new RedshiftDestination.SecretsManagerConfiguration();
        cfg.setEnabled(true);
        cfg.setSecretArn("arn:aws:secretsmanager:us-east-1:000000000000:secret:admin-AbCdEf");
        destination.setSecretsManagerConfiguration(cfg);
        SecretVersion version = mock(SecretVersion.class);
        when(version.getSecretString()).thenReturn("{\"username\":\"admin\",\"password\":\"pw-managed\"}");
        when(secrets.getSecretValue(eq("arn:aws:secretsmanager:us-east-1:000000000000:secret:admin-AbCdEf"), any(), any(), eq("us-east-1"))).thenReturn(version);

        delivery.copy("000000000000", "us-east-1", destination, "stage", "k1");

        assertEquals("admin", executed.get(0)[1]);
        assertEquals("pw-managed", executed.get(0)[2]);
    }

    @Test
    void serverlessWithoutAnyCredentialsFailsTheDelivery() throws Exception {
        workgroup("analytics");
        RedshiftDestination destination = serverlessDestination();
        destination.setPassword(null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> delivery.copy("000000000000", "us-east-1", destination, "stage", "k1"));

        assertTrue(e.getMessage().contains("workgroup wg1"), e.getMessage());
    }

    private RedshiftDestination destination() throws Exception {
        return mapper.readValue("""
                {"RoleARN": "arn:aws:iam::000000000000:role/firehose",
                 "ClusterJDBCURL": "jdbc:redshift://c1.abc.us-east-1.redshift.amazonaws.com:5439/dev",
                 "Username": "admin", "Password": "Secret123",
                 "CopyCommand": {"DataTableName": "events", "CopyOptions": "json 'auto'"}}
                """, RedshiftDestination.class);
    }

    private static final String STREAM_ARN = "arn:aws:firehose:us-east-1:000000000000:deliverystream/rs";
    private static final String LOAD_ROLE = "arn:aws:iam::000000000000:role/firehose";
    private static final String STAGING_ROLE = "arn:aws:iam::000000000000:role/firehose-staging";

    private RedshiftDestination destinationWithStagingRole() throws Exception {
        RedshiftDestination destination = destination();
        DeliveryStreamDescription.S3Destination s3 = new DeliveryStreamDescription.S3Destination();
        s3.setRoleArn(STAGING_ROLE);
        destination.setS3(s3);
        return destination;
    }

    @Test
    void stagingChecksTheS3ConfigurationRoleForPutObjectOnTheObject() throws Exception {
        when(roleAuthorizer.refusal(STAGING_ROLE, "000000000000", "firehose.amazonaws.com", STREAM_ARN,
                List.of(new ServiceRoleAuthorizer.Permission("s3:PutObject", "arn:aws:s3:::stage/k1"))))
                .thenReturn(Optional.of("The role " + STAGING_ROLE + " does not trust firehose.amazonaws.com."));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> delivery
                .requireStagingAccess("000000000000", STREAM_ARN, destinationWithStagingRole(), "stage", "k1"));

        assertTrue(e.getMessage().contains("s3://stage/k1") && e.getMessage().contains("does not trust"),
                e.getMessage());
    }

    @Test
    void loadChecksTheDestinationRoleForGetObjectOnTheObject() throws Exception {
        when(roleAuthorizer.refusal(LOAD_ROLE, "000000000000", "firehose.amazonaws.com", STREAM_ARN,
                List.of(new ServiceRoleAuthorizer.Permission("s3:GetObject", "arn:aws:s3:::stage/k1"))))
                .thenReturn(Optional.of("The role " + LOAD_ROLE + " is not authorized to perform s3:GetObject."));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> delivery
                .requireLoadAccess("000000000000", STREAM_ARN, destinationWithStagingRole(), "stage", "k1"));

        assertTrue(e.getMessage().contains("not authorized"), e.getMessage());
    }

    @Test
    void aPermittedRoleStagesAndLoads() throws Exception {
        delivery.requireStagingAccess("000000000000", STREAM_ARN, destinationWithStagingRole(), "stage", "k1");
        delivery.requireLoadAccess("000000000000", STREAM_ARN, destinationWithStagingRole(), "stage", "k1");

        verify(roleAuthorizer, times(2)).refusal(anyString(), eq("000000000000"), eq("firehose.amazonaws.com"),
                eq(STREAM_ARN), any());
    }

    @Test
    void stagingWithoutAnS3ConfigurationRoleDelegatesToAuthorizer() throws Exception {
        delivery.requireStagingAccess("000000000000", STREAM_ARN, destination(), "stage", "k1");

        verify(roleAuthorizer).refusal(eq(""), eq("000000000000"), eq("firehose.amazonaws.com"),
                eq(STREAM_ARN), eq(List.of()));
    }

    @Test
    void runsCopyWithoutAnIamRoleThroughTheProxyPortInSimpleMode() throws Exception {
        delivery.copy("000000000000", "us-east-1", destination(), "stage", "k1");

        assertEquals(1, executed.size());
        assertEquals("jdbc:postgresql://127.0.0.1:41234/dev?preferQueryMode=simple", executed.get(0)[0]);
        assertEquals("admin", executed.get(0)[1]);
        assertEquals("Secret123", executed.get(0)[2]);
        assertEquals("COPY events FROM 's3://stage/k1' json 'auto'", executed.get(0)[3]);
    }

    private void knownLoadRole() {
        when(iamService.findRole("000000000000", "firehose"))
                .thenReturn(Optional.of(new IamRole("id-firehose", "firehose", "/", LOAD_ROLE, "{}")));
    }

    private static String sessionKey(String sql) {
        return sql.substring(sql.indexOf("aws_access_key_id=") + "aws_access_key_id=".length(), sql.indexOf(';'));
    }

    @Test
    void loadsWithTemporaryCredentialsOfTheDestinationRole() throws Exception {
        knownLoadRole();

        delivery.copy("000000000000", "us-east-1", destination(), "stage", "k1");

        String sql = executed.get(0)[3];
        assertTrue(sql.startsWith("COPY events FROM 's3://stage/k1' CREDENTIALS 'aws_access_key_id=ASIA"), sql);
        assertTrue(sql.contains(";aws_secret_access_key="), sql);
        assertTrue(sql.contains(";token="), sql);
        assertTrue(sql.endsWith("' json 'auto'"), sql);
        verify(iamService).registerSessionForAccount(eq("000000000000"), anyString(), anyString(), anyString(),
                eq(LOAD_ROLE), any(Instant.class), isNull());
    }

    @Test
    void everyAttemptMintsFreshCredentialsAndReleasesThem() throws Exception {
        knownLoadRole();

        delivery.copy("000000000000", "us-east-1", destination(), "stage", "k1");
        delivery.copy("000000000000", "us-east-1", destination(), "stage", "k1");

        assertNotEquals(sessionKey(executed.get(0)[3]), sessionKey(executed.get(1)[3]));
        verify(iamService, times(2)).unregisterSession(eq("000000000000"), anyString());
    }

    @Test
    void aFailedCopyNeitherLeaksCredentialsIntoTheErrorNorKeepsTheSession() throws Exception {
        knownLoadRole();
        List<String> sent = new ArrayList<>();
        FirehoseRedshiftDelivery failing = new FirehoseRedshiftDelivery(redshift, serverless, secrets,
                roleAuthorizer, iamService, mapper, (url, user, password, sql) -> {
                    sent.add(sql);
                    throw new SQLException("rejected: " + sql, "XX000");
                });

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> failing.copy("000000000000", "us-east-1", destination(), "stage", "k1"));

        String secret = sent.get(0).substring(sent.get(0).indexOf("aws_secret_access_key=") + 22,
                sent.get(0).indexOf(";token="));
        String token = sent.get(0).substring(sent.get(0).indexOf(";token=") + 7, sent.get(0).indexOf("' json"));
        assertFalse(e.getMessage().contains(secret), e.getMessage());
        assertFalse(e.getMessage().contains(token), e.getMessage());
        verify(iamService).unregisterSession(eq("000000000000"), anyString());
    }

    @Test
    void aRoleIamDoesNotKnowLoadsWithoutCredentials() throws Exception {
        delivery.copy("000000000000", "us-east-1", destination(), "stage", "k1");

        assertFalse(executed.get(0)[3].contains("CREDENTIALS"), executed.get(0)[3]);
        verify(iamService, never()).registerSessionForAccount(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(Instant.class), any());
    }

    @Test
    void secretOverridesInlineCredentialsWhenEnabled() throws Exception {
        RedshiftDestination dest = destination();
        RedshiftDestination.SecretsManagerConfiguration cfg = new RedshiftDestination.SecretsManagerConfiguration();
        cfg.setEnabled(true);
        cfg.setSecretArn("arn:aws:secretsmanager:us-east-1:000000000000:secret:rs-creds-AbCdEf");
        dest.setSecretsManagerConfiguration(cfg);
        SecretVersion version = mock(SecretVersion.class);
        when(version.getSecretString()).thenReturn("{\"username\":\"fromsecret\",\"password\":\"pw-secret\"}");
        when(secrets.getSecretValue(eq(cfg.getSecretArn()), any(), any(), eq("us-east-1"))).thenReturn(version);

        delivery.copy("000000000000", "us-east-1", dest, "stage", "k1");

        assertEquals("fromsecret", executed.get(0)[1]);
        assertEquals("pw-secret", executed.get(0)[2]);
    }

    @Test
    void disabledSecretsManagerKeepsTheInlineCredentials() throws Exception {
        RedshiftDestination dest = destination();
        RedshiftDestination.SecretsManagerConfiguration cfg = new RedshiftDestination.SecretsManagerConfiguration();
        cfg.setEnabled(false);
        dest.setSecretsManagerConfiguration(cfg);

        delivery.copy("000000000000", "us-east-1", dest, "stage", "k1");

        assertEquals("Secret123", executed.get(0)[2]);
    }

    @Test
    void unknownClusterSurfacesAsAwsException() {
        when(redshift.describeClustersForAccount("000000000000", "c1"))
                .thenThrow(new AwsException("ClusterNotFound", "Cluster c1 not found", 404));

        assertThrows(AwsException.class,
                () -> delivery.copy("000000000000", "us-east-1", destination(), "stage", "k"));
    }

    @Test
    void jdbcFailureIsWrappedWithTableClusterAndSqlState() {
        FirehoseRedshiftDelivery failing = new FirehoseRedshiftDelivery(redshift, serverless, secrets,
                roleAuthorizer, iamService, mapper,
                (url, user, password, sql) -> {
                    throw new SQLException("relation \"events\" does not exist", "42P01");
                });

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> failing.copy("000000000000", "us-east-1", destination(), "stage", "k"));

        assertTrue(e.getMessage().contains("events"), e.getMessage());
        assertTrue(e.getMessage().contains("c1"), e.getMessage());
        assertTrue(e.getMessage().contains("42P01"), e.getMessage());
    }

    @Test
    void jdbcConnectionsCarryConnectAndSocketTimeouts() {
        Properties properties = FirehoseRedshiftDelivery.connectionProperties("admin", "Secret123");

        assertEquals("admin", properties.getProperty("user"));
        assertEquals("Secret123", properties.getProperty("password"));
        assertTrue(Integer.parseInt(properties.getProperty("connectTimeout")) > 0);
        assertTrue(Integer.parseInt(properties.getProperty("socketTimeout")) > 0);
    }

    @Test
    void closeFailureAfterACommittedCopyIsNotAFailedDelivery() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        doThrow(new SQLException("connection reset")).when(connection).close();

        FirehoseRedshiftDelivery.executeAndClose(connection, "COPY t FROM 's3://b/k'");

        verify(statement).execute("COPY t FROM 's3://b/k'");
    }

    @Test
    void failedCopyIsReportedAndTheConnectionStillClosed() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(any())).thenThrow(new SQLException("relation does not exist", "42P01"));

        SQLException e = assertThrows(SQLException.class,
                () -> FirehoseRedshiftDelivery.executeAndClose(connection, "COPY t FROM 's3://b/k'"));

        assertEquals("42P01", e.getSQLState());
        verify(connection).close();
    }

    @Test
    void destinationWithoutAnyPasswordFailsWithAClearMessage() throws Exception {
        RedshiftDestination dest = destination();
        dest.setPassword(null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> delivery.copy("000000000000", "us-east-1", dest, "stage", "k"));

        assertTrue(e.getMessage().contains("Password"), e.getMessage());
    }
}
