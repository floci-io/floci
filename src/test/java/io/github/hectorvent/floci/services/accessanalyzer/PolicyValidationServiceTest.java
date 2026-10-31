package io.github.hectorvent.floci.services.accessanalyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyValidationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PolicyValidationService service = new PolicyValidationService(mapper);

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{}|MISSING_STATEMENT",
        "[]|DATA_TYPE_MISMATCH",
        "{\"Statement\":[]}|MISSING_STATEMENT",
        "{\"Statement\":{}}|MISSING_EFFECT",
        "{\"Statement\":{\"Effect\":\"Permit\"}}|INVALID_EFFECT",
        "{\"Statement\":{\"Effect\":true}}|DATA_TYPE_MISMATCH",
        "{\"Statement\":{\"Effect\":\"Allow\"}}|MISSING_ACTION",
        "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\"}}|MISSING_RESOURCE",
        "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":[],\"Resource\":\"*\"}}|EMPTY_ARRAY_ACTION",
        "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":[]}}|EMPTY_ARRAY_RESOURCE",
        "{\"Statement\":{\"Action\":true}}|DATA_TYPE_MISMATCH",
        "{\"Statement\":{\"Action\":\"s3:GetObject\",\"NotAction\":\"s3:PutObject\"}}|UNSUPPORTED_ELEMENT_COMBINATION",
        "{\"Statement\":{\"Principal\":\"*\"}}|UNSUPPORTED_PRINCIPAL",
        "{\"Statement\":{\"Other\":true}}|INVALID_POLICY_ELEMENT",
        "{\"Statement\":{\"Sid\":\"\"}}|EMPTY_SID_VALUE",
        "{\"Version\":\"2020-01-01\",\"Statement\":{}}|INVALID_VERSION",
        "{\"Statement\":{\"Condition\":[]}}|DATA_TYPE_MISMATCH"
    })
    void reportsDocumentAndStatementGrammarFindings(String policy, String code) {
        JsonNode result = validate(policy);
        assertTrue(StreamSupport.stream(result.path("findings").spliterator(), false)
                .anyMatch(finding -> code.equals(finding.path("issueCode").asText())), result::toString);
    }

    @Test
    void findingSpanPointsToTheSubmittedValueAndUsesAnArrayPath() {
        String policy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [{
                    "Effect": "Permit",
                    "Action": "s3:GetObject", "Resource": "*"
                  }]
                }
                """;
        JsonNode finding = StreamSupport.stream(validate(policy).path("findings").spliterator(), false)
                .filter(item -> "INVALID_EFFECT".equals(item.path("issueCode").asText())).findFirst().orElseThrow();
        JsonNode location = finding.path("locations").get(0);
        int start = policy.indexOf("\"Permit\"");
        assertEquals(start, location.at("/span/start/offset").asInt());
        assertEquals(start + 8, location.at("/span/end/offset").asInt());
        assertEquals(4, location.at("/span/start/line").asInt());
        assertEquals("Statement", location.at("/path/0/value").asText());
        assertEquals(0, location.at("/path/1/index").asInt());
        assertEquals("Effect", location.at("/path/2/value").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"Effect\":\"Permit\",\"Effect\":\"Allow\"",
        "\"Effect\":null,\"Effect\":\"Allow\"", "\"Effect\":\"Allow\",\"Eff\\u0065ct\":\"Allow\""})
    void repeatedKeysAreSyntaxFindingsAtTheRepeatedSourceKey(String effects) {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":{\"Action\":\"*\",\"Resource\":\"*\"," + effects + "}}";
        JsonNode result = validate(policy);
        assertEquals(1, result.path("findings").size(), result::toString);
        JsonNode finding = result.at("/findings/0");
        assertEquals("JSON_SYNTAX_ERROR", finding.path("issueCode").asText());
        assertEquals("ERROR", finding.path("findingType").asText());
        JsonNode location = finding.at("/locations/0");
        assertEquals("Statement", location.at("/path/0/value").asText());
        assertEquals("Effect", location.at("/path/1/key").asText());
        int keyStart = policy.indexOf(effects) + effects.indexOf(',') + 1;
        assertEquals(keyStart, location.at("/span/start/offset").asInt());
        assertEquals(policy.indexOf('"', keyStart + 1) + 1, location.at("/span/end/offset").asInt());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "RESOURCE_POLICY|{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}}|MISSING_PRINCIPAL",
        "SERVICE_CONTROL_POLICY|{\"Statement\":{\"Principal\":\"*\"}}|SCP_SYNTAX_ERROR_PRINCIPAL",
        "RESOURCE_CONTROL_POLICY|{\"Statement\":{\"NotAction\":\"s3:GetObject\"}}|RCP_SYNTAX_ERROR_NOTACTION",
        "RESOURCE_CONTROL_POLICY|{\"Statement\":{\"Principal\":{\"AWS\":\"123456789012\"}}}|RCP_SYNTAX_ERROR_PRINCIPAL",
        "RESOURCE_CONTROL_POLICY|{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Resource\":\"*\",\"Condition\":{}}}|RCP_SYNTAX_ERROR_ALLOW",
        "IDENTITY_POLICY|{\"Statement\":{\"Condition\":{\"UnknownOperator\":{\"aws:username\":\"user\"}}}}|INVALID_CONDITION_OPERATOR"
    })
    void reportsPolicyTypeAndConditionGrammar(String type, String policy, String code) {
        JsonNode result = service.validate(mapper.createObjectNode().put("policyType", type).put("policyDocument", policy), null, null);
        assertTrue(StreamSupport.stream(result.path("findings").spliterator(), false)
                .anyMatch(finding -> code.equals(finding.path("issueCode").asText())), result::toString);
    }

    @Test
    void sourcePositionsCountCarriageReturnLineBreaks() {
        String policy = "{\r\"Version\":\"2012-10-17\",\r\"Statement\":{\"Effect\":\"Permit\"}}";
        JsonNode finding = StreamSupport.stream(validate(policy).path("findings").spliterator(), false)
                .filter(item -> "INVALID_EFFECT".equals(item.path("issueCode").asText())).findFirst().orElseThrow();
        assertEquals(3, finding.at("/locations/0/span/start/line").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sts:TagSession", "sts:SetSourceIdentity"})
    void trustPolicyContextAppliesToAllOfItsStatements(String additionalAction) {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"AWS":"123456789012"},"Action":"sts:AssumeRole"},
                  {"Effect":"Allow","Principal":{"AWS":"123456789012"},"Action":"%s"}]}
                """.formatted(additionalAction);
        JsonNode result = service.validate(mapper.createObjectNode().put("policyType", "RESOURCE_POLICY")
                .put("policyDocument", policy), null, null);
        assertTrue(result.path("findings").isEmpty(), result::toString);
    }

    @Test
    void resourceControlAllowAcceptsAResourceWildcardArray() {
        JsonNode result = service.validate(mapper.createObjectNode().put("policyType", "RESOURCE_CONTROL_POLICY")
                .put("policyDocument", """
                        {"Version":"2012-10-17","Statement":{"Effect":"Allow","Principal":"*",
                          "Action":"*","Resource":["*"]}}
                        """), null, null);
        assertTrue(result.path("findings").isEmpty(), result::toString);
    }

    @Test
    void principalKeyNamesAreValidated() {
        JsonNode result = service.validate(mapper.createObjectNode().put("policyType", "RESOURCE_POLICY")
                .put("policyDocument", """
                        {"Version":"2012-10-17","Statement":{"Effect":"Allow","Action":"s3:GetObject",
                          "Resource":"*","Principal":{"Aws":"123456789012"}}}
                        """), null, null);
        assertEquals("INVALID_PRINCIPAL_KEY", result.at("/findings/0/issueCode").asText());
    }

    @Test
    void anInvalidPrincipalKeyPointsToTheKeyRatherThanItsValue() {
        String policy = """
                {"Version":"2012-10-17","Statement":{"Effect":"Allow","Action":"s3:GetObject",
                  "Resource":"*","Principal":{"Aws":"123456789012"}}}
                """;
        JsonNode result = service.validate(mapper.createObjectNode().put("policyType", "RESOURCE_POLICY")
                .put("policyDocument", policy), null, null);
        JsonNode location = result.at("/findings/0/locations/0");
        assertEquals("Aws", location.at("/path/2/key").asText());
        assertEquals(policy.indexOf("\"Aws\""), location.at("/span/start/offset").asInt());
        assertEquals(policy.indexOf("\"Aws\"") + 5, location.at("/span/end/offset").asInt());
    }

    @Test
    void anEscapedUnsupportedPolicyKeyHasItsOriginalSourceSpan() {
        String policy = "{\"A\\u0077s\":0}";
        JsonNode result = validate(policy);
        JsonNode location = result.at("/findings/0/locations/0");
        assertEquals("Aws", location.at("/path/0/key").asText());
        assertEquals(1, location.at("/span/start/offset").asInt());
        assertEquals(11, location.at("/span/end/offset").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"policyDocument\":7,\"policyType\":\"IDENTITY_POLICY\"}",
        "{\"policyDocument\":\"{}\",\"policyType\":\"OTHER\"}",
        "{\"policyDocument\":\"{}\",\"policyType\":\"IDENTITY_POLICY\",\"locale\":\"XX\"}",
        "{\"policyDocument\":\"{}\",\"policyType\":\"IDENTITY_POLICY\",\"validatePolicyResourceType\":\"AWS::S3::Bucket\"}"})
    void rejectsInvalidRequestMembers(String request) throws Exception {
        AwsException error = assertThrows(AwsException.class, () -> service.validate(mapper.readTree(request), null, null));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void paginationTokensAreBoundToThePolicyAndPageSizeIsValidated() {
        ObjectNode request = mapper.createObjectNode().put("policyType", "IDENTITY_POLICY").put("policyDocument", "{}");
        String token = service.validate(request, 1, null).path("nextToken").asText();
        ObjectNode changed = request.deepCopy().put("policyDocument", "{\"Statement\":[]}");
        assertThrows(AwsException.class, () -> service.validate(changed, 1, token));
        assertThrows(AwsException.class, () -> service.validate(request, 0, null));
        assertThrows(AwsException.class, () -> service.validate(request, 1001, null));
    }

    @Test
    void paginationDistinguishesUnpairedSurrogatesFromReplacementCharacters() {
        ObjectNode request = mapper.createObjectNode().put("policyType", "IDENTITY_POLICY")
                .put("policyDocument", "{\"" + (char) 0xD800 + "\":0}");
        String token = service.validate(request, 1, null).path("nextToken").asText();
        ObjectNode changed = request.deepCopy().put("policyDocument", "{\"?\":0}");
        AwsException error = assertThrows(AwsException.class, () -> service.validate(changed, 1, token));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void aForgedCursorWithTheRightRequestHashIsStillRejected() {
        // A captured cursor namespace for this request followed by a forged nonnumeric cursor.
        String token = "dmFsaWRhdGUtcG9saWN5OjAwNjI0ZjA4YzRlNDk1NGQ5MjgwYTMxN2IwMWI4YWY2N2YzMTRmMzVhNDUyNmQwYjI2NzBhYmI0MGE0NjgwMGE6Z2FyYmFnZQ";
        AwsException error = assertThrows(AwsException.class, () -> service.validate(mapper.createObjectNode()
                .put("policyType", "IDENTITY_POLICY").put("policyDocument", "{}"), 1, token));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void findingJsonIsMaterializedOnlyForTheRequestedPage() {
        AtomicInteger objects = new AtomicInteger();
        ObjectMapper counting = new ObjectMapper().setNodeFactory(new JsonNodeFactory(false) {
            @Override
            public ObjectNode objectNode() {
                objects.incrementAndGet();
                return super.objectNode();
            }
        });
        String policy = "{\"Statement\":[" + String.join(",", Collections.nCopies(1000, "{}")) + "]}";
        JsonNode result = new PolicyValidationService(counting).validate(counting.createObjectNode()
                .put("policyType", "IDENTITY_POLICY").put("policyDocument", policy), 1, null);
        assertEquals(1, result.path("findings").size());
        assertTrue(objects.get() < 2000, () -> "Created " + objects.get() + " JSON objects for one finding page");
    }

    private ObjectNode validate(String policy) {
        ObjectNode request = mapper.createObjectNode().put("policyDocument", policy).put("policyType", "IDENTITY_POLICY");
        return service.validate(request, null, null);
    }
}
