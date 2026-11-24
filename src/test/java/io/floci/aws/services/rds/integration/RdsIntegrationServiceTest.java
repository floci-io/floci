package io.floci.aws.services.rds.integration;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.rds.RdsService;
import io.floci.aws.services.rds.model.DatabaseEngine;
import io.floci.aws.services.rds.model.DbCluster;
import io.floci.aws.services.rds.model.DbInstance;
import io.floci.aws.services.rds.model.Integration;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import io.floci.aws.services.redshiftserverless.model.Namespace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The integration record and its checks, with the RDS source and the Redshift target looked up
 * through mocks: the statuses each call reports, the source and target checks, and the identifier
 * forms a describe, modify or delete accepts.
 */
class RdsIntegrationServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String CLUSTER_ARN = "arn:aws:rds:us-east-1:000000000000:cluster:orders-src";
    private static final String INSTANCE_ARN = "arn:aws:rds:us-east-1:000000000000:db:orders-db";
    private static final String NAMESPACE_ARN = "arn:aws:redshift-serverless:us-east-1:000000000000:"
            + "namespace/4b2f2f9c-7d1e-4f55-9a77-1a2b3c4d5e6f";
    private static final String REDSHIFT_CLUSTER_ARN = "arn:aws:redshift:us-east-1:000000000000:cluster:orders-dw";
    private static final String FILTER = "include: orders.*.*";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T09:00:00Z"), ZoneOffset.UTC);

    private InMemoryStorage<String, Integration> storage;
    private RdsService rdsService;
    private RedshiftService redshiftService;
    private RedshiftServerlessService serverless;
    private RdsIntegrationService service;

    @BeforeEach
    void setUp() {
        storage = new InMemoryStorage<>();
        rdsService = mock(RdsService.class);
        redshiftService = mock(RedshiftService.class);
        serverless = mock(RedshiftServerlessService.class);
        when(rdsService.getDbCluster("orders-src", REGION)).thenReturn(cluster("orders-src", "aurora-postgresql"));
        when(rdsService.getDbCluster("missing", REGION)).thenThrow(
                new AwsException("DBClusterNotFoundFault", "DB cluster missing not found.", 404));
        when(serverless.findNamespaceByArn(anyString(), anyString())).thenReturn(Optional.empty());
        when(serverless.findNamespaceByArn(NAMESPACE_ARN, REGION)).thenReturn(Optional.of(new Namespace()));
        when(redshiftService.findClusterByArn(anyString(), anyString())).thenReturn(Optional.empty());
        when(redshiftService.findClusterByArn(REDSHIFT_CLUSTER_ARN, REGION)).thenReturn(Optional.of(new Cluster()));
        service = new RdsIntegrationService(storage, rdsService, redshiftService, serverless,
                new RegionResolver(REGION, ACCOUNT), CLOCK);
    }

    private static DbCluster cluster(String id, String engine) {
        DbCluster cluster = new DbCluster();
        cluster.setDbClusterIdentifier(id);
        cluster.setEngine(engine.contains("postgres") ? DatabaseEngine.POSTGRES : DatabaseEngine.MYSQL);
        cluster.setEngineIdentifier(engine);
        return cluster;
    }

    private static DbInstance instance(String id, String engine, String clusterId) {
        DbInstance instance = new DbInstance();
        instance.setDbInstanceIdentifier(id);
        instance.setEngine(engine.contains("postgres") ? DatabaseEngine.POSTGRES : DatabaseEngine.MYSQL);
        instance.setEngineIdentifier(engine);
        instance.setDbClusterIdentifier(clusterId);
        return instance;
    }

    private static RdsIntegrationService.CreateRequest request(String name, String sourceArn, String targetArn) {
        return new RdsIntegrationService.CreateRequest(name, sourceArn, targetArn, null, Map.of(),
                Map.of("team", "data"), FILTER, "orders into Redshift");
    }

    private Integration create(String name) {
        return service.createIntegration(request(name, CLUSTER_ARN, NAMESPACE_ARN), REGION);
    }

    private static void assertError(String code, int status, Runnable call) {
        AwsException error = assertThrows(AwsException.class, call::run);
        assertEquals(code, error.getErrorCode(), error.getMessage());
        assertEquals(status, error.getHttpStatus());
    }

    @Test
    void createIntegration_reportsCreatingAndIsActiveWhenReadBack() {
        Integration created = create("orders-zero-etl");

        assertThat(created.getStatus(), equalTo("creating"));
        assertThat(created.getIntegrationArn(), matchesPattern(
                "arn:aws:rds:us-east-1:000000000000:integration:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
                        + "[0-9a-f]{12}"));
        assertThat(created.getSourceArn(), equalTo(CLUSTER_ARN));
        assertThat(created.getTargetArn(), equalTo(NAMESPACE_ARN));
        assertThat(created.getDataFilter(), equalTo(FILTER));
        assertThat(created.getDescription(), equalTo("orders into Redshift"));
        assertThat(created.getTags(), equalTo(Map.of("team", "data")));
        assertThat(created.getCreateTime(), equalTo(CLOCK.instant()));
        assertThat(created.getErrors(), empty());

        Integration described = service.describeIntegrations(null, Map.of(), REGION).getFirst();
        assertThat(described.getStatus(), equalTo("active"));
        assertThat(described.getIntegrationArn(), equalTo(created.getIntegrationArn()));
    }

    @Test
    void createIntegration_takesAPostgresInstanceAndAProvisionedCluster() {
        when(rdsService.getDbInstance("orders-db", REGION)).thenReturn(instance("orders-db", "postgres", null));

        Integration created = service.createIntegration(
                request("orders-from-instance", INSTANCE_ARN, REDSHIFT_CLUSTER_ARN), REGION);

        assertThat(created.getSourceArn(), equalTo(INSTANCE_ARN));
        assertThat(created.getTargetArn(), equalTo(REDSHIFT_CLUSTER_ARN));
    }

    @Test
    void createIntegration_refusesASourceThatDoesNotExist() {
        assertError("DBClusterNotFoundFault", 404, () -> service.createIntegration(
                request("orders", "arn:aws:rds:us-east-1:000000000000:cluster:missing", NAMESPACE_ARN), REGION));
        when(rdsService.getDbInstance("gone", REGION)).thenThrow(
                new AwsException("DBInstanceNotFound", "DB instance gone not found.", 404));
        assertError("DBInstanceNotFound", 404, () -> service.createIntegration(
                request("orders", "arn:aws:rds:us-east-1:000000000000:db:gone", NAMESPACE_ARN), REGION));
    }

    @Test
    void createIntegration_refusesATargetThatDoesNotExist() {
        assertError("InvalidParameterValue", 400, () -> service.createIntegration(request("orders", CLUSTER_ARN,
                "arn:aws:redshift-serverless:us-east-1:000000000000:namespace/missing"), REGION));
        assertError("InvalidParameterValue", 400, () -> service.createIntegration(request("orders", CLUSTER_ARN,
                "arn:aws:redshift:us-east-1:000000000000:cluster:missing"), REGION));
    }

    @Test
    void createIntegration_refusesASourceOrTargetOfAnotherKind() {
        assertError("InvalidParameterValue", 400, () -> service.createIntegration(request("orders",
                "arn:aws:rds:us-east-1:000000000000:snapshot:orders", NAMESPACE_ARN), REGION));
        assertError("InvalidParameterValue", 400, () -> service.createIntegration(request("orders",
                "arn:aws:rds:us-east-1:000000000000:cluster:", NAMESPACE_ARN), REGION));
        assertError("InvalidParameterValue", 400, () -> service.createIntegration(request("orders",
                "arn:aws:rds:us-east-1:000000000000:db:", NAMESPACE_ARN), REGION));
        assertError("InvalidParameterValue", 400, () -> service.createIntegration(request("orders",
                CLUSTER_ARN, "arn:aws:s3:::orders"), REGION));
        assertError("InvalidParameterValue", 400, () -> service.createIntegration(request("orders",
                "not-an-arn", NAMESPACE_ARN), REGION));
    }

    @Test
    void createIntegration_refusesASourceInAnotherRegionOrAccount() {
        assertError("InvalidParameterValue", 400, () -> service.createIntegration(request("orders",
                "arn:aws:rds:us-west-2:000000000000:cluster:orders-src", NAMESPACE_ARN), REGION));
        assertError("InvalidParameterValue", 400, () -> service.createIntegration(request("orders",
                "arn:aws:rds:us-east-1:111111111111:cluster:orders-src", NAMESPACE_ARN), REGION));
    }

    @Test
    void createIntegration_refusesAnEngineThatIsNotPostgres() {
        when(rdsService.getDbCluster("mysql-src", REGION)).thenReturn(cluster("mysql-src", "aurora-mysql"));
        when(rdsService.getDbCluster("multi-az", REGION)).thenReturn(cluster("multi-az", "postgres"));
        when(rdsService.getDbInstance("mysql-db", REGION)).thenReturn(instance("mysql-db", "mysql", null));

        assertError("InvalidParameterValue", 400, () -> service.createIntegration(request("orders",
                "arn:aws:rds:us-east-1:000000000000:cluster:mysql-src", NAMESPACE_ARN), REGION));
        assertError("InvalidParameterValue", 400, () -> service.createIntegration(request("orders",
                "arn:aws:rds:us-east-1:000000000000:cluster:multi-az", NAMESPACE_ARN), REGION));
        assertError("InvalidParameterValue", 400, () -> service.createIntegration(request("orders",
                "arn:aws:rds:us-east-1:000000000000:db:mysql-db", NAMESPACE_ARN), REGION));
    }

    @Test
    void createIntegration_refusesAnAuroraMemberInstance() {
        when(rdsService.getDbInstance("orders-src-1", REGION))
                .thenReturn(instance("orders-src-1", "aurora-postgresql", "orders-src"));

        AwsException error = assertThrows(AwsException.class, () -> service.createIntegration(request("orders",
                "arn:aws:rds:us-east-1:000000000000:db:orders-src-1", NAMESPACE_ARN), REGION));
        assertThat(error.getMessage(), containsString("DB cluster ARN"));
    }

    @Test
    void createIntegration_refusesAnInvalidName() {
        assertError("InvalidParameterValue", 400, () -> create("1orders"));
        assertError("InvalidParameterValue", 400, () -> create("orders-"));
        assertError("InvalidParameterValue", 400, () -> create("orders--etl"));
        assertError("InvalidParameterValue", 400, () -> create("a".repeat(64)));
    }

    @Test
    void createIntegration_refusesANameAlreadyTaken() {
        create("orders-zero-etl");

        assertError("IntegrationAlreadyExistsFault", 400, () -> create("orders-zero-etl"));
    }

    @Test
    void createIntegration_refusesADuplicateSourceAndTargetPair() {
        create("orders-zero-etl");

        assertError("IntegrationAlreadyExistsFault", 400, () -> service.createIntegration(
                request("orders-second-name", CLUSTER_ARN, NAMESPACE_ARN), REGION));
    }

    @Test
    void createIntegration_requiresADataFilterNamingADatabase() {
        assertError("InvalidParameterValue", 400, () -> service.createIntegration(
                new RdsIntegrationService.CreateRequest("orders", CLUSTER_ARN, NAMESPACE_ARN, null, Map.of(),
                        Map.of(), null, null), REGION));
    }

    @Test
    void createIntegration_takesAnEncryptionContextOnlyWithAKey() {
        assertError("InvalidParameterValue", 400, () -> service.createIntegration(
                new RdsIntegrationService.CreateRequest("orders", CLUSTER_ARN, NAMESPACE_ARN, null,
                        Map.of("purpose", "analytics"), Map.of(), FILTER, null), REGION));

        Integration created = service.createIntegration(new RdsIntegrationService.CreateRequest("orders",
                CLUSTER_ARN, NAMESPACE_ARN, "alias/orders", Map.of("purpose", "analytics"), Map.of(), FILTER,
                null), REGION);
        assertThat(created.getKmsKeyId(), equalTo("alias/orders"));
        assertThat(created.getAdditionalEncryptionContext(), equalTo(Map.of("purpose", "analytics")));
    }

    @Test
    void describeIntegrations_findsAnIntegrationByArnIdOrName() {
        Integration created = create("orders-zero-etl");
        service.createIntegration(request("other-zero-etl", CLUSTER_ARN, REDSHIFT_CLUSTER_ARN), REGION);

        for (String identifier : List.of(created.getIntegrationArn(), created.integrationId(), "orders-zero-etl")) {
            List<Integration> found = service.describeIntegrations(identifier, Map.of(), REGION);
            assertThat(found.stream().map(Integration::getIntegrationArn).toList(),
                    contains(created.getIntegrationArn()));
        }
        assertThat(service.describeIntegrations(created.getIntegrationArn(),
                Map.of("status", List.of("active")), REGION).stream().map(Integration::getIntegrationArn).toList(),
                contains(created.getIntegrationArn()));
        assertThat(service.describeIntegrations(created.getIntegrationArn(),
                Map.of("status", List.of("failed")), REGION), empty());
        assertError("IntegrationNotFoundFault", 404,
                () -> service.describeIntegrations("missing", Map.of(), REGION));
    }

    @Test
    void describeIntegrations_prefersIdMatchOverAnotherIntegrationsName() {
        Integration first = create("orders-zero-etl");
        Integration second = service.createIntegration(
                request("other-zero-etl", CLUSTER_ARN, REDSHIFT_CLUSTER_ARN), REGION);
        String secondId = second.integrationId();
        String firstKey = "integration::" + REGION + "::" + first.integrationId();
        Integration renamed = new Integration(storage.get(firstKey).orElseThrow());
        renamed.setIntegrationName(secondId);
        storage.put(firstKey, renamed);

        List<Integration> found = service.describeIntegrations(secondId, Map.of(), REGION);
        assertThat(found.stream().map(Integration::getIntegrationArn).toList(),
                contains(second.getIntegrationArn()));
    }

    @Test
    void describeIntegrations_isScopedToTheRegionAndFiltered() {
        Integration orders = create("orders-zero-etl");
        service.createIntegration(request("other-zero-etl", CLUSTER_ARN, REDSHIFT_CLUSTER_ARN), REGION);

        assertThat(service.describeIntegrations(null, Map.of(), "us-west-2"), empty());
        assertThat(service.describeIntegrations(null, Map.of("integration-name", List.of("orders-zero-etl")),
                REGION).stream().map(Integration::getIntegrationArn).toList(), contains(orders.getIntegrationArn()));
        assertThat(service.describeIntegrations(null, Map.of("status", List.of("failed")), REGION), empty());
        assertError("InvalidParameterValue", 400,
                () -> service.describeIntegrations(null, Map.of("engine", List.of("postgres")), REGION));
    }

    @Test
    void modifyIntegration_reportsModifyingAndKeepsTheChanges() {
        Integration created = create("orders-zero-etl");

        Integration modified = service.modifyIntegration(created.getIntegrationArn(), "orders-renamed",
                "include: orders.public.*", "renamed", REGION);

        assertThat(modified.getStatus(), equalTo("modifying"));
        Integration described = service.describeIntegrations(created.getIntegrationArn(), Map.of(), REGION)
                .getFirst();
        assertThat(described.getStatus(), equalTo("active"));
        assertThat(described.getIntegrationName(), equalTo("orders-renamed"));
        assertThat(described.getDataFilter(), equalTo("include: orders.public.*"));
        assertThat(described.getDescription(), equalTo("renamed"));
    }

    @Test
    void modifyIntegration_refusesATakenNameAnInvalidFilterAndAnUnknownIntegration() {
        Integration created = create("orders-zero-etl");
        service.createIntegration(request("other-zero-etl", CLUSTER_ARN, REDSHIFT_CLUSTER_ARN), REGION);

        assertError("IntegrationAlreadyExistsFault", 400, () -> service.modifyIntegration(
                created.getIntegrationArn(), "other-zero-etl", null, null, REGION));
        assertError("InvalidParameterValue", 400, () -> service.modifyIntegration(
                created.getIntegrationArn(), null, "include: orders.items", null, REGION));
        assertError("IntegrationNotFoundFault", 404, () -> service.modifyIntegration(
                "missing", null, null, "changed", REGION));
    }

    @Test
    void modifyIntegration_refusesAnIntegrationThatIsNotActive() {
        Integration created = create("orders-zero-etl");
        String key = "integration::" + REGION + "::" + created.integrationId();
        Integration stored = new Integration(storage.get(key).orElseThrow());
        stored.setStatus(Integration.STATUS_CREATING);
        storage.put(key, stored);

        assertError("InvalidIntegrationStateFault", 400, () -> service.modifyIntegration(
                created.getIntegrationArn(), null, null, "changed", REGION));
    }

    @Test
    void deleteIntegration_reportsDeletingAndRemovesTheRecord() {
        Integration created = create("orders-zero-etl");

        Integration deleted = service.deleteIntegration(created.getIntegrationArn(), REGION);

        assertThat(deleted.getStatus(), equalTo("deleting"));
        assertThat(service.describeIntegrations(null, Map.of(), REGION), empty());
        assertError("IntegrationNotFoundFault", 404,
                () -> service.deleteIntegration(created.getIntegrationArn(), REGION));
        // The name is free again once the integration is gone.
        create("orders-zero-etl");
    }

    @Test
    void clear_removesEveryIntegration() {
        create("orders-zero-etl");

        service.clear();

        assertThat(service.describeIntegrations(null, Map.of(), REGION), empty());
    }

    @Test
    void addDestination_syncsUntilTheLoadFinishesThenIsActive() {
        Integration created = create("orders-zero-etl");
        Integration integration = service.findById(created.integrationId()).orElseThrow();

        Integration syncing = service.addDestination(integration, "orders_dw", "orders", false);
        assertEquals(Integration.STATUS_SYNCING, syncing.getStatus());
        assertEquals(Integration.STATUS_SYNCING, describe(created).getStatus());
        assertEquals(true, service.isLoading(integration, "orders", "orders_dw"));
        assertError("InvalidIntegrationStateFault", 400,
                () -> service.modifyIntegration(created.getIntegrationArn(), null, null, "x", REGION));

        service.finishDestination(integration, "orders", "orders_dw", null);
        Integration active = describe(created);
        assertEquals(Integration.STATUS_ACTIVE, active.getStatus());
        assertEquals(Integration.Destination.STATE_SYNCED, active.destinationFor("orders").orElseThrow().getState());
        assertEquals(false, service.isLoading(integration, "orders", "orders_dw"));
    }

    @Test
    void addDestination_refusesASecondDatabaseFromTheSameSourceDatabase() {
        Integration integration = service.findById(create("orders-zero-etl").integrationId()).orElseThrow();
        service.addDestination(integration, "orders_dw", "orders", false);
        service.finishDestination(integration, "orders", "orders_dw", null);

        assertError("IntegrationAlreadyExistsFault", 400,
                () -> service.addDestination(integration, "orders_dw2", "orders", false));
    }

    @Test
    void addDestination_takesAnotherSourceDatabaseWhileSyncing() {
        Integration integration = service.findById(create("orders-zero-etl").integrationId()).orElseThrow();
        service.addDestination(integration, "orders_dw", "orders", false);

        Integration both = service.addDestination(integration, "billing_dw", "billing", false);

        assertEquals(2, both.getDestinations().size());
        assertEquals(Integration.STATUS_SYNCING, both.getStatus());
    }

    @Test
    void addDestination_replacesAFailedLoadOrADroppedDatabase() {
        Integration created = create("orders-zero-etl");
        Integration integration = service.findById(created.integrationId()).orElseThrow();
        service.addDestination(integration, "orders_dw", "orders", false);
        service.finishDestination(integration, "orders", "orders_dw", "source is down");

        Integration retried = service.addDestination(integration, "orders_dw", "orders", false);
        assertEquals(Integration.STATUS_SYNCING, retried.getStatus());
        assertEquals(1, retried.getDestinations().size());

        service.finishDestination(integration, "orders", "orders_dw", null);
        Integration reloaded = service.addDestination(integration, "orders_dw_v2", "orders", true);
        assertEquals("orders_dw_v2", reloaded.destinationFor("orders").orElseThrow().getDatabaseName());
    }

    @Test
    void finishDestination_withAFailureMarksTheIntegrationFailed() {
        Integration created = create("orders-zero-etl");
        Integration integration = service.findById(created.integrationId()).orElseThrow();
        service.addDestination(integration, "orders_dw", "orders", false);

        service.finishDestination(integration, "orders", "orders_dw", "source is down");

        Integration failed = describe(created);
        assertEquals(Integration.STATUS_FAILED, failed.getStatus());
        assertEquals(RdsIntegrationService.INITIAL_LOAD_FAILED, failed.getErrors().get(0).getErrorCode());
        assertThat(failed.getErrors().get(0).getErrorMessage(), containsString("source is down"));
    }

    @Test
    void addDestination_preservesErrorsOfOtherFailedDestinations() {
        Integration created = create("orders-zero-etl");
        Integration integration = service.findById(created.integrationId()).orElseThrow();
        service.addDestination(integration, "orders_dw", "orders", false);
        service.finishDestination(integration, "orders", "orders_dw", "orders load failed");

        service.addDestination(integration, "billing_dw", "billing", false);
        service.finishDestination(integration, "billing", "billing_dw", null);

        Integration described = describe(created);
        assertEquals(Integration.STATUS_FAILED, described.getStatus());
        assertEquals(1, described.getErrors().size());
        assertThat(described.getErrors().get(0).getErrorMessage(), containsString("orders load failed"));
    }

    @Test
    void restoreDestination_restoresPreviousFailedDestinationAndErrors() {
        Integration created = create("orders-zero-etl");
        Integration integration = service.findById(created.integrationId()).orElseThrow();
        service.addDestination(integration, "orders_dw", "orders", false);
        service.finishDestination(integration, "orders", "orders_dw", "source is down");
        Integration failed = describe(created);
        Integration.Destination previous = failed.destinationFor("orders").orElseThrow();
        List<Integration.IntegrationError> previousErrors = List.copyOf(failed.getErrors());

        service.addDestination(integration, "orders_dw_v2", "orders", false);
        service.restoreDestination(integration, "orders", previous, previousErrors);

        Integration restored = describe(created);
        assertEquals(Integration.STATUS_FAILED, restored.getStatus());
        assertEquals("orders_dw", restored.destinationFor("orders").orElseThrow().getDatabaseName());
        assertEquals(Integration.Destination.STATE_FAILED,
                restored.destinationFor("orders").orElseThrow().getState());
        assertEquals(1, restored.getErrors().size());
        assertThat(restored.getErrors().get(0).getErrorMessage(), containsString("source is down"));
    }

    @Test
    void removeDestination_putsTheIntegrationBackToActive() {
        Integration created = create("orders-zero-etl");
        Integration integration = service.findById(created.integrationId()).orElseThrow();
        service.addDestination(integration, "orders_dw", "orders", false);

        service.removeDestination(integration, "orders");

        assertEquals(Integration.STATUS_ACTIVE, describe(created).getStatus());
        assertThat(describe(created).getDestinations(), empty());
    }

    @Test
    void finishDestination_ignoresADeletedIntegration() {
        Integration created = create("orders-zero-etl");
        Integration integration = service.findById(created.integrationId()).orElseThrow();
        service.addDestination(integration, "orders_dw", "orders", false);
        service.deleteIntegration(created.getIntegrationArn(), REGION);

        service.finishDestination(integration, "orders", "orders_dw", null);

        assertThat(storage.scan(key -> true), empty());
    }

    @Test
    void integrationsWithLoadsInProgress_listsOnlySyncingOnes() {
        Integration first = service.findById(create("orders-zero-etl").integrationId()).orElseThrow();
        service.createIntegration(request("other-zero-etl", CLUSTER_ARN, REDSHIFT_CLUSTER_ARN), REGION);
        service.addDestination(first, "orders_dw", "orders", false);

        List<Integration> pending = service.integrationsWithLoadsInProgress();

        assertEquals(1, pending.size());
        assertEquals(first.getIntegrationArn(), pending.get(0).getIntegrationArn());
    }

    @Test
    void findById_returnsNothingForAnUnknownId() {
        create("orders-zero-etl");
        assertEquals(Optional.empty(), service.findById("4b2f2f9c-0000-0000-0000-000000000000"));
    }

    private Integration describe(Integration integration) {
        return service.describeIntegrations(integration.getIntegrationArn(), Map.of(), REGION).get(0);
    }
}
