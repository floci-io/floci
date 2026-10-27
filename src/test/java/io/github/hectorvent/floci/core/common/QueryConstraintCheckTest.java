package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryConstraintCheckTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
            {
              "operations": {"Put": "PutRequest", "Take": "TakeRequest"},
              "shapes": {
                "PutRequest": {"type": "structure", "required": ["Name", "Arn"], "members": {
                  "Name": "nameType", "Arn": "arnType", "Status": "statusType",
                  "Age": "ageType", "Default": "booleanType"}},
                "TakeRequest": {"type": "structure", "members": {"Name": "nameType"}},
                "nameType": {"type": "string", "min": 1, "max": 8, "pattern": "[\\\\w+=,.@-]+"},
                "arnType": {"type": "string", "min": 20, "max": 2048},
                "statusType": {"type": "string", "enum": ["Active", "Inactive"]},
                "ageType": {"type": "integer", "min": 1, "max": 30},
                "booleanType": {"type": "boolean"}
              }
            }
            """;

    private static final String LIST_MODEL = """
            {
              "operations": {"Vend": "VendRequest"},
              "shapes": {
                "VendRequest": {"type": "structure", "required": ["Audience", "Target"], "members": {
                  "Audience": "audienceList", "Names": "nameList", "Tags": "tagList",
                  "Target": "targetType"}},
                "audienceList": {"type": "list", "member": "audienceType", "min": 1, "max": 2},
                "audienceType": {"type": "string", "min": 1, "max": 6},
                "nameList": {"type": "list", "member": "nameType", "min": 2},
                "tagList": {"type": "list", "member": "tagType", "max": 2},
                "tagType": {"type": "structure", "required": ["Key", "Value"], "members": {
                  "Key": "nameType", "Value": "valueType"}},
                "targetType": {"type": "structure", "required": ["Arn"], "members": {
                  "Arn": "arnType", "Note": "valueType"}},
                "nameType": {"type": "string", "min": 1, "max": 8, "pattern": "[\\\\w+=,.@-]+"},
                "valueType": {"type": "string", "max": 4},
                "arnType": {"type": "string", "min": 20, "max": 2048}
              }
            }
            """;

    private static final String ARN = "arn:aws:iam::000000000000:role/r";

    private static QueryConstraintCheck check() throws Exception {
        return QueryConstraintCheck.of(MAPPER.readTree(MODEL));
    }

    private static QueryConstraintCheck lists() throws Exception {
        return QueryConstraintCheck.of(MAPPER.readTree(LIST_MODEL));
    }

    private static MultivaluedMap<String, String> params(String... pairs) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        for (int pair = 0; pair < pairs.length; pair += 2) {
            params.putSingle(pairs[pair], pairs[pair + 1]);
        }
        return params;
    }

    /** Every required member the request leaves out, in the order the model lists the members. */
    @Test
    void theRequiredMembersAreReportedTogetherInTheModelsOrder() throws Exception {
        List<String> violations = check().violations("Put", params());

        assertEquals(List.of(
                "Value null at 'name' failed to satisfy constraint: Member must not be null",
                "Value null at 'arn' failed to satisfy constraint: Member must not be null"),
                violations);
    }

    /**
     * A member sent empty is a member the request carries, so what it breaks are its own
     * constraints, where one that never arrived is the member reported as null. An empty value
     * breaks both the minimum length and a pattern that asks for a character, and both are
     * reported, as #6402 settled for an id breaking two constraints.
     */
    @Test
    void aMemberSentEmptyBreaksItsConstraintsRatherThanReadingAsAbsent() throws Exception {
        List<String> violations = check().violations("Take", params("Name", ""));

        assertEquals(List.of(
                "Value '' at 'name' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 1",
                "Value '' at 'name' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: [\\w+=,.@-]+"),
                violations);
        assertEquals(List.of(), check().violations("Take", params()), "Name is optional here");
    }

    /** Length, pattern and enum, each reported by the member that breaks it. */
    @Test
    void aStringIsHeldToItsLengthItsPatternAndItsEnum() throws Exception {
        assertEquals(List.of("Value 'abcdefghi' at 'name' failed to satisfy constraint: "
                + "Member must have length less than or equal to 8"),
                check().violations("Take", params("Name", "abcdefghi")),
                "nine word characters break the length and nothing else");

        assertEquals(List.of("Value 'two w' at 'name' failed to satisfy constraint: "
                + "Member must satisfy regular expression pattern: [\\w+=,.@-]+"),
                check().violations("Take", params("Name", "two w")),
                "a space inside the length breaks the pattern and nothing else");

        assertEquals(List.of(
                "Value 'two words' at 'name' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 8",
                "Value 'two words' at 'name' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: [\\w+=,.@-]+"),
                check().violations("Take", params("Name", "two words")),
                "a value breaking both is reported twice");

        assertEquals(List.of("Value 'Pending' at 'status' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [Active, Inactive]"),
                check().violations("Put", params("Name", "ok", "Arn", "a".repeat(20),
                        "Status", "Pending")));
    }

    /** A number is held to its range, and one that is no number at all is left to the operation. */
    @Test
    void aNumberIsHeldToItsRangeAndANonNumberIsLeftToTheOperation() throws Exception {
        MultivaluedMap<String, String> tooOld = params("Name", "ok", "Arn", "a".repeat(20), "Age", "31");
        assertEquals(List.of("Value '31' at 'age' failed to satisfy constraint: "
                + "Member must have value less than or equal to 30"),
                check().violations("Put", tooOld));

        MultivaluedMap<String, String> words = params("Name", "ok", "Arn", "a".repeat(20), "Age", "soon");
        assertEquals(List.of(), check().violations("Put", words));
    }

    /**
     * A number too large for a {@code long} is a number all the same, and the bound it breaks is
     * one that fits in one. Reading it as no number at all would have let it past the maximum.
     */
    @Test
    void aNumberTooLargeForALongIsStillHeldToItsRange() throws Exception {
        assertEquals(List.of("Value '9223372036854775808' at 'age' failed to satisfy constraint: "
                + "Member must have value less than or equal to 30"),
                check().violations("Put", params("Name", "ok", "Arn", "a".repeat(20),
                        "Age", "9223372036854775808")));

        assertEquals(List.of("Value '-9223372036854775809' at 'age' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 1"),
                check().violations("Put", params("Name", "ok", "Arn", "a".repeat(20),
                        "Age", "-9223372036854775809")));

        assertEquals(List.of("Value '31' at 'age' failed to satisfy constraint: "
                + "Member must have value less than or equal to 30"),
                check().violations("Put", params("Name", "ok", "Arn", "a".repeat(20), "Age", "+31")),
                "a value is written as the number reads, the plus gone, as a parsed one already was");

        assertEquals(List.of(), check().violations("Put",
                params("Name", "ok", "Arn", "a".repeat(20), "Age", "+2")),
                "and a signed value inside the range is still inside it");
    }

    /** A boolean declares nothing a request can break, so nothing is checked for one. */
    @Test
    void aBooleanIsNotChecked() throws Exception {
        assertEquals(List.of(), check().violations("Put",
                params("Name", "ok", "Arn", "a".repeat(20), "Default", "yes")));
    }

    /** The file carries one wave of operations, so an operation outside it passes untouched. */
    @Test
    void anOperationTheFileDoesNotCarryPasses() throws Exception {
        assertEquals(List.of(), check().violations("Delete", params("Name", "two words")));
    }

    /** The protocol's code and the Smithy count, from the same violations. */
    @Test
    void checkThrowsTheQueryProtocolsValidationError() throws Exception {
        AwsException thrown = assertThrows(AwsException.class,
                () -> check().check("Put", params("Name", "two w")));

        assertEquals("ValidationError", thrown.getErrorCode());
        assertEquals(400, thrown.getHttpStatus());
        assertEquals("2 validation errors detected: Value 'two w' at 'name' failed to satisfy"
                + " constraint: Member must satisfy regular expression pattern: [\\w+=,.@-]+;"
                + " Value null at 'arn' failed to satisfy constraint: Member must not be null",
                thrown.getMessage());
    }

    /**
     * A map is read from numbered entries whose key is a value of its own, and this package answers
     * one today against the whole map rather than against an entry, so an operation carrying one is
     * refused when the file loads rather than passed over without its constraints. So is anything
     * nested deeper than a list of structures or a structure of scalars.
     */
    @Test
    void anOperationCarryingAMapOrDeeperNestingIsRefusedWhenTheFileLoads() throws Exception {
        JsonNode map = MAPPER.readTree("""
                {
                  "operations": {"Put": "PutRequest"},
                  "shapes": {
                    "PutRequest": {"type": "structure", "members": {"Properties": "propertyMap"}},
                    "propertyMap": {"type": "map", "key": "nameType", "value": "nameType"},
                    "nameType": {"type": "string", "min": 1}
                  }
                }
                """);

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> QueryConstraintCheck.of(map));
        assertTrue(refused.getMessage().contains("Put's Properties is a map"), refused.getMessage());

        JsonNode listOfLists = MAPPER.readTree("""
                {
                  "operations": {"Put": "PutRequest"},
                  "shapes": {
                    "PutRequest": {"type": "structure", "members": {"Rows": "rowList"}},
                    "rowList": {"type": "list", "member": "nameList"},
                    "nameList": {"type": "list", "member": "nameType"},
                    "nameType": {"type": "string", "min": 1}
                  }
                }
                """);

        IllegalStateException nested =
                assertThrows(IllegalStateException.class, () -> QueryConstraintCheck.of(listOfLists));
        assertTrue(nested.getMessage().contains("Put's Rows is a list of list"), nested.getMessage());

        JsonNode structureInStructure = MAPPER.readTree("""
                {
                  "operations": {"Put": "PutRequest"},
                  "shapes": {
                    "PutRequest": {"type": "structure", "members": {"Target": "targetType"}},
                    "targetType": {"type": "structure", "members": {"Inner": "innerType"}},
                    "innerType": {"type": "structure", "members": {"Name": "nameType"}},
                    "nameType": {"type": "string", "min": 1}
                  }
                }
                """);

        IllegalStateException deeper = assertThrows(IllegalStateException.class,
                () -> QueryConstraintCheck.of(structureInStructure));
        assertTrue(deeper.getMessage().contains("Put's Target.Inner is a structure"),
                deeper.getMessage());
    }

    /**
     * A list's members are held to the element's own constraints, each named under the index the
     * request spelled rather than the position it sits at, which is what #6478 settled for a member
     * of a Query list. A member present and empty is still a member.
     */
    @Test
    void aListsMembersAreNamedUnderTheIndexTheRequestSpelled() throws Exception {
        List<String> violations = lists().violations("Vend", params(
                "Audience.member.1", "", "Audience.member.7", "overlong", "Target.Arn", ARN));

        assertEquals(List.of(
                "Value '' at 'audience.1.member' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 1",
                "Value 'overlong' at 'audience.7.member' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 6"),
                violations);

        assertEquals(List.of("Value '' at 'audience.1.member' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 1"),
                lists().violations("Vend", params("Audience.1", "", "Target.Arn", ARN)),
                "the bare numbered form is a member too");
    }

    /**
     * The count is held to the list's own bounds, with the value left out of the message: quoting it
     * would echo every member of the list back. A list of no members is how an absent list arrives
     * on this protocol, so a required one reads as null and a minimum of 1 is unreachable.
     */
    @Test
    void aListIsHeldToItsOwnBoundsAndOneOfNoMembersReadsAsAbsent() throws Exception {
        assertEquals(List.of("Value at 'audience' failed to satisfy constraint: "
                + "Member must have length less than or equal to 2"),
                lists().violations("Vend", params("Audience.member.1", "a", "Audience.member.2", "b",
                        "Audience.member.3", "c", "Target.Arn", ARN)));

        assertEquals(List.of("Value null at 'audience' failed to satisfy constraint: "
                + "Member must not be null"),
                lists().violations("Vend", params("Target.Arn", ARN)),
                "a required list of no members is the member reading as null");

        assertEquals(List.of("Value at 'names' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 2"),
                lists().violations("Vend", params("Audience.member.1", "a", "Target.Arn", ARN,
                        "Names.member.1", "one")),
                "a minimum above one is reachable, where a minimum of one is not");
    }

    /** A member of a list of structures carries its fields under its own prefix. */
    @Test
    void aListOfStructuresNamesEachFieldUnderItsMember() throws Exception {
        List<String> violations = lists().violations("Vend", params(
                "Audience.member.1", "a", "Target.Arn", ARN,
                "Tags.member.1.Key", "", "Tags.member.1.Value", "toolong",
                "Tags.member.2.Key", "ok"));

        assertEquals(List.of(
                "Value '' at 'tags.1.member.key' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 1",
                "Value '' at 'tags.1.member.key' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: [\\w+=,.@-]+",
                "Value 'toolong' at 'tags.1.member.value' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 4",
                "Value null at 'tags.2.member.value' failed to satisfy constraint: "
                        + "Member must not be null"),
                violations);
    }

    /**
     * A structure member arrives under its own name, and a request naming none of its fields is a
     * request without the member at all.
     */
    @Test
    void aStructureMemberNamesItsFieldsUnderItAndReadsAsAbsentWithNone() throws Exception {
        assertEquals(List.of(
                "Value null at 'target.arn' failed to satisfy constraint: Member must not be null",
                "Value 'toolong' at 'target.note' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 4"),
                lists().violations("Vend", params("Audience.member.1", "a", "Target.Note", "toolong")));

        assertEquals(List.of("Value null at 'target' failed to satisfy constraint: "
                + "Member must not be null"),
                lists().violations("Vend", params("Audience.member.1", "a")));
    }

    /**
     * The vendored IAM file itself, read the way the handler reads it. Loading it is the check on
     * it: every operation's members have to be ones this walk reads and every pattern has to be one
     * Java's engine compiles, and a wave that breaks either would otherwise surface as a failure on
     * the first IAM request rather than here. Naming the operations keeps each wave's own contract,
     * so one renamed or dropped in the model is a failure with a name on it.
     */
    @Test
    void theVendoredIamFileLoadsAndCarriesTheOperationsTheseWavesClaim() {
        QueryConstraintCheck iam =
                QueryConstraintCheck.load("aws/iam-request-shapes.json", "IAM request shapes");

        List<String> managedPolicyFamily = List.of("AttachGroupPolicy", "AttachRolePolicy",
                "AttachUserPolicy", "DetachGroupPolicy", "DetachRolePolicy", "DetachUserPolicy",
                "GetPolicy", "DeletePolicy", "CreatePolicyVersion", "GetPolicyVersion",
                "DeletePolicyVersion", "SetDefaultPolicyVersion");
        for (String operation : managedPolicyFamily) {
            assertTrue(iam.violations(operation, params()).stream()
                            .allMatch(violation -> violation.contains("must not be null")),
                    operation + " reported something other than its absent required members");
            assertTrue(iam.violations(operation, params()).size() >= 1,
                    operation + " resolved to no required members, so its shape did not resolve");
        }

        assertEquals(List.of("Value 'v1' at 'policyArn' failed to satisfy constraint: Member must"
                        + " have length greater than or equal to 20"),
                iam.violations("GetPolicy", params("PolicyArn", "v1")));

        assertEquals(List.of(), iam.violations("GetAccountAuthorizationDetails", params()),
                "every member of it is optional, so a request naming none of them breaks nothing");
        assertEquals(List.of("Value 'Users' at 'filter.1.member' failed to satisfy constraint:"
                        + " Member must satisfy enum value set:"
                        + " [User, Role, Group, LocalManagedPolicy, AWSManagedPolicy]"),
                iam.violations("GetAccountAuthorizationDetails", params("Filter.member.1", "Users")),
                "the one list this wave opts in resolves to its member's enum");
    }
}
