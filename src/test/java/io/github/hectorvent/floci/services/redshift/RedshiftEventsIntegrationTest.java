package io.github.hectorvent.floci.services.redshift;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.DescribeEventsResponse;
import software.amazon.awssdk.services.redshift.model.Event;
import software.amazon.awssdk.services.redshift.model.EventCategoriesMap;
import software.amazon.awssdk.services.redshift.model.EventSubscription;
import software.amazon.awssdk.services.redshift.model.Parameter;
import software.amazon.awssdk.services.redshift.model.RedshiftException;
import software.amazon.awssdk.services.redshift.model.Tag;
import software.amazon.awssdk.services.redshift.model.TaggedResource;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RedshiftEventsIntegrationTest {

    private static final String ACCOUNT = "313131313131";
    private static final String SOURCE_TYPE = "cluster-parameter-group";

    @TestHTTPResource
    URI endpoint;

    @Test
    void sdkSubscriptionLifecyclePreservesNamedListsTagsAndTimestamps() {
        String name = name();
        try (RedshiftClient redshift = redshift(Region.US_EAST_1); SnsClient sns = sns(Region.US_EAST_1)) {
            String topic = sns.createTopic(request -> request.name(name)).topicArn();
            createGroup(redshift, name);
            try {
                EventSubscription created = redshift.createEventSubscription(request -> request
                        .subscriptionName(name).snsTopicArn(topic).sourceType(SOURCE_TYPE)
                        .sourceIds(name).eventCategories("configuration").severity("INFO")
                        .tags(Tag.builder().key("team").value("analytics").build())).eventSubscription();
                try {
                    assertEquals(name, created.custSubscriptionId());
                    assertEquals(ACCOUNT, created.customerAwsId());
                    assertEquals(SOURCE_TYPE, created.sourceType());
                    assertEquals(List.of(name), created.sourceIdsList());
                    assertEquals(List.of("configuration"), created.eventCategoriesList());
                    assertEquals("INFO", created.severity());
                    assertEquals("active", created.status());
                    assertTrue(created.enabled());
                    assertNotNull(created.subscriptionCreationTime());
                    assertEquals("team", created.tags().getFirst().key());
                    assertEquals("analytics", created.tags().getFirst().value());

                    List<EventSubscription> described = redshift.describeEventSubscriptions(request -> request
                            .subscriptionName(name)).eventSubscriptionsList();
                    assertEquals(1, described.size());
                    assertEquals(created.subscriptionCreationTime(), described.getFirst().subscriptionCreationTime());
                    assertEquals(List.of(name), redshift.describeEventSubscriptions(request -> request
                            .tagKeys("team").tagValues("analytics")).eventSubscriptionsList().stream()
                            .map(EventSubscription::custSubscriptionId).filter(name::equals).toList());

                    EventSubscription disabled = redshift.modifyEventSubscription(request -> request
                            .subscriptionName(name).enabled(false)).eventSubscription();
                    assertFalse(disabled.enabled());
                    assertEquals(created.subscriptionCreationTime(), disabled.subscriptionCreationTime());
                    assertEquals(List.of(name), disabled.sourceIdsList());
                    assertEquals(List.of("configuration"), disabled.eventCategoriesList());
                    assertEquals("INFO", disabled.severity());
                } finally {
                    redshift.deleteEventSubscription(request -> request.subscriptionName(name));
                }
                RedshiftException missing = assertThrows(RedshiftException.class,
                        () -> redshift.describeEventSubscriptions(request -> request.subscriptionName(name)));
                assertEquals("SubscriptionNotFound", missing.awsErrorDetails().errorCode());
                assertEquals(404, missing.statusCode());
            } finally {
                redshift.deleteClusterParameterGroup(request -> request.parameterGroupName(name));
                sns.deleteTopic(request -> request.topicArn(topic));
            }
        }
    }

    @Test
    void sdkDescribesRealParameterGroupEventsAndCategoryMetadata() {
        String name = name();
        Instant before = Instant.now().minusSeconds(1);
        try (RedshiftClient redshift = redshift(Region.US_EAST_1)) {
            createGroup(redshift, name);
            try {
                redshift.modifyClusterParameterGroup(request -> request.parameterGroupName(name)
                        .parameters(Parameter.builder().parameterName("statement_timeout").parameterValue("5000").build()));
                DescribeEventsResponse response = redshift.describeEvents(request -> request.sourceIdentifier(name)
                        .sourceType(SOURCE_TYPE).startTime(before).endTime(Instant.now().plusSeconds(1)));
                assertTrue(response.events().size() >= 2);
                Event created = response.events().stream().filter(event -> "REDSHIFT-EVENT-1003".equals(event.eventId()))
                        .findFirst().orElseThrow();
                assertEquals(name, created.sourceIdentifier());
                assertEquals(SOURCE_TYPE, created.sourceTypeAsString());
                assertEquals("INFO", created.severity());
                assertEquals(List.of("configuration"), created.eventCategories());
                assertFalse(created.date().isBefore(before));
                assertTrue(created.message().contains(name));
                assertTrue(response.events().stream().anyMatch(event -> "REDSHIFT-EVENT-1005".equals(event.eventId())));

                List<EventCategoriesMap> categories = redshift.describeEventCategories(request -> request
                        .sourceType(SOURCE_TYPE)).eventCategoriesMapList();
                assertEquals(1, categories.size());
                assertEquals(SOURCE_TYPE, categories.getFirst().sourceType());
                assertTrue(categories.getFirst().events().stream().anyMatch(info ->
                        "REDSHIFT-EVENT-1003".equals(info.eventId())
                                && info.eventCategories().equals(List.of("configuration"))
                                && "INFO".equals(info.severity()) && !info.eventDescription().isBlank()));
            } finally {
                redshift.deleteClusterParameterGroup(request -> request.parameterGroupName(name));
            }
            assertTrue(redshift.describeEvents(request -> request.sourceIdentifier(name).sourceType(SOURCE_TYPE))
                    .events().stream().anyMatch(event -> "REDSHIFT-EVENT-1004".equals(event.eventId())));
        }
    }

    @Test
    void sdkSubscriptionDeliversMatchingEventsThroughSnsToSqsAndStopsWhenDisabled() {
        String name = name();
        try (RedshiftClient redshift = redshift(Region.US_EAST_1); SnsClient sns = sns(Region.US_EAST_1);
                SqsClient sqs = sqs()) {
            String topic = sns.createTopic(request -> request.name(name)).topicArn();
            String queue = sqs.createQueue(request -> request.queueName(name)).queueUrl();
            String queueArn = sqs.getQueueAttributes(request -> request.queueUrl(queue)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
            String snsSubscription = sns.subscribe(request -> request.topicArn(topic).protocol("sqs")
                    .endpoint(queueArn).attributes(Map.of("RawMessageDelivery", "true"))).subscriptionArn();
            redshift.createEventSubscription(request -> request.subscriptionName(name).snsTopicArn(topic)
                    .sourceType(SOURCE_TYPE).eventCategories("configuration").severity("INFO"));
            try {
                createGroup(redshift, name);
                try {
                    AtomicReference<Message> received = new AtomicReference<>();
                    await().atMost(Duration.ofSeconds(10)).until(() -> {
                        List<Message> messages = sqs.receiveMessage(request -> request.queueUrl(queue)
                                .maxNumberOfMessages(1).waitTimeSeconds(0)).messages();
                        if (messages.isEmpty()) {
                            return false;
                        }
                        received.set(messages.getFirst());
                        return true;
                    });
                    assertTrue(received.get().body().contains("REDSHIFT-EVENT-1003"));
                    assertTrue(received.get().body().contains(name));
                    sqs.deleteMessage(request -> request.queueUrl(queue).receiptHandle(received.get().receiptHandle()));

                    redshift.modifyEventSubscription(request -> request.subscriptionName(name).enabled(false));
                    redshift.modifyClusterParameterGroup(request -> request.parameterGroupName(name)
                            .parameters(Parameter.builder().parameterName("statement_timeout").parameterValue("5001").build()));
                    assertTrue(sqs.receiveMessage(request -> request.queueUrl(queue).waitTimeSeconds(1)).messages().isEmpty());
                } finally {
                    redshift.deleteClusterParameterGroup(request -> request.parameterGroupName(name));
                }
            } finally {
                redshift.deleteEventSubscription(request -> request.subscriptionName(name));
                sns.unsubscribe(request -> request.subscriptionArn(snsSubscription));
                sns.deleteTopic(request -> request.topicArn(topic));
                sqs.deleteQueue(request -> request.queueUrl(queue));
            }
        }
    }

    @Test
    void sdkReceivesAwsErrorsForInvalidFiltersAndFifoTopics() {
        String name = name();
        try (RedshiftClient redshift = redshift(Region.US_EAST_1); SnsClient sns = sns(Region.US_EAST_1)) {
            String topic = sns.createTopic(request -> request.name(name)).topicArn();
            String fifo = sns.createTopic(request -> request.name(name + ".fifo")
                    .attributes(Map.of("FifoTopic", "true"))).topicArn();
            try {
                RedshiftException unpaired = assertThrows(RedshiftException.class, () -> redshift.describeEvents(request ->
                        request.sourceIdentifier(name)));
                assertEquals("InvalidParameterCombination", unpaired.awsErrorDetails().errorCode());
                RedshiftException severity = assertThrows(RedshiftException.class, () -> redshift.createEventSubscription(
                        request -> request.subscriptionName(name).snsTopicArn(topic).severity("WARN")));
                assertEquals("SubscriptionSeverityNotFound", severity.awsErrorDetails().errorCode());
                RedshiftException fifoError = assertThrows(RedshiftException.class, () -> redshift.createEventSubscription(
                        request -> request.subscriptionName(name).snsTopicArn(fifo)));
                assertEquals("SNSInvalidTopic", fifoError.awsErrorDetails().errorCode());
            } finally {
                sns.deleteTopic(request -> request.topicArn(topic));
                sns.deleteTopic(request -> request.topicArn(fifo));
            }
        }
    }

    @Test
    void subscriptionNamesAndHistoryAreIsolatedByRegion() {
        String name = name();
        try (RedshiftClient east = redshift(Region.US_EAST_1); RedshiftClient west = redshift(Region.US_WEST_2);
                SnsClient eastSns = sns(Region.US_EAST_1); SnsClient westSns = sns(Region.US_WEST_2)) {
            String eastTopic = eastSns.createTopic(request -> request.name(name)).topicArn();
            String westTopic = westSns.createTopic(request -> request.name(name)).topicArn();
            east.createEventSubscription(request -> request.subscriptionName(name).snsTopicArn(eastTopic));
            try {
                west.createEventSubscription(request -> request.subscriptionName(name).snsTopicArn(westTopic));
                try {
                    assertEquals(eastTopic, east.describeEventSubscriptions(request -> request.subscriptionName(name))
                            .eventSubscriptionsList().getFirst().snsTopicArn());
                    assertEquals(westTopic, west.describeEventSubscriptions(request -> request.subscriptionName(name))
                            .eventSubscriptionsList().getFirst().snsTopicArn());
                    createGroup(east, name);
                    try {
                        assertTrue(west.describeEvents(request -> request.sourceIdentifier(name).sourceType(SOURCE_TYPE))
                                .events().isEmpty());
                    } finally {
                        east.deleteClusterParameterGroup(request -> request.parameterGroupName(name));
                    }
                } finally {
                    west.deleteEventSubscription(request -> request.subscriptionName(name));
                }
            } finally {
                east.deleteEventSubscription(request -> request.subscriptionName(name));
                eastSns.deleteTopic(request -> request.topicArn(eastTopic));
                westSns.deleteTopic(request -> request.topicArn(westTopic));
            }
        }
    }

    @Test
    void sdkTagsSubscriptionArnAndSubscriptionDescriptionsReflectChanges() {
        String name = name();
        String arn = "arn:aws:redshift:us-east-1:" + ACCOUNT + ":eventsubscription:" + name;
        Tag original = Tag.builder().key("team").value("analytics").build();
        Tag updated = Tag.builder().key("team").value("platform").build();
        Tag environment = Tag.builder().key("environment").value("test").build();
        try (RedshiftClient redshift = redshift(Region.US_EAST_1); SnsClient sns = sns(Region.US_EAST_1)) {
            String topic = sns.createTopic(request -> request.name(name)).topicArn();
            try {
                redshift.createEventSubscription(request -> request.subscriptionName(name).snsTopicArn(topic).tags(original));
                try {
                    List<TaggedResource> initial = redshift.describeTags(request -> request.resourceName(arn)).taggedResources();
                    assertEquals(1, initial.size());
                    assertEquals(arn, initial.getFirst().resourceName());
                    assertEquals("eventsubscription", initial.getFirst().resourceType());
                    assertEquals(original, initial.getFirst().tag());

                    redshift.createTags(request -> request.resourceName(arn).tags(updated, environment));
                    List<Tag> described = redshift.describeTags(request -> request.resourceName(arn)).taggedResources()
                            .stream().map(TaggedResource::tag).toList();
                    assertEquals(2, described.size());
                    assertTrue(described.containsAll(List.of(updated, environment)));
                    List<Tag> subscriptionTags = redshift.describeEventSubscriptions(request -> request.subscriptionName(name))
                            .eventSubscriptionsList().getFirst().tags();
                    assertEquals(2, subscriptionTags.size());
                    assertTrue(subscriptionTags.containsAll(List.of(updated, environment)));

                    redshift.deleteTags(request -> request.resourceName(arn).tagKeys("team"));
                    assertEquals(List.of(environment), redshift.describeTags(request -> request.resourceName(arn))
                            .taggedResources().stream().map(TaggedResource::tag).toList());
                    assertEquals(List.of(environment), redshift.describeEventSubscriptions(request -> request.subscriptionName(name))
                            .eventSubscriptionsList().getFirst().tags());
                } finally {
                    redshift.deleteEventSubscription(request -> request.subscriptionName(name));
                }
            } finally {
                sns.deleteTopic(request -> request.topicArn(topic));
            }
        }
    }

    @Test
    void subscriptionNamesAndHistoryAreIsolatedByAccount() {
        String name = name();
        String otherAccount = "414141414141";
        try (RedshiftClient owner = redshift(Region.US_EAST_1); RedshiftClient other = redshift(Region.US_EAST_1, otherAccount);
                SnsClient ownerSns = sns(Region.US_EAST_1); SnsClient otherSns = sns(Region.US_EAST_1, otherAccount)) {
            String ownerTopic = ownerSns.createTopic(request -> request.name(name)).topicArn();
            String otherTopic = otherSns.createTopic(request -> request.name(name)).topicArn();
            try {
                owner.createEventSubscription(request -> request.subscriptionName(name).snsTopicArn(ownerTopic));
                try {
                    assertTrue(other.describeEventSubscriptions().eventSubscriptionsList().stream()
                            .noneMatch(subscription -> name.equals(subscription.custSubscriptionId())));
                    other.createEventSubscription(request -> request.subscriptionName(name).snsTopicArn(otherTopic));
                    try {
                        assertEquals(ACCOUNT, owner.describeEventSubscriptions(request -> request.subscriptionName(name))
                                .eventSubscriptionsList().getFirst().customerAwsId());
                        assertEquals(otherAccount, other.describeEventSubscriptions(request -> request.subscriptionName(name))
                                .eventSubscriptionsList().getFirst().customerAwsId());
                        createGroup(owner, name);
                        try {
                            assertEquals(1, owner.describeEvents(request -> request.sourceIdentifier(name).sourceType(SOURCE_TYPE))
                                    .events().size());
                            assertTrue(other.describeEvents(request -> request.sourceIdentifier(name).sourceType(SOURCE_TYPE))
                                    .events().isEmpty());
                            createGroup(other, name);
                            try {
                                assertEquals(1, other.describeEvents(request -> request.sourceIdentifier(name).sourceType(SOURCE_TYPE))
                                        .events().size());
                                assertEquals(1, owner.describeEvents(request -> request.sourceIdentifier(name).sourceType(SOURCE_TYPE))
                                        .events().size());
                            } finally {
                                other.deleteClusterParameterGroup(request -> request.parameterGroupName(name));
                            }
                        } finally {
                            owner.deleteClusterParameterGroup(request -> request.parameterGroupName(name));
                        }
                    } finally {
                        other.deleteEventSubscription(request -> request.subscriptionName(name));
                    }
                } finally {
                    owner.deleteEventSubscription(request -> request.subscriptionName(name));
                }
            } finally {
                ownerSns.deleteTopic(request -> request.topicArn(ownerTopic));
                otherSns.deleteTopic(request -> request.topicArn(otherTopic));
            }
        }
    }

    private RedshiftClient redshift(Region region) {
        return redshift(region, ACCOUNT);
    }

    private RedshiftClient redshift(Region region, String account) {
        return RedshiftClient.builder().endpointOverride(endpoint).region(region)
                .credentialsProvider(credentials(account)).build();
    }

    private SnsClient sns(Region region) {
        return sns(region, ACCOUNT);
    }

    private SnsClient sns(Region region, String account) {
        return SnsClient.builder().endpointOverride(endpoint).region(region)
                .credentialsProvider(credentials(account)).build();
    }

    private SqsClient sqs() {
        return SqsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials()).build();
    }

    private static StaticCredentialsProvider credentials() {
        return credentials(ACCOUNT);
    }

    private static StaticCredentialsProvider credentials(String account) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"));
    }

    private static String name() {
        return "sdk-rsevent-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private static void createGroup(RedshiftClient client, String name) {
        client.createClusterParameterGroup(request -> request.parameterGroupName(name)
                .parameterGroupFamily("redshift-1.0").description("SDK event integration"));
    }
}
