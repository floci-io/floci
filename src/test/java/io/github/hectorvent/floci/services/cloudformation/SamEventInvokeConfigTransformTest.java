package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A SAM function's {@code EventInvokeConfig}, expanded as AWS's own SAM translator expands it.
 *
 * <p>Each fixture under {@code src/test/resources/cloudformation/sam-event-invoke-config} holds a
 * template and what aws-sam-translator 1.115.0 (the library behind {@code sam deploy} and the
 * {@code AWS::Serverless-2016-10-31} transform) generated from it: the
 * {@code FnEventInvokeConfig*} resources, the generated role's attributes and inline policies, and
 * any conditions it added, or the error it rejected the template with. They were produced with
 * {@code samtranslator.translator.transform.transform(template, {}, loader)}, a loader whose
 * {@code load()} returns {@code {}}.
 */
class SamEventInvokeConfigTransformTest {

    private static final Path FIXTURES = Path.of("src/test/resources/cloudformation/sam-event-invoke-config");

    private final ObjectMapper mapper = new ObjectMapper();
    private final SamTransformProcessor processor = new SamTransformProcessor(mapper);

    static Stream<String> fixtures() throws IOException {
        try (Stream<Path> files = Files.list(FIXTURES)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void matchesTheSamTranslator(String fixtureName) throws IOException {
        JsonNode fixture = mapper.readTree(FIXTURES.resolve(fixtureName).toFile());
        JsonNode input = fixture.path("input");

        if (fixture.has("error")) {
            AwsException e = assertThrows(AwsException.class, () -> processor.expandSamTemplate(input));
            assertEquals("ValidationError", e.getErrorCode());
            assertEquals(fixture.path("error").asText(), e.getMessage());
            return;
        }

        JsonNode expanded = processor.expandSamTemplate(input);
        JsonNode resources = expanded.path("Resources");
        JsonNode expected = fixture.path("expected");

        List<String> generated = new ArrayList<>();
        resources.fieldNames().forEachRemaining(id -> {
            if (id.startsWith("FnEventInvokeConfig")) {
                generated.add(id);
            }
        });
        List<String> expectedIds = new ArrayList<>();
        expected.fieldNames().forEachRemaining(id -> {
            if (id.startsWith("FnEventInvokeConfig")) {
                expectedIds.add(id);
            }
        });
        assertEquals(expectedIds.stream().sorted().toList(), generated.stream().sorted().toList(),
                "the resources SAM generates for EventInvokeConfig");
        for (String id : expectedIds) {
            assertEquals(withFlociAliasId(expected.path(id), resources), resources.path(id), id);
        }

        JsonNode expectedRole = expected.path("FnRole");
        JsonNode role = resources.path("FnRole");
        if (expectedRole.isMissingNode()) {
            assertTrue(role.isMissingNode(), "a function with its own Role gets no generated role");
        } else {
            assertEquals(expectedRole.path("Condition"), role.path("Condition"), "the role's Condition");
            JsonNode expectedPolicies = expectedRole.path("Policies");
            JsonNode policies = role.path("Properties").path("Policies");
            if (expectedPolicies.isNull() || expectedPolicies.isMissingNode()) {
                assertTrue(policies.isMissingNode(), "no inline policies: " + policies);
            } else {
                assertEquals(expectedPolicies, policies, "the role's inline policies");
            }
        }

        // SAM passes the function's Condition, DeletionPolicy and UpdateReplacePolicy to the alias
        // and version AutoPublishAlias generates as well, and retains the version
        // (DeletionPolicy: Retain) when the function declares no DeletionPolicy.
        for (Map.Entry<String, JsonNode> byType : fixture.path("expectedAttributesByType").properties()) {
            JsonNode generatedResource = null;
            for (Map.Entry<String, JsonNode> resource : resources.properties()) {
                if (byType.getKey().equals(resource.getValue().path("Type").asText())) {
                    generatedResource = resource.getValue();
                }
            }
            for (Map.Entry<String, JsonNode> attribute : byType.getValue().properties()) {
                assertEquals(attribute.getValue(), generatedResource.path(attribute.getKey()),
                        byType.getKey() + " " + attribute.getKey());
            }
        }

        JsonNode inputConditions = input.path("Conditions");
        JsonNode expectedConditions = fixture.path("expectedConditions");
        List<String> added = new ArrayList<>();
        expanded.path("Conditions").fieldNames().forEachRemaining(name -> {
            if (!inputConditions.has(name)) {
                added.add(name);
            }
        });
        List<String> expectedAdded = new ArrayList<>();
        expectedConditions.fieldNames().forEachRemaining(expectedAdded::add);
        assertEquals(expectedAdded.stream().sorted().toList(), added.stream().sorted().toList(),
                "the conditions SAM adds");
        for (Map.Entry<String, JsonNode> condition : expectedConditions.properties()) {
            assertEquals(condition.getValue(), expanded.path("Conditions").path(condition.getKey()),
                    condition.getKey());
        }
    }

    /**
     * The expected resource with a {@code DependsOn} on SAM's alias id replaced by floci's. SAM
     * names an {@code AutoPublishAlias} alias {@code FnAlias<name>} with the name as written; floci
     * capitalises it ({@code FnAliasLive}), which predates this expansion and would rename the alias
     * in existing stacks if changed. The configuration must depend on the alias floci creates.
     */
    private JsonNode withFlociAliasId(JsonNode expected, JsonNode resources) {
        if (!expected.path("DependsOn").isArray()) {
            return expected;
        }
        String flociAlias = null;
        for (Map.Entry<String, JsonNode> resource : resources.properties()) {
            if ("AWS::Lambda::Alias".equals(resource.getValue().path("Type").asText())) {
                flociAlias = resource.getKey();
            }
        }
        ObjectNode copy = expected.deepCopy();
        copy.set("DependsOn", mapper.createArrayNode().add(flociAlias));
        return copy;
    }

    /** Without the property nothing changes: no configuration, and the generated role carries no inline policies. */
    @Test
    void aFunctionWithoutEventInvokeConfigGetsNoneAndAnUnchangedRole() throws IOException {
        JsonNode expanded = processor.expandSamTemplate(mapper.readTree("""
                {"Transform": "AWS::Serverless-2016-10-31",
                 "Resources": {"Fn": {"Type": "AWS::Serverless::Function",
                   "Properties": {"Runtime": "python3.12", "Handler": "index.handler", "CodeUri": "s3://b/k"}}}}
                """));

        assertTrue(expanded.path("Resources").path("FnEventInvokeConfig").isMissingNode());
        assertTrue(expanded.path("Resources").path("FnRole").path("Properties").path("Policies").isMissingNode());
        assertTrue(expanded.path("Resources").path("FnRole").path("Condition").isMissingNode());
        assertFalse(expanded.has("Conditions"), "no Conditions section is added when none is needed");
    }

    private static final String TYPE_FROM_PARAMETER = """
            {"Transform": "AWS::Serverless-2016-10-31",
             "Parameters": {"DestType": {"Type": "String" %s}},
             "Resources": {"Fn": {"Type": "AWS::Serverless::Function",
               "Properties": {"Runtime": "python3.12", "Handler": "index.handler", "CodeUri": "s3://b/k",
                 "EventInvokeConfig": {"DestinationConfig": {"OnFailure": {"Type": {"Ref": "DestType"}}}}}}}}
            """;

    /** SAM resolves a parameter reference to the value the stack is given before its Default. */
    @Test
    void aTypeFromAParameterTakesTheGivenValueOverTheDefault() throws IOException {
        JsonNode expanded = processor.expandSamTemplate(
                mapper.readTree(TYPE_FROM_PARAMETER.formatted(", \"Default\": \"SQS\"")), Map.of("DestType", "SNS"));

        assertEquals("AWS::SNS::Topic",
                expanded.path("Resources").path("FnEventInvokeConfigOnFailureTopic").path("Type").asText());
        assertTrue(expanded.path("Resources").path("FnEventInvokeConfigOnFailureQueue").isMissingNode());
    }

    @Test
    void aTypeFromAParameterWithoutADefaultTakesTheGivenValue() throws IOException {
        JsonNode expanded = processor.expandSamTemplate(
                mapper.readTree(TYPE_FROM_PARAMETER.formatted("")), Map.of("DestType", "SQS"));

        assertEquals("AWS::SQS::Queue",
                expanded.path("Resources").path("FnEventInvokeConfigOnFailureQueue").path("Type").asText());
    }

    /** An Fn::If whose condition is not a name is passed through as given, not read as a conditional destination. */
    @Test
    void anFnIfWithoutAConditionNameIsPassedThroughAsGiven() throws IOException {
        JsonNode expanded = processor.expandSamTemplate(mapper.readTree("""
                {"Transform": "AWS::Serverless-2016-10-31",
                 "Resources": {"Fn": {"Type": "AWS::Serverless::Function",
                   "Properties": {"Runtime": "python3.12", "Handler": "index.handler", "CodeUri": "s3://b/k",
                     "EventInvokeConfig": {"DestinationConfig": {"OnFailure": {"Type": "SQS",
                       "Destination": {"Fn::If": [{"Ref": "X"}, {"Ref": "AWS::NoValue"}, "arn:aws:sqs:us-east-1:123456789012:q"]}}}}}}}}
                """));

        JsonNode resources = expanded.path("Resources");
        assertTrue(resources.path("FnEventInvokeConfigOnFailureQueue").isMissingNode(), "no queue is created");
        assertEquals(mapper.readTree("""
                {"Fn::If": [{"Ref": "X"}, {"Ref": "AWS::NoValue"}, "arn:aws:sqs:us-east-1:123456789012:q"]}
                """), resources.path("FnEventInvokeConfig").path("Properties").path("DestinationConfig")
                .path("OnFailure").path("Destination"));
        assertFalse(expanded.has("Conditions"), "no condition is generated");
    }

    /** An empty EventInvokeConfig is falsy to SAM, which then generates nothing for it. */
    @Test
    void anEmptyEventInvokeConfigGeneratesNothing() throws IOException {
        JsonNode expanded = processor.expandSamTemplate(mapper.readTree("""
                {"Transform": "AWS::Serverless-2016-10-31",
                 "Resources": {"Fn": {"Type": "AWS::Serverless::Function",
                   "Properties": {"Runtime": "python3.12", "Handler": "index.handler", "CodeUri": "s3://b/k",
                                  "EventInvokeConfig": {}}}}}
                """));

        assertTrue(expanded.path("Resources").path("FnEventInvokeConfig").isMissingNode());
    }
}
