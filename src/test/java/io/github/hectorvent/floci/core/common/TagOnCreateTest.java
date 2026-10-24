package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** How {@link TagOnCreate} reads the tags a request carries and the condition keys it checks them with. */
class TagOnCreateTest {

    @Test
    void aFormsTagsAreReadInTheOrderTheyAreNumbered() {
        Optional<TagOnCreate.Tagging> tagging = TagOnCreate.of("sns:CreateTopic", form(
                "Name=t&Tags.member.10.Key=c&Tags.member.10.Value=3&Tags.member.2.Key=b&Tags.member.2.Value=2"
                        + "&Tags.member.1.Key=a&Tags.member.1.Value=1"));
        assertEquals("sns:TagResource", tagging.orElseThrow().action());
        assertEquals(List.of("a", "b", "c"), List.copyOf(tagging.get().tags().keySet()));
        assertEquals(List.of("a", "b", "c"), TagOnCreate.conditionContext(tagging.get(), "sns:CreateTopic")
                .get("aws:TagKeys"));
        // A number too long for a long is still ordered by its value.
        assertEquals(List.of("a", "b"), List.copyOf(TagOnCreate.of("sns:CreateTopic", form(
                "Name=t&Tags.member.99999999999999999999.Key=b&Tags.member.1.Key=a")).orElseThrow().tags().keySet()));
    }

    @Test
    void aTagWithNoValueIsReadWithAnEmptyOneAndAKeyGivenTwiceKeepsItsLastValue() {
        assertEquals(Map.of("team", ""), TagOnCreate.of("sns:CreateTopic",
                form("Name=t&Tags.member.1.Key=team")).orElseThrow().tags());
        assertEquals(Map.of("team", "red", "env", ""), TagOnCreate.of("dynamodb:CreateTable", json(
                "{\"TableName\":\"t\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"blue\"},{\"Key\":\"env\"},"
                        + "{\"Key\":\"team\",\"Value\":\"red\"}]}")).orElseThrow().tags());
        // A value with no key tags nothing.
        assertEquals(Optional.empty(), TagOnCreate.of("sns:CreateTopic", form("Name=t&Tags.member.1.Value=v")));
    }

    @Test
    void sqsTagsAreReadAsTheJsonProtocolsMapAndTheQueryProtocolsTagParameters() {
        TagOnCreate.Tagging json = TagOnCreate.of("sqs:CreateQueue", json(
                "{\"QueueName\":\"q\",\"tags\":{\"team\":\"blue\"}}")).orElseThrow();
        assertEquals(new TagOnCreate.Tagging("sqs:TagQueue", Map.of("team", "blue"), false, true), json);
        assertEquals(json, TagOnCreate.of("sqs:CreateQueue",
                form("Action=CreateQueue&QueueName=q&Tag.1.Key=team&Tag.1.Value=blue")).orElseThrow());
    }

    @Test
    void anEc2RequestsTagsAreReadFromEveryTagSpecificationAndCheckedWithItsCreateAction() {
        TagOnCreate.Tagging tagging = TagOnCreate.of("ec2:RunInstances", form("Action=RunInstances&ImageId=ami-1"
                + "&TagSpecification.1.ResourceType=instance&TagSpecification.1.Tag.1.Key=team"
                + "&TagSpecification.1.Tag.1.Value=blue&TagSpecification.2.ResourceType=volume"
                + "&TagSpecification.2.Tag.1.Key=env&TagSpecification.2.Tag.1.Value=dev")).orElseThrow();
        assertEquals(new TagOnCreate.Tagging("ec2:CreateTags", Map.of("team", "blue", "env", "dev"), true, true),
                tagging);
        Map<String, List<String>> context = TagOnCreate.conditionContext(tagging, "ec2:RunInstances");
        assertEquals(List.of("RunInstances"), context.get("ec2:CreateAction"));
        assertEquals(List.of("blue"), context.get("aws:RequestTag/team"));
        assertEquals(List.of("dev"), context.get("aws:RequestTag/env"));
        assertEquals(List.of("team", "env"), context.get("aws:TagKeys"));
    }

    /** The handlers apply these tags too, so they are checked too. */
    @Test
    void tagsUnderASpellingAHandlerAlsoReadsAreRead() {
        assertEquals(Map.of("team", "blue"), TagOnCreate.of("ec2:RunInstances", form("Action=RunInstances"
                + "&TagSpecifications.1.ResourceType=instance&TagSpecifications.1.Tag.1.Key=team"
                + "&TagSpecifications.1.Tag.1.Value=blue")).orElseThrow().tags());
        assertEquals(Map.of("team", "blue"), TagOnCreate.of("cloudwatch:PutMetricAlarm", json(
                "{\"AlarmName\":\"a\",\"tags\":[{\"Key\":\"team\",\"Value\":\"blue\"}]}")).orElseThrow().tags());
    }

    @Test
    void aCborBodysTagsAreRead() throws Exception {
        byte[] body = new ObjectMapper(new CBORFactory()).writeValueAsBytes(Map.of("AlarmName", "a",
                "Tags", List.of(Map.of("Key", "team", "Value", "blue"))));
        ContainerRequestContext request = request("application/cbor", body);
        TagOnCreate.Tagging tagging = TagOnCreate.of("cloudwatch:PutMetricAlarm", request).orElseThrow();
        assertEquals(new TagOnCreate.Tagging("cloudwatch:TagResource", Map.of("team", "blue"), false, true),
                tagging);
        assertFalse(TagOnCreate.conditionContext(tagging, "cloudwatch:PutMetricAlarm").keySet().stream()
                .anyMatch(key -> key.endsWith(":CreateAction")));
    }

    @Test
    void theOperationsOwnActionTakesTheTagsOnlyWhereTheServiceReferenceSaysItDoes() {
        TagOnCreate.Tagging queue = TagOnCreate.of("sqs:CreateQueue", json("{\"QueueName\":\"q\",\"tags\":{\"team\":\"blue\"}}"))
                .orElseThrow();
        assertEquals(Map.of("aws:RequestTag/team", List.of("blue"), "aws:TagKeys", List.of("team")),
                TagOnCreate.requestTagContext(queue));
        // RDS lists aws:RequestTag for neither ModifyDBInstance nor its resources, only for the tagging action.
        assertFalse(TagOnCreate.of("rds:ModifyDBInstance", form("Action=ModifyDBInstance&DBInstanceIdentifier=d"
                + "&TagSpecifications.item.1.ResourceType=auto-backup&TagSpecifications.item.1.Tags.Tag.1.Key=team"
                + "&TagSpecifications.item.1.Tags.Tag.1.Value=blue")).orElseThrow().requestTags());
    }

    @Test
    void anOperationTheTableDoesNotReadOrChecksByHandNeedsNothingHere() {
        assertEquals(Optional.empty(), TagOnCreate.of("sqs:SendMessage", json("{\"tags\":{\"a\":\"b\"}}")));
        assertEquals(Optional.empty(), TagOnCreate.of("logs:CreateLogGroup", json(
                "{\"logGroupName\":\"g\",\"tags\":{\"a\":\"b\"}}")));
        assertEquals(Optional.empty(), TagOnCreate.of("dynamodb:CreateTable", json("{\"Tags\":[]}")));
        // A tags member of the wrong shape carries no tags.
        assertEquals(Optional.empty(), TagOnCreate.of("dynamodb:CreateTable", json("{\"Tags\":{\"a\":\"b\"}}")));
    }

    private static ContainerRequestContext json(String body) {
        return request("application/x-amz-json-1.1", body.getBytes(StandardCharsets.UTF_8));
    }

    private static ContainerRequestContext form(String body) {
        return request("application/x-www-form-urlencoded", body.getBytes(StandardCharsets.UTF_8));
    }

    private static ContainerRequestContext request(String mediaType, byte[] bytes) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getMediaType()).thenReturn(MediaType.valueOf(mediaType));
        when(request.getEntityStream()).thenAnswer(invocation -> new ByteArrayInputStream(bytes));
        return request;
    }
}
