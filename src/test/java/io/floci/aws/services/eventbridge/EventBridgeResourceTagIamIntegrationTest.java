package io.floci.aws.services.eventbridge;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.EventBridgeException;
import software.amazon.awssdk.services.eventbridge.model.Tag;

import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class EventBridgeResourceTagIamIntegrationTest {
    private static final String ACCOUNT = "000000000000";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "cn-north-1", "us-gov-west-1"})
    void describeUsesCurrentStoredTagsInEveryPartition(String regionName) {
        Region region = Region.of(regionName);
        String name = "bus-tags-" + UUID.randomUUID();
        try (EventBridgeClient root = AwsTestClients.create(EventBridgeClient.builder(), ACCOUNT, region)) {
            String arn = root.createEventBus(r -> r.name(name).tags(tag("Owner", "team-a"))).eventBusArn();
            try {
                EnforcementFixtures.UserCredentials credentials = reader(arn);
                try (EventBridgeClient reader = AwsTestClients.create(EventBridgeClient.builder(),
                        credentials.accessKeyId(), credentials.secretAccessKey(), region)) {
                    assertEquals(arn, reader.describeEventBus(r -> r.name(name)).arn());
                    assertEquals(arn, reader.describeEventBus(r -> r.name(arn)).arn());
                    root.tagResource(r -> r.resourceARN(arn).tags(tag("Owner", "team-b")));
                    denied(reader, name);
                    root.tagResource(r -> r.resourceARN(arn).tags(tag("Owner", "team-a")));
                    assertEquals(arn, reader.describeEventBus(r -> r.name(name)).arn());
                    root.untagResource(r -> r.resourceARN(arn).tagKeys("Owner"));
                    denied(reader, name);
                    root.deleteEventBus(r -> r.name(name));
                    denied(reader, name);
                }
            } finally {
                root.deleteEventBus(r -> r.name(name));
            }
        }
    }

    @Test
    void anotherTargetOrForgedRequestTagCannotBorrowTheAllowedBusTags() {
        String name = "bus-boundary-" + UUID.randomUUID();
        String other = name + "-other";
        try (EventBridgeClient root = AwsTestClients.create(EventBridgeClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            String arn = root.createEventBus(r -> r.name(name).tags(tag("Owner", "team-a"))).eventBusArn();
            try {
                root.createEventBus(r -> r.name(other));
                try {
                    EnforcementFixtures.UserCredentials credentials = reader("*");
                    try (EventBridgeClient reader = AwsTestClients.create(EventBridgeClient.builder(),
                            credentials.accessKeyId(), credentials.secretAccessKey(), Region.US_EAST_1)) {
                        assertEquals(arn, reader.describeEventBus(r -> r.name(name)).arn());
                        denied(reader, other);
                        denied(reader, arn.replace(ACCOUNT, "111111111111"));
                        denied(reader, arn.replace("us-east-1", "us-west-2"));
                        denied(reader, arn.replace("arn:aws:", "arn:aws-cn:"));
                    }
                    given().header("Authorization", auth(credentials.accessKeyId(), "events"))
                            .header("X-Amz-Target", "AWSEvents.DescribeEventBus")
                            .contentType("application/x-amz-json-1.1")
                            .body("{\"Name\":\"" + other + "\",\"Tags\":[{\"Key\":\"Owner\",\"Value\":\"team-a\"}]}")
                            .post("/").then().statusCode(400).body(containsString("AccessDenied"));
                } finally {
                    root.deleteEventBus(r -> r.name(other));
                }
            } finally {
                root.deleteEventBus(r -> r.name(name));
            }
        }
    }

    @Test
    void ruleLifecycleUsesRuleTagsSeparatelyFromItsBus() {
        String bus = "rule-tags-" + UUID.randomUUID();
        try (EventBridgeClient root = AwsTestClients.create(EventBridgeClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            String busArn = root.createEventBus(r -> r.name(bus).tags(tag("Owner", "team-a"))).eventBusArn();
            try {
                String ruleArn = root.putRule(r -> r.name("rule").eventBusName(bus)
                        .eventPattern("{\"source\":[\"iam-regression\"]}").tags(tag("Owner", "team-b"))).ruleArn();
                try {
                    String user = "rule-reader-" + UUID.randomUUID();
                    EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                            cleanup, RestAssured.port, auth(ACCOUNT, "iam"), user);
                    EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), user, "scope", """
                            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                             "Action":["events:DescribeRule","events:ListTargetsByRule","events:ListTagsForResource",
                               "events:RemoveTargets","events:DeleteRule","events:DeleteEventBus"],
                             "Resource":["%s","%s"],
                             "Condition":{"StringEquals":{"aws:ResourceTag/Owner":"team-a"}}}]}
                            """.formatted(busArn, ruleArn));
                    try (EventBridgeClient limited = AwsTestClients.create(EventBridgeClient.builder(),
                            credentials.accessKeyId(), credentials.secretAccessKey(), Region.US_EAST_1)) {
                        EventBridgeException denied = assertThrows(EventBridgeException.class,
                                () -> limited.describeRule(r -> r.name("rule").eventBusName(bus)));
                        assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
                        root.tagResource(r -> r.resourceARN(ruleArn).tags(tag("Owner", "team-a")));
                        assertEquals(ruleArn, limited.describeRule(r -> r.name("rule").eventBusName(bus)).arn());
                        assertEquals(1, limited.listTagsForResource(r -> r.resourceARN(ruleArn)).tags().size());
                        root.putTargets(r -> r.rule("rule").eventBusName(bus).targets(target -> target.id("queue")
                                .arn("arn:aws:sqs:us-east-1:000000000000:iam-regression")));
                        assertEquals(1, limited.listTargetsByRule(r -> r.rule("rule").eventBusName(bus)).targets().size());
                        assertEquals(0, limited.removeTargets(r -> r.rule("rule").eventBusName(bus).ids("queue"))
                                .failedEntryCount());
                        limited.deleteRule(r -> r.name("rule").eventBusName(bus));
                        root.tagResource(r -> r.resourceARN(busArn).tags(tag("Owner", "team-b")));
                        EventBridgeException busDenied = assertThrows(EventBridgeException.class,
                                () -> limited.deleteEventBus(r -> r.name(bus)));
                        assertEquals("AccessDeniedException", busDenied.awsErrorDetails().errorCode());
                        root.tagResource(r -> r.resourceARN(busArn).tags(tag("Owner", "team-a")));
                        limited.deleteEventBus(r -> r.name(bus));
                    }
                } finally {
                    try {
                        root.removeTargets(r -> r.rule("rule").eventBusName(bus).ids("queue"));
                        root.deleteRule(r -> r.name("rule").eventBusName(bus));
                    } catch (EventBridgeException expected) {
                        // The authorized lifecycle may already have deleted both the rule and its bus.
                        if (!"ResourceNotFoundException".equals(expected.awsErrorDetails().errorCode())) {
                            throw expected;
                        }
                    }
                }
            } finally {
                root.deleteEventBus(r -> r.name(bus));
            }
        }
    }

    private EnforcementFixtures.UserCredentials reader(String resource) {
        String user = "event-tag-reader-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, RestAssured.port, auth(ACCOUNT, "iam"), user);
        EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), user, "read", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"events:DescribeEventBus",
                 "Resource":"%s","Condition":{"StringEquals":{"aws:ResourceTag/Owner":"team-a"}}}]}
                """.formatted(resource));
        return credentials;
    }

    private static Tag tag(String key, String value) {
        return Tag.builder().key(key).value(value).build();
    }

    private static void denied(EventBridgeClient client, String name) {
        EventBridgeException exception = assertThrows(EventBridgeException.class,
                () -> client.describeEventBus(r -> r.name(name)));
        assertEquals("AccessDeniedException", exception.awsErrorDetails().errorCode());
    }
}
