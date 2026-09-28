package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sns.model.Topic;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The {@code MaximumMessageSize} topic attribute, which AWS added in September 2026 to raise the
 * SNS payload ceiling from 256 KiB to 1 MiB. Every expectation here -- error codes, error wording,
 * boundary values and the subscription constraints -- was taken from the live API.
 */
class SnsMaximumMessageSizeTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final int DEFAULT = 262_144;
    private static final int ONE_MIB = 1_048_576;

    private SnsService snsService;

    @BeforeEach
    void setUp() {
        snsService = new SnsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver(REGION, ACCOUNT),
                null,
                null);
    }

    private String topic(String name) {
        return snsService.createTopic(name, null, null, REGION).getTopicArn();
    }

    private String largeTopic(String name) {
        String arn = topic(name);
        snsService.setTopicAttributes(arn, "MaximumMessageSize", String.valueOf(ONE_MIB), REGION);
        return arn;
    }

    /**
     * Write a value straight onto the stored topic, the way one persisted before this attribute
     * was validated carries it: the old generic {@code SetTopicAttributes} wrote anything
     * through. {@code createTopic} returns the stored instance for a name that already exists,
     * so this is the same topic the service reads back.
     */
    private void persistUnvalidated(String name, String value) {
        snsService.createTopic(name, null, null, REGION)
                .getAttributes().put("MaximumMessageSize", value);
    }

    // --- The attribute itself ---

    /** AWS omits the attribute entirely until it is set; it does not report the default. */
    @Test
    void getTopicAttributes_omitsMaximumMessageSizeUntilSet() {
        String arn = topic("unset");
        assertFalse(snsService.getTopicAttributes(arn, REGION).containsKey("MaximumMessageSize"));

        snsService.setTopicAttributes(arn, "MaximumMessageSize", "1048576", REGION);
        assertEquals("1048576",
                snsService.getTopicAttributes(arn, REGION).get("MaximumMessageSize"));
    }

    @Test
    void setTopicAttributes_acceptsRangeBoundaries() {
        String arn = topic("bounds");
        for (String value : List.of("1024", "262144", "1048576")) {
            snsService.setTopicAttributes(arn, "MaximumMessageSize", value, REGION);
            assertEquals(value,
                    snsService.getTopicAttributes(arn, REGION).get("MaximumMessageSize"));
        }
    }

    /**
     * AWS parses the value strictly -- no trimming, no decimals -- and rejects an empty value
     * outright rather than treating it as a reset, which is how the SQS attribute of the same
     * name behaves.
     */
    @Test
    void setTopicAttributes_rejectsValuesOutsideRange() {
        String arn = topic("invalid");
        for (String value : List.of("1023", "1048577", "0", "-1", "abc", "1024.5", "", " 262144 ")) {
            AwsException ex = assertThrows(AwsException.class, () ->
                    snsService.setTopicAttributes(arn, "MaximumMessageSize", value, REGION));
            assertEquals("InvalidParameter", ex.getErrorCode());
            assertEquals("Invalid parameter: MaximumMessageSize: " + value
                    + " is not an integer between 1024 and 1048576 bytes", ex.getMessage());
        }
    }

    /** CreateTopic reports the same reason behind its own {@code Attributes Reason:} prefix. */
    @Test
    void createTopic_rejectsValueOutsideRange() {
        AwsException ex = assertThrows(AwsException.class, () -> snsService.createTopic(
                "bad", Map.of("MaximumMessageSize", "999"), null, REGION));
        assertEquals("InvalidParameter", ex.getErrorCode());
        assertEquals("Invalid parameter: Attributes Reason: MaximumMessageSize: 999 "
                + "is not an integer between 1024 and 1048576 bytes", ex.getMessage());
    }

    @Test
    void createTopic_acceptsMaximumMessageSize() {
        Topic created = snsService.createTopic(
                "big", Map.of("MaximumMessageSize", "1048576"), null, REGION);
        assertEquals("1048576", created.getAttributes().get("MaximumMessageSize"));
        assertNotNull(snsService.publish(created.getTopicArn(), null, "x".repeat(300_000), null, REGION));
    }

    /** FIFO topics take the attribute too. */
    @Test
    void fifoTopic_supportsMaximumMessageSize() {
        Topic fifo = snsService.createTopic("orders.fifo",
                Map.of("MaximumMessageSize", "1048576"), null, REGION);
        assertEquals("true", fifo.getAttributes().get("FifoTopic"));
        assertNotNull(snsService.publish(fifo.getTopicArn(), null, null, "x".repeat(300_000),
                null, null, "group-1", "dedup-1", REGION));
    }

    // --- Publish ---

    @Test
    void publish_defaultTopic_acceptsExactlyTheDefaultLimit() {
        String arn = topic("default-limit");
        assertNotNull(snsService.publish(arn, null, "x".repeat(DEFAULT), null, REGION));

        AwsException ex = assertThrows(AwsException.class, () ->
                snsService.publish(arn, null, "x".repeat(DEFAULT + 1), null, REGION));
        assertEquals("InvalidParameter", ex.getErrorCode());
        assertEquals("Invalid parameter: Message too long", ex.getMessage());
    }

    @Test
    void publish_raisedTopic_acceptsExactlyOneMebibyte() {
        String arn = largeTopic("raised-limit");
        assertNotNull(snsService.publish(arn, null, "x".repeat(ONE_MIB), null, REGION));

        AwsException ex = assertThrows(AwsException.class, () ->
                snsService.publish(arn, null, "x".repeat(ONE_MIB + 1), null, REGION));
        assertEquals("InvalidParameter", ex.getErrorCode());
    }

    /** Lowering the attribute takes effect immediately. */
    @Test
    void publish_loweredTopic_rejectsBelowTheDefault() {
        String arn = topic("lowered");
        snsService.setTopicAttributes(arn, "MaximumMessageSize", "1024", REGION);
        assertNotNull(snsService.publish(arn, null, "x".repeat(1024), null, REGION));
        assertThrows(AwsException.class, () ->
                snsService.publish(arn, null, "x".repeat(1025), null, REGION));
    }

    /** Subject is not counted: a message at exactly the limit publishes whatever its subject. */
    @Test
    void publish_subjectDoesNotCountTowardsTheLimit() {
        String arn = topic("subject");
        assertNotNull(snsService.publish(arn, null, "x".repeat(DEFAULT), "s".repeat(100), REGION));
    }

    /** Attributes are counted as name + data type + value, exactly. */
    @Test
    void publish_attributesCountTowardsTheLimit() {
        String arn = topic("attrs");
        // "attr1" (5) + "String" (6) + a 44-byte value = 55 bytes of attribute.
        Map<String, MessageAttributeValue> attrs = Map.of(
                "attr1", new MessageAttributeValue("v".repeat(44), "String"));

        assertNotNull(snsService.publish(arn, null, null, "x".repeat(DEFAULT - 55),
                null, attrs, REGION));
        assertThrows(AwsException.class, () -> snsService.publish(arn, null, null,
                "x".repeat(DEFAULT - 54), null, attrs, REGION));
    }

    /** An SMS publish never reaches a topic, so it keeps the default limit. */
    @Test
    void publish_toPhoneNumber_keepsTheDefaultLimit() {
        assertNotNull(snsService.publish(null, null, "+819012345678",
                "x".repeat(DEFAULT), null, null, REGION));
        assertThrows(AwsException.class, () -> snsService.publish(null, null, "+819012345678",
                "x".repeat(DEFAULT + 1), null, null, REGION));
    }

    /**
     * A persisted value outside the AWS range is not honoured on the way back out. Validation
     * only guards new writes, so a topic stored before the attribute existed could otherwise
     * lift a publish past the 1 MiB ceiling, or -- at zero or below -- reject every publish.
     */
    @Test
    void publish_persistedValueOutsideRange_fallsBackToTheDefault() {
        String arn = topic("legacy");
        for (String value : List.of("5242880", "0", "-1", "abc")) {
            persistUnvalidated("legacy", value);
            assertNotNull(snsService.publish(arn, null, "x".repeat(DEFAULT), null, REGION));
            AwsException ex = assertThrows(AwsException.class, () ->
                    snsService.publish(arn, null, "x".repeat(DEFAULT + 1), null, REGION));
            assertEquals("Invalid parameter: Message too long", ex.getMessage());
        }
    }

    /** It does not gate subscriptions either: the topic counts as sitting at the default. */
    @Test
    void subscribe_persistedValueOutsideRange_appliesNoProtocolConstraint() {
        String arn = topic("legacy-gate");
        persistUnvalidated("legacy-gate", "5242880");
        assertNotNull(snsService.subscribe(arn, "email", "nobody@example.com", REGION, Map.of()));
    }

    // --- PublishBatch ---

    @Test
    void publishBatch_sumsEntriesAgainstTheTopicLimit() {
        String arn = topic("batch");
        List<Map<String, Object>> entries = List.of(
                Map.of("Id", "1", "Message", "x".repeat(131_072)),
                Map.of("Id", "2", "Message", "x".repeat(131_073)));

        AwsException ex = assertThrows(AwsException.class, () ->
                snsService.publishBatch(arn, entries, REGION));
        assertEquals("BatchRequestTooLong", ex.getErrorCode());
        assertEquals("The length of all the messages put together is more than the limit.",
                ex.getMessage());

        snsService.setTopicAttributes(arn, "MaximumMessageSize", String.valueOf(ONE_MIB), REGION);
        assertEquals(2, snsService.publishBatch(arn, entries, REGION).successful().size());
    }

    // --- Subscription constraints above 256 KiB ---

    @Test
    void subscribe_aboveDefault_rejectsUnsupportedProtocols() {
        String arn = largeTopic("protocols");
        for (String protocol : List.of("email", "email-json", "sms", "http", "https", "application")) {
            AwsException ex = assertThrows(AwsException.class, () -> snsService.subscribe(
                    arn, protocol, protocol.startsWith("http") ? protocol + "://example.com/hook"
                            : "endpoint-" + protocol, REGION, Map.of()));
            assertEquals("InvalidParameter", ex.getErrorCode());
            assertEquals("Invalid parameter: MaximumMessageSize greater than 262144 bytes "
                    + "is not supported for the following protocol: [" + protocol + "]",
                    ex.getMessage());
        }
    }

    @Test
    void subscribe_aboveDefault_allowsSqsFirehoseAndLambda() {
        String arn = largeTopic("allowed-protocols");
        for (String protocol : List.of("sqs", "firehose", "lambda")) {
            assertNotNull(snsService.subscribe(arn, protocol, "arn:aws:" + protocol
                    + ":us-east-1:000000000000:target", REGION,
                    "firehose".equals(protocol)
                            ? Map.of("SubscriptionRoleArn", "arn:aws:iam::000000000000:role/firehose-role")
                            : Map.of()));
        }
    }

    /** Pending confirmations count: an unconfirmed email subscription still blocks the raise. */
    @Test
    void setTopicAttributes_rejectsRaiseWithUnsupportedSubscription() {
        String arn = topic("has-email");
        snsService.subscribe(arn, "email", "nobody@example.com", REGION, Map.of());

        AwsException ex = assertThrows(AwsException.class, () -> snsService.setTopicAttributes(
                arn, "MaximumMessageSize", "1048576", REGION));
        assertEquals("InvalidParameter", ex.getErrorCode());
        assertEquals("Invalid parameter: MaximumMessageSize greater than 262144 bytes "
                + "is not supported for the following protocol: [email]", ex.getMessage());
        assertFalse(snsService.getTopicAttributes(arn, REGION).containsKey("MaximumMessageSize"));
    }

    @Test
    void setTopicAttributes_rejectsRaiseAboveOneHundredSubscriptions() {
        String arn = topic("many-subs");
        for (int i = 0; i < 100; i++) {
            snsService.subscribe(arn, "sqs", "arn:aws:sqs:us-east-1:000000000000:q" + i,
                    REGION, Map.of());
        }
        snsService.setTopicAttributes(arn, "MaximumMessageSize", "1048576", REGION);

        snsService.subscribe(arn, "sqs", "arn:aws:sqs:us-east-1:000000000000:q100", REGION, Map.of());
        AwsException ex = assertThrows(AwsException.class, () -> snsService.setTopicAttributes(
                arn, "MaximumMessageSize", "1048575", REGION));
        assertEquals("InvalidParameter", ex.getErrorCode());
        assertEquals("Invalid parameter: A topic with MaximumMessageSize greater than 262144 "
                + "bytes supports a maximum of 100 subscriptions", ex.getMessage());
    }

    /**
     * AWS only enforces the subscription count when the attribute is set, so a raised topic can
     * drift past 100 subscriptions and only fail the next time it is set.
     */
    @Test
    void subscribe_aboveDefault_doesNotEnforceTheSubscriptionCount() {
        String arn = largeTopic("drift");
        for (int i = 0; i <= 100; i++) {
            assertNotNull(snsService.subscribe(arn, "sqs",
                    "arn:aws:sqs:us-east-1:000000000000:q" + i, REGION, Map.of()));
        }
        assertEquals(101, snsService.listSubscriptionsByTopic(arn, REGION).size());
    }

    /** Neither constraint applies at or below the default, however many subscriptions there are. */
    @Test
    void setTopicAttributes_atOrBelowDefault_ignoresSubscriptionConstraints() {
        String arn = topic("lower-freely");
        snsService.subscribe(arn, "email", "nobody@example.com", REGION, Map.of());
        for (int i = 0; i < 120; i++) {
            snsService.subscribe(arn, "sqs", "arn:aws:sqs:us-east-1:000000000000:q" + i,
                    REGION, Map.of());
        }
        snsService.setTopicAttributes(arn, "MaximumMessageSize", "262144", REGION);
        assertEquals("262144",
                snsService.getTopicAttributes(arn, REGION).get("MaximumMessageSize"));
    }
}
