package io.floci.aws.services.rds;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.CreateIntegrationResponse;
import software.amazon.awssdk.services.rds.model.DbClusterNotFoundException;
import software.amazon.awssdk.services.rds.model.DeleteIntegrationResponse;
import software.amazon.awssdk.services.rds.model.Integration;
import software.amazon.awssdk.services.rds.model.IntegrationAlreadyExistsException;
import software.amazon.awssdk.services.rds.model.IntegrationNotFoundException;
import software.amazon.awssdk.services.rds.model.IntegrationStatus;
import software.amazon.awssdk.services.rds.model.ModifyIntegrationResponse;
import software.amazon.awssdk.services.rds.model.Tag;
import software.amazon.awssdk.services.redshiftserverless.RedshiftServerlessClient;
import software.amazon.awssdk.services.redshiftserverless.model.ResourceNotFoundException;

import java.util.List;
import java.util.Map;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static io.floci.aws.testing.RdsRequests.rdsClient;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The RDS zero-ETL integration control plane through the SDK: an Aurora PostgreSQL cluster as the
 * source and a Redshift Serverless namespace as the target, created, described, modified and deleted,
 * and a missing source or target refused.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsIntegrationIntegrationTest {

    private static final String ACCOUNT = "246813579024";
    private static final String CLUSTER = "zetl-orders-src";
    private static final String NAMESPACE = "zetl-orders-dest";
    private static final String FILTER = "include: orders.*.*";

    @AfterEach
    void cleanUp() {
        try (RdsClient rds = rdsClient("us-east-1", ACCOUNT);
             RedshiftServerlessClient serverless = serverlessClient()) {
            for (Integration integration : rds.describeIntegrations(request -> {}).integrations()) {
                rds.deleteIntegration(request -> request.integrationIdentifier(integration.integrationArn()));
            }
            try {
                rds.deleteDBCluster(request -> request.dbClusterIdentifier(CLUSTER).skipFinalSnapshot(true));
            } catch (DbClusterNotFoundException ignored) {
                // the test failed before it created the cluster
            }
            try {
                serverless.deleteNamespace(request -> request.namespaceName(NAMESPACE));
            } catch (ResourceNotFoundException ignored) {
                // the test failed before it created the namespace
            }
        }
    }

    private static RedshiftServerlessClient serverlessClient() {
        return AwsTestClients.create(RedshiftServerlessClient.builder(), ACCOUNT, Region.US_EAST_1);
    }

    private static String createSource(RdsClient rds) {
        return rds.createDBCluster(request -> request.dbClusterIdentifier(CLUSTER).engine("aurora-postgresql")
                .masterUsername("postgres").masterUserPassword("Secret123")).dbCluster().dbClusterArn();
    }

    private static String createTarget(RedshiftServerlessClient serverless) {
        return serverless.createNamespace(request -> request.namespaceName(NAMESPACE)).namespace().namespaceArn();
    }

    @Test
    void integrationLifecycleThroughTheSdk() {
        try (RdsClient rds = rdsClient("us-east-1", ACCOUNT);
             RedshiftServerlessClient serverless = serverlessClient()) {
            String sourceArn = createSource(rds);
            String targetArn = createTarget(serverless);

            CreateIntegrationResponse created = rds.createIntegration(request -> request
                    .integrationName("orders-zero-etl").sourceArn(sourceArn).targetArn(targetArn)
                    .dataFilter(FILTER).description("orders into Redshift")
                    .kmsKeyId("alias/orders").additionalEncryptionContext(Map.of("purpose", "analytics"))
                    .tags(Tag.builder().key("team").value("data").build()));
            assertThat(created.status(), equalTo(IntegrationStatus.CREATING));
            assertThat(created.integrationArn(), matchesPattern(
                    "arn:aws:rds:us-east-1:" + ACCOUNT + ":integration:[0-9a-f-]{36}"));
            assertThat(created.sourceArn(), equalTo(sourceArn));
            assertThat(created.targetArn(), equalTo(targetArn));
            assertThat(created.dataFilter(), equalTo(FILTER));
            assertThat(created.kmsKeyId(), equalTo("alias/orders"));
            assertThat(created.additionalEncryptionContext(), equalTo(Map.of("purpose", "analytics")));
            assertThat(created.tags(), contains(Tag.builder().key("team").value("data").build()));
            assertThat(created.createTime(), notNullValue());
            assertThat(created.errors(), empty());

            Integration described = rds.describeIntegrations(request -> request
                    .integrationIdentifier(created.integrationArn())).integrations().getFirst();
            assertThat(described.status(), equalTo(IntegrationStatus.ACTIVE));
            assertThat(described.integrationName(), equalTo("orders-zero-etl"));
            assertThat(described.description(), equalTo("orders into Redshift"));

            assertThrows(IntegrationAlreadyExistsException.class, () -> rds.createIntegration(request -> request
                    .integrationName("orders-zero-etl").sourceArn(sourceArn).targetArn(targetArn)
                    .dataFilter(FILTER)));

            ModifyIntegrationResponse modified = rds.modifyIntegration(request -> request
                    .integrationIdentifier("orders-zero-etl").integrationName("orders-renamed")
                    .dataFilter("include: orders.public.*").description("renamed"));
            assertThat(modified.status(), equalTo(IntegrationStatus.MODIFYING));
            assertThat(modified.integrationName(), equalTo("orders-renamed"));

            List<Integration> byFilter = rds.describeIntegrations(request -> request.filters(filter -> filter
                    .name("source-arn").values(sourceArn))).integrations();
            assertThat(byFilter.stream().map(Integration::dataFilter).toList(),
                    contains("include: orders.public.*"));

            DeleteIntegrationResponse deleted = rds.deleteIntegration(request -> request
                    .integrationIdentifier(created.integrationArn()));
            assertThat(deleted.status(), equalTo(IntegrationStatus.DELETING));
            assertThat(rds.describeIntegrations(request -> {}).integrations(), empty());
            assertThrows(IntegrationNotFoundException.class, () -> rds.describeIntegrations(request -> request
                    .integrationIdentifier(created.integrationArn())));
        }
    }

    @Test
    void createIntegrationRefusesAMissingSourceOrTarget() {
        try (RdsClient rds = rdsClient("us-east-1", ACCOUNT);
             RedshiftServerlessClient serverless = serverlessClient()) {
            String targetArn = createTarget(serverless);
            assertAwsError("DBClusterNotFoundFault", 404, () -> rds.createIntegration(request -> request
                    .integrationName("orders-zero-etl")
                    .sourceArn("arn:aws:rds:us-east-1:" + ACCOUNT + ":cluster:missing")
                    .targetArn(targetArn).dataFilter(FILTER)));

            String sourceArn = createSource(rds);
            assertAwsError("InvalidParameterValue", 400, () -> rds.createIntegration(request -> request
                    .integrationName("orders-zero-etl").sourceArn(sourceArn)
                    .targetArn("arn:aws:redshift-serverless:us-east-1:" + ACCOUNT
                            + ":namespace/00000000-0000-0000-0000-000000000000")
                    .dataFilter(FILTER)));
            assertAwsError("InvalidParameterValue", 400, () -> rds.createIntegration(request -> request
                    .integrationName("orders-zero-etl").sourceArn(sourceArn).targetArn(targetArn)));
            assertThat(rds.describeIntegrations(request -> {}).integrations(), empty());
        }
    }
}
