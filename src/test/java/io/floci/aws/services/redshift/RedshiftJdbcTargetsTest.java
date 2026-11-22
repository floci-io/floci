package io.floci.aws.services.redshift;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.DatabaseSecretCredentials;
import io.floci.aws.services.redshift.RedshiftJdbcTargets.Backend;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.redshift.model.Endpoint;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService.WorkgroupTarget;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.secretsmanager.model.SecretVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedshiftJdbcTargetsTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String SECRET_ARN = "arn:aws:secretsmanager:us-east-1:000000000000:secret:admin-AbCdEf";

    private RedshiftService redshift;
    private RedshiftServerlessService serverless;
    private SecretsManagerService secrets;
    private RedshiftJdbcTargets targets;

    @BeforeEach
    void setUp() {
        redshift = mock(RedshiftService.class);
        serverless = mock(RedshiftServerlessService.class);
        secrets = mock(SecretsManagerService.class);
        targets = new RedshiftJdbcTargets(redshift, serverless, secrets, new ObjectMapper());
    }

    private static RedshiftJdbcUrl clusterUrl() {
        return RedshiftJdbcUrl.parse("jdbc:redshift://c1.abc.us-east-1.redshift.amazonaws.com:5439/analytics");
    }

    private static RedshiftJdbcUrl workgroupUrl(String database) {
        return RedshiftJdbcUrl.parse("jdbc:redshift://wg1.000000000000.us-east-1.redshift-serverless.amazonaws.com:5439/"
                + database);
    }

    private void workgroup(String database, Endpoint endpoint, int runtimePort) {
        when(serverless.getWorkgroupTarget("wg1", REGION)).thenReturn(new WorkgroupTarget(
                "arn:aws:redshift-serverless:us-east-1:000000000000:workgroup/id1", "wg1", "host.docker.internal",
                runtimePort, database, "workgroupadmin", "ignored", "workgroupadmin", List.of(), null, endpoint));
    }

    @Test
    void aClusterUrlResolvesToTheClusterPortAndTheEmulatedDevDatabase() {
        Cluster cluster = mock(Cluster.class);
        when(cluster.getEndpoint()).thenReturn(new Endpoint("host.docker.internal", 41234));
        when(cluster.getMasterUsername()).thenReturn("admin");
        when(redshift.describeClustersForAccount(ACCOUNT_ID, "c1")).thenReturn(List.of(cluster));

        Backend backend = targets.resolve(ACCOUNT_ID, REGION, clusterUrl());

        assertEquals(new Backend("cluster c1", "127.0.0.1", 41234, "dev", "admin"), backend);
    }

    @Test
    void aClusterThatDoesNotExistIsRefusedByTheRedshiftService() {
        when(redshift.describeClustersForAccount(ACCOUNT_ID, "c1"))
                .thenThrow(new AwsException("ClusterNotFound", "Cluster c1 not found.", 404));

        assertThrows(AwsException.class, () -> targets.resolve(ACCOUNT_ID, REGION, clusterUrl()));
    }

    @Test
    void aServerlessUrlResolvesToTheWorkgroupEndpointItsDatabaseAndItsAdminUser() {
        workgroup("analytics", new Endpoint("localhost", 41300), 5432);

        Backend backend = targets.resolve(ACCOUNT_ID, REGION, workgroupUrl("analytics"));

        assertEquals(new Backend("workgroup wg1", "127.0.0.1", 41300, "analytics", "workgroupadmin"), backend);
    }

    @Test
    void aWorkgroupWithoutAnEndpointFallsBackToItsRuntimePort() {
        workgroup("analytics", null, 5432);

        assertEquals(5432, targets.resolve(ACCOUNT_ID, REGION, workgroupUrl("analytics")).port());
    }

    @Test
    void aServerlessUrlNamingAnotherDatabaseIsRefused() {
        workgroup("analytics", new Endpoint("localhost", 41300), 5432);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> targets.resolve(ACCOUNT_ID, REGION, workgroupUrl("other")));

        assertTrue(refused.getMessage().contains("Database other does not exist in workgroup wg1"));
    }

    @Test
    void credentialsComeFromTheUsernameAndPasswordInTheSecret() {
        SecretVersion version = mock(SecretVersion.class);
        when(version.getSecretString()).thenReturn("{\"username\":\"admin\",\"password\":\"pw-managed\"}");
        when(secrets.getSecretValue(eq(SECRET_ARN), any(), any(), eq(REGION))).thenReturn(version);

        DatabaseSecretCredentials credentials = targets.credentialsFromSecret(SECRET_ARN, REGION);

        assertEquals(new DatabaseSecretCredentials("admin", "pw-managed"), credentials);
    }

    @Test
    void aSecretWithoutUsernameAndPasswordIsRefused() {
        SecretVersion version = mock(SecretVersion.class);
        when(version.getSecretString()).thenReturn("{\"token\":\"x\"}");
        when(secrets.getSecretValue(eq(SECRET_ARN), any(), any(), eq(REGION))).thenReturn(version);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> targets.credentialsFromSecret(SECRET_ARN, REGION));

        assertEquals("Secret " + SECRET_ARN + " does not contain username and password fields.",
                refused.getMessage());
    }
}
