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
    void aTagListIsReadAsItsStepsAndItsTagsFromAForm() {
        Member list = member("{\"member\":\"TagSpecification[item].Tag[item]\",\"key\":\"Key\",\"value\":\"Value\"}");
        assertEquals(List.of(new Step("TagSpecification", Into.LIST, "item"), new Step("Tag", Into.LIST, "item")),
                list.steps());
        assertTrue(list.queryTags().matcher("TagSpecification.1.Tag.2.Key").matches());
        assertTrue(list.queryTags().matcher("TagSpecification.item.1.Tag.member.2.Value").matches());
        assertFalse(list.queryTags().matcher("TagSpecification.1.Tag.2.key").matches());
        assertFalse(list.queryTags().matcher("TagSpecification.1.ResourceType").matches());

        Member named = member("{\"member\":\"Tags[Tag]\",\"key\":\"Key\",\"value\":\"Value\"}");
        assertTrue(named.queryTags().matcher("Tags.Tag.1.Key").matches());
        assertTrue(named.queryTags().matcher("Tags.member.1.Key").matches());
        assertFalse(named.queryTags().matcher("Tags.Other.1.Key").matches());
    }

    @Test
    void aTagMapIsReadFromAFormWithOrWithoutEntryAndUnderItsQueryName() {
        Member map = member("{\"member\":\"tags{}\",\"query\":\"Tag{}\"}");
        assertEquals(List.of(new Step("tags", Into.MAP, null)), map.steps());
        assertTrue(map.queryTags().matcher("Tag.1.Key").matches());
        assertTrue(map.queryTags().matcher("Tag.entry.1.value").matches());
        assertFalse(map.queryTags().matcher("tags.1.Key").matches());
        assertNull(map.key());
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
    }

    @TestFactory
    Stream<DynamicTest> everyMemberOfTheTableIsReadFromAJsonBody() {
        return TagOnCreateMembers.table().entries().entrySet().stream()
                .flatMap(operation -> operation.getValue().members().stream().map(member -> DynamicTest.dynamicTest(
                        operation.getKey() + " " + member.path(), () -> assertEquals(expected(operation.getValue()),
                                TagOnCreate.of(operation.getKey(), json(body(member).toString()))))));
    }

    @TestFactory
    Stream<DynamicTest> everyMemberOfTheTableAQueryRequestCanCarryIsReadFromItsForm() {
        return TagOnCreateMembers.table().entries().entrySet().stream()
                .flatMap(operation -> operation.getValue().members().stream()
                        .filter(member -> member.queryTags() != null)
                        .map(member -> DynamicTest.dynamicTest(operation.getKey() + " " + member.path(),
                                () -> assertEquals(expected(operation.getValue()),
                                        TagOnCreate.of(operation.getKey(), form(formBody(member)))))));
    }

    @TestFactory
    Stream<DynamicTest> aRequestCarryingNoTagsNeedsNoTaggingAction() {
        return TagOnCreateMembers.table().entries().keySet().stream().map(operation -> DynamicTest.dynamicTest(
                operation, () -> assertEquals(Optional.empty(), TagOnCreate.of(operation, json("{\"Name\":\"n\"}")))));
    }

    private static Optional<TagOnCreate.Tagging> expected(Entry entry) {
        return Optional.of(new TagOnCreate.Tagging(entry.tagging(), Map.of("team", "blue"), entry.createAction(),
                entry.requestTags()));
    }

    /** A body carrying the tag {@code team=blue} at the member, as one element of each list on the way. */
    private static JsonNode body(Member member) {
        List<Step> steps = member.steps();
        Step last = steps.getLast();
        JsonNode node = last.into() == Into.MAP
                ? MAPPER.createObjectNode().put("team", "blue")
                : MAPPER.createArrayNode().add(MAPPER.createObjectNode().put(member.key(), "team")
                        .put(member.value(), "blue"));
        for (int i = steps.size() - 1; i >= 0; i--) {
            Step step = steps.get(i);
            JsonNode inner = i == steps.size() - 1 || step.into() == Into.NONE ? node
                    : step.into() == Into.LIST ? MAPPER.createArrayNode().add(node)
                    : MAPPER.createObjectNode().set("any", node);
            ObjectNode outer = MAPPER.createObjectNode();
            outer.set(step.name(), inner);
            node = outer;
        }
        return node;
    }

    /** The form parameters of the tag {@code team=blue} at the member, in the generic forms of lists and maps. */
    private static String formBody(Member member) {
        List<String> parts = new ArrayList<>();
        for (Step step : member.querySteps()) {
            parts.add(step.name());
            if (step.into() == Into.LIST) {
                parts.add("member.1");
            } else if (step.into() == Into.MAP) {
                parts.add("entry.1");
            }
        }
        String prefix = String.join(".", parts);
        return prefix + "." + (member.key() == null ? "key" : member.key()) + "=team&"
                + prefix + "." + (member.value() == null ? "value" : member.value()) + "=blue";
    }

    private static Member member(String member) {
        try {
            return parse("{\"operations\":{\"a:B\":{\"tagging\":[\"a:TagResource\"],\"tags\":[" + member + "]}}}")
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
