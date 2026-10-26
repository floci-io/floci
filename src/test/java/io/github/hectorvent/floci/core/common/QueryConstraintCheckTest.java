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

    private static QueryConstraintCheck check() throws Exception {
        return QueryConstraintCheck.of(MAPPER.readTree(MODEL));
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
     * A list or a map is read from numbered parameters rather than from one, so an operation
     * carrying either is refused when the file loads rather than passed over without its
     * constraints.
     */
    @Test
    void anOperationCarryingAListIsRefusedWhenTheFileLoads() throws Exception {
        JsonNode model = MAPPER.readTree("""
                {
                  "operations": {"Tag": "TagRequest"},
                  "shapes": {
                    "TagRequest": {"type": "structure", "members": {"Tags": "tagList"}},
                    "tagList": {"type": "list", "member": "nameType", "max": 50},
                    "nameType": {"type": "string", "min": 1}
                  }
                }
                """);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> QueryConstraintCheck.of(model));

        assertTrue(thrown.getMessage().contains("Tag's Tags is a list"), thrown.getMessage());
    }

    /**
     * The vendored IAM file itself, read the way the handler reads it. Loading it is the check on
     * it: every operation's members have to be scalars and every pattern has to be one Java's
     * engine compiles, and a wave that breaks either would otherwise surface as a failure on the
     * first IAM request rather than here. Naming the twelve operations keeps the wave's own
     * contract, so an operation renamed or dropped in the model is a failure with a name on it.
     */
    @Test
    void theVendoredIamFileLoadsAndCarriesTheOperationsThisWaveClaims() {
        QueryConstraintCheck iam =
                QueryConstraintCheck.load("aws/iam-request-shapes.json", "IAM request shapes");

        List<String> wave = List.of("AttachGroupPolicy", "AttachRolePolicy", "AttachUserPolicy",
                "DetachGroupPolicy", "DetachRolePolicy", "DetachUserPolicy", "GetPolicy",
                "DeletePolicy", "CreatePolicyVersion", "GetPolicyVersion", "DeletePolicyVersion",
                "SetDefaultPolicyVersion");
        for (String operation : wave) {
            assertTrue(iam.violations(operation, params()).stream()
                            .allMatch(violation -> violation.contains("must not be null")),
                    operation + " reported something other than its absent required members");
            assertTrue(iam.violations(operation, params()).size() >= 1,
                    operation + " resolved to no required members, so its shape did not resolve");
        }

        assertEquals(List.of("Value 'v1' at 'policyArn' failed to satisfy constraint: Member must"
                        + " have length greater than or equal to 20"),
                iam.violations("GetPolicy", params("PolicyArn", "v1")));
    }
}
