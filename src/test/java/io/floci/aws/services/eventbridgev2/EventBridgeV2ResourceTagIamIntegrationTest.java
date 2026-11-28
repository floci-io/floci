package io.floci.aws.services.eventbridgev2;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridgev2.EventBridgeV2Client;
import software.amazon.awssdk.services.eventbridgev2.model.EventBridgeV2Exception;
import software.amazon.awssdk.services.eventbridgev2.model.EventSourceConfiguration;
import software.amazon.awssdk.services.eventbridgev2.model.WebhookAuthConfiguration;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class EventBridgeV2ResourceTagIamIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/EventBusDelivery";
    private static final Map<String, String> DENY = Map.of("floci", "deny");

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void theTagsOfABusASubscriberAndAnEventSourceDecideTheirOwnRequests() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        try (EventBridgeV2Client root = AwsTestClients.create(EventBridgeV2Client.builder(), ACCOUNT,
                Region.US_EAST_1)) {
            String taggedBus = root.createEventBus(r -> r.name("tagged-" + suffix).tags(DENY)).eventBusArn();
            String plainBus = root.createEventBus(r -> r.name("plain-" + suffix)).eventBusArn();
            String taggedSource = root.createEventSource(r -> r.name("hook-" + suffix).eventBusArn(plainBus)
                    .tags(DENY).configuration(webhook())).eventSourceArn();
            String plainSource = root.createEventSource(r -> r.name("plain-hook-" + suffix).eventBusArn(plainBus)
                    .configuration(webhook())).eventSourceArn();
            String taggedSubscriber = root.createSubscriber(r -> r.name("sub-" + suffix).eventBusArn(plainBus)
                    .tags(DENY).invokeConfiguration(invoke -> invoke.targetArn(
                            "arn:aws:sqs:us-east-1:000000000000:q-" + suffix).roleArn(ROLE))).subscriberArn();

            EnforcementFixtures.UserCredentials credentials = limitedUser();
            try (EventBridgeV2Client limited = AwsTestClients.create(EventBridgeV2Client.builder(),
                    credentials.accessKeyId(), credentials.secretAccessKey(), Region.US_EAST_1)) {
                assertDenied(() -> limited.describeEventBus(r -> r.eventBusArn(taggedBus)));
                assertDenied(() -> limited.describeEventSource(r -> r.eventSourceArn(taggedSource)));
                assertDenied(() -> limited.describeSubscriber(r -> r.subscriberArn(taggedSubscriber)));
                assertDenied(() -> limited.createEventSource(r -> r.name("new-" + suffix).eventBusArn(taggedBus)
                        .configuration(webhook())));

                assertEquals(plainBus, limited.describeEventBus(r -> r.eventBusArn(plainBus)).eventBusArn());
                assertNotNull(limited.describeEventSource(r -> r.eventSourceArn(plainSource)).eventSourceArn());
                assertEquals(taggedSource, limited.revokeResource(r -> r.arn(taggedSource)).arn());
            }
        }
    }

    private EnforcementFixtures.UserCredentials limitedUser() {
        String user = "v2-tag-user-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, RestAssured.port, auth(ACCOUNT, "iam"), user);
        EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), user, "scope", """
                {"Version":"2012-10-17","Statement":[
                 {"Effect":"Allow","Action":["events:*","iam:PassRole"],"Resource":"*"},
                 {"Effect":"Deny","Action":"events:*","Resource":"*",
                  "Condition":{"StringEquals":{"aws:ResourceTag/floci":"deny"}}}]}
                """);
        return credentials;
    }

    private static EventSourceConfiguration webhook() {
        return EventSourceConfiguration.fromWebhookEventsConfiguration(webhook -> webhook.roleArn(ROLE)
                .authConfiguration(WebhookAuthConfiguration.builder().staticTokenAuthConfiguration(
                        token -> token.tokenHttpHeader("X-Token").secretValue("s3cret-token")).build()));
    }

    private static void assertDenied(Supplier<?> call) {
        EventBridgeV2Exception exception = assertThrows(EventBridgeV2Exception.class, call::get);
        assertEquals("AccessDeniedException", exception.awsErrorDetails().errorCode());
    }
}
