package io.floci.aws.services.rds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EventRuleQueue;
import io.floci.aws.testing.RdsMockProfile;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.RdsException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SDK control-plane calls through an EventBridge rule to SQS; only database containers are mocked. */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsLifecycleEventIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void standaloneStopAndStartEmitNativeEventsAndRejectedCallsDoNot() throws Exception {
        String account = "111122223333";
        Region region = Region.US_WEST_2;
        String id = "rds-events-" + Long.toUnsignedString(System.nanoTime(), 36);
        try (RdsClient rds = AwsTestClients.create(RdsClient.builder(), account, region);
             EventRuleQueue queue = EventRuleQueue.route(account, region.id(), id, "{\"source\":[\"aws.rds\"]}")) {
            String arn = rds.createDBInstance(r -> r.dbInstanceIdentifier(id).engine("postgres")
                    .masterUsername("admin").masterUserPassword("password123")
                    .dbInstanceClass("db.t3.micro").allocatedStorage(5)).dbInstance().dbInstanceArn();
            try {
                assertThrows(RdsException.class, () -> rds.startDBInstance(r -> r.dbInstanceIdentifier(id)));
                assertTrue(queue.drain().isEmpty());
                assertEquals("stopping", rds.stopDBInstance(r -> r.dbInstanceIdentifier(id))
                        .dbInstance().dbInstanceStatus());
                assertEquals("starting", rds.startDBInstance(r -> r.dbInstanceIdentifier(id))
                        .dbInstance().dbInstanceStatus());
                List<JsonNode> events = queue.drain();
                assertEquals(2, events.size());
                assertEvent(events.get(0), account, region, arn, id, false, false);
                assertEvent(events.get(1), account, region, arn, id, false, true);
                assertTrue(queue.drain().isEmpty());
            } finally {
                rds.deleteDBInstance(r -> r.dbInstanceIdentifier(id).skipFinalSnapshot(true));
            }
        }
    }

    @Test
    void clusterTransitionsNotifyTheClusterAndItsMemberInTheirOwnScope() throws Exception {
        String account = "444455556666";
        Region region = Region.CN_NORTH_1;
        String cluster = "rds-events-" + Long.toUnsignedString(System.nanoTime(), 36);
        String member = cluster + "-member";
        String pattern = "{\"source\":[\"aws.rds\"]}";
        try (RdsClient rds = AwsTestClients.create(RdsClient.builder(), account, region);
             EventRuleQueue queue = EventRuleQueue.route(account, region.id(), cluster, pattern);
             EventRuleQueue foreignAccount = EventRuleQueue.route("777788889999", region.id(), cluster, pattern);
             EventRuleQueue foreignRegion = EventRuleQueue.route(account, Region.CN_NORTHWEST_1.id(), cluster, pattern)) {
            String clusterArn = rds.createDBCluster(r -> r.dbClusterIdentifier(cluster)
                    .engine("aurora-postgresql").masterUsername("admin").masterUserPassword("password123"))
                    .dbCluster().dbClusterArn();
            String memberArn = rds.createDBInstance(r -> r.dbInstanceIdentifier(member)
                    .dbClusterIdentifier(cluster).engine("aurora-postgresql").dbInstanceClass("db.r6g.large"))
                    .dbInstance().dbInstanceArn();
            try {
                rds.stopDBCluster(r -> r.dbClusterIdentifier(cluster));
                rds.startDBCluster(r -> r.dbClusterIdentifier(cluster));
                List<JsonNode> events = queue.drain();
                assertEquals(4, events.size());
                for (boolean started : List.of(false, true)) {
                    String instanceEvent = started ? "RDS-EVENT-0088" : "RDS-EVENT-0087";
                    String clusterEvent = started ? "RDS-EVENT-0151" : "RDS-EVENT-0150";
                    assertEvent(find(events, instanceEvent), account, region, memberArn, member, false, started);
                    assertEvent(find(events, clusterEvent), account, region, clusterArn, cluster, true, started);
                }
                assertTrue(foreignAccount.drain().isEmpty());
                assertTrue(foreignRegion.drain().isEmpty());
            } finally {
                rds.deleteDBInstance(r -> r.dbInstanceIdentifier(member).skipFinalSnapshot(true));
                rds.deleteDBCluster(r -> r.dbClusterIdentifier(cluster).skipFinalSnapshot(true));
            }
        }
    }

    @Test
    void awsClusterSourceTypePatternReceivesClusterLifecycleEvents() throws Exception {
        String name = "rds-cluster-filter-" + Long.toUnsignedString(System.nanoTime(), 36);
        String pattern = """
                {"source":["aws.rds"],"detail-type":["RDS DB Cluster Event"],
                 "detail":{"SourceType":["CLUSTER"]}}
                """;
        String account = "222233334444";
        Region region = Region.EU_WEST_2;
        try (RdsClient rds = AwsTestClients.create(RdsClient.builder(), account, region);
             EventRuleQueue queue = EventRuleQueue.route(account, region.id(), name, pattern)) {
            String arn = rds.createDBCluster(r -> r.dbClusterIdentifier(name)
                    .engine("aurora-postgresql").masterUsername("admin").masterUserPassword("password123"))
                    .dbCluster().dbClusterArn();
            try {
                rds.stopDBCluster(r -> r.dbClusterIdentifier(name));
                rds.startDBCluster(r -> r.dbClusterIdentifier(name));
                List<JsonNode> events = queue.drain();
                assertEquals(2, events.size());
                assertEvent(find(events, "RDS-EVENT-0150"), account, region, arn, name, true, false);
                assertEvent(find(events, "RDS-EVENT-0151"), account, region, arn, name, true, true);
            } finally {
                rds.deleteDBCluster(r -> r.dbClusterIdentifier(name).skipFinalSnapshot(true));
            }
        }
    }

    private JsonNode find(List<JsonNode> events, String eventId) {
        return events.stream().filter(e -> eventId.equals(e.path("detail").path("EventID").asText()))
                .findFirst().orElseThrow();
    }

    private void assertEvent(JsonNode event, String account, Region region, String arn, String id,
                             boolean cluster, boolean started) {
        assertEquals("aws.rds", event.path("source").asText());
        assertEquals(cluster ? "RDS DB Cluster Event" : "RDS DB Instance Event",
                event.path("detail-type").asText());
        assertEquals(account, event.path("account").asText());
        assertEquals(region.id(), event.path("region").asText());
        assertEquals(mapper.valueToTree(List.of(arn)), event.path("resources"));
        JsonNode detail = event.path("detail");
        assertEquals(mapper.valueToTree(List.of("notification")), detail.path("EventCategories"));
        assertEquals(cluster ? "CLUSTER" : "DB_INSTANCE", detail.path("SourceType").asText());
        assertEquals(arn, detail.path("SourceArn").asText());
        assertEquals(id, detail.path("SourceIdentifier").asText());
        assertEquals("DB " + (cluster ? "cluster" : "instance") + (started ? " started." : " stopped."),
                detail.path("Message").asText());
        assertEquals(cluster ? (started ? "RDS-EVENT-0151" : "RDS-EVENT-0150")
                : (started ? "RDS-EVENT-0088" : "RDS-EVENT-0087"), detail.path("EventID").asText());
        assertFalse(Instant.parse(detail.path("Date").asText()).isAfter(Instant.now()));
    }

}
