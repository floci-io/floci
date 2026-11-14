package io.floci.aws.services.redshiftdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.redshift.RedshiftCredentialBroker;
import io.floci.aws.services.redshift.RedshiftIamDbUserResolver;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.secretsmanager.model.SecretVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedshiftDataResourceResolverIdentityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RedshiftService redshiftService;
    private SecretsManagerService secretsManagerService;
    private RedshiftCredentialBroker credentialBroker;
    private RegionResolver regionResolver;
    private RedshiftServerlessService serverlessService;
    private RedshiftIamDbUserResolver iamDbUserResolver;
    private RequestContext requestContext;
    private RedshiftDataResourceResolver resolver;

    @BeforeEach
    void setUp() {
        redshiftService = mock(RedshiftService.class);
        secretsManagerService = mock(SecretsManagerService.class);
        credentialBroker = new RedshiftCredentialBroker();
        regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        serverlessService = mock(RedshiftServerlessService.class);
        iamDbUserResolver = mock(RedshiftIamDbUserResolver.class);
        requestContext = new RequestContext();
        requestContext.setAccessKeyId("ASIA123");

        resolver = new RedshiftDataResourceResolver(
                redshiftService, secretsManagerService, MAPPER,
                credentialBroker, regionResolver, serverlessService,
                iamDbUserResolver, requestContext);
    }

    @Test
    void resolvesProvisionedGetClusterCredentialsWithoutAutoCreate() {
        Cluster cluster = runningCluster("c1");
        when(redshiftService.describeClusters("c1")).thenReturn(List.of(cluster));
        credentialBroker.issue("000000000000", "c1", "IAM:analyst", List.of("g1"), 900, "analyst", false);

        ObjectNode req = MAPPER.createObjectNode()
                .put("ClusterIdentifier", "c1")
                .put("Database", "dev")
                .put("DbUser", "IAM:analyst");

        RedshiftDataResourceResolver.DatabaseTarget target = resolver.resolve(req, "us-east-1");
        assertNotNull(target.dbIdentity());
        assertEquals("analyst", target.dbIdentity().role());
        assertFalse(target.dbIdentity().autoCreate());
        assertEquals(List.of("g1"), target.dbIdentity().dbGroups());
        assertEquals("IAM:analyst", target.auditUser());
    }

    @Test
    void resolvesProvisionedGetClusterCredentialsWithAutoCreateAndIam() {
        Cluster cluster = runningCluster("c1");
        when(redshiftService.describeClusters("c1")).thenReturn(List.of(cluster));
        credentialBroker.issue("000000000000", "c1", "IAMA:alice", List.of("g1"), 900, "alice", true);
        credentialBroker.issue("000000000000", "c1", "IAMR:MyRole", List.of("g2"), 900, "IAMR:MyRole", true);

        ObjectNode autoReq = MAPPER.createObjectNode()
                .put("ClusterIdentifier", "c1")
                .put("Database", "dev")
                .put("DbUser", "IAMA:alice");
        RedshiftDataResourceResolver.DatabaseTarget autoTarget = resolver.resolve(autoReq, "us-east-1");
        assertNotNull(autoTarget.dbIdentity());
        assertEquals("alice", autoTarget.dbIdentity().role());
        assertTrue(autoTarget.dbIdentity().autoCreate());
        assertEquals(List.of("g1"), autoTarget.dbIdentity().dbGroups());

        ObjectNode iamReq = MAPPER.createObjectNode()
                .put("ClusterIdentifier", "c1")
                .put("Database", "dev")
                .put("DbUser", "IAMR:MyRole");
        RedshiftDataResourceResolver.DatabaseTarget iamTarget = resolver.resolve(iamReq, "us-east-1");
        assertNotNull(iamTarget.dbIdentity());
        assertEquals("IAMR:MyRole", iamTarget.dbIdentity().role());
        assertTrue(iamTarget.dbIdentity().autoCreate());
        assertEquals(List.of("g2"), iamTarget.dbIdentity().dbGroups());
    }

    @Test
    void resolvesServerlessIamWithoutSecretArnAndDirectSecretWithSecretArn() {
        RedshiftServerlessService.WorkgroupTarget wg = new RedshiftServerlessService.WorkgroupTarget(
                "arn:aws:redshift-serverless:us-east-1:000000000000:workgroup/wg1",
                "wg1", "127.0.0.1", 5439, "dev", "admin", "Secret123",
                null, List.of(), null);
        when(serverlessService.getWorkgroupTarget("wg1", "us-east-1")).thenReturn(wg);
        when(iamDbUserResolver.resolveDbUserForAccessKey("ASIA123")).thenReturn("IAMR:MyRole");

        ObjectNode iamReq = MAPPER.createObjectNode()
                .put("WorkgroupName", "wg1")
                .put("Database", "dev");
        RedshiftDataResourceResolver.DatabaseTarget iamTarget = resolver.resolve(iamReq, "us-east-1");
        assertNotNull(iamTarget.dbIdentity());
        assertEquals("IAMR:MyRole", iamTarget.dbIdentity().role());
        assertTrue(iamTarget.dbIdentity().autoCreate());
        assertEquals("IAMR:MyRole", iamTarget.auditUser());

        SecretVersion sv = new SecretVersion();
        sv.setSecretString("{\"username\":\"admin\",\"password\":\"Secret123\"}");
        when(secretsManagerService.getSecretValue(
                "arn:aws:secretsmanager:us-east-1:000000000000:secret:my-secret",
                null, null, "us-east-1")).thenReturn(sv);

        ObjectNode secretReq = MAPPER.createObjectNode()
                .put("WorkgroupName", "wg1")
                .put("Database", "dev")
                .put("SecretArn", "arn:aws:secretsmanager:us-east-1:000000000000:secret:my-secret");
        RedshiftDataResourceResolver.DatabaseTarget secretTarget = resolver.resolve(secretReq, "us-east-1");
        assertNull(secretTarget.dbIdentity());
    }

    private static Cluster runningCluster(String id) {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(id);
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123");
        cluster.setContainerHost("127.0.0.1");
        cluster.setContainerPort(5432);
        return cluster;
    }
}
