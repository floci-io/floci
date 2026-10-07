package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.redshift.RedshiftCredentialBroker;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessService;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedshiftDataResourceResolverTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "acc";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RedshiftCredentialBroker broker = new RedshiftCredentialBroker();

    private static Cluster cluster() {
        Cluster c = new Cluster();
        c.setClusterIdentifier("wh");
        c.setMasterUsername("admin");
        c.setMasterPassword("Secret123");
        c.setContainerHost("127.0.0.1");
        c.setContainerPort(55432);
        return c;
    }

    private final RedshiftServerlessService serverless = mock(RedshiftServerlessService.class);

    private RedshiftDataResourceResolver resolver(RedshiftService redshift, SecretsManagerService secrets) {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT);
        return new RedshiftDataResourceResolver(redshift, secrets, mapper, broker, regionResolver, serverless);
    }

    @Test
    void resolvesClusterIdentifierAndMasterDbUser() {
        RedshiftService redshift = mock(RedshiftService.class);
        when(redshift.describeClusters("wh")).thenReturn(List.of(cluster()));

        ObjectNode req = mapper.createObjectNode();
        req.put("ClusterIdentifier", "wh");
        req.put("DbUser", "admin");
        req.put("Database", "dev");

        RedshiftDataResourceResolver.DatabaseTarget target =
                resolver(redshift, mock(SecretsManagerService.class)).resolve(req, REGION);

        assertEquals("127.0.0.1", target.host());
        assertEquals(55432, target.port());
        assertEquals("dev", target.database());
        assertEquals("admin", target.user());
        assertEquals("Secret123", target.password());
    }

    private static RedshiftServerlessService.WorkgroupTarget workgroupTarget() {
        return new RedshiftServerlessService.WorkgroupTarget(
                "arn:aws:redshift-serverless:us-east-1:" + ACCOUNT + ":workgroup/wg-id", "wg-1",
                "127.0.0.1", 55433, "analytics", "root", "Secret123", "root",
                List.of("arn:aws:iam::" + ACCOUNT + ":role/Spectrum"));
    }

    @Test
    void resolvesAWorkgroupAsTheNamespaceAdminWhenNoSecretIsGiven() {
        when(serverless.getWorkgroupTarget("wg-1", REGION)).thenReturn(workgroupTarget());
        ObjectNode req = mapper.createObjectNode();
        req.put("WorkgroupName", "wg-1");
        req.put("Database", "analytics");

        RedshiftDataResourceResolver.DatabaseTarget target =
                resolver(mock(RedshiftService.class), mock(SecretsManagerService.class)).resolve(req, REGION);

        assertEquals("arn:aws:redshift-serverless:us-east-1:" + ACCOUNT + ":workgroup/wg-id", target.arn());
        assertEquals("127.0.0.1", target.host());
        assertEquals(55433, target.port());
        assertEquals("analytics", target.database());
        assertEquals("root", target.user());
        assertEquals("Secret123", target.password());
        assertEquals(ACCOUNT, target.spectrum().accountId());
        assertEquals(ACCOUNT + ":serverless_us-east-1_wg-1", target.spectrum().clusterKey());
        assertEquals("analytics", target.spectrum().databaseName());
        assertEquals(List.of("arn:aws:iam::" + ACCOUNT + ":role/Spectrum"), target.spectrum().iamRoleArns());
    }

    @Test
    void aWorkgroupWithASecretConnectsWithTheSecretsCredentials() {
        when(serverless.getWorkgroupTarget("wg-1", REGION)).thenReturn(workgroupTarget());
        SecretsManagerService secrets = mock(SecretsManagerService.class);
        String secretArn = "arn:aws:secretsmanager:us-east-1:" + ACCOUNT + ":secret:wg-creds";
        SecretVersion version = new SecretVersion();
        version.setSecretString("{\"username\":\"root\",\"password\":\"Secret123\"}");
        when(secrets.getSecretValue(eq(secretArn), any(), any(), eq(REGION))).thenReturn(version);
        ObjectNode req = mapper.createObjectNode();
        req.put("WorkgroupName", "wg-1");
        req.put("Database", "analytics");
        req.put("SecretArn", secretArn);

        RedshiftDataResourceResolver.DatabaseTarget target =
                resolver(mock(RedshiftService.class), secrets).resolve(req, REGION);

        assertEquals("root", target.user());
        assertEquals("Secret123", target.password());
        assertEquals(55433, target.port());
    }

    @Test
    void aWorkgroupRejectsAClusterIdentifierAndAWrongDatabase() {
        when(serverless.getWorkgroupTarget("wg-1", REGION)).thenReturn(workgroupTarget());
        RedshiftDataResourceResolver resolver = resolver(mock(RedshiftService.class), mock(SecretsManagerService.class));

        ObjectNode both = mapper.createObjectNode();
        both.put("WorkgroupName", "wg-1");
        both.put("ClusterIdentifier", "wh");
        both.put("Database", "analytics");
        assertEquals("ValidationException", assertThrows(AwsException.class, () -> resolver.resolve(both, REGION))
                .getErrorCode());

        ObjectNode wrongDatabase = mapper.createObjectNode();
        wrongDatabase.put("WorkgroupName", "wg-1");
        wrongDatabase.put("Database", "dev");
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> resolver.resolve(wrongDatabase, REGION)).getErrorCode());
    }

    @Test
    void aWorkgroupStillRequiresADatabaseAndPropagatesAMissingWorkgroup() {
        when(serverless.getWorkgroupTarget("absent-wg", REGION))
                .thenThrow(new AwsException("ResourceNotFoundException", "no such workgroup", 404));
        RedshiftDataResourceResolver resolver = resolver(mock(RedshiftService.class), mock(SecretsManagerService.class));

        ObjectNode noDatabase = mapper.createObjectNode();
        noDatabase.put("WorkgroupName", "wg-1");
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> resolver.resolve(noDatabase, REGION)).getErrorCode());

        ObjectNode absent = mapper.createObjectNode();
        absent.put("WorkgroupName", "absent-wg");
        absent.put("Database", "dev");
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> resolver.resolve(absent, REGION)).getErrorCode());
    }

    @Test
    void rejectsUnknownCluster() {
        RedshiftService redshift = mock(RedshiftService.class);
        when(redshift.describeClusters("missing"))
                .thenThrow(new AwsException("ClusterNotFound", "Cluster missing not found", 404));
        ObjectNode req = mapper.createObjectNode();
        req.put("ClusterIdentifier", "missing");
        req.put("DbUser", "admin");
        req.put("Database", "dev");
        AwsException e = assertThrows(AwsException.class,
                () -> resolver(redshift, mock(SecretsManagerService.class)).resolve(req, REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void rejectsNonMasterDbUser() {
        RedshiftService redshift = mock(RedshiftService.class);
        when(redshift.describeClusters("wh")).thenReturn(List.of(cluster()));
        ObjectNode req = mapper.createObjectNode();
        req.put("ClusterIdentifier", "wh");
        req.put("DbUser", "analyst");
        req.put("Database", "dev");
        AwsException e = assertThrows(AwsException.class,
                () -> resolver(redshift, mock(SecretsManagerService.class)).resolve(req, REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void resolvesLiveMintedDbUserAsMaster() {
        RedshiftService redshift = mock(RedshiftService.class);
        when(redshift.describeClusters("wh")).thenReturn(List.of(cluster()));
        broker.issue(ACCOUNT, "wh", "analyst", List.of(), 900);

        ObjectNode req = mapper.createObjectNode();
        req.put("ClusterIdentifier", "wh");
        req.put("DbUser", "analyst");
        req.put("Database", "dev");

        RedshiftDataResourceResolver.DatabaseTarget target =
                resolver(redshift, mock(SecretsManagerService.class)).resolve(req, REGION);

        assertEquals("admin", target.user());
        assertEquals("Secret123", target.password());
    }

    @Test
    void resolvesSecretArn() {
        RedshiftService redshift = mock(RedshiftService.class);
        when(redshift.describeClusters("wh")).thenReturn(List.of(cluster()));
        SecretsManagerService secrets = mock(SecretsManagerService.class);
        SecretVersion secret = new SecretVersion();
        secret.setSecretString("{\"username\":\"svc\",\"password\":\"p4ss\"}");
        when(secrets.getSecretValue(eq("arn:aws:secretsmanager:us-east-1:000000000000:secret:wh-creds"), any(), any(), eq(REGION)))
                .thenReturn(secret);

        ObjectNode req = mapper.createObjectNode();
        req.put("SecretArn", "arn:aws:secretsmanager:us-east-1:000000000000:secret:wh-creds");
        req.put("ClusterIdentifier", "wh");
        req.put("Database", "dev");

        RedshiftDataResourceResolver.DatabaseTarget target = resolver(redshift, secrets).resolve(req, REGION);
        assertEquals("svc", target.user());
        assertEquals("p4ss", target.password());
    }

    @Test
    void rejectsCrossRegionSecretArn() {
        ObjectNode req = mapper.createObjectNode();
        req.put("SecretArn", "arn:aws:secretsmanager:eu-west-1:000000000000:secret:wh-creds");
        req.put("ClusterIdentifier", "wh");
        req.put("Database", "dev");
        AwsException e = assertThrows(AwsException.class,
                () -> resolver(mock(RedshiftService.class), mock(SecretsManagerService.class)).resolve(req, REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void rejectsClusterWithNoContainerRuntime() {
        Cluster c = cluster();
        c.setContainerHost("");
        c.setContainerPort(0);
        RedshiftService redshift = mock(RedshiftService.class);
        when(redshift.describeClusters("wh")).thenReturn(List.of(c));
        ObjectNode req = mapper.createObjectNode();
        req.put("ClusterIdentifier", "wh");
        req.put("DbUser", "admin");
        req.put("Database", "dev");
        AwsException e = assertThrows(AwsException.class,
                () -> resolver(redshift, mock(SecretsManagerService.class)).resolve(req, REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }
}
