package io.floci.aws.services.rds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.Target;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.RdsException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.time.Instant;
import java.util.ArrayList;
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

    @Test
    void standaloneStopAndStartEmitNativeEventsAndRejectedCallsDoNot() throws Exception {
        try (Fixture fixture = new Fixture("111122223333", Region.US_WEST_2)) {
            String id = fixture.name;
            String arn = fixture.rds.createDBInstance(r -> r.dbInstanceIdentifier(id).engine("postgres")
                    .masterUsername("admin").masterUserPassword("password123")
                    .dbInstanceClass("db.t3.micro").allocatedStorage(5)).dbInstance().dbInstanceArn();
            try {
                assertThrows(RdsException.class, () -> fixture.rds.startDBInstance(r -> r.dbInstanceIdentifier(id)));
                assertTrue(fixture.receive().isEmpty());
                assertEquals("stopping", fixture.rds.stopDBInstance(r -> r.dbInstanceIdentifier(id))
                        .dbInstance().dbInstanceStatus());
                assertEquals("starting", fixture.rds.startDBInstance(r -> r.dbInstanceIdentifier(id))
                        .dbInstance().dbInstanceStatus());
                List<JsonNode> events = fixture.receive();
                assertEquals(2, events.size());
                assertEvent(events.get(0), fixture, arn, id, false, false);
                assertEvent(events.get(1), fixture, arn, id, false, true);
                assertTrue(fixture.receive().isEmpty());
            } finally {
                fixture.rds.deleteDBInstance(r -> r.dbInstanceIdentifier(id).skipFinalSnapshot(true));
            }
        }
    }

    @Test
    void clusterTransitionsNotifyTheClusterAndItsMemberInTheirOwnScope() throws Exception {
        try (Fixture fixture = new Fixture("444455556666", Region.CN_NORTH_1);
             Fixture foreignAccount = new Fixture("777788889999", Region.CN_NORTH_1);
             Fixture foreignRegion = new Fixture("444455556666", Region.CN_NORTHWEST_1)) {
            String cluster = fixture.name;
            String member = cluster + "-member";
            String clusterArn = fixture.rds.createDBCluster(r -> r.dbClusterIdentifier(cluster)
                    .engine("aurora-postgresql").masterUsername("admin").masterUserPassword("password123"))
                    .dbCluster().dbClusterArn();
            String memberArn = fixture.rds.createDBInstance(r -> r.dbInstanceIdentifier(member)
                    .dbClusterIdentifier(cluster).engine("aurora-postgresql").dbInstanceClass("db.r6g.large"))
                    .dbInstance().dbInstanceArn();
            try {
                fixture.rds.stopDBCluster(r -> r.dbClusterIdentifier(cluster));
                fixture.rds.startDBCluster(r -> r.dbClusterIdentifier(cluster));
                List<JsonNode> events = fixture.receive();
                assertEquals(4, events.size());
                for (boolean started : List.of(false, true)) {
                    String instanceEvent = started ? "RDS-EVENT-0088" : "RDS-EVENT-0087";
                    String clusterEvent = started ? "RDS-EVENT-0151" : "RDS-EVENT-0150";
                    assertEvent(find(events, instanceEvent), fixture, memberArn, member, false, started);
                    assertEvent(find(events, clusterEvent), fixture, clusterArn, cluster, true, started);
                }
                assertTrue(foreignAccount.receive().isEmpty());
                assertTrue(foreignRegion.receive().isEmpty());
            } finally {
                fixture.rds.deleteDBInstance(r -> r.dbInstanceIdentifier(member).skipFinalSnapshot(true));
                fixture.rds.deleteDBCluster(r -> r.dbClusterIdentifier(cluster).skipFinalSnapshot(true));
            }
        }
    }

    private JsonNode find(List<JsonNode> events, String eventId) {
        return events.stream().filter(e -> eventId.equals(e.path("detail").path("EventID").asText()))
                .findFirst().orElseThrow();
    }

    private void assertEvent(JsonNode event, Fixture fixture, String arn, String id,
                             boolean cluster, boolean started) {
        assertEquals("aws.rds", event.path("source").asText());
        assertEquals(cluster ? "RDS DB Cluster Event" : "RDS DB Instance Event",
                event.path("detail-type").asText());
        assertEquals(fixture.account, event.path("account").asText());
        assertEquals(fixture.region.id(), event.path("region").asText());
        assertEquals(mapper.valueToTree(List.of(arn)), event.path("resources"));
        JsonNode detail = event.path("detail");
        assertEquals(mapper.valueToTree(List.of("notification")), detail.path("EventCategories"));
        assertEquals(cluster ? "DB_CLUSTER" : "DB_INSTANCE", detail.path("SourceType").asText());
        assertEquals(arn, detail.path("SourceArn").asText());
        assertEquals(id, detail.path("SourceIdentifier").asText());
        assertEquals("DB " + (cluster ? "cluster" : "instance") + (started ? " started." : " stopped."),
                detail.path("Message").asText());
        assertEquals(cluster ? (started ? "RDS-EVENT-0151" : "RDS-EVENT-0150")
                : (started ? "RDS-EVENT-0088" : "RDS-EVENT-0087"), detail.path("EventID").asText());
        assertFalse(Instant.parse(detail.path("Date").asText()).isAfter(Instant.now()));
    }

    private class Fixture implements AutoCloseable {
        private final String name = "rds-events-" + Long.toUnsignedString(System.nanoTime(), 36);
        private final String account;
        private final Region region;
        private final RdsClient rds;
        private final EventBridgeClient eventBridge;
        private final SqsClient sqs;
        private final String queueUrl;

        Fixture(String account, Region region) {
            this.account = account;
            this.region = region;
            rds = AwsTestClients.create(RdsClient.builder(), account, region);
            eventBridge = AwsTestClients.create(EventBridgeClient.builder(), account, region);
            sqs = AwsTestClients.create(SqsClient.builder(), account, region);
            queueUrl = sqs.createQueue(r -> r.queueName(name)).queueUrl();
            String queueArn = sqs.getQueueAttributes(r -> r.queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
            eventBridge.putRule(r -> r.name(name).eventPattern("{\"source\":[\"aws.rds\"]}"));
            eventBridge.putTargets(r -> r.rule(name).targets(Target.builder().id("queue").arn(queueArn).build()));
        }

        List<JsonNode> receive() throws Exception {
            List<JsonNode> events = new ArrayList<>();
            for (Message message : sqs.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10)).messages()) {
                events.add(mapper.readTree(message.body()));
                sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
            }
            return events;
        }

        @Override
        public void close() {
            eventBridge.removeTargets(r -> r.rule(name).ids("queue"));
            eventBridge.deleteRule(r -> r.name(name));
            sqs.deleteQueue(r -> r.queueUrl(queueUrl));
            rds.close();
            eventBridge.close();
            sqs.close();
        }
    }
}
