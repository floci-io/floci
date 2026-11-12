package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.floci.aws.core.common.TagOnCreate.TagSet;
import io.floci.aws.core.common.TagOnCreate.Tagging;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** How {@link TagOnCreate} reads the tags a request carries and the condition keys it checks them with. */
class TagOnCreateTest {

    @Test
    void controlTowerUsesModeledTagMapsAndChecksTheCreateActionWithTheirTags() {
        for (String operation : List.of("CreateLandingZone", "EnableControl", "EnableBaseline")) {
            Tagging tagging = TagOnCreate.of("controltower:" + operation, json(
                    "{\"tags\":{\"team\":\"blue\"}}")).orElseThrow();
            assertEquals("controltower:TagResource", tagging.action());
            assertEquals(List.of(set(Map.of("team", "blue"))), tagging.sets());
            assertTrue(tagging.requestTags());
            assertFalse(tagging.createAction());
            assertEquals(List.of(Map.of("aws:RequestTag/team", List.of("blue"), "aws:TagKeys", List.of("team"))),
                    TagOnCreate.requestTagContexts(tagging));
            assertFalse(TagOnCreate.of("controltower:" + operation, json("{\"tags\":{}}")).isPresent());
        }
    }

    @Test
    void aFormsTagsAreReadFromOneToTheFirstNumberWithNoKey() {
        assertEquals(List.of(set(Map.of("a", "1", "b", "2"))), sets("sns:CreateTopic", form("Name=t"
                + "&Tags.member.2.Key=b&Tags.member.2.Value=2&Tags.member.1.Key=a&Tags.member.1.Value=1"
                + "&Tags.member.4.Key=d&Tags.member.4.Value=4")));
        assertEquals(List.of("a", "b"), sets("sns:CreateTopic", form("Name=t&Tags.member.1.Key=a&Tags.member.2.Key=b"))
                .getFirst().tags().keySet().stream().toList());
        // SNS's handler and SQS's stop where the number is missing, so a tag past the gap is not one the queue
        // gets, though the tagging action is still checked for it.
        assertEquals(List.of(), sets("sqs:CreateQueue", form("Action=CreateQueue&QueueName=q&Tag.2.Key=team&Tag.2.Value=blue")));
        assertEquals(List.of(set(Map.of("team", "blue"))), TagOnCreate.of("sqs:CreateQueue",
                form("Action=CreateQueue&QueueName=q&Tag.2.Key=team&Tag.2.Value=blue")).orElseThrow().otherSpellings());
        assertEquals(List.of(set(Map.of("team", ""))), TagOnCreate.of("sns:CreateTopic",
                form("Name=t&Tags.member.99999999999999999999.Key=team")).orElseThrow().checked());
    }

    /**
     * A handler reads the SDK's own spelling, so a tag under another is not one the resource gets; the tagging
     * action is still checked for it, as another handler might apply it.
     */
    @Test
    void aTagUnderASpellingTheHandlerDoesNotReadIsCheckedOnlyOnTheTaggingAction() {
        Tagging bare = TagOnCreate.of("sns:CreateTopic", form("Name=t&Tags.1.Key=team&Tags.1.Value=b")).orElseThrow();
        assertEquals(List.of(), bare.sets());
        assertEquals(List.of(set(Map.of("team", "b"))), bare.otherSpellings());
        // The own action is checked with no tags, as the handler applies none, and with the other spelling's.
        assertEquals(List.of(Map.of(), Map.of("aws:RequestTag/team", List.of("b"), "aws:TagKeys", List.of("team"))),
                TagOnCreate.requestTagContexts(bare));
        assertEquals(List.of(set(Map.of("team", "b"))), TagOnCreate.of("sns:CreateTopic",
                form("Name=t&TAGS.Tag.1.key=team&Tags.Tag.1.VALUE=b")).orElseThrow().otherSpellings());
        assertEquals(List.of(new TagSet("instance", Map.of("team", ""))), TagOnCreate.of("ec2:RunInstances",
                form("Action=RunInstances&TagSpecification.item.1.ResourceType=instance"
                        + "&TagSpecification.item.1.Tag.1.Key=team")).orElseThrow().otherSpellings());
    }

    /** CodeDeploy reads its modeled {@code Key}/{@code Value} tags; other spellings stay on the tagging check. */
    @Test
    void aBodysTagsAreReadInTheModeledCaseAndCheckedOnTheTaggingActionInAnyOther() {
        for (String operation : List.of("CreateApplication", "CreateDeploymentGroup")) {
            Tagging tagging = TagOnCreate.of("codedeploy:" + operation, json(
                    "{\"applicationName\":\"demo\",\"tags\":[{\"Key\":\"team\",\"Value\":\"red\"}]}")).orElseThrow();
            assertEquals(List.of(set(Map.of("team", "red"))), tagging.sets(), operation);
            assertEquals(List.of(), tagging.otherSpellings(), operation);
        }
        Tagging lower = TagOnCreate.of("codedeploy:CreateApplication", json(
                "{\"applicationName\":\"demo\",\"tags\":[{\"key\":\"team\",\"value\":\"red\"}]}")).orElseThrow();
        assertEquals(List.of(), lower.sets());
        assertEquals(List.of(set(Map.of("team", "red"))), lower.otherSpellings());
        Tagging upper = TagOnCreate.of("codedeploy:CreateApplication", json(
                "{\"applicationName\":\"demo\",\"tags\":[{\"KEY\":\"team\",\"VALUE\":\"red\"}]}")).orElseThrow();
        assertEquals(List.of(), upper.sets());
        assertEquals(List.of(set(Map.of("team", "red"))), upper.otherSpellings());
        assertEquals(List.of(), sets("dynamodb:CreateTable", json(
                "{\"TableName\":\"t\",\"Tags\":[{\"key\":\"team\",\"value\":\"red\"}]}")));
    }

    /** A non-list CodeDeploy tags member is not applied, though its contents remain visible to the safety check. */
    @Test
    void anObjectsValuesAreOnlyOtherSpellingsForCodeDeploy() {
        Tagging object = TagOnCreate.of("codedeploy:CreateApplication", json("{\"applicationName\":\"demo\","
                + "\"tags\":{\"0\":{\"key\":\"team\",\"value\":\"red\"}}}")).orElseThrow();
        assertEquals(List.of(), object.sets());
        assertTrue(object.otherSpellings().contains(set(Map.of("team", "red"))));
        // Elsewhere it is checked on the tagging action and with the own action, beside no tags.
        Tagging elsewhere = TagOnCreate.of("dynamodb:CreateTable", json("{\"TableName\":\"t\","
                + "\"Tags\":{\"0\":{\"Key\":\"team\",\"Value\":\"red\"}}}")).orElseThrow();
        assertEquals(List.of(), elsewhere.sets());
        assertEquals("red", elsewhere.otherSpellings().getFirst().tags().get("team"));
        assertEquals(List.of(), TagOnCreate.requestTagContexts(elsewhere).getFirst().keySet().stream().toList());
        // A map's string values are its own, not elements.
        assertEquals(List.of(), TagOnCreate.of("sqs:CreateQueue", json("{\"QueueName\":\"q\",\"tags\":{\"team\":\"blue\"}}"))
                .orElseThrow().otherSpellings());
    }

    /**
     * An object read as a list of tags and as a map gives two sets, so a member named as its structure's key does not
     * replace the tag a handler iterating the object applies.
     */
    @Test
    void anObjectReadAsTagsAndAsAMapGivesASetForEach() {
        Tagging collision = TagOnCreate.of("kms:CreateKey", json(
                "{\"Tags\":{\"team\":{\"TagKey\":\"team\",\"TagValue\":\"red\"}}}")).orElseThrow();
        assertEquals(List.of(), collision.sets());
        assertEquals(List.of(set(Map.of("team", "red")), set(Map.of("team", ""))), collision.otherSpellings());
        assertEquals(List.of(List.of(), List.of("red"), List.of("")), TagOnCreate.requestTagContexts(collision).stream()
                .map(context -> context.getOrDefault("aws:RequestTag/team", List.of())).toList());
        // A map holding only strings is read as a map alone.
        assertEquals(List.of(set(Map.of("team", "red"))), TagOnCreate.of("kms:CreateKey", json(
                "{\"Tags\":{\"team\":\"red\"}}")).orElseThrow().otherSpellings());
    }

    /** Some handlers read non-string tag fields as text, and the check follows their current behavior. */
    @Test
    void aTagKeyThatIsNoStringIsReadAsText() {
        assertEquals(List.of(set(Map.of("true", "1"))), sets("dynamodb:CreateTable", json(
                "{\"TableName\":\"t\",\"Tags\":[{\"Key\":true,\"Value\":1}]}")));
        // Under any other spelling, every element of a tag list is checked on the tagging action.
        assertEquals(List.of(set(Map.of("", ""))), TagOnCreate.of("dynamodb:CreateTable", json(
                "{\"TableName\":\"t\",\"Tags\":[{\"Key\":{}}]}")).orElseThrow().checked());
    }

    @Test
    void aTagWithNoValueIsReadWithAnEmptyOneAndAKeyGivenTwiceKeepsItsLastValue() {
        assertEquals(List.of(set(Map.of("team", ""))), sets("sns:CreateTopic", form("Name=t&Tags.member.1.Key=team")));
        assertEquals(List.of(set(Map.of("team", "red", "env", ""))), sets("dynamodb:CreateTable", json(
                "{\"TableName\":\"t\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"blue\"},{\"Key\":\"env\"},"
                        + "{\"Key\":\"team\",\"Value\":\"red\"}]}")));
        // A value with no key tags nothing.
        assertEquals(Optional.empty(), TagOnCreate.of("sns:CreateTopic", form("Name=t&Tags.member.1.Value=v")));
    }

    @Test
    void sqsTagsAreReadAsTheJsonProtocolsMapAndTheQueryProtocolsTagParameters() {
        Tagging json = TagOnCreate.of("sqs:CreateQueue", json("{\"QueueName\":\"q\",\"tags\":{\"team\":\"blue\"}}"))
                .orElseThrow();
        assertEquals(new Tagging("sqs:TagQueue", List.of(set(Map.of("team", "blue"))), List.of(), false, true), json);
        assertEquals(json, TagOnCreate.of("sqs:CreateQueue",
                form("Action=CreateQueue&QueueName=q&Tag.1.Key=team&Tag.1.Value=blue")).orElseThrow());
    }

    /**
     * Each resource type an EC2 request names gets its own specifications' tags, as {@code Ec2QueryHandler}
     * applies them, so one type's tags cannot stand in for another's.
     */
    @Test
    void anEc2RequestsTagsAreReadByTheResourceTypeEachSpecificationNames() {
        Tagging tagging = TagOnCreate.of("ec2:RunInstances", form("Action=RunInstances&ImageId=ami-1"
                + "&TagSpecification.1.ResourceType=instance&TagSpecification.1.Tag.1.Key=team"
                + "&TagSpecification.1.Tag.1.Value=red&TagSpecification.2.ResourceType=volume"
                + "&TagSpecification.2.Tag.1.Key=team&TagSpecification.2.Tag.1.Value=blue"
                + "&TagSpecifications.1.ResourceType=instance&TagSpecifications.1.Tag.1.Key=env"
                + "&TagSpecifications.1.Tag.1.Value=dev")).orElseThrow();
        assertEquals(new Tagging("ec2:CreateTags", List.of(new TagSet("instance", Map.of("team", "red", "env", "dev")),
                new TagSet("volume", Map.of("team", "blue"))), List.of(), true, true), tagging);
        Map<String, List<String>> context = TagOnCreate.conditionContext(tagging, tagging.sets().getFirst(),
                "ec2:RunInstances");
        assertEquals(List.of("RunInstances"), context.get("ec2:CreateAction"));
        assertEquals(List.of("red"), context.get("aws:RequestTag/team"));
        assertEquals(List.of("team", "env"), context.get("aws:TagKeys"));
    }

    /** {@code Ec2QueryHandler} stops at the first tag specification that names no resource type. */
    @Test
    void anEc2RequestsSpecificationsAreReadToTheFirstWithNoResourceType() {
        assertEquals(List.of(new TagSet("instance", Map.of("a", "1"))), sets("ec2:RunInstances", form(
                "Action=RunInstances&TagSpecification.1.ResourceType=instance&TagSpecification.1.Tag.1.Key=a"
                        + "&TagSpecification.1.Tag.1.Value=1&TagSpecification.2.Tag.1.Key=b"
                        + "&TagSpecification.3.ResourceType=volume&TagSpecification.3.Tag.1.Key=c")));
    }

    /** A tag specification's tags are checked on the resource it names, its name not known yet. */
    @Test
    void aSpecificationsTagsGoToTheResourceOfItsTypeAndOtherTagsToTheOperationsResources() {
        List<String> checked = List.of("*");
        assertEquals(List.of("arn:aws:ec2:us-east-1:000000000000:instance/*"), TagOnCreate.resources("ec2:RunInstances",
                new TagSet("instance", Map.of()), "aws", "us-east-1", "000000000000", checked));
        assertEquals(List.of("arn:aws-cn:ec2:cn-north-1::image/*"), TagOnCreate.resources("ec2:CreateImage",
                new TagSet("image", Map.of()), "aws-cn", "cn-north-1", "000000000000", checked));
        assertEquals(checked, TagOnCreate.resources("ec2:RunInstances", new TagSet("no-such-type", Map.of()),
                "aws", "us-east-1", "000000000000", checked));
        assertEquals(checked, TagOnCreate.resources("sns:CreateTopic", new TagSet(null, Map.of()),
                "aws", "us-east-1", "000000000000", checked));
    }

    /**
     * RDS, DocumentDB and Neptune read three spellings, the first two in either order, so a key given under both
     * with two values may end up with either, and each is checked.
     */
    @Test
    void rdsTagsAreReadUnderEachSpellingItsHandlersRead() {
        assertEquals(List.of(set(Map.of("a", "1", "b", "2", "c", "3"))), sets("rds:CreateDBParameterGroup", form(
                "Tags.member.1.Key=a&Tags.member.1.Value=1&Tags.Tag.1.Key=b&Tags.Tag.1.Value=2&Tag.1.Key=c&Tag.1.Value=3")));
        assertEquals(List.of(set(Map.of("team", "blue")), set(Map.of("team", "red"))), sets("rds:CreateDBParameterGroup",
                form("Tags.member.1.Key=team&Tags.member.1.Value=red&Tags.Tag.1.Key=team&Tags.Tag.1.Value=blue")));
    }

    @Test
    void elastiCacheAndRedshiftTagsAreReadAsTheirHandlersNumberThem() {
        // ElastiCacheServerlessQueryHandler takes either spelling at each number.
        assertEquals(List.of(set(Map.of("a", "1", "b", "2"))), sets("elasticache:CreateServerlessCache",
                form("Tags.Tag.1.Key=a&Tags.Tag.1.Value=1&Tags.member.2.Key=b&Tags.member.2.Value=2")));
        // ElastiCacheQueryHandler reads each spelling from 1.
        assertEquals(List.of(set(Map.of("a", "1"))), sets("elasticache:CreateCacheCluster",
                form("Tags.Tag.1.Key=a&Tags.Tag.1.Value=1&Tags.member.2.Key=b&Tags.member.2.Value=2")));
        // RedshiftQueryHandler reads every number of an integration's tags, but a blank key.
        assertEquals(List.of(set(Map.of("a", "1", "b", "2"))), sets("redshift:CreateIntegration",
                form("TagList.Tag.3.Key=a&TagList.Tag.3.Value=1&TagList.Tag.7.Key=b&TagList.Tag.7.Value=2"
                        + "&TagList.Tag.8.Key=%20")));
    }

    /** CloudWatch's handler reads an alarm's tags from {@code tags} too; other handlers read the member's name only. */
    @Test
    void aBodysMemberIsReadUnderItsOwnNameAndAnAlarmsUnderTagsToo() {
        assertEquals(List.of(set(Map.of("team", "blue"))), sets("cloudwatch:PutMetricAlarm", json(
                "{\"AlarmName\":\"a\",\"tags\":[{\"Key\":\"team\",\"Value\":\"blue\"}]}")));
        assertEquals(List.of(set(Map.of("team", "red"))), sets("cloudwatch:PutCompositeAlarm", json(
                "{\"AlarmName\":\"a\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"red\"}],"
                        + "\"tags\":[{\"Key\":\"team\",\"Value\":\"blue\"}]}")));
        Tagging lower = TagOnCreate.of("dynamodb:CreateTable", json(
                "{\"TableName\":\"t\",\"tags\":[{\"Key\":\"team\",\"Value\":\"blue\"}]}")).orElseThrow();
        assertEquals(List.of(), lower.sets());
        assertEquals(List.of(set(Map.of("team", "blue"))), lower.otherSpellings());
    }

    @Test
    void aCborBodysTagsAreRead() throws Exception {
        byte[] body = new ObjectMapper(new CBORFactory()).writeValueAsBytes(Map.of("AlarmName", "a",
                "Tags", List.of(Map.of("Key", "team", "Value", "blue"))));
        Tagging tagging = TagOnCreate.of("cloudwatch:PutMetricAlarm", request("application/cbor", body)).orElseThrow();
        assertEquals(new Tagging("cloudwatch:TagResource", List.of(set(Map.of("team", "blue"))), List.of(), false, true),
                tagging);
        assertFalse(TagOnCreate.conditionContext(tagging, tagging.sets().getFirst(), "cloudwatch:PutMetricAlarm")
                .keySet().stream().anyMatch(key -> key.endsWith(":CreateAction")));
    }

    @Test
    void theOperationsOwnActionTakesEachSetOfTagsOnlyWhereTheServiceReferenceSaysItDoes() {
        Tagging queue = TagOnCreate.of("sqs:CreateQueue", json("{\"QueueName\":\"q\",\"tags\":{\"team\":\"blue\"}}"))
                .orElseThrow();
        assertEquals(List.of(Map.of("aws:RequestTag/team", List.of("blue"), "aws:TagKeys", List.of("team"))),
                TagOnCreate.requestTagContexts(queue));
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
        // A tags member of the other shape is none the handler reads, though the tagging action is checked for it.
        assertEquals(List.of(), sets("dynamodb:CreateTable", json("{\"Tags\":{\"a\":\"b\"}}")));
    }

    private static TagSet set(Map<String, String> tags) {
        return new TagSet(null, tags);
    }

    /** The sets of tags the request carries as its handler reads them. */
    private static List<TagSet> sets(String operation, ContainerRequestContext request) {
        return TagOnCreate.of(operation, request).orElseThrow().sets();
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
