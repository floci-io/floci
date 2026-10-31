package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    private static final String MAP_MODEL = """
            {
              "operations": {"Acquire": "AcquireRequest"},
              "shapes": {
                "AcquireRequest": {"type": "structure", "required": ["Values"], "members": {
                  "Values": "valueMap", "Nested": "nestedType", "Entries": "entryList"}},
                "valueMap": {"type": "map", "key": "keyType", "value": "valueType", "max": 2},
                "nestedType": {"type": "structure", "members": {
                  "Arn": "arnType", "Parameters": "nameList"}},
                "entryList": {"type": "list", "member": "entryType"},
                "entryType": {"type": "structure", "members": {
                  "Name": "keyType", "Values": "nameList"}},
                "nameList": {"type": "list", "member": "keyType", "max": 2},
                "keyType": {"type": "string", "min": 1, "max": 4},
                "valueType": {"type": "string", "max": 3, "pattern": "[a-z]*"},
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

    private static QueryConstraintCheck maps() throws Exception {
        return QueryConstraintCheck.of(MAPPER.readTree(MAP_MODEL));
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
     * Every member of every operation is read whatever it nests, so what the file refuses to load
     * is a shape of a type this walk would pass over without reading: letting one through would
     * answer a 200 for a request nothing checked. A shape that holds itself is read once rather
     * than forever, which is what lets the walk go all the way down.
     */
    @Test
    void aShapeOfATypeTheWalkCannotReadIsRefusedWhenTheFileLoads() throws Exception {
        JsonNode unread = MAPPER.readTree("""
                {
                  "operations": {"Put": "PutRequest"},
                  "shapes": {
                    "PutRequest": {"type": "structure", "members": {"Body": "blobType"}},
                    "blobType": {"type": "blob"}
                  }
                }
                """);

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> QueryConstraintCheck.of(unread));
        assertTrue(refused.getMessage().contains("Put's Body is a blob"), refused.getMessage());

        JsonNode nestedUnread = MAPPER.readTree("""
                {
                  "operations": {"Put": "PutRequest"},
                  "shapes": {
                    "PutRequest": {"type": "structure", "members": {"Rows": "rowList"}},
                    "rowList": {"type": "list", "member": "rowType"},
                    "rowType": {"type": "structure", "members": {"At": "stampType"}},
                    "stampType": {"type": "timestamp"}
                  }
                }
                """);

        IllegalStateException deeper = assertThrows(IllegalStateException.class,
                () -> QueryConstraintCheck.of(nestedUnread));
        assertTrue(deeper.getMessage().contains("Put's Rows.At is a timestamp"), deeper.getMessage());

        JsonNode holdsItself = MAPPER.readTree("""
                {
                  "operations": {"Put": "PutRequest"},
                  "shapes": {
                    "PutRequest": {"type": "structure", "members": {"Node": "nodeType"}},
                    "nodeType": {"type": "structure", "members": {
                      "Name": "nameType", "Children": "nodeList"}},
                    "nodeList": {"type": "list", "member": "nodeType"},
                    "nameType": {"type": "string", "min": 1}
                  }
                }
                """);

        assertEquals(List.of("Value '' at 'node.name' failed to satisfy constraint: Member must"
                + " have length greater than or equal to 1"),
                QueryConstraintCheck.of(holdsItself).violations("Put", params("Node.Name", "")));
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
     * A scalar member's value lives at its own name, so an index the request numbered only under a
     * deeper key is no member of the list: nothing reads a value there, and reading one would
     * answer an empty string the request never sent.
     */
    @Test
    void anIndexNumberedOnlyUnderADeeperKeyIsNoMemberOfAListOfScalars() throws Exception {
        assertEquals(List.of(), lists().violations("Vend", params(
                "Audience.member.1", "ok", "Target.Arn", ARN, "Names.member.1.Nested", "x")));
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
        // Loaded in IAM's own form, naming the member with no value beside it, which is what
        // IamRequestShapes passes and what DRIFT-13 settles.
        QueryConstraintCheck iam = QueryConstraintCheck.load("aws/iam-request-shapes.json",
                "IAM request shapes", SmithyValidationErrors.Violations.WITHOUT_VALUES);

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

        assertEquals(List.of("Value at 'policyArn' failed to satisfy constraint: Member must"
                        + " have length greater than or equal to 20"),
                iam.violations("GetPolicy", params("PolicyArn", "v1")));

        assertEquals(List.of(), iam.violations("GetAccountAuthorizationDetails", params()),
                "every member of it is optional, so a request naming none of them breaks nothing");
        assertEquals(List.of("Value at 'filter.1.member' failed to satisfy constraint:"
                        + " Member must satisfy enum value set:"
                        + " [User, Role, Group, LocalManagedPolicy, AWSManagedPolicy]"),
                iam.violations("GetAccountAuthorizationDetails", params("Filter.member.1", "Users")),
                "the one list this wave opts in resolves to its member's enum");
    }

    /**
     * A constraint the service documents and the model declares nowhere is reported where that
     * member's own violations would sit rather than after the members below it, and not at all
     * where the member already breaks one it declares: a value a declared constraint refuses is
     * not read against a documented set as well, which is how the checks these waves replace
     * answered one.
     */
    @Test
    void aDocumentedConstraintSitsInItsMembersPlaceAndYieldsToADeclaredOne() throws Exception {
        Map<String, String> documented = Map.of("Name",
                "Value 'a' at 'name' failed to satisfy constraint: Member must satisfy enum value"
                        + " set: [b, c]");

        assertEquals(List.of(
                "Value 'a' at 'name' failed to satisfy constraint: Member must satisfy enum value"
                        + " set: [b, c]",
                "Value null at 'arn' failed to satisfy constraint: Member must not be null"),
                check().violations("Put", params("Name", "a"), documented),
                "Name is listed ahead of Arn, so what it is answered for is too");

        assertEquals(List.of(
                "Value 'a!' at 'name' failed to satisfy constraint: Member must satisfy regular"
                        + " expression pattern: [\\w+=,.@-]+"),
                check().violations("Take", params("Name", "a!"), documented),
                "the shape refuses the value already, so the documented set is not read against it");

        assertEquals(List.of(), check().violations("Take", params(), documented),
                "an absent member sent no value, so there is nothing to read a set against");

        assertEquals(List.of(
                "Value null at 'name' failed to satisfy constraint: Member must not be null",
                "Value null at 'arn' failed to satisfy constraint: Member must not be null"),
                check().violations("Put", params(), documented),
                "and a required one absent is answered for that alone");
    }

    /**
     * The STS file this wave vendors, loaded as the handler loads it: the three operations resolve,
     * their required members report in the model's order, and the two lists resolve to their
     * members. {@code GetSessionToken} declares no required member, so a request naming none of
     * them breaks nothing.
     */
    @Test
    void theVendoredStsFileLoadsAndCarriesTheOperationsThisWaveClaims() {
        QueryConstraintCheck sts =
                QueryConstraintCheck.load("aws/sts-request-shapes.json", "STS request shapes");

        assertEquals(List.of("Value null at 'accessKeyId' failed to satisfy constraint: Member must"
                        + " not be null"),
                sts.violations("GetAccessKeyInfo", params()));

        assertEquals(List.of(
                "Value null at 'audience' failed to satisfy constraint: Member must not be null",
                "Value null at 'signingAlgorithm' failed to satisfy constraint: Member must not be"
                        + " null"),
                sts.violations("GetWebIdentityToken", params()));

        assertEquals(List.of(), sts.violations("GetSessionToken", params()));
        assertEquals(List.of("Value 'short' at 'serialNumber' failed to satisfy constraint: Member"
                        + " must have length greater than or equal to 9"),
                sts.violations("GetSessionToken", params("SerialNumber", "short")),
                "a member nothing read until this wave resolves to its own shape");

        assertEquals(List.of("Value null at 'tags.1.member.value' failed to satisfy constraint:"
                        + " Member must not be null"),
                sts.violations("GetWebIdentityToken", params("Audience.member.1", "app",
                        "SigningAlgorithm", "RS256", "Tags.member.1.Key", "Team")),
                "a tag is a list of structures, named under the index the request spelled");
    }

    /**
     * A map's entries arrive numbered under its own name, and an entry's value is named under the
     * key that entry carries rather than under the number it was sent with. The key and the value
     * each carry their own shape, so an entry can break both, and the map's own bounds hold the
     * count with the value left out of the message.
     */
    @Test
    void aMapsValueIsNamedUnderTheKeyTheEntryCarries() throws Exception {
        assertEquals(List.of(), maps().violations("Acquire",
                params("Values.entry.1.key", "k", "Values.entry.1.value", "ok")));

        assertEquals(List.of(
                "Value 'toolong' at 'values' failed to satisfy constraint: Map keys must satisfy"
                        + " constraint: [Member must have length less than or equal to 4, Member"
                        + " must have length greater than or equal to 1]",
                "Value 'NOPE' at 'values.toolong' failed to satisfy constraint: Member must"
                        + " have length less than or equal to 3",
                "Value 'NOPE' at 'values.toolong' failed to satisfy constraint: Member must"
                        + " satisfy regular expression pattern: [a-z]*"),
                maps().violations("Acquire",
                        params("Values.entry.1.key", "toolong", "Values.entry.1.value", "NOPE")));

        assertEquals(List.of("Value at 'values' failed to satisfy constraint: Member must have"
                + " length less than or equal to 2"),
                maps().violations("Acquire", params(
                        "Values.entry.1.key", "a", "Values.entry.1.value", "x",
                        "Values.entry.2.key", "b", "Values.entry.2.value", "y",
                        "Values.entry.3.key", "c", "Values.entry.3.value", "z")));
    }

    /** A map the request names nowhere is the required member reading as null. */
    @Test
    void aMapNamedNowhereIsTheRequiredMemberReadingAsNull() throws Exception {
        assertEquals(List.of("Value null at 'values' failed to satisfy constraint: Member must not"
                + " be null"), maps().violations("Acquire", params()));
    }

    /**
     * An entry is read off its key, so one numbered with a value and no key of its own is not an
     * entry the map could hold: it is neither counted nor answered, and a map of nothing else is
     * the required member reading as null.
     */
    @Test
    void anEntryNumberedWithNoKeyIsNoEntryAtAll() throws Exception {
        assertEquals(List.of("Value null at 'values' failed to satisfy constraint: Member must not"
                + " be null"), maps().violations("Acquire", params("Values.entry.1.value", "ok")));

        assertEquals(List.of(), maps().violations("Acquire", params(
                "Values.entry.1.key", "k", "Values.entry.1.value", "ok",
                "Values.entry.2.value", "x", "Values.entry.3.value", "y")),
                "an entry with no key is not one of the two the map admits either");
    }

    /** The bare form the protocol also accepts, {@code Values.1.key}, reads the same way. */
    @Test
    void aMapEntryIsReadWithoutTheEntrySegmentToo() throws Exception {
        assertEquals(List.of("Value 'toolong' at 'values' failed to satisfy constraint: Map keys"
                + " must satisfy constraint: [Member must have length less than or equal to 4,"
                + " Member must have length greater than or equal to 1]"),
                maps().violations("Acquire", params("Values.1.key", "toolong", "Values.1.value", "x")));
    }

    /**
     * A structure's field may be a list, and a list's member a structure holding one, each read
     * under the name the request gives it and named the way the walk names a list anywhere.
     */
    @Test
    void aListInsideAStructureAndInsideAListsMemberAreBothRead() throws Exception {
        assertEquals(List.of(
                "Value 'toolong' at 'nested.parameters.1.member' failed to satisfy constraint:"
                        + " Member must have length less than or equal to 4"),
                maps().violations("Acquire", params(
                        "Values.entry.1.key", "k", "Values.entry.1.value", "ok",
                        "Nested.Parameters.member.1", "toolong")));

        assertEquals(List.of(
                "Value at 'entries.1.member.values' failed to satisfy constraint: Member must have"
                        + " length less than or equal to 2"),
                maps().violations("Acquire", params(
                        "Values.entry.1.key", "k", "Values.entry.1.value", "ok",
                        "Entries.member.1.Name", "n",
                        "Entries.member.1.Values.member.1", "a",
                        "Entries.member.1.Values.member.2", "b",
                        "Entries.member.1.Values.member.3", "c")));
    }
}
