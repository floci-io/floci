package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
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
    private final ObjectMapper mapper = new ObjectMapper();

    private static Cluster cluster() {
        Cluster c = new Cluster();
        c.setClusterIdentifier("wh");
        c.setMasterUsername("admin");
        c.setMasterPassword("Secret123");
        c.setContainerHost("127.0.0.1");
        c.setContainerPort(55432);
        return c;
    }

    private RedshiftDataResourceResolver resolver(RedshiftService redshift, SecretsManagerService secrets) {
        return new RedshiftDataResourceResolver(redshift, secrets, mapper);
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

    @Test
    void rejectsWorkgroupName() {
        ObjectNode req = mapper.createObjectNode();
        req.put("WorkgroupName", "wg-1");
        req.put("Database", "dev");
        AwsException e = assertThrows(AwsException.class,
                () -> resolver(mock(RedshiftService.class), mock(SecretsManagerService.class)).resolve(req, REGION));
        assertEquals("ValidationException", e.getErrorCode());
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
