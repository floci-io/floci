package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("EventBridge Archive and Replay")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventBridgeReplayTest {

    private static EventBridgeClient eb;
    private static SqsClient sqs;

    private static String busName;
    private static String busArn;
    private static String archiveName;
    private static String archiveArn;
    private static String queueUrl;
    private static String queueArn;
    private static Instant beforePut;

    @BeforeAll
    static void setup() {
        eb = TestFixtures.eventBridgeClient();
        sqs = TestFixtures.sqsClient();
        busName = TestFixtures.uniqueName("replay-bus");
        archiveName = TestFixtures.uniqueName("replay-archive");
    }

    @AfterAll
    static void cleanup() {
        try { eb.deleteArchive(DeleteArchiveRequest.builder().archiveName(archiveName).build()); } catch (Exception ignored) {}
        try {
            eb.removeTargets(RemoveTargetsRequest.builder().rule("replay-compat-rule").eventBusName(busName).ids("sink").build());
        } catch (Exception ignored) {}
        try {
            eb.deleteRule(DeleteRuleRequest.builder().name("replay-compat-rule").eventBusName(busName).build());
        } catch (Exception ignored) {}
        try { eb.deleteEventBus(DeleteEventBusRequest.builder().name(busName).build()); } catch (Exception ignored) {}
        try { sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build()); } catch (Exception ignored) {}
        eb.close();
        sqs.close();
    }

    // ──────────────────────────── Setup ────────────────────────────

    @Test
    @Order(1)
    void createEventBus() {
        busArn = eb.createEventBus(CreateEventBusRequest.builder().name(busName).build())
                .eventBusArn();
        assertThat(busArn).contains(busName);
    }

    @Test
    @Order(2)
    void createArchive() {
        CreateArchiveResponse response = eb.createArchive(CreateArchiveRequest.builder()
                .archiveName(archiveName)
                .eventSourceArn(busArn)
                .description("Replay compatibility test archive")
                .retentionDays(1)
                .build());
        archiveArn = response.archiveArn();
        assertThat(archiveArn).contains(archiveName);
        assertThat(response.state()).isEqualTo(ArchiveState.ENABLED);
    }

    @Test
    @Order(3)
    void describeArchive() {
        DescribeArchiveResponse response = eb.describeArchive(
                DescribeArchiveRequest.builder().archiveName(archiveName).build());
        assertThat(response.archiveName()).isEqualTo(archiveName);
        assertThat(response.eventSourceArn()).isEqualTo(busArn);
        assertThat(response.state()).isEqualTo(ArchiveState.ENABLED);
        assertThat(response.retentionDays()).isEqualTo(1);
        assertThat(response.eventCount()).isZero();
    }

    @Test
    @Order(4)
    void listArchivesReturnsCreatedArchive() {
        ListArchivesResponse response = eb.listArchives(ListArchivesRequest.builder().build());
        assertThat(response.archives())
                .extracting(Archive::archiveName)
                .contains(archiveName);
    }

    @Test
    @Order(5)
    void updateArchive() {
        UpdateArchiveResponse response = eb.updateArchive(UpdateArchiveRequest.builder()
                .archiveName(archiveName)
                .description("Updated description")
                .retentionDays(7)
                .build());
        assertThat(response.state()).isEqualTo(ArchiveState.ENABLED);

        DescribeArchiveResponse desc = eb.describeArchive(
                DescribeArchiveRequest.builder().archiveName(archiveName).build());
        assertThat(desc.description()).isEqualTo("Updated description");
        assertThat(desc.retentionDays()).isEqualTo(7);
    }

    // ──────────────────────────── Capture events via PutEvents ────────────────────────────

    @Test
    @Order(10)
    void createSinkQueueAndRule() {
        queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(TestFixtures.uniqueName("replay-sink")).build()).queueUrl();
        queueArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl).attributeNamesWithStrings("QueueArn").build())
                .attributesAsStrings().get("QueueArn");

        eb.putRule(PutRuleRequest.builder()
                .name("replay-compat-rule")
                .eventBusName(busName)
                .eventPattern("{\"source\":[\"compat.replay.test\"]}")
                .state(RuleState.ENABLED)
                .build());

        eb.putTargets(PutTargetsRequest.builder()
                .rule("replay-compat-rule")
                .eventBusName(busName)
                .targets(Target.builder().id("sink").arn(queueArn).build())
                .build());
    }

    @Test
    @Order(11)
    void putEventsAreArchivedAndDelivered() {
        beforePut = Instant.now().minusSeconds(1);

        PutEventsResponse response = eb.putEvents(PutEventsRequest.builder()
                .entries(
                        PutEventsRequestEntry.builder()
                                .eventBusName(busName)
                                .source("compat.replay.test")
                                .detailType("OrderCreated")
                                .detail("{\"orderId\":\"A1\"}")
                                .build(),
                        PutEventsRequestEntry.builder()
                                .eventBusName(busName)
                                .source("compat.replay.test")
                                .detailType("OrderShipped")
                                .detail("{\"orderId\":\"A2\"}")
                                .build()
                )
                .build());

        assertThat(response.failedEntryCount()).isZero();

        // Verify archive captured both events
        DescribeArchiveResponse desc = eb.describeArchive(
                DescribeArchiveRequest.builder().archiveName(archiveName).build());
        assertThat(desc.eventCount()).isEqualTo(2);
    }

    // ──────────────────────────── Replay ────────────────────────────

    @Test
    @Order(20)
    void startReplay() throws InterruptedException {
        // Drain events already delivered by putEvents
        sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl).maxNumberOfMessages(10).build());

        Instant afterPut = Instant.now().plusSeconds(1);
        String replayName = TestFixtures.uniqueName("replay");

        StartReplayResponse response = eb.startReplay(StartReplayRequest.builder()
                .replayName(replayName)
                .eventSourceArn(archiveArn)
                .eventStartTime(beforePut)
                .eventEndTime(afterPut)
                .destination(ReplayDestination.builder().arn(busArn).build())
                .build());

        assertThat(response.replayArn()).contains(replayName);
        assertThat(response.state()).isIn(ReplayState.STARTING, ReplayState.RUNNING, ReplayState.COMPLETED);

        // Poll until completed (up to 5 s)
        ReplayState state = response.state();
        for (int i = 0; i < 50 && state != ReplayState.COMPLETED && state != ReplayState.FAILED; i++) {
            Thread.sleep(100);
            state = eb.describeReplay(DescribeReplayRequest.builder().replayName(replayName).build()).state();
        }
        assertThat(state).isEqualTo(ReplayState.COMPLETED);

        // Verify 2 replayed events arrived in the queue
        List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(2).build())
                .messages();
        assertThat(messages).hasSize(2);
    }

    @Test
    @Order(21)
    void listReplaysFilterByState() {
        ListReplaysResponse response = eb.listReplays(ListReplaysRequest.builder()
                .state(ReplayState.COMPLETED)
                .build());
        assertThat(response.replays()).isNotEmpty();
        assertThat(response.replays()).allMatch(r -> r.state() == ReplayState.COMPLETED);
    }

    @Test
    @Order(22)
    void describeReplayShowsTimestamps() {
        String replayName = eb.listReplays(ListReplaysRequest.builder()
                .state(ReplayState.COMPLETED).build())
                .replays().get(0).replayName();

        DescribeReplayResponse desc = eb.describeReplay(
                DescribeReplayRequest.builder().replayName(replayName).build());

        assertThat(desc.state()).isEqualTo(ReplayState.COMPLETED);
        assertThat(desc.replayStartTime()).isNotNull();
        assertThat(desc.replayEndTime()).isNotNull();
        assertThat(desc.eventLastReplayedTime()).isNotNull();
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(90)
    void deleteArchive() {
        eb.deleteArchive(DeleteArchiveRequest.builder().archiveName(archiveName).build());

        assertThatThrownBy(() ->
                eb.describeArchive(DescribeArchiveRequest.builder().archiveName(archiveName).build()))
                .isInstanceOf(software.amazon.awssdk.services.eventbridge.model.ResourceNotFoundException.class);
    }
}
