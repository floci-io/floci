package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.CreateIntegrationResponse;
import software.amazon.awssdk.services.rds.model.DbClusterNotFoundException;
import software.amazon.awssdk.services.rds.model.Integration;
import software.amazon.awssdk.services.rds.model.IntegrationNotFoundException;
import software.amazon.awssdk.services.rds.model.IntegrationStatus;
import software.amazon.awssdk.services.redshiftserverless.RedshiftServerlessClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RDS zero-ETL integrations")
class RdsZeroEtlIntegrationTest {

    @Test
    @DisplayName("An Aurora PostgreSQL to Redshift Serverless integration is created, described, modified and deleted")
    void integrationLifecycle() {
        String clusterId = TestFixtures.uniqueName("sdk-zetl-src");
        String namespaceName = TestFixtures.uniqueName("sdk-zetl-dest");
        String integrationName = TestFixtures.uniqueName("sdk-zetl");
        try (RdsClient rds = TestFixtures.rdsClient();
             RedshiftServerlessClient serverless = TestFixtures.redshiftServerlessClient()) {
            boolean clusterCreated = false;
            boolean namespaceCreated = false;
            try {
                String sourceArn = rds.createDBCluster(request -> request.dbClusterIdentifier(clusterId)
                        .engine("aurora-postgresql").masterUsername("postgres")
                        .masterUserPassword("Secret12345")).dbCluster().dbClusterArn();
                clusterCreated = true;
                String targetArn = serverless.createNamespace(request -> request.namespaceName(namespaceName))
                        .namespace().namespaceArn();
                namespaceCreated = true;

                CreateIntegrationResponse created = rds.createIntegration(request -> request
                        .integrationName(integrationName).sourceArn(sourceArn).targetArn(targetArn)
                        .dataFilter("include: orders.*.*"));
                assertThat(created.status()).isEqualTo(IntegrationStatus.CREATING);
                assertThat(created.integrationArn()).contains(":integration:");

                Integration described = rds.describeIntegrations(request -> request
                        .integrationIdentifier(created.integrationArn())).integrations().get(0);
                assertThat(described.status()).isEqualTo(IntegrationStatus.ACTIVE);
                assertThat(described.sourceArn()).isEqualTo(sourceArn);
                assertThat(described.targetArn()).isEqualTo(targetArn);

                assertThat(rds.modifyIntegration(request -> request
                        .integrationIdentifier(created.integrationArn()).description("orders"))
                        .status()).isEqualTo(IntegrationStatus.MODIFYING);

                assertThat(rds.deleteIntegration(request -> request
                        .integrationIdentifier(created.integrationArn())).status())
                        .isEqualTo(IntegrationStatus.DELETING);
                assertThatThrownBy(() -> rds.describeIntegrations(request -> request
                        .integrationIdentifier(created.integrationArn())))
                        .isInstanceOf(IntegrationNotFoundException.class);

                assertThatThrownBy(() -> rds.createIntegration(request -> request
                        .integrationName(integrationName)
                        .sourceArn(sourceArn.replace(clusterId, clusterId + "-missing"))
                        .targetArn(targetArn).dataFilter("include: orders.*.*")))
                        .isInstanceOf(DbClusterNotFoundException.class);
            } finally {
                try {
                    if (namespaceCreated) {
                        serverless.deleteNamespace(request -> request.namespaceName(namespaceName));
                    }
                } finally {
                    if (clusterCreated) {
                        rds.deleteDBCluster(request -> request.dbClusterIdentifier(clusterId)
                                .skipFinalSnapshot(true));
                    }
                }
            }
        }
    }
}
