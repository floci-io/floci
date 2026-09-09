package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code Fn::Transform} with {@code Name: AWS::Include} splices the S3 snippet a
 * {@code DefinitionSubstitutions} mapping points at into the template before any other intrinsic
 * resolves, so the fragment's keys land in the enclosing mapping and {@code Fn::Transform} itself
 * does not survive into it. {@code Location} accepts only an {@code s3://} URI: the template
 * CloudFormation itself receives always carries one, because {@code aws cloudformation package}
 * rewrites every local path before a stack ever sees it.
 */
class AwsIncludeProcessorTest {

    private static final String BUCKET = "cfn-snippets";
    private static final String KEY = "substitutions.yaml";
    private static final String LOCATION = "s3://" + BUCKET + "/" + KEY;
    private static final Path FRAGMENT_PATH =
            Path.of("src/test/resources/cloudformation/aws-include/definition-substitutions.yaml");

    // The exact value SnakeYAML 2.6 parses out of the fragment's folded (>-) block scalar: three
    // line breaks preserved, because its continuation lines are more indented than the first, and
    // AWS::Include's merge does not fold them (it splices the parsed node, unmodified).
    private static final String EXPAND_ITEMS =
              "{% (\n"
            + "  $c := function($v) { $exists($v.S) ? $v.S : $exists($v.N) ? $number($v.N) : $v };\n"
            + "  $map($states.result.Items, function($i) { $merge($map($keys($i), function($k) { {$k: $c($lookup($i, $k))} })) })\n"
            + ") %}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final S3Service s3Service = mock(S3Service.class);
    private final AwsIncludeProcessor processor = new AwsIncludeProcessor(mapper, s3Service);

    private void stubSnippet(String bucket, String key, String content) {
        S3Object object = new S3Object();
        object.setData(content.getBytes(StandardCharsets.UTF_8));
        when(s3Service.getObject(bucket, key)).thenReturn(object);
    }

    private void stubMeasuredFragment() throws Exception {
        stubSnippet(BUCKET, KEY, Files.readString(FRAGMENT_PATH));
    }

    private static String blockForm() {
        return """
            Resources:
              OrdersStateMachine:
                Type: AWS::StepFunctions::StateMachine
                Properties:
                  DefinitionSubstitutions:
                    Fn::Transform:
                      Name: AWS::Include
                      Parameters:
                        Location: %s
            """.formatted(LOCATION);
    }

    private static String flowForm() {
        return """
            Resources:
              OrdersStateMachine:
                Type: AWS::StepFunctions::StateMachine
                Properties:
                  DefinitionSubstitutions:
                    Fn::Transform: { Name: AWS::Include, Parameters: { Location: %s } }
            """.formatted(LOCATION);
    }

    private JsonNode mergedSubstitutions(String templateYaml) throws Exception {
        JsonNode template = new CloudFormationYamlParser(mapper).parse(templateYaml);
        JsonNode merged = processor.mergeIncludes(template);
        return merged.at("/Resources/OrdersStateMachine/Properties/DefinitionSubstitutions");
    }

    @Test
    void blockFormIncludeMergesSnippetKeysIntoSubstitutions() throws Exception {
        stubMeasuredFragment();

        JsonNode substitutions = mergedSubstitutions(blockForm());

        assertEquals(EXPAND_ITEMS, substitutions.path("expandItems").asText(),
                "AWS::Include did not merge the snippet's scalar verbatim; substitutions are " + substitutions);
        assertEquals("orders", substitutions.get("orderTableName").asText());
        assertFalse(substitutions.has("Fn::Transform"),
                "the Fn::Transform node survived the merge");
    }

    @Test
    void flowFormIncludeMergesSnippetKeysIntoSubstitutions() throws Exception {
        stubMeasuredFragment();

        JsonNode substitutions = mergedSubstitutions(flowForm());

        assertEquals(EXPAND_ITEMS, substitutions.path("expandItems").asText(),
                "AWS::Include did not merge the snippet's scalar verbatim; substitutions are " + substitutions);
        assertEquals("orders", substitutions.get("orderTableName").asText());
        assertFalse(substitutions.has("Fn::Transform"),
                "the Fn::Transform node survived the merge");
    }

    @Test
    void mergeIncludesReturnsTheSameReferenceWhenTemplateCarriesNoInclude() throws Exception {
        JsonNode template = new CloudFormationYamlParser(mapper).parse("""
            Resources:
              Bucket:
                Type: AWS::S3::Bucket
            """);

        JsonNode merged = processor.mergeIncludes(template);

        // CloudFormationService.executeTemplate tells a real merge apart from a no-op one by
        // comparing this return value's reference against the one it passed in; a copy here, even
        // an unmodified one, would make every executeTemplate call reserialize the submitted body.
        assertSame(template, merged,
                "a template with no AWS::Include must come back as the exact same JsonNode instance");
    }

    @Test
    void mergeIncludesReturnsADifferentReferenceWhenTemplateCarriesAnInclude() throws Exception {
        stubMeasuredFragment();
        JsonNode template = new CloudFormationYamlParser(mapper).parse(blockForm());

        JsonNode merged = processor.mergeIncludes(template);

        assertNotSame(template, merged,
                "a template whose Fn::Transform/AWS::Include was actually merged must not come back "
                        + "as the caller's own unmodified reference");
    }

    @Test
    void includeKeepsSiblingKeysOfTheTransformAndAppendsSnippetKeysAfterThem() throws Exception {
        stubMeasuredFragment();
        String template = """
            Resources:
              OrdersStateMachine:
                Type: AWS::StepFunctions::StateMachine
                Properties:
                  DefinitionSubstitutions:
                    alreadyWrittenKey: alreadyWrittenValue
                    Fn::Transform:
                      Name: AWS::Include
                      Parameters:
                        Location: %s
            """.formatted(LOCATION);

        JsonNode substitutions = mergedSubstitutions(template);

        assertEquals("alreadyWrittenValue", substitutions.get("alreadyWrittenKey").asText(),
                "a key already written beside Fn::Transform did not survive the merge");
        assertEquals(EXPAND_ITEMS, substitutions.path("expandItems").asText());
        assertEquals("orders", substitutions.get("orderTableName").asText());

        // Matches the shape read back from a deployed stack's processed template:
        // the sibling keys already written beside Fn::Transform keep their original order, and the
        // snippet's own keys are appended after them, not interleaved or reordered.
        List<String> fieldOrder = new ArrayList<>();
        substitutions.fieldNames().forEachRemaining(fieldOrder::add);
        assertEquals(List.of("alreadyWrittenKey", "expandItems", "orderTableName"), fieldOrder,
                "sibling keys must precede the snippet's keys, both in their original order: " + fieldOrder);
    }

    @Test
    void includeWithNonS3LocationIsRejected() {
        String blockFormLocalPath = """
            Resources:
              OrdersStateMachine:
                Type: AWS::StepFunctions::StateMachine
                Properties:
                  DefinitionSubstitutions:
                    Fn::Transform:
                      Name: AWS::Include
                      Parameters:
                        Location: ./src/shared/substitutions.yaml
            """;
        AwsException relative = assertThrows(AwsException.class,
                () -> mergedSubstitutions(blockFormLocalPath));
        assertTrue(relative.getMessage().contains("./src/shared/substitutions.yaml"),
                "message does not name the rejected location: " + relative.getMessage());

        String blockFormBareName = """
            Resources:
              OrdersStateMachine:
                Type: AWS::StepFunctions::StateMachine
                Properties:
                  DefinitionSubstitutions:
                    Fn::Transform:
                      Name: AWS::Include
                      Parameters:
                        Location: swagger.yaml
            """;
        AwsException bare = assertThrows(AwsException.class,
                () -> mergedSubstitutions(blockFormBareName));
        assertTrue(bare.getMessage().contains("swagger.yaml"),
                "message does not name the rejected location: " + bare.getMessage());
    }

    @Test
    void locationAsAMappingNamesTheNodeInTheRejectionMessage() {
        // Location: {Ref: InputValue} is the form AWS's own Fn::Transform documentation uses in its
        // only AWS::Include example. Ref resolution is not implemented; the node must still be named.
        String template = """
            Resources:
              OrdersStateMachine:
                Type: AWS::StepFunctions::StateMachine
                Properties:
                  DefinitionSubstitutions:
                    Fn::Transform:
                      Name: AWS::Include
                      Parameters:
                        Location: { Ref: InputValue }
            """;

        AwsException e = assertThrows(AwsException.class, () -> mergedSubstitutions(template));
        assertTrue(e.getMessage().contains("\"Ref\":\"InputValue\""),
                "message does not name the rejected Location node: " + e.getMessage());
    }

    @Test
    void includeWithUnreadableSnippetFailsTheStack() {
        when(s3Service.getObject(BUCKET, KEY))
                .thenThrow(new AwsException("NoSuchKey", "The specified key does not exist.", 404));

        AwsException e = assertThrows(AwsException.class, () -> mergedSubstitutions(blockForm()));
        assertTrue(e.getMessage().contains(LOCATION),
                "message does not name the unreadable location: " + e.getMessage());
        // A well-formed s3:// Location that cannot be read surfaces S3's own error, not a generic
        // ValidationError: S3 already reports the precise cause and floci has nothing more specific
        // to say.
        assertEquals("NoSuchKey", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void nestedIncludeInsideASnippetIsRejected() {
        String innerLocation = "s3://" + BUCKET + "/inner-fragment";
        stubSnippet(BUCKET, KEY, """
            expandItems:
              Fn::Transform:
                Name: AWS::Include
                Parameters:
                  Location: %s
            """.formatted(innerLocation));

        assertThrows(AwsException.class, () -> mergedSubstitutions(blockForm()));
    }

    @Test
    void snippetContainingAForeignTransformIsNotRejected() throws Exception {
        // AWS's own considerations bullet is narrow: "You can't use AWS::Include to reference a
        // template snippet that also uses AWS::Include." A snippet carrying an unrelated macro under
        // Fn::Transform must pass through untouched, not fail the whole merge.
        stubSnippet(BUCKET, KEY, """
            someResource:
              Fn::Transform:
                Name: SomeOtherMacro
                Parameters:
                  Foo: bar
            orderTableName: orders
            """);

        JsonNode substitutions = mergedSubstitutions(blockForm());

        assertEquals("SomeOtherMacro", substitutions.at("/someResource/Fn::Transform/Name").asText(),
                "the foreign transform node did not survive the merge untouched");
        assertEquals("bar", substitutions.at("/someResource/Fn::Transform/Parameters/Foo").asText());
        assertEquals("orders", substitutions.get("orderTableName").asText());
    }

    @Test
    void missingLocationNamesTheParametersObjectInsteadOfLeavingAnEmptyTail() {
        String template = """
            Resources:
              OrdersStateMachine:
                Type: AWS::StepFunctions::StateMachine
                Properties:
                  DefinitionSubstitutions:
                    Fn::Transform:
                      Name: AWS::Include
                      Parameters: {}
            """;

        AwsException e = assertThrows(AwsException.class, () -> mergedSubstitutions(template));
        assertTrue(e.getMessage().endsWith("got {}"),
                "an absent Location must name the empty Parameters mapping, not leave an empty tail: "
                        + e.getMessage());
    }

    @Test
    void parametersAbsentEntirelyNamesTheTransformNodeInsteadOfLeavingAnEmptyTail() {
        String template = """
            Resources:
              OrdersStateMachine:
                Type: AWS::StepFunctions::StateMachine
                Properties:
                  DefinitionSubstitutions:
                    Fn::Transform:
                      Name: AWS::Include
            """;

        AwsException e = assertThrows(AwsException.class, () -> mergedSubstitutions(template));
        assertEquals("Fn::Transform AWS::Include Location must be an s3://bucket/key URI, "
                + "got {\"Name\":\"AWS::Include\"}", e.getMessage());
    }

    @Test
    void twoResourcesEachWithTheirOwnFnTransformBothMerge() throws Exception {
        // The measured corpus carries 41 Fn::Transform nodes in one template, one per resource's
        // own Resources.<id>.Properties.DefinitionSubstitutions - never a single node shared across
        // resources. A regression that merges the first one found and stops before walking the
        // rest of the template would pass every single-resource test above and still ship broken.
        stubMeasuredFragment();
        String secondBucket = "second-fragment-bucket";
        String secondKey = "second-fragment";
        String secondLocation = "s3://" + secondBucket + "/" + secondKey;
        stubSnippet(secondBucket, secondKey, "secondOrderTableName: second-orders\n");
        String template = """
            Resources:
              OrdersStateMachine:
                Type: AWS::StepFunctions::StateMachine
                Properties:
                  DefinitionSubstitutions:
                    Fn::Transform:
                      Name: AWS::Include
                      Parameters:
                        Location: %s
              InventoryStateMachine:
                Type: AWS::StepFunctions::StateMachine
                Properties:
                  DefinitionSubstitutions:
                    Fn::Transform:
                      Name: AWS::Include
                      Parameters:
                        Location: %s
            """.formatted(LOCATION, secondLocation);

        JsonNode merged = processor.mergeIncludes(new CloudFormationYamlParser(mapper).parse(template));
        JsonNode first = merged.at("/Resources/OrdersStateMachine/Properties/DefinitionSubstitutions");
        JsonNode second = merged.at("/Resources/InventoryStateMachine/Properties/DefinitionSubstitutions");

        assertEquals("orders", first.path("orderTableName").asText(),
                "the first resource's Fn::Transform did not merge; substitutions are " + first);
        assertFalse(first.has("Fn::Transform"));
        assertEquals("second-orders", second.path("secondOrderTableName").asText(),
                "the second resource's Fn::Transform did not merge; a regression that splices the "
                        + "first include and stops would leave this unset: substitutions are " + second);
        assertFalse(second.has("Fn::Transform"));
    }

    @Test
    void emptyStringLocationRendersItsQuotedEmptyStringInsteadOfLeavingAnEmptyTail() {
        String template = """
            Resources:
              OrdersStateMachine:
                Type: AWS::StepFunctions::StateMachine
                Properties:
                  DefinitionSubstitutions:
                    Fn::Transform:
                      Name: AWS::Include
                      Parameters:
                        Location: ""
            """;

        AwsException e = assertThrows(AwsException.class, () -> mergedSubstitutions(template));
        assertEquals("Fn::Transform AWS::Include Location must be an s3://bucket/key URI, "
                + "got \"\"", e.getMessage());
    }

    @Test
    void whitespaceOnlyLocationRendersItsQuotedStringInsteadOfLeavingAnEmptyTail() {
        String template = """
            Resources:
              OrdersStateMachine:
                Type: AWS::StepFunctions::StateMachine
                Properties:
                  DefinitionSubstitutions:
                    Fn::Transform:
                      Name: AWS::Include
                      Parameters:
                        Location: "   "
            """;

        AwsException e = assertThrows(AwsException.class, () -> mergedSubstitutions(template));
        assertEquals("Fn::Transform AWS::Include Location must be an s3://bucket/key URI, "
                + "got \"   \"", e.getMessage());
    }

    @Test
    void explicitNullParametersNamesTheTransformNodeInsteadOfLeavingAnEmptyTail() {
        String template = """
            Resources:
              OrdersStateMachine:
                Type: AWS::StepFunctions::StateMachine
                Properties:
                  DefinitionSubstitutions:
                    Fn::Transform:
                      Name: AWS::Include
                      Parameters:
            """;

        AwsException e = assertThrows(AwsException.class, () -> mergedSubstitutions(template));
        assertEquals("Fn::Transform AWS::Include Location must be an s3://bucket/key URI, "
                + "got {\"Name\":\"AWS::Include\",\"Parameters\":null}", e.getMessage());
    }

    @Test
    void stringParametersNamesTheTransformNodeInsteadOfLeakingItselfAsTheLocation() {
        String template = """
            Resources:
              OrdersStateMachine:
                Type: AWS::StepFunctions::StateMachine
                Properties:
                  DefinitionSubstitutions:
                    Fn::Transform:
                      Name: AWS::Include
                      Parameters: "s3://b/k"
            """;

        AwsException e = assertThrows(AwsException.class, () -> mergedSubstitutions(template));
        assertEquals("Fn::Transform AWS::Include Location must be an s3://bucket/key URI, "
                + "got {\"Name\":\"AWS::Include\",\"Parameters\":\"s3://b/k\"}", e.getMessage());
    }

    @Test
    void listParametersNamesTheTransformNodeInsteadOfLeakingItselfAsTheLocation() {
        String template = """
            Resources:
              OrdersStateMachine:
                Type: AWS::StepFunctions::StateMachine
                Properties:
                  DefinitionSubstitutions:
                    Fn::Transform:
                      Name: AWS::Include
                      Parameters: [1, 2]
            """;

        AwsException e = assertThrows(AwsException.class, () -> mergedSubstitutions(template));
        assertEquals("Fn::Transform AWS::Include Location must be an s3://bucket/key URI, "
                + "got {\"Name\":\"AWS::Include\",\"Parameters\":[1,2]}", e.getMessage());
    }
}
