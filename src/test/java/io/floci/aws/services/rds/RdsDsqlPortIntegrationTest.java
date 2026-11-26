package io.floci.aws.services.rds;

import io.floci.aws.services.ec2.Ec2Service;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.AuthScheme;
import software.amazon.awssdk.services.rds.model.DBProxy;
import software.amazon.awssdk.services.rds.model.UserAuthConfig;

import java.util.Map;

import static io.floci.aws.testing.RdsRequests.rdsClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
@TestProfile(RdsDsqlPortIntegrationTest.Profile.class)
class RdsDsqlPortIntegrationTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.rds.mock", "true", "floci.services.dsql.mock", "false",
                    "floci.services.dsql.port", "5432");
        }
    }

    @Inject
    RdsService service;

    @Test
    void sdkProxyTargetRegistrationAndDeletionKeepDsqlPortReserved() {
        String proxyName = "dsql-reserved-proxy";
        String clusterName = "dsql-reserved-target";
        String region = "us-east-1";
        String account = "000000000000";
        try (RdsClient client = rdsClient(region, account)) {
            boolean proxyCreated = false;
            boolean clusterCreated = false;
            try {
                DBProxy proxy = client.createDBProxy(b -> b.dbProxyName(proxyName).engineFamily("POSTGRESQL")
                        .roleArn("arn:aws:iam::" + account + ":role/proxy")
                        .vpcSubnetIds(Ec2Service.defaultSubnetId(region, "a", account),
                                Ec2Service.defaultSubnetId(region, "b", account))
                        .auth(UserAuthConfig.builder().authScheme(AuthScheme.SECRETS)
                                .secretArn("arn:aws:secretsmanager:" + region + ":" + account + ":secret:db-AbCdEf")
                                .build())).dbProxy();
                proxyCreated = true;
                assertNotEquals(5432, service.getDbProxy(proxyName).getProxyPort());
                assertEquals(service.getDbProxy(proxyName).getEndpointHost(), proxy.endpoint());
                client.createDBCluster(b -> b.dbClusterIdentifier(clusterName).engine("aurora-postgresql")
                        .masterUsername("admin").masterUserPassword("password123"));
                clusterCreated = true;
                assertEquals(1, client.registerDBProxyTargets(b -> b.dbProxyName(proxyName)
                        .dbClusterIdentifiers(clusterName)).dbProxyTargets().size());
                assertEquals(proxy.endpoint(), client.describeDBProxies(b -> b.dbProxyName(proxyName))
                        .dbProxies().getFirst().endpoint());
            } finally {
                try {
                    if (proxyCreated) {
                        client.deleteDBProxy(b -> b.dbProxyName(proxyName));
                    }
                } finally {
                    if (clusterCreated) {
                        client.deleteDBCluster(b -> b.dbClusterIdentifier(clusterName).skipFinalSnapshot(true));
                    }
                }
            }
        }
    }
}
