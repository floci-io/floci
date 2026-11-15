package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.floci.aws.services.cloudformation.provisioners.PropertyChanges.Kind;
import io.floci.aws.services.cloudformation.provisioners.PropertyChanges.PropertyChange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the engine tells a provisioner about each top-level property of an update: added, changed,
 * dropped or unchanged. Every expected value is written out by hand from the rules of the design
 * (floci-spine 1.1), never read back from the implementation.
 */
class PropertyChangesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static List<String> names(PropertyChanges changes) {
        return List.copyOf(changes.byName().keySet());
    }

    // Rule 2: not an update, or no usable prior properties.

    @Test
    void createMarksEveryPropertyAddedAndNothingElse() {
        PropertyChanges changes = PropertyChanges.between("AWS::SQS::Queue", false, null,
                json("{\"QueueName\": \"q\", \"DelaySeconds\": 5}"));

        assertEquals(List.of("QueueName", "DelaySeconds"), names(changes));
        assertEquals(Kind.ADDED, changes.kindOf("QueueName"));
        assertEquals(Kind.ADDED, changes.kindOf("DelaySeconds"));
        assertEquals(Set.of("QueueName", "DelaySeconds"), changes.added());
        assertTrue(changes.changed().isEmpty());
        assertTrue(changes.dropped().isEmpty());
        assertTrue(changes.unchanged().isEmpty());
    }

    @Test
    void createIgnoresPriorPropertiesEvenWhenTheyAreSupplied() {
        PropertyChanges changes = PropertyChanges.between(null, false,
                json("{\"Gone\": 1, \"Same\": 2}"), json("{\"Same\": 2, \"New\": 3}"));

        assertEquals(List.of("Same", "New"), names(changes));
        assertEquals(Set.of("Same", "New"), changes.added());
        assertTrue(changes.dropped().isEmpty());
        assertTrue(changes.unchanged().isEmpty());
        assertNull(changes.kindOf("Gone"));
    }

    @Test
    void createWithComparedFormsStillMarksEveryPropertyAdded() {
        PropertyChanges changes = PropertyChanges.between(null, false, null, json("{\"A\": 1}"),
                json("{\"A\": 1}"), json("{\"A\": 1}"));

        assertEquals(Kind.ADDED, changes.kindOf("A"));
    }

    @Test
    void createWithNullPropertiesHasNoChanges() {
        PropertyChanges changes = PropertyChanges.between(null, false, null, null);

        assertTrue(changes.byName().isEmpty());
        assertTrue(changes.added().isEmpty());
        assertTrue(changes.dropped().isEmpty());
    }

    @Test
    void createMarksAJsonNullPropertyAdded() {
        PropertyChanges changes = PropertyChanges.between(null, false, null, json("{\"A\": null}"));

        assertEquals(Kind.ADDED, changes.kindOf("A"));
    }

    @Test
    void updateWithNullPriorPropertiesMarksEveryPropertyAdded() {
        PropertyChanges changes = PropertyChanges.between(null, true, null, json("{\"A\": 1, \"B\": 2}"));

        assertEquals(Set.of("A", "B"), changes.added());
        assertTrue(changes.changed().isEmpty());
        assertTrue(changes.dropped().isEmpty());
        assertTrue(changes.unchanged().isEmpty());
    }

    @Test
    void updateWithANonObjectPriorMarksEveryPropertyAdded() {
        for (String prior : List.of("[1, 2]", "\"text\"", "7", "null", "true")) {
            PropertyChanges changes = PropertyChanges.between(null, true, json(prior), json("{\"A\": 1, \"B\": 2}"));

            assertEquals(List.of("A", "B"), names(changes), prior);
            assertEquals(Set.of("A", "B"), changes.added(), prior);
            assertTrue(changes.dropped().isEmpty(), prior);
            assertTrue(changes.unchanged().isEmpty(), prior);
        }
    }

    // Rule 3: update with prior properties.

    @Test
    void updateClassifiesAddedChangedDroppedAndUnchangedAsWritten() {
        PropertyChanges changes = PropertyChanges.between("AWS::SQS::Queue", true,
                json("{\"Kept\": \"a\", \"Edited\": \"b\", \"Removed\": \"c\"}"),
                json("{\"Kept\": \"a\", \"Edited\": \"B\", \"Fresh\": \"d\"}"));

        assertEquals(Kind.UNCHANGED, changes.kindOf("Kept"));
        assertEquals(Kind.CHANGED, changes.kindOf("Edited"));
        assertEquals(Kind.DROPPED, changes.kindOf("Removed"));
        assertEquals(Kind.ADDED, changes.kindOf("Fresh"));
        assertEquals(Set.of("Fresh"), changes.added());
        assertEquals(Set.of("Edited"), changes.changed());
        assertEquals(Set.of("Removed"), changes.dropped());
        assertEquals(Set.of("Kept"), changes.unchanged());
    }

    @Test
    void aNameInNeitherTemplateHasNoKind() {
        PropertyChanges changes = PropertyChanges.between(null, true, json("{\"A\": 1}"), json("{\"A\": 1}"));

        assertNull(changes.kindOf("Absent"));
        assertFalse(changes.byName().containsKey("Absent"));
    }

    @Test
    void asWrittenComparisonIsStructuralJsonEquality() {
        PropertyChanges changes = PropertyChanges.between(null, true,
                json("{\"Obj\": {\"x\": [1, 2]}, \"Num\": 1, \"Str\": \"1\"}"),
                json("{\"Obj\": {\"x\": [1, 2]}, \"Num\": 1, \"Str\": 1}"));

        assertEquals(Kind.UNCHANGED, changes.kindOf("Obj"));
        assertEquals(Kind.UNCHANGED, changes.kindOf("Num"));
        assertEquals(Kind.CHANGED, changes.kindOf("Str"), "the text \"1\" is not the number 1 as written");
    }

    // Rule 1: JSON null.

    @Test
    void aJsonNullBeforeIsNotDeclaredSoANonNullNowIsAdded() {
        PropertyChanges changes = PropertyChanges.between(null, true,
                json("{\"A\": null}"), json("{\"A\": \"v\"}"));

        PropertyChange change = changes.byName().get("A");
        assertEquals(Kind.ADDED, change.kind());
        assertNull(change.prior(), "not declared before, so no prior node");
        assertEquals("v", change.current().asText());
    }

    @Test
    void aJsonNullNowIsDeclaredSoANonNullBeforeIsChanged() {
        JsonNode current = json("{\"A\": null}");
        PropertyChanges changes = PropertyChanges.between(null, true, json("{\"A\": \"v\"}"), current);

        PropertyChange change = changes.byName().get("A");
        assertEquals(Kind.CHANGED, change.kind());
        assertEquals("v", change.prior().asText());
        assertSame(current.get("A"), change.current());
        assertTrue(change.current().isNull());
    }

    @Test
    void aJsonNullOnBothSidesIsAddedBecauseOnlyNowIsDeclared() {
        PropertyChanges changes = PropertyChanges.between(null, true, json("{\"A\": null}"), json("{\"A\": null}"));

        PropertyChange change = changes.byName().get("A");
        assertEquals(Kind.ADDED, change.kind());
        assertNull(change.prior());
        assertEquals(NullNode.getInstance(), change.current());
    }

    @Test
    void aJsonNullBeforeAndAbsentNowIsInNeitherTemplate() {
        PropertyChanges changes = PropertyChanges.between(null, true, json("{\"A\": null, \"B\": 1}"), json("{}"));

        assertNull(changes.kindOf("A"));
        assertEquals(Set.of("B"), changes.dropped());
    }

    // Rule 8: properties null (or not an object) on an update.

    @Test
    void nullPropertiesOnAnUpdateDropEveryPreviouslyDeclaredName() {
        PropertyChanges changes = PropertyChanges.between("AWS::SQS::Queue", true,
                json("{\"A\": 1, \"Nothing\": null, \"B\": 2}"), null);

        assertEquals(List.of("A", "B"), names(changes));
        assertEquals(Set.of("A", "B"), changes.dropped());
        assertTrue(changes.added().isEmpty());
        assertTrue(changes.changed().isEmpty());
        assertTrue(changes.unchanged().isEmpty());
    }

    @Test
    void nonObjectPropertiesOnAnUpdateDropEveryPreviouslyDeclaredName() {
        PropertyChanges changes = PropertyChanges.between(null, true, json("{\"A\": 1}"), json("[\"A\"]"));

        assertEquals(Set.of("A"), changes.dropped());
    }

    // Rule 4: compared forms.

    @Test
    void aDifferenceOnlyAsWrittenIsUnchangedWhenTheComparedFormsAgree() {
        PropertyChanges changes = PropertyChanges.between(null, true,
                json("{\"Timeout\": \"30\"}"),
                json("{\"Timeout\": {\"Fn::Join\": [\"\", [\"3\", \"0\"]]}}"),
                json("{\"Timeout\": \"30\"}"), json("{\"Timeout\": \"30\"}"));

        assertEquals(Kind.UNCHANGED, changes.kindOf("Timeout"));
        assertEquals(Set.of("Timeout"), changes.unchanged());
        assertTrue(changes.changed().isEmpty());
    }

    @Test
    void anAsWrittenEqualityIsChangedWhenTheComparedFormsDiffer() {
        PropertyChanges changes = PropertyChanges.between(null, true,
                json("{\"Size\": {\"Ref\": \"P\"}}"), json("{\"Size\": {\"Ref\": \"P\"}}"),
                json("{\"Size\": \"1\"}"), json("{\"Size\": \"2\"}"));

        assertEquals(Kind.CHANGED, changes.kindOf("Size"));
    }

    @Test
    void withoutComparedFormsTheAsWrittenValuesDecide() {
        PropertyChanges nullForms = PropertyChanges.between(null, true,
                json("{\"A\": 1, \"B\": 1}"), json("{\"A\": 1, \"B\": 2}"), null, null);
        PropertyChanges shortForm = PropertyChanges.between(null, true,
                json("{\"A\": 1, \"B\": 1}"), json("{\"A\": 1, \"B\": 2}"));

        assertEquals(Kind.UNCHANGED, nullForms.kindOf("A"));
        assertEquals(Kind.CHANGED, nullForms.kindOf("B"));
        assertEquals(shortForm.kindOf("A"), nullForms.kindOf("A"));
        assertEquals(shortForm.kindOf("B"), nullForms.kindOf("B"));
    }

    @Test
    void aNullComparedPriorOrCurrentFallsBackToAsWritten() {
        JsonNode prior = json("{\"Same\": 1, \"Diff\": 1}");
        JsonNode current = json("{\"Same\": 1, \"Diff\": 2}");
        JsonNode compared = json("{\"Same\": \"x\", \"Diff\": \"x\"}");

        PropertyChanges noPrior = PropertyChanges.between(null, true, prior, current, null, compared);
        PropertyChanges noCurrent = PropertyChanges.between(null, true, prior, current, compared, null);

        assertEquals(Kind.UNCHANGED, noPrior.kindOf("Same"));
        assertEquals(Kind.CHANGED, noPrior.kindOf("Diff"), "the compared forms agree but one side is absent");
        assertEquals(Kind.UNCHANGED, noCurrent.kindOf("Same"));
        assertEquals(Kind.CHANGED, noCurrent.kindOf("Diff"));
    }

    @Test
    void aNameMissingFromTheComparedCurrentUsesTheAsWrittenValues() {
        PropertyChanges differs = PropertyChanges.between(null, true,
                json("{\"A\": 1}"), json("{\"A\": 2}"),
                json("{\"A\": \"x\"}"), json("{}"));
        PropertyChanges equal = PropertyChanges.between(null, true,
                json("{\"A\": 1}"), json("{\"A\": 1}"),
                json("{\"A\": \"x\"}"), json("{}"));

        assertEquals(Kind.CHANGED, differs.kindOf("A"));
        assertEquals(Kind.UNCHANGED, equal.kindOf("A"));
    }

    @Test
    void aNameMissingFromBothComparedFormsUsesTheAsWrittenValues() {
        PropertyChanges changes = PropertyChanges.between(null, true,
                json("{\"A\": 1}"), json("{\"A\": 2}"), json("{}"), json("{}"));

        assertEquals(Kind.CHANGED, changes.kindOf("A"), "two missing nodes are not an agreement");
    }

    @Test
    void comparedFormsNeverTurnAnAddedOrDroppedNameIntoAnythingElse() {
        PropertyChanges changes = PropertyChanges.between(null, true,
                json("{\"Old\": 1}"), json("{\"New\": 1}"),
                json("{\"Old\": 1, \"New\": 1}"), json("{\"Old\": 1, \"New\": 1}"));

        assertEquals(Kind.DROPPED, changes.kindOf("Old"));
        assertEquals(Kind.ADDED, changes.kindOf("New"));
    }

    // Rule 5: prior and current are the raw nodes.

    @Test
    void priorAndCurrentAreTheNodesOfTheTemplatePropertiesNotTheComparedForms() {
        JsonNode prior = json("{\"A\": {\"Fn::Join\": [\"\", [\"a\"]]}, \"Gone\": \"g\"}");
        JsonNode current = json("{\"A\": \"a\", \"New\": \"n\"}");

        PropertyChanges changes = PropertyChanges.between(null, true, prior, current,
                json("{\"A\": \"COMPARED-PRIOR\"}"), json("{\"A\": \"COMPARED-PRIOR\"}"));

        PropertyChange a = changes.byName().get("A");
        assertSame(prior.get("A"), a.prior());
        assertSame(current.get("A"), a.current());
        assertEquals("A", a.name());
        PropertyChange gone = changes.byName().get("Gone");
        assertSame(prior.get("Gone"), gone.prior());
        assertNull(gone.current(), "a dropped property has no current node");
        PropertyChange added = changes.byName().get("New");
        assertNull(added.prior(), "an added property has no prior node");
        assertSame(current.get("New"), added.current());
    }

    // Rule 6: createOnly.

    /** {@code create-only-properties.json}: {@code AWS::SQS::Queue} lists FifoQueue and QueueName. */
    @Test
    void createOnlyFollowsTheRegistryForTheResourceType() {
        PropertyChanges changes = PropertyChanges.between("AWS::SQS::Queue", true,
                json("{\"QueueName\": \"a\", \"VisibilityTimeout\": 1, \"FifoQueue\": true}"),
                json("{\"QueueName\": \"b\", \"VisibilityTimeout\": 2, \"New\": 1}"));

        assertTrue(changes.byName().get("QueueName").createOnly());
        assertTrue(changes.byName().get("FifoQueue").createOnly(), "also true for a dropped create-only property");
        assertFalse(changes.byName().get("VisibilityTimeout").createOnly());
        assertFalse(changes.byName().get("New").createOnly());
    }

    @Test
    void createOnlyIsFalseForEveryNameWithoutAResourceType() {
        PropertyChanges changes = PropertyChanges.between(null, true,
                json("{\"QueueName\": \"a\"}"), json("{\"QueueName\": \"b\"}"));

        assertFalse(changes.byName().get("QueueName").createOnly());
    }

    @Test
    void createOnlyIsFalseForATypeTheRegistryDoesNotKnow() {
        PropertyChanges changes = PropertyChanges.between("Custom::Thing", false, null, json("{\"QueueName\": \"a\"}"));

        assertFalse(changes.byName().get("QueueName").createOnly());
    }

    // Rule 7: order and unmodifiable views.

    @Test
    void byNameFollowsThePriorTemplatesOrderThenTheRemainingNamesInPropertiesOrder() {
        PropertyChanges changes = PropertyChanges.between(null, true,
                json("{\"B\": 1, \"A\": 2, \"C\": 3}"), json("{\"D\": 4, \"A\": 2, \"B\": 9, \"E\": 5}"));

        assertEquals(List.of("B", "A", "C", "D", "E"), names(changes));
        assertEquals(Kind.CHANGED, changes.kindOf("B"));
        assertEquals(Kind.UNCHANGED, changes.kindOf("A"));
        assertEquals(Kind.DROPPED, changes.kindOf("C"));
        assertEquals(Kind.ADDED, changes.kindOf("D"));
    }

    @Test
    void eachSetKeepsThatOrder() {
        PropertyChanges changes = PropertyChanges.between(null, true,
                json("{\"Z\": 1, \"A\": 2, \"M\": 3, \"Keep\": 0}"),
                json("{\"Y\": 1, \"Keep\": 0, \"X\": 2}"));

        assertEquals(List.of("Z", "A", "M"), List.copyOf(changes.dropped()));
        assertEquals(List.of("Y", "X"), List.copyOf(changes.added()));
        assertEquals(List.of("Keep"), List.copyOf(changes.unchanged()));
        assertTrue(changes.changed().isEmpty());
    }

    @Test
    void theFourSetsAreUnmodifiable() {
        PropertyChanges changes = PropertyChanges.between(null, true,
                json("{\"A\": 1, \"B\": 1, \"C\": 1}"), json("{\"A\": 2, \"B\": 1, \"D\": 1}"));

        assertThrows(UnsupportedOperationException.class, () -> changes.added().add("X"));
        assertThrows(UnsupportedOperationException.class, () -> changes.changed().add("X"));
        assertThrows(UnsupportedOperationException.class, () -> changes.dropped().add("X"));
        assertThrows(UnsupportedOperationException.class, () -> changes.unchanged().add("X"));
        assertThrows(UnsupportedOperationException.class, () -> changes.dropped().clear());
    }

    @Test
    void theFourSetsPartitionByName() {
        PropertyChanges changes = PropertyChanges.between(null, true,
                json("{\"A\": 1, \"B\": 1, \"C\": 1}"), json("{\"A\": 2, \"B\": 1, \"D\": 1}"));

        Map<String, PropertyChange> byName = changes.byName();
        assertEquals(byName.size(), changes.added().size() + changes.changed().size()
                + changes.dropped().size() + changes.unchanged().size());
        assertEquals(Set.of("D"), changes.added());
        assertEquals(Set.of("A"), changes.changed());
        assertEquals(Set.of("C"), changes.dropped());
        assertEquals(Set.of("B"), changes.unchanged());
    }
}
