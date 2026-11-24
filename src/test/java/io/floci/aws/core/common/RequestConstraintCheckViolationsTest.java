package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The collecting walk: every violation at once, worded as Smithy words them. */
class RequestConstraintCheckViolationsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
            {"operations": {"Put": "PutInput", "Blobby": "BlobbyInput", "Listy": "ListyInput", "Mappy": "MappyInput",
                            "Loop": "LoopInput", "Bounded": "BoundedInput"},
             "shapes": {
               "PutInput": {"type": "structure", "required": ["Name", "Inner"],
                            "members": {"Name": "Name", "Inner": "Inner", "Kind": "Kind", "Count": "Count",
                                        "Ratio": "Ratio", "Flag": "Flag", "Secret": "Secret", "Wide": "Wide",
                                        "Tags": "Tags", "Names": "Names", "Pairs": "Pairs"}},
               "Inner": {"type": "structure", "required": ["Leaf"], "members": {"Leaf": "Name", "Other": "Name"}},
               "Name": {"type": "string", "min": 2, "max": 5, "pattern": "[a-z]+"},
               "Kind": {"type": "string", "enum": ["B", "A"]},
               "Count": {"type": "integer", "min": 1, "max": 900},
               "Wide": {"type": "long", "min": 1},
               "Ratio": {"type": "double", "min": 0, "max": 1},
               "Flag": {"type": "boolean"},
               "Secret": {"type": "string", "min": 6, "sensitive": true},
               "Tags": {"type": "map", "min": 0, "max": 2, "key": "TagKey", "value": "TagValue"},
               "TagKey": {"type": "string", "min": 1, "max": 3, "pattern": "[a-z]+"},
               "TagValue": {"type": "string", "min": 0, "max": 4, "pattern": "[a-z]*"},
               "Names": {"type": "list", "min": 1, "max": 2, "member": "Name"},
               "Pairs": {"type": "list", "member": "Flag"},
               "Longs": {"type": "list", "member": "Wide"},
               "BoundedInput": {"type": "structure", "members": {"Longs": "Longs"}},
               "BlobbyInput": {"type": "structure", "members": {"Data": "Data"}},
               "Data": {"type": "blob"},
               "ListyInput": {"type": "structure", "members": {"Items": "Items"}},
               "Items": {"type": "list", "member": "Inner"},
               "MappyInput": {"type": "structure", "members": {"Marks": "Marks"}},
               "Marks": {"type": "map", "key": "Name", "value": "Inner"},
               "LoopInput": {"type": "structure", "members": {"Self": "LoopInput", "Name": "Name"}}
             }}
            """;

    private static final RequestConstraintCheck CHECK = RequestConstraintCheck.of(json(MODEL),
            RequestConstraintCheck.standardMessages("InvalidInputException"));

    private static final String REQUIRED_OK = "\"Name\": \"abc\", \"Inner\": {\"Leaf\": \"abc\"}";

    @Test
    void emptyRequestReportsEveryRequiredMemberInModelOrder() {
        assertEquals(List.of(
                "Value null at 'name' failed to satisfy constraint: Member must not be null",
                "Value null at 'inner' failed to satisfy constraint: Member must not be null"),
                CHECK.violations("Put", json("{}")));
    }

    @Test
    void nullAndMissingRequestsAreEmptyStructures() {
        List<String> expected = CHECK.violations("Put", json("{}"));

        assertEquals(expected, CHECK.violations("Put", null));
        assertEquals(expected, CHECK.violations("Put", json("null")));
    }

    @Test
    void jsonNullMemberIsAbsentAndUnknownMembersAreIgnored() {
        assertEquals(List.of("Value null at 'inner' failed to satisfy constraint: Member must not be null"),
                CHECK.violations("Put", json("{\"Name\": \"abc\", \"Inner\": null, \"Kind\": null, \"Extra\": 1}")));
    }

    @Test
    void nestedMemberPathLowerCasesEachFirstLetter() {
        assertEquals(List.of(
                "Value null at 'inner.leaf' failed to satisfy constraint: Member must not be null"),
                CHECK.violations("Put", json("{\"Name\": \"abc\", \"Inner\": {}}")));
    }

    @Test
    void stringReportsLengthThenPatternThenEnumEachIndependently() {
        assertEquals(List.of(
                "Value 'A' at 'name' failed to satisfy constraint: Member must have length greater than or equal to 2",
                "Value 'A' at 'name' failed to satisfy constraint: Member must satisfy regular expression pattern: [a-z]+"),
                CHECK.violations("Put", json("{\"Name\": \"A\", \"Inner\": {\"Leaf\": \"abc\"}}")));
        assertEquals(List.of(
                "Value 'abcdef' at 'name' failed to satisfy constraint: Member must have length less than or equal to 5"),
                CHECK.violations("Put", json("{\"Name\": \"abcdef\", \"Inner\": {\"Leaf\": \"abc\"}}")));
    }

    @Test
    void stringLengthCountsCodePoints() {
        String sixEmoji = "\\ud83d\\ude00".repeat(6);

        assertEquals(List.of(), CHECK.violations("Put",
                json("{" + REQUIRED_OK + ", \"Secret\": \"" + sixEmoji + "\"}")));
    }

    @Test
    void enumValuesKeepTheVendoredOrder() {
        assertEquals(List.of(
                "Value 'C' at 'kind' failed to satisfy constraint: Member must satisfy enum value set: [B, A]"),
                CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Kind\": \"C\"}")));
    }

    @Test
    void sensitiveStringLeavesItsValueOut() {
        assertEquals(List.of(
                "Value at 'secret' failed to satisfy constraint: Member must have length greater than or equal to 6"),
                CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Secret\": \"abc\"}")));
    }

    @Test
    void integerAboveMaxAndBelowMinAreRangeViolations() {
        assertEquals(List.of("Value '901' at 'count' failed to satisfy constraint: "
                + "Member must have value less than or equal to 900"),
                CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Count\": 901}")));
        assertEquals(List.of("Value '0' at 'count' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 1"),
                CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Count\": 0}")));
    }

    @Test
    void integerTooBigForLongIsStillARangeViolation() {
        assertEquals(List.of("Value '99999999999999999999' at 'count' failed to satisfy constraint: "
                + "Member must have value less than or equal to 900"),
                CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Count\": 99999999999999999999}")));
    }

    @Test
    void integerWithoutDeclaredBoundThatDoesNotFitTheTypeIsATypeError() {
        AwsException error = assertThrows(AwsException.class,
                () -> CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Wide\": 99999999999999999999}")));

        assertEquals("Invalid parameter at 'Wide' failed to satisfy constraint: 'Member must be a long'",
                error.getMessage());
    }

    @Test
    void fractionalNumberWhereIntegerIsModeledIsATypeError() {
        AwsException error = assertThrows(AwsException.class,
                () -> CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Count\": 1.5}")));

        assertEquals("Invalid parameter at 'Count' failed to satisfy constraint: 'Member must be a integer'",
                error.getMessage());
    }

    @Test
    void doubleUsesTheNumbersTextAgainstItsBounds() {
        assertEquals(List.of("Value '1.5' at 'ratio' failed to satisfy constraint: "
                + "Member must have value less than or equal to 1"),
                CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Ratio\": 1.5}")));
        assertEquals(List.of("Value '-0.5' at 'ratio' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 0"),
                CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Ratio\": -0.5}")));
    }

    @Test
    void booleanChecksTheTypeOnly() {
        assertEquals(List.of(), CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Flag\": false}")));
        AwsException error = assertThrows(AwsException.class,
                () -> CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Flag\": \"yes\"}")));

        assertEquals("Invalid parameter at 'Flag' failed to satisfy constraint: 'Member must be a boolean'",
                error.getMessage());
    }

    @Test
    void wrongTypeThrowsAtOnceWithTheModelSpelledPath() {
        AwsException structure = assertThrows(AwsException.class,
                () -> CHECK.violations("Put", json("{\"Name\": \"abc\", \"Inner\": \"x\"}")));
        AwsException element = assertThrows(AwsException.class,
                () -> CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Names\": [\"abc\", 3]}")));
        AwsException root = assertThrows(AwsException.class, () -> CHECK.violations("Put", json("[]")));

        assertEquals("Invalid parameter at 'Inner' failed to satisfy constraint: 'Member must be a structure'",
                structure.getMessage());
        assertEquals("Invalid parameter at 'Names' failed to satisfy constraint: 'Member must be a string'",
                element.getMessage());
        assertEquals("Invalid parameter at '' failed to satisfy constraint: 'Member must be a structure'",
                root.getMessage());
    }

    @Test
    void listSizeViolationQuotesTheWholeListAndDropsNullAsText() {
        assertEquals(List.of("Value '[abc, abc, null]' at 'names' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 2",
                "Value '[abc, abc, null]' at 'names' failed to satisfy constraint: "
                        + "Member must satisfy constraint: [Member must have length less than or equal to 5, "
                        + "Member must have length greater than or equal to 2, "
                        + "Member must satisfy regular expression pattern: [a-z]+, Member must not be null]"),
                CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Names\": [\"abc\", \"abc\", null]}")));
    }

    @Test
    void listBelowItsMinimumSizeIsReported() {
        assertEquals(List.of("Value '[]' at 'names' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 1"),
                CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Names\": []}")));
    }

    @Test
    void listMemberBreakingItsShapeIsOneViolationForTheWholeList() {
        assertEquals(List.of("Value '[ok, B]' at 'names' failed to satisfy constraint: "
                + "Member must satisfy constraint: [Member must have length less than or equal to 5, "
                + "Member must have length greater than or equal to 2, "
                + "Member must satisfy regular expression pattern: [a-z]+, Member must not be null]"),
                CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Names\": [\"ok\", \"B\"]}")));
    }

    @Test
    void listOfBooleansWithANullMemberHasOnlyTheNullRule() {
        assertEquals(List.of("Value '[true, null]' at 'pairs' failed to satisfy constraint: "
                + "Member must satisfy constraint: [Member must not be null]"),
                CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Pairs\": [true, null]}")));
    }

    @Test
    void mapSizeViolationQuotesTheWholeMapInRequestOrder() {
        assertEquals(List.of("Value '{b=x, a=y, c=z}' at 'tags' failed to satisfy constraint: "
                + "Member must have length less than or equal to 2"),
                CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Tags\": {\"b\": \"x\", \"a\": \"y\", \"c\": \"z\"}}")));
    }

    @Test
    void mapKeyThenValueViolationsAreOneEachAndOnlyValuesCarryNotNull() {
        assertEquals(List.of(
                "Value '{ k= v}' at 'tags' failed to satisfy constraint: Map keys must satisfy constraint: "
                        + "[Member must have length less than or equal to 3, "
                        + "Member must have length greater than or equal to 1, "
                        + "Member must satisfy regular expression pattern: [a-z]+]",
                "Value '{ k= v}' at 'tags' failed to satisfy constraint: Map value must satisfy constraint: "
                        + "[Member must have length less than or equal to 4, "
                        + "Member must have length greater than or equal to 0, "
                        + "Member must satisfy regular expression pattern: [a-z]*, Member must not be null]"),
                CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Tags\": {\" k\": \" v\"}}")));
    }

    @Test
    void mapValueThatIsNullIsAValueViolation() {
        assertEquals(List.of(
                "Value '{k=null}' at 'tags' failed to satisfy constraint: Map value must satisfy constraint: "
                        + "[Member must have length less than or equal to 4, "
                        + "Member must have length greater than or equal to 0, "
                        + "Member must satisfy regular expression pattern: [a-z]*, Member must not be null]"),
                CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Tags\": {\"k\": null}}")));
    }

    @Test
    void mapValueOfTheWrongTypeThrowsWithTheKeyInThePath() {
        AwsException error = assertThrows(AwsException.class,
                () -> CHECK.violations("Put", json("{" + REQUIRED_OK + ", \"Tags\": {\"k\": 1}}")));

        assertEquals("Invalid parameter at 'Tags.k' failed to satisfy constraint: 'Member must be a string'",
                error.getMessage());
    }

    @Test
    void everyViolationIsCollectedInMemberOrder() {
        List<String> found = CHECK.violations("Put",
                json("{\"Name\": \"A\", \"Kind\": \"C\", \"Count\": 0, \"Secret\": \"x\"}"));

        assertEquals(List.of(
                "Value 'A' at 'name' failed to satisfy constraint: Member must have length greater than or equal to 2",
                "Value 'A' at 'name' failed to satisfy constraint: Member must satisfy regular expression pattern: [a-z]+",
                "Value null at 'inner' failed to satisfy constraint: Member must not be null",
                "Value 'C' at 'kind' failed to satisfy constraint: Member must satisfy enum value set: [B, A]",
                "Value '0' at 'count' failed to satisfy constraint: Member must have value greater than or equal to 1",
                "Value at 'secret' failed to satisfy constraint: Member must have length greater than or equal to 6"),
                found);
    }

    @Test
    void unknownOperationIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> CHECK.violations("Nope", json("{}")));
    }

    @Test
    void operationHoldingABlobIsRefusedNamingTheMember() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> CHECK.violations("Blobby", json("{}")));

        assertEquals("Blobby's Data is a blob, which this check does not word yet", error.getMessage());
    }

    @Test
    void listOfStructuresIsRefused() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> CHECK.violations("Listy", json("{}")));

        assertEquals("Listy's Items[] is a structure, which this check does not word yet", error.getMessage());
    }

    @Test
    void mapWithStructureValuesIsRefused() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> CHECK.violations("Mappy", json("{}")));

        assertEquals("Mappy's Marks.value is a structure, which this check does not word yet", error.getMessage());
    }

    @Test
    void listOfBoundedNumbersIsRefused() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> CHECK.violations("Bounded", json("{}")));
        assertEquals("Bounded's Longs[] is a long, which this check does not word yet", error.getMessage());
    }

    @Test
    void listOrMapHoldingASensitiveStringIsRefused() {
        RequestConstraintCheck check = RequestConstraintCheck.of(json("""
                {"operations": {"Listy": "ListyInput", "Mappy": "MappyInput"},
                 "shapes": {
                   "ListyInput": {"type": "structure", "members": {"Tokens": "Tokens"}},
                   "Tokens": {"type": "list", "member": "Token"},
                   "MappyInput": {"type": "structure", "members": {"Marks": "Marks"}},
                   "Marks": {"type": "map", "key": "Plain", "value": "Token"},
                   "Plain": {"type": "string"},
                   "Token": {"type": "string", "sensitive": true}}}
                """), RequestConstraintCheck.standardMessages("InvalidInputException"));

        assertEquals("Listy's Tokens[] is a sensitive string, which this check does not word yet",
                assertThrows(IllegalStateException.class, () -> check.violations("Listy", json("{}"))).getMessage());
        assertEquals("Mappy's Marks.value is a sensitive string, which this check does not word yet",
                assertThrows(IllegalStateException.class, () -> check.violations("Mappy", json("{}"))).getMessage());
    }

    @Test
    void shapeHoldingItselfIsWalkedOnce() {
        assertEquals(List.of(), CHECK.violations("Loop",
                json("{\"Name\": \"abc\", \"Self\": {\"Name\": \"abc\", \"Self\": {}}}")));
    }

    @Test
    void refusalDoesNotAffectCheckOrOtherOperations() {
        CHECK.check("Blobby", json("{}"));
        assertEquals(List.of(), CHECK.violations("Put", json("{" + REQUIRED_OK + "}")));
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
