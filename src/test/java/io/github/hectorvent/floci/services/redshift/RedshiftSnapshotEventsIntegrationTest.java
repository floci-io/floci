package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.Event;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class RedshiftSnapshotEventsIntegrationTest {

    private static final String OWNER = "565656565656";
    private static final String DEFAULT_ACCOUNT = "000000000000";

    @InjectMock
    RedshiftContainerManager manager;
    @InjectMock
    RedshiftProxyManager proxy;
    @Inject
    RedshiftSnapshotScheduler scheduler;
    @Inject
    StorageFactory factory;
    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void backgroundSnapshotHistoryAndNotificationsUseTheResourceAccountAndRegion(boolean snapshotFails) throws Exception {
        String name = "sdk-snapshot-events-" + UUID.randomUUID().toString().substring(0, 8);
        String clusterId = name + "-cluster";
        String scheduleId = name + "-schedule";
        String terminalEvent = snapshotFails ? "REDSHIFT-EVENT-3503" : "REDSHIFT-EVENT-3004";
        when(manager.start(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new RedshiftContainerHandle("mock", clusterId, "localhost", 5432));
        doAnswer(invocation -> {
            if (snapshotFails) {
                throw new IOException("snapshot failed for " + clusterId);
            }
            Files.writeString(invocation.getArgument(3, Path.class), "backup contents");
            return null;
        }).when(manager).takeSnapshot(anyString(), anyString(), anyString(), any(Path.class));

        try (Scope ownerWest = scope(OWNER, Region.US_WEST_2);
                Scope fallback = scope(DEFAULT_ACCOUNT, Region.US_EAST_1);
                Scope ownerEast = scope(OWNER, Region.US_EAST_1);
                Watcher westWatcher = watcher(ownerWest, name);
                Watcher fallbackWatcher = watcher(fallback, name);
                Watcher eastWatcher = watcher(ownerEast, name)) {
            ownerWest.redshift().createSnapshotSchedule(request -> request.scheduleIdentifier(scheduleId)
                    .scheduleDefinitions("rate(1 hour)"));
            try {
                ownerWest.redshift().createCluster(request -> request.clusterIdentifier(clusterId).nodeType("dc2.large")
                        .masterUsername("admin").masterUserPassword("Secret123").automatedSnapshotRetentionPeriod(1));
                try {
                    ownerWest.redshift().modifyClusterSnapshotSchedule(request -> request.clusterIdentifier(clusterId)
                            .scheduleIdentifier(scheduleId));
                    AccountAwareStorageBackend<Cluster> clusters = factory.create("redshift", "redshift-clusters.json",
                            new TypeReference<Map<String, Cluster>>() { });
                    Instant due = clusters.getForAccount(OWNER, clusterId).orElseThrow()
                            .getAutomatedSnapshotAnchor().plusSeconds(3600);
                    CompletableFuture.runAsync(() -> scheduler.tick(due)).get(20, TimeUnit.SECONDS);

                    List<Event> history = snapshotEvents(ownerWest.redshift(), clusterId);
                    assertEquals(List.of("REDSHIFT-EVENT-2009", terminalEvent), history.stream().map(Event::eventId).toList());
                    assertTrue(snapshotEvents(fallback.redshift(), clusterId).isEmpty(),
                            "Background events must not be stored under the default account");
                    assertTrue(snapshotEvents(ownerEast.redshift(), clusterId).isEmpty(),
                            "Background events must not use the default region within the resource account");

                    List<Message> delivered = receiveTwo(ownerWest.sqs(), westWatcher.queueUrl());
                    assertEquals(2, delivered.size());
                    assertTrue(delivered.stream().allMatch(message -> message.body().contains(clusterId)));
                    assertTrue(delivered.stream().anyMatch(message -> message.body().contains("REDSHIFT-EVENT-2009")));
                    assertTrue(delivered.stream().anyMatch(message -> message.body().contains(terminalEvent)));
                    assertTrue(fallback.sqs().receiveMessage(request -> request.queueUrl(fallbackWatcher.queueUrl())
                            .waitTimeSeconds(0)).messages().isEmpty(), "Default-account subscription received a foreign event");
                    assertTrue(ownerEast.sqs().receiveMessage(request -> request.queueUrl(eastWatcher.queueUrl())
                            .waitTimeSeconds(0)).messages().isEmpty(), "Another region's subscription received the snapshot event");
                    verify(manager).takeSnapshot(eq(OWNER), eq(clusterId), eq("admin"), any(Path.class));
                } finally {
                    ownerWest.redshift().deleteCluster(request -> request.clusterIdentifier(clusterId).skipFinalClusterSnapshot(true));
                }
            } finally {
                ownerWest.redshift().deleteSnapshotSchedule(request -> request.scheduleIdentifier(scheduleId));
            }
        }
    }

    private Scope scope(String account, Region region) {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"));
        RedshiftClient redshift = RedshiftClient.builder().endpointOverride(endpoint).region(region)
                .credentialsProvider(credentials).build();
        SnsClient sns = SnsClient.builder().endpointOverride(endpoint).region(region).credentialsProvider(credentials).build();
        SqsClient sqs = SqsClient.builder().endpointOverride(endpoint).region(region).credentialsProvider(credentials).build();
        return new Scope(redshift, sns, sqs);
    }

    private static Watcher watcher(Scope scope, String name) {
        String topic = scope.sns().createTopic(request -> request.name(name)).topicArn();
        String queue = scope.sqs().createQueue(request -> request.queueName(name)).queueUrl();
        String queueArn = scope.sqs().getQueueAttributes(request -> request.queueUrl(queue)
                .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
        String snsSubscription = scope.sns().subscribe(request -> request.topicArn(topic).protocol("sqs")
                .endpoint(queueArn).attributes(Map.of("RawMessageDelivery", "true"))).subscriptionArn();
        scope.redshift().createEventSubscription(request -> request.subscriptionName(name).snsTopicArn(topic)
                .sourceType("cluster-snapshot"));
        return new Watcher(scope, name, topic, queue, snsSubscription);
    }

    private static List<Event> snapshotEvents(RedshiftClient client, String clusterId) {
        return client.describeEvents().events().stream()
                .filter(event -> "cluster-snapshot".equals(event.sourceTypeAsString()) && event.message().contains(clusterId))
                .toList();
    }

    private static List<Message> receiveTwo(SqsClient sqs, String queue) {
        List<Message> received = new ArrayList<>();
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            received.addAll(sqs.receiveMessage(request -> request.queueUrl(queue).maxNumberOfMessages(10)
                    .waitTimeSeconds(0)).messages());
            return received.size() >= 2;
        });
        return received;
    }

    private record Scope(RedshiftClient redshift, SnsClient sns, SqsClient sqs) implements AutoCloseable {
        @Override
        public void close() {
            redshift.close();
            sns.close();
            sqs.close();
        }
    }

    private record Watcher(Scope scope, String name, String topicArn,
                           String queueUrl, String snsSubscription) implements AutoCloseable {
        @Override
        public void close() {
            scope.redshift().deleteEventSubscription(request -> request.subscriptionName(name));
            scope.sns().unsubscribe(request -> request.subscriptionArn(snsSubscription));
            scope.sns().deleteTopic(request -> request.topicArn(topicArn));
            scope.sqs().deleteQueue(request -> request.queueUrl(queueUrl));
        }
    }
}
