package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two structural rules the estate-wide tagging scan rests on: which field is the resource's
 * own ARN, and which shapes count as tags.
 */
class TaggedResourceScannerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ─── resolveArn ────────────────────────────────────────────────────────────

    @Test
    void takesAFieldNamedArn() {
        JsonNode node = json("""
                {"arn": "arn:aws:iam::000000000000:role/app", "roleName": "app"}
                """);
        assertEquals("arn:aws:iam::000000000000:role/app",
                TaggedResourceScanner.resolveArn(node, "IamRole"));
    }

    @Test
    void takesAFieldNamedResourceArn() {
        JsonNode node = json("""
                {"resourceArn": "arn:aws:acm:us-east-1:000000000000:certificate/abc"}
                """);
        assertEquals("arn:aws:acm:us-east-1:000000000000:certificate/abc",
                TaggedResourceScanner.resolveArn(node, "Certificate"));
    }

    @Test
    void takesAPrefixedArnFieldWhenTheTypeNameMatches() {
        JsonNode node = json("""
                {"functionArn": "arn:aws:lambda:us-east-1:000000000000:function:app",
                 "kmsKeyArn": "arn:aws:kms:us-east-1:000000000000:key/k1"}
                """);
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:app",
                TaggedResourceScanner.resolveArn(node, "LambdaFunction"));
    }

    @Test
    void matchesAnArnFieldNamedForAMulti_wordTypeSuffix() {
        JsonNode node = json("""
                {"deliveryStreamARN": "arn:aws:firehose:us-east-1:000000000000:deliverystream/s"}
                """);
        assertEquals("arn:aws:firehose:us-east-1:000000000000:deliverystream/s",
                TaggedResourceScanner.resolveArn(node, "DeliveryStreamDescription"));
    }

    /**
     * The case that makes the rule worth having. An EKS create request carries exactly one
     * ARN-valued field and it belongs to a different resource; attributing the cluster's tags to
     * the IAM role would put a wrong ARN in front of every caller that joins on it.
     */
    @Test
    void refusesAReferenceToAnotherResource() {
        JsonNode node = json("""
                {"name": "app", "roleArn": "arn:aws:iam::000000000000:role/eks"}
                """);
        assertNull(TaggedResourceScanner.resolveArn(node, "CreateClusterRequest"));
    }

    @Test
    void refusesAnInstanceProfileReferenceOnAnEc2Model() {
        JsonNode node = json("""
                {"instanceId": "i-1",
                 "iamInstanceProfileArn": "arn:aws:iam::000000000000:instance-profile/p"}
                """);
        assertNull(TaggedResourceScanner.resolveArn(node, "Instance"));
    }

    @Test
    void refusesWhenTwoArnFieldsBothMatchTheTypeName() {
        JsonNode node = json("""
                {"streamArn": "arn:aws:kinesis:us-east-1:000000000000:stream/a",
                 "streamConsumerArn": "arn:aws:kinesis:us-east-1:000000000000:stream/a/consumer/c"}
                """);
        assertNull(TaggedResourceScanner.resolveArn(node, "StreamConsumerStream"));
    }

    @Test
    void ignoresAStringThatIsNotAnArn() {
        JsonNode node = json("""
                {"arn": "not-an-arn", "queueUrl": "http://localhost:4566/000000000000/q"}
                """);
        assertNull(TaggedResourceScanner.resolveArn(node, "Queue"));
    }

    // ─── extractTags ───────────────────────────────────────────────────────────

    @Test
    void readsAMapShapedTagField() {
        Map<String, String> tags = TaggedResourceScanner.extractTags(
                json("""
                        {"tags": {"tofu-estate": "demo", "Name": "app"}}
                        """));
        assertEquals(Map.of("tofu-estate", "demo", "Name", "app"), tags);
    }

    @Test
    void readsAListOfKeyValueObjects() {
        Map<String, String> tags = TaggedResourceScanner.extractTags(
                json("""
                        {"tags": [{"key": "tofu-estate", "value": "demo"},
                                  {"Key": "Name", "Value": "app"}]}
                        """));
        assertEquals(Map.of("tofu-estate", "demo", "Name", "app"), tags);
    }

    @Test
    void acceptsACapitalisedTagsField() {
        Map<String, String> tags = TaggedResourceScanner.extractTags(
                json("""
                        {"Tags": {"tofu-estate": "demo"}}
                        """));
        assertEquals(Map.of("tofu-estate", "demo"), tags);
    }

    @Test
    void yieldsNothingForAModelWithNoTags() {
        assertTrue(TaggedResourceScanner.extractTags(json("{\"name\": \"app\"}")).isEmpty());
        assertTrue(TaggedResourceScanner.extractTags(json("{\"tags\": {}}")).isEmpty());
        assertTrue(TaggedResourceScanner.extractTags(json("{\"tags\": []}")).isEmpty());
    }
}
