package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.DescribeEventsResponse;
import software.amazon.awssdk.services.redshift.model.Event;
import software.amazon.awssdk.services.redshift.model.EventCategoriesMap;
import software.amazon.awssdk.services.redshift.model.EventSubscription;
import software.amazon.awssdk.services.redshift.model.Parameter;
import software.amazon.awssdk.services.redshift.model.RedshiftException;
import software.amazon.awssdk.services.redshift.model.Tag;
import software.amazon.awssdk.services.sns.SnsClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Redshift event history and notification subscriptions")
class RedshiftEventsTest {

    @Test
    @DisplayName("SDK reads parameter-group events and manages a filtered SNS event subscription")
    void parameterGroupEventsAndSubscriptionLifecycle() {
        assumeFalse(TestFixtures.isRealAws(), "Checks immediate local event recording and subscription updates");
        String name = "compat-rsevent-" + UUID.randomUUID().toString().substring(0, 12);
        String sourceType = "cluster-parameter-group";
        Instant start = Instant.now().minusSeconds(1);
        try (RedshiftClient redshift = TestFixtures.redshiftClient(); SnsClient sns = TestFixtures.snsClient()) {
            String topic = sns.createTopic(request -> request.name(name)).topicArn();
            try {
                redshift.createClusterParameterGroup(request -> request.parameterGroupName(name)
                        .parameterGroupFamily("redshift-1.0").description("SDK event compatibility"));
                try {
                    EventSubscription created = redshift.createEventSubscription(request -> request
                            .subscriptionName(name).snsTopicArn(topic).sourceType(sourceType).sourceIds(name)
                            .eventCategories("configuration").severity("INFO").enabled(true)
                            .tags(Tag.builder().key("suite").value("sdk-java").build())).eventSubscription();
                    try {
                        assertThat(created.custSubscriptionId()).isEqualTo(name);
                        assertThat(created.snsTopicArn()).isEqualTo(topic);
                        assertThat(created.sourceType()).isEqualTo(sourceType);
                        assertThat(created.sourceIdsList()).containsExactly(name);
                        assertThat(created.eventCategoriesList()).containsExactly("configuration");
                        assertThat(created.subscriptionCreationTime()).isNotNull();
                        assertThat(created.enabled()).isTrue();
                        assertThat(created.status()).isEqualTo("active");
                        assertThat(created.tags()).extracting(Tag::key).containsExactly("suite");

                        EventSubscription described = redshift.describeEventSubscriptions(request -> request
                                .subscriptionName(name)).eventSubscriptionsList().get(0);
                        assertThat(described.sourceIdsList()).containsExactly(name);
                        assertThat(described.subscriptionCreationTime()).isEqualTo(created.subscriptionCreationTime());

                        redshift.modifyClusterParameterGroup(request -> request.parameterGroupName(name)
                                .parameters(Parameter.builder().parameterName("statement_timeout")
                                        .parameterValue("5000").build()));
                        DescribeEventsResponse history = redshift.describeEvents(request -> request
                                .sourceIdentifier(name).sourceType(sourceType).startTime(start));
                        assertThat(history.events()).extracting(Event::eventId)
                                .contains("REDSHIFT-EVENT-1003", "REDSHIFT-EVENT-1005");
                        assertThat(history.events()).allSatisfy(event -> {
                            assertThat(event.sourceIdentifier()).isEqualTo(name);
                            assertThat(event.sourceTypeAsString()).isEqualTo(sourceType);
                            assertThat(event.date()).isAfterOrEqualTo(start);
                            assertThat(event.message()).isNotBlank();
                        });

                        List<EventCategoriesMap> categories = redshift.describeEventCategories(request -> request
                                .sourceType(sourceType)).eventCategoriesMapList();
                        assertThat(categories).hasSize(1);
                        assertThat(categories.get(0).sourceType()).isEqualTo(sourceType);
                        assertThat(categories.get(0).events()).anySatisfy(info -> {
                            assertThat(info.eventId()).isEqualTo("REDSHIFT-EVENT-1003");
                            assertThat(info.eventCategories()).containsExactly("configuration");
                            assertThat(info.severity()).isEqualTo("INFO");
                        });

                        EventSubscription disabled = redshift.modifyEventSubscription(request -> request
                                .subscriptionName(name).enabled(false)).eventSubscription();
                        assertThat(disabled.enabled()).isFalse();
                        assertThat(disabled.sourceIdsList()).containsExactly(name);
                        assertThat(disabled.eventCategoriesList()).containsExactly("configuration");
                        assertThat(disabled.subscriptionCreationTime()).isEqualTo(created.subscriptionCreationTime());
                    } finally {
                        redshift.deleteEventSubscription(request -> request.subscriptionName(name));
                    }
                    assertThatThrownBy(() -> redshift.describeEventSubscriptions(request -> request.subscriptionName(name)))
                            .isInstanceOfSatisfying(RedshiftException.class, error -> {
                                assertThat(error.awsErrorDetails().errorCode()).isEqualTo("SubscriptionNotFound");
                                assertThat(error.statusCode()).isEqualTo(404);
                            });
                } finally {
                    redshift.deleteClusterParameterGroup(request -> request.parameterGroupName(name));
                }
            } finally {
                sns.deleteTopic(request -> request.topicArn(topic));
            }
        }
    }
}
