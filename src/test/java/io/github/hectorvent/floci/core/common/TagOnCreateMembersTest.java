package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.PassedRoleMembers.Into;
import io.github.hectorvent.floci.core.common.PassedRoleMembers.Step;
import io.github.hectorvent.floci.core.common.TagOnCreateMembers.Entry;
import io.github.hectorvent.floci.core.common.TagOnCreateMembers.Member;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The vendored table of the tagging action each operation that tags what it creates needs, and that
 * {@link TagOnCreate} reads every member it gives, from a JSON body and from Query form parameters.
 */
class TagOnCreateMembersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void theTableReadsTheOperationsOfEveryNamespaceItCoversOnce() {
        TagOnCreateMembers.Table table = TagOnCreateMembers.table();
        assertFalse(table.entries().isEmpty());
        for (String operation : table.entries().keySet()) {
            assertFalse(table.byHand().contains(operation) || table.notRead().contains(operation), operation);
            assertTrue(table.namespaces().contains(operation.substring(0, operation.indexOf(':'))), operation);
        }
        assertFalse(table.byHand().stream().anyMatch(table.notRead()::contains));
        assertTrue(table.otherNamespaces().stream().noneMatch(table.namespaces()::contains));
        assertTrue(table.notCovered().stream().noneMatch(table.namespaces()::contains));
    }

    /** The operations the table says Floci checks by hand are the ones {@link TagOnCreate} leaves to the filter. */
    @Test
    void exactlyTheOperationsCheckedByHandAreLeftToTheFilter() {
        assertEquals(TagOnCreate.BY_HAND, TagOnCreateMembers.table().byHand());
    }

    @Test
    void aTagListIsReadAsItsStepsAndATagSpecificationListByItsResourceType() {
        Member list = member("{\"member\":\"TagSpecification[item].Tag[item]\",\"key\":\"Key\",\"value\":\"Value\","
                + "\"resourceType\":\"ResourceType\"}");
        assertEquals(List.of(new Step("TagSpecification", Into.LIST, "item"), new Step("Tag", Into.LIST, "item")),
                list.steps());
        assertEquals("ResourceType", list.resourceType());
        assertTrue(list.inForm());

        Member named = member("{\"member\":\"Tags[Tag]\",\"key\":\"Key\",\"value\":\"Value\"}");
        assertEquals(List.of(new Step("Tags", Into.LIST, "Tag")), named.querySteps());
        assertNull(named.resourceType());
        assertFalse(member("{\"member\":\"Settings{Tags}.Tags[]\",\"key\":\"Key\",\"value\":\"Value\"}").inForm());
    }

    @Test
    void aTagMapIsReadFromAFormUnderItsQueryName() {
        Member map = member("{\"member\":\"tags{}\",\"query\":\"Tag{}\"}");
        assertEquals(List.of(new Step("tags", Into.MAP, null)), map.steps());
        assertEquals(List.of(new Step("Tag", Into.MAP, null)), map.querySteps());
        assertNull(map.key());
    }

    /** A tag specification's tags are checked on the resource of its type, an ARN the table gives each type. */
    @Test
    void theTableGivesAnArnForEachResourceTypeATagSpecificationCanName() {
        Map<String, String> ec2 = TagOnCreateMembers.table().resourceArns().get("ec2");
        assertEquals("arn:${Partition}:ec2:${Region}:${Account}:instance/*", ec2.get("instance"));
        assertEquals("arn:${Partition}:ec2:${Region}::image/*", ec2.get("image"));
        for (Map.Entry<String, Entry> operation : TagOnCreateMembers.table().entries().entrySet()) {
            for (Member member : operation.getValue().members()) {
                assertTrue(member.resourceType() == null || TagOnCreateMembers.table().resourceArns()
                        .containsKey(operation.getKey().substring(0, operation.getKey().indexOf(':'))), operation.getKey());
            }
        }
    }

    @Test
    void aMalformedTableFailsToLoad() {
        assertThrows(IllegalStateException.class, () -> parse("{\"operations\":{}}"));
        assertThrows(IllegalStateException.class, () -> parse("{\"operations\":{\"a:B\":{\"tagging\":[\"a:Tag\"]}}}"));
        // Several tagging actions are checked by hand, never read from the table.
        assertThrows(IllegalStateException.class, () -> parse("{\"operations\":{\"a:B\":{\"tagging\":[\"a:T\",\"a:U\"],"
                + "\"tags\":[{\"member\":\"Tags{}\"}]}}}"));
        assertThrows(IllegalStateException.class, () -> member("{\"member\":\"Tags\"}"));
        assertThrows(IllegalStateException.class, () -> member("{\"member\":\"Tags[]\"}"));
        assertThrows(IllegalStateException.class, () -> member("{\"member\":\"Tags{}\",\"key\":\"K\",\"value\":\"V\"}"));
        assertThrows(IllegalStateException.class, () -> member("{\"member\":\"Tags{}\",\"query\":\"Tag[]\"}"));
        // A resource type belongs to a list of tag specifications, and its namespace's types need ARNs.
        assertThrows(IllegalStateException.class, () -> member("{\"member\":\"Tags[]\",\"key\":\"K\",\"value\":\"V\","
                + "\"resourceType\":\"ResourceType\"}"));
        assertThrows(IllegalStateException.class, () -> parse("{\"operations\":{\"a:B\":{\"tagging\":[\"a:T\"],"
                + "\"tags\":[{\"member\":\"Specs[].Tags[]\",\"key\":\"K\",\"value\":\"V\",\"resourceType\":\"Type\"}]}}}"));
        assertThrows(IllegalStateException.class, () -> parse("{\"resourceArns\":{\"a\":{\"thing\":\"*\"}},"
                + "\"operations\":{\"a:B\":{\"tagging\":[\"a:T\"],\"tags\":[{\"member\":\"Tags{}\"}]}}}"));
    }

    @TestFactory
    Stream<DynamicTest> everyMemberOfTheTableIsReadFromAJsonBody() {
        return TagOnCreateMembers.table().entries().entrySet().stream()
                .flatMap(operation -> operation.getValue().members().stream().map(member -> DynamicTest.dynamicTest(
                        operation.getKey() + " " + member.path(), () -> assertEquals(expected(operation.getValue(), member),
                                TagOnCreate.of(operation.getKey(), json(body(member).toString()))))));
    }

    /** Each member a form can carry, as the SDK sends it. */
    @TestFactory
    Stream<DynamicTest> everyMemberOfTheTableAQueryRequestCanCarryIsReadFromItsForm() {
        return TagOnCreateMembers.table().entries().entrySet().stream()
                .flatMap(operation -> operation.getValue().members().stream()
                        .filter(Member::inForm)
                        .map(member -> DynamicTest.dynamicTest(operation.getKey() + " " + member.path(),
                                () -> assertEquals(expected(operation.getValue(), member),
                                        TagOnCreate.of(operation.getKey(), form(formBody(operation.getKey(), member)))))));
    }

    @TestFactory
    Stream<DynamicTest> aRequestCarryingNoTagsNeedsNoTaggingAction() {
        return TagOnCreateMembers.table().entries().keySet().stream().map(operation -> DynamicTest.dynamicTest(
                operation, () -> assertEquals(Optional.empty(), TagOnCreate.of(operation, json("{\"Name\":\"n\"}")))));
    }

    private static Optional<TagOnCreate.Tagging> expected(Entry entry, Member member) {
        return Optional.of(new TagOnCreate.Tagging(entry.tagging(), List.of(new TagOnCreate.TagSet(
                member.resourceType() == null ? null : "thing", Map.of("team", "blue"))), List.of(), entry.createAction(),
                entry.requestTags()));
    }

    /**
     * A body carrying the tag {@code team=blue} at the member, as one element of each list on the way, a tag
     * specification naming the resource type {@code thing}.
     */
    private static JsonNode body(Member member) {
        List<Step> steps = member.steps();
        Step last = steps.getLast();
        JsonNode node = last.into() == Into.MAP
                ? MAPPER.createObjectNode().put("team", "blue")
                : MAPPER.createArrayNode().add(MAPPER.createObjectNode().put(member.key(), "team")
                        .put(member.value(), "blue"));
        for (int i = steps.size() - 1; i >= 0; i--) {
            Step step = steps.get(i);
            if (i < steps.size() - 1 && step.into() == Into.LIST && member.resourceType() != null) {
                ((ObjectNode) node).put(member.resourceType(), "thing");
            }
            JsonNode inner = i == steps.size() - 1 || step.into() == Into.NONE ? node
                    : step.into() == Into.LIST ? MAPPER.createArrayNode().add(node)
                    : MAPPER.createObjectNode().set("any", node);
            ObjectNode outer = MAPPER.createObjectNode();
            outer.set(step.name(), inner);
            node = outer;
        }
        return node;
    }

    /**
     * The form parameters of the tag {@code team=blue} at the member as the SDK sends them: EC2 numbers a list
     * under its name, the Query protocol under the name its shape gives elements, or {@code member}, and a map's
     * entries under {@code entry}, but SQS, whose tags go by {@code Tag.N.Key} and {@code Tag.N.Value}. A tag
     * specification names the resource type {@code thing}.
     */
    private static String formBody(String operation, Member member) {
        boolean ec2 = operation.startsWith("ec2:");
        boolean sqs = operation.startsWith("sqs:");
        List<String> parts = new ArrayList<>();
        String specification = null;
        for (Step step : member.querySteps()) {
            parts.add(step.name());
            if (step.into() == Into.LIST && !ec2) {
                parts.add(step.key().isEmpty() ? "member" : step.key());
            } else if (step.into() == Into.MAP && !sqs) {
                parts.add("entry");
            }
            parts.add("1");
            if (specification == null && member.resourceType() != null) {
                specification = String.join(".", parts) + "." + member.resourceType() + "=thing&";
            }
        }
        String prefix = String.join(".", parts);
        String key = member.key() != null ? member.key() : sqs ? "Key" : "key";
        String value = member.value() != null ? member.value() : sqs ? "Value" : "value";
        return (specification == null ? "" : specification) + prefix + "." + key + "=team&" + prefix + "." + value + "=blue";
    }

    private static Member member(String member) {
        try {
            return parse("{\"resourceArns\":{\"a\":{\"thing\":\"arn:${Partition}:a:${Region}:${Account}:thing/*\"}},"
                    + "\"operations\":{\"a:B\":{\"tagging\":[\"a:TagResource\"],\"tags\":[" + member + "]}}}")
                    .entries().get("a:B").members().get(0);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static TagOnCreateMembers.Table parse(String document) throws Exception {
        return TagOnCreateMembers.parse(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
    }

    private static ContainerRequestContext json(String body) {
        return request("application/x-amz-json-1.1", body);
    }

    private static ContainerRequestContext form(String body) {
        return request("application/x-www-form-urlencoded", body);
    }

    private static ContainerRequestContext request(String mediaType, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getMediaType()).thenReturn(MediaType.valueOf(mediaType));
        when(request.getEntityStream()).thenAnswer(invocation -> new ByteArrayInputStream(bytes));
        return request;
    }
}
