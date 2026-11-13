package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The shared helpers on {@link ProvisionContext} that extracted provisioners build on. */
class ProvisionContextTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudFormationTemplateEngine engine;

    @BeforeEach
    void setUp() {
        // The engine resolves intrinsics; these tests are about what ProvisionContext asks it to
        // resolve, so it passes values through the way it would with no intrinsics present.
        engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
    }

    private ProvisionContext context(String priorPhysicalId) {
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    private static JsonNode props(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private ProvisionContext contextWithPriorProperties(String priorProperties) {
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", "prior-id",
                event -> { }, priorProperties == null ? null : props(priorProperties));
    }

    @Test
    void withPriorPhysicalIdChangesOnlyThePriorPhysicalId() throws Exception {
        PropertyChanges changes = PropertyChanges.between("AWS::SQS::Queue", true,
                props("{\"A\":1}"), props("{\"A\":2}"));
        ProvisionContext original = new ProvisionContext(engine, "eu-west-1", "123456789012", "stack",
                "old-id", event -> {}, props("{\"A\":1}"), changes);

        for (String replacement : new String[] {"new-id", null}) {
            ProvisionContext copy = original.withPriorPhysicalId(replacement);

            for (RecordComponent component : ProvisionContext.class.getRecordComponents()) {
                Object expected = component.getName().equals("priorPhysicalId")
                        ? replacement : component.getAccessor().invoke(original);
                assertSame(expected, component.getAccessor().invoke(copy), component.getName());
            }
        }
        assertEquals("old-id", original.priorPhysicalId());
    }

    @Test
    void previouslyDeclaredReadsThePreviousTemplatesProperties() {
        ProvisionContext ctx = contextWithPriorProperties("""
                {"Declared": {"Fn::GetAtt": ["Other", "Arn"]}, "ExplicitNull": null}
                """);

        assertTrue(ctx.previouslyDeclared("Declared"), "declared, whatever it resolved to");
        assertFalse(ctx.previouslyDeclared("ExplicitNull"), "null is how a template omits a property");
        assertFalse(ctx.previouslyDeclared("Absent"));
    }

    @Test
    void droppedPropertiesAreTheOnesThePreviousTemplateDeclaredAndThisOneLacks() {
        ProvisionContext ctx = contextWithPriorProperties("""
                {"Kept": "a", "Dropped": "b", "DroppedIf": {"Fn::If": ["C", "x", "y"]}, "ExplicitNull": null}
                """);

        assertEquals(Set.of("Dropped", "DroppedIf"),
                ctx.droppedProperties(props("{\"Kept\": \"a\", \"NewOne\": \"c\"}")),
                "a property the stack never declared, or declared as null, is not dropped");
        assertEquals(Set.of("Kept", "Dropped", "DroppedIf"), ctx.droppedProperties(props("{}")));
        assertEquals(Set.of("Kept", "Dropped", "DroppedIf"), ctx.droppedProperties(null));
    }

    /** One prior/current pair for the rule that {@code changes(...).dropped()} is {@code droppedProperties}. */
    private record DroppedCase(String label, String priorPhysicalId, String prior, String props,
                               List<String> expected) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static DroppedCase dropped(String label, String prior, String props, String... expected) {
        return new DroppedCase(label, "prior-id", prior, props, List.of(expected));
    }

    /** Expected lists are the result of today's droppedProperties, worked out by hand from its definition. */
    static Stream<DroppedCase> droppedCases() {
        return Stream.of(
                dropped("one name removed", "{\"A\":1,\"B\":2}", "{\"A\":1}", "B"),
                dropped("everything removed, empty props", "{\"A\":1,\"B\":2}", "{}", "A", "B"),
                dropped("everything removed, null props", "{\"A\":1,\"B\":2}", null, "A", "B"),
                dropped("a prior JSON null was never declared", "{\"A\":null,\"B\":2}", "{}", "B"),
                dropped("a JSON null now still counts as declared now", "{\"A\":1}", "{\"A\":null}"),
                dropped("same names in another order", "{\"A\":1,\"B\":2}", "{\"B\":2,\"A\":1}"),
                dropped("prior field order is kept", "{\"Z\":1,\"A\":2,\"M\":3}", "{\"A\":2}", "Z", "M"),
                dropped("no prior properties", null, "{}"),
                dropped("prior is an array", "[1,2]", "{}"),
                dropped("prior is a string", "\"x\"", "{}"),
                dropped("props is an array", "{\"A\":1}", "[\"A\"]", "A"),
                new DroppedCase("not an update", null, "{\"A\":1}", "{}", List.of()),
                dropped("empty prior, new property", "{}", "{\"A\":1}"),
                dropped("a prior Fn::If is declared", "{\"A\":{\"Fn::If\":[\"C\",\"x\",\"y\"]}}", "{\"B\":1}", "A"),
                dropped("a null in the middle is skipped", "{\"A\":1,\"B\":null,\"C\":3}", "{\"B\":1}", "A", "C"),
                new DroppedCase("a blank prior physical id is not an update", "  ", "{\"A\":1}", "{}", List.of()));
    }

    @ParameterizedTest
    @MethodSource("droppedCases")
    void changesDroppedEqualsDroppedPropertiesAndTheHandWrittenSet(DroppedCase c) {
        ProvisionContext ctx = new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack",
                c.priorPhysicalId(), event -> { }, c.prior() == null ? null : props(c.prior()));
        JsonNode current = c.props() == null ? null : props(c.props());

        Set<String> viaChanges = ctx.changes("AWS::SQS::Queue", current).dropped();

        assertEquals(c.expected(), List.copyOf(viaChanges), c.label());
        assertEquals(Set.copyOf(c.expected()), viaChanges, c.label());
        assertEquals(ctx.droppedProperties(current), viaChanges, c.label());
        assertEquals(List.copyOf(ctx.droppedProperties(current)), List.copyOf(viaChanges), c.label());
    }

    @Test
    void changesReturnsTheEngineSuppliedObjectWhenTheContextWasBuiltWithOne() {
        PropertyChanges supplied = PropertyChanges.between("AWS::SQS::Queue", true,
                props("{\"A\":1}"), props("{\"A\":2}"));
        ProvisionContext ctx = new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack",
                "prior-id", event -> { }, props("{\"Other\":1}"), supplied);

        assertSame(supplied, ctx.changes());
        assertSame(supplied, ctx.changes("AWS::SQS::Queue", props("{\"Different\":1}")),
                "the engine's statement wins over what the arguments would derive");
        assertSame(supplied, ctx.changes("AWS::S3::Bucket", null));
    }

    @Test
    void changesIsDerivedFromTheContextsOwnInputsWhenTheEngineSuppliedNone() {
        JsonNode prior = props("{\"QueueName\":\"q\",\"Old\":1,\"Same\":2}");
        JsonNode current = props("{\"QueueName\":\"q\",\"Same\":3,\"New\":4}");
        ProvisionContext ctx = new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack",
                "prior-id", event -> { }, prior);

        PropertyChanges derived = ctx.changes("AWS::SQS::Queue", current);

        assertNotNull(derived);
        assertEquals(PropertyChanges.between("AWS::SQS::Queue", true, prior, current), derived);
        assertEquals(PropertyChanges.Kind.UNCHANGED, derived.kindOf("QueueName"));
        assertTrue(derived.byName().get("QueueName").createOnly(), "the resource type given to changes() is used");
        assertEquals(Set.of("Old"), derived.dropped());
        assertEquals(Set.of("New"), derived.added());
        assertEquals(Set.of("Same"), derived.changed());
    }

    @Test
    void changesDerivedOnACreateMarksEveryPropertyAdded() {
        ProvisionContext ctx = new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");

        PropertyChanges derived = ctx.changes("AWS::SQS::Queue", props("{\"A\":1,\"B\":2}"));

        assertEquals(Set.of("A", "B"), derived.added());
        assertTrue(derived.dropped().isEmpty());
    }

    @Test
    void everyExistingConstructorYieldsNoSuppliedChanges() {
        ProvisionContext four = new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
        ProvisionContext five = new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", "prior-id");
        ProvisionContext seven = new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", "prior-id",
                event -> { }, props("{\"A\":1}"));

        assertNull(four.changes());
        assertNull(five.changes());
        assertNull(seven.changes());
        assertEquals("prior-id", seven.priorPhysicalId());
        assertEquals(props("{\"A\":1}"), seven.priorProperties());
    }

    @Test
    void nothingIsDroppedOnACreateOrWithoutAPreviousTemplate() {
        ProvisionContext create = new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", null,
                event -> { }, props("{\"Declared\": \"value\"}"));

        assertTrue(create.droppedProperties(props("{}")).isEmpty());
        assertTrue(contextWithPriorProperties(null).droppedProperties(props("{}")).isEmpty());
        assertTrue(context("prior-id").droppedProperties(props("{}")).isEmpty());
    }

    /** A resource the previous deployment never provisioned, such as one its Condition kept out, declared nothing. */
    @Test
    void aResourceWithoutAPriorPhysicalIdDeclaredNothing() {
        ProvisionContext ctx = new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", null,
                event -> { }, props("{\"Declared\": \"value\"}"));

        assertFalse(ctx.previouslyDeclared("Declared"));
    }

    @Test
    void nothingIsPreviouslyDeclaredWithoutAPreviousTemplate() {
        assertFalse(contextWithPriorProperties(null).previouslyDeclared("Declared"));
        assertFalse(context("prior-id").previouslyDeclared("Declared"),
                "the constructors without prior properties have none");
    }

    @Test
    void isUpdateReflectsWhetherAPriorPhysicalIdExists() {
        assertFalse(context(null).isUpdate(), "a first-time create has no prior physical id");
        assertFalse(context("  ").isUpdate(), "a blank id is not a prior provision");
        assertTrue(context("my-stack-Queue-abc123").isUpdate());
    }

    /**
     * The four-argument form is what the existing construction sites use, so it must keep meaning
     * "create".
     */
    @Test
    void theCreateOnlyConstructorIsNotAnUpdate() {
        ProvisionContext ctx = new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");

        assertFalse(ctx.isUpdate());
        assertNull(ctx.priorPhysicalId());
    }

    @Test
    void resolveOrDefaultReturnsTheValueOtherwiseTheDefault() {
        ProvisionContext ctx = context(null);

        assertEquals("HTTP", ctx.resolveOrDefault(props("{\"ProtocolType\":\"HTTP\"}"), "ProtocolType", "WEBSOCKET"));
        assertEquals("WEBSOCKET", ctx.resolveOrDefault(props("{}"), "ProtocolType", "WEBSOCKET"), "absent falls back");
        assertEquals("WEBSOCKET", ctx.resolveOrDefault(props("{\"ProtocolType\":\"\"}"), "ProtocolType", "WEBSOCKET"),
                "blank falls back");
        assertEquals("WEBSOCKET", ctx.resolveOrDefault(null, "ProtocolType", "WEBSOCKET"), "null props falls back");
    }

    @Test
    void resolveOptionalIntReadsAnIntegerAndLeavesWhatTheTemplateOmitsUnset() {
        ProvisionContext ctx = context(null);
        JsonNode size = props("{\"Ref\": \"Size\"}");
        when(engine.resolve(size)).thenReturn("3");

        assertEquals(42, ctx.resolveOptionalInt(props("{\"Port\": 42}"), "Port"), "a number");
        assertEquals(42, ctx.resolveOptionalInt(props("{\"Port\": \"42\"}"), "Port"), "a numeric string");
        assertEquals(7, ctx.resolveOptionalInt(props("{\"Port\": \" 7 \"}"), "Port"), "surrounding blanks are trimmed");
        assertEquals(3, ctx.resolveOptionalInt(props("{\"Port\": {\"Ref\": \"Size\"}}"), "Port"), "an intrinsic's result");
        assertNull(ctx.resolveOptionalInt(props("{}"), "Port"), "absent");
        assertNull(ctx.resolveOptionalInt(props("{\"Port\": null}"), "Port"), "explicitly null");
        assertNull(ctx.resolveOptionalInt(null, "Port"), "no properties at all");
        assertNull(ctx.resolveOptionalInt(props("{\"Port\": {\"Ref\": \"AWS::NoValue\"}}"), "Port"),
                "an intrinsic that resolves to blank, as AWS::NoValue does");
        for (String literal : List.of("\"\"", "\"ten\"", "\"4.5\"", "4.5", "true")) {
            AwsException e = assertThrows(AwsException.class,
                    () -> ctx.resolveOptionalInt(props("{\"Port\": " + literal + "}"), "Port"), literal);
            assertEquals("ValidationError", e.getErrorCode(), literal);
            assertEquals("Value of property Port must be an integer.", e.getMessage(), literal);
        }
    }

    @Test
    void stablePhysicalNamePrefersTheTemplatesName() {
        assertEquals("chosen",
                context("prior").stablePhysicalName("chosen", "Bucket", 63, true));
    }

    /**
     * The reason this helper exists: provision runs again on every UpdateStack, so generating
     * unconditionally would give an unnamed resource a new name each time, creating a second
     * resource and orphaning the first. These names are createOnly in the schemas, so an unchanged
     * template must keep its physical id.
     */
    @Test
    void stablePhysicalNameKeepsThePriorNameWhenTheTemplateGivesNone() {
        assertEquals("my-stack-Bucket-abc123def456",
                context("my-stack-Bucket-abc123def456").stablePhysicalName(null, "Bucket", 63, true));
        assertEquals("my-stack-Bucket-abc123def456",
                context("my-stack-Bucket-abc123def456").stablePhysicalName("  ", "Bucket", 63, true));
    }

    @Test
    void stablePhysicalNameGeneratesOnlyForAFirstCreate() {
        String generated = context(null).stablePhysicalName(null, "Bucket", 63, true);

        assertTrue(generated.startsWith("my-stack-bucket-"), generated);
        assertEquals(generated.toLowerCase(), generated, "lowercase was requested");
    }

    @Test
    void resolveTagsKeepsTemplateOrderAndDefaultsAMissingValue() {
        Map<String, String> tags = context(null).resolveTags(
                props("""
                        {
                          "Tags": [
                            {"Key": "zeta", "Value": "z"},
                            {"Key": "alpha", "Value": "a"},
                            {"Key": "novalue"}
                          ]
                        }
                        """),
                "Tags");

        assertEquals(List.of("zeta", "alpha", "novalue"), List.copyOf(tags.keySet()),
                "tag order follows the template, not hash order");
        assertEquals("z", tags.get("zeta"));
        assertEquals("", tags.get("novalue"), "a missing Value becomes empty, not null");
    }

    @Test
    void resolveTagsReadsTheMapShapeSomeTypesDeclare() {
        // AWS::Batch::* declare Tags as {key: value} rather than [{Key, Value}]; the map form
        // resolves each value and keeps template order too.
        Map<String, String> tags = context(null).resolveTags(
                props("""
                        {"Tags": {"zeta": "z", "alpha": "a", "empty": ""}}
                        """),
                "Tags");

        assertEquals(List.of("zeta", "alpha", "empty"), List.copyOf(tags.keySet()));
        assertEquals("z", tags.get("zeta"));
        assertEquals("", tags.get("empty"));
    }

    @Test
    void resolveTagsSkipsBlankKeysAndNeverReturnsNull() {
        Map<String, String> blankKeys = context(null).resolveTags(
                props("""
                        {
                          "Tags": [
                            {"Key": "", "Value": "v"},
                            {"Key": "  ", "Value": "v"},
                            {"Key": "ok", "Value": "v"}
                          ]
                        }
                        """),
                "Tags");
        assertEquals(Map.of("ok", "v"), blankKeys);

        assertEquals(Map.of(), context(null).resolveTags(props("{}"), "Tags"),
                "an absent property is an empty map");
        assertEquals(Map.of(), context(null).resolveTags(props("""
                {"Tags": "not-a-list"}
                """), "Tags"),
                "a non-array property is an empty map");
        assertEquals(Map.of(), context(null).resolveTags(null, "Tags"));
    }

    /**
     * The whole property is handed to the engine, which is what lets an intrinsic wrapping the list
     * work. Resolving only each entry would see an unresolved {@code Fn::If} object and yield
     * nothing.
     */
    @Test
    void resolveTagsResolvesAnIntrinsicWrappingTheWholeList() {
        JsonNode wrapped = props("""
                {
                  "Tags": {
                    "Fn::If": [
                      "isProd",
                      [{"Key": "env", "Value": "prod"}],
                      [{"Key": "env", "Value": "dev"}]
                    ]
                  }
                }
                """);
        JsonNode chosenBranch = props("""
                [{"Key": "env", "Value": "prod"}]
                """);
        when(engine.resolveNode(wrapped.get("Tags"))).thenReturn(chosenBranch);

        assertEquals(Map.of("env", "prod"), context(null).resolveTags(wrapped, "Tags"));
    }
    @Test
    void reusesPriorEntityIsTrueOnlyWhenTheDerivedNameMatchesThePriorId() {
        ProvisionContext create = new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
        assertFalse(create.reusesPriorEntity("anything"),
                "a create has no prior entity to reuse");

        ProvisionContext update = context("my-stack-Pipe-0123456789ab");
        assertTrue(update.reusesPriorEntity("my-stack-Pipe-0123456789ab"),
                "the same name means the same entity, so the provisioner must update it");
        assertFalse(update.reusesPriorEntity("explicitly-named"),
                "a replacing update derives a different name and must still create");
    }

    @Test
    void isGeneratedPhysicalNameRecognisesOnlyWhatGeneratePhysicalNameProducesForTheLogicalId() {
        ProvisionContext ctx = context(null);
        String generated = ctx.generatePhysicalName("AdminGroup", 128, false);
        assertTrue(ctx.isGeneratedPhysicalName(generated, "AdminGroup", 128, false), generated);

        ProvisionContext longStack = new ProvisionContext(engine, "us-east-1", "000000000000",
                "a-stack-name-long-enough-to-force-truncation", null);
        String longLogicalId = "AVeryLongLogicalIdThatPushesTheGeneratedNamePastItsLimit";
        String truncated = longStack.generatePhysicalName(longLogicalId, 64, false);
        assertEquals(64, truncated.length(), truncated);
        assertTrue(longStack.isGeneratedPhysicalName(truncated, longLogicalId, 64, false), truncated);

        assertFalse(ctx.isGeneratedPhysicalName("admin", "AdminGroup", 128, false), "an explicit name");
        assertFalse(ctx.isGeneratedPhysicalName(ctx.generatePhysicalName("OtherGroup", 128, false),
                "AdminGroup", 128, false), "another logical id's name");
        assertFalse(ctx.isGeneratedPhysicalName("my-stack-AdminGroup-0123456789zz", "AdminGroup", 128, false),
                "a suffix that is not hex");
        assertFalse(ctx.isGeneratedPhysicalName(null, "AdminGroup", 128, false));
    }


    /**
     * The whole property node is handed to {@code engine.resolveStringList}, so a list-valued
     * intrinsic ({@code Fn::Split} over a cross-stack {@code Fn::ImportValue}) expands to its real
     * values instead of collapsing to one comma-joined string (issue #2937). Resolving each element
     * in isolation, as the old copy did, would have yielded a single unusable entry.
     */
    @Test
    void resolveStringListRoutesListValuedIntrinsicsThroughTheEngine() {
        JsonNode props = props("""
                {
                  "SubnetIds": {
                    "Fn::Split": [",", {"Fn::ImportValue": "vpc-stack:PrivateSubnetIds"}]
                  }
                }
                """);
        when(engine.resolveStringList(props.get("SubnetIds")))
                .thenReturn(List.of("subnet-a", "subnet-b", "subnet-c"));

        assertEquals(List.of("subnet-a", "subnet-b", "subnet-c"),
                context(null).resolveStringList(props, "SubnetIds"));
    }

    @Test
    void resolveStringListReturnsAMutableEmptyListForAnAbsentPropertyWithoutCallingTheEngine() {
        List<String> values = context(null).resolveStringList(props("{}"), "ManagedPolicyArns");

        assertEquals(List.of(), values);
        values.add("still-mutable");
        verify(engine, never()).resolveStringList(any());
    }

    @Test
    void staleTagKeysListsTheCurrentKeysTheTemplateDropped() {
        Map<String, String> current = new LinkedHashMap<>();
        current.put("Keep", "same");
        current.put("Old", "value");
        current.put("Gone", "too");

        assertEquals(List.of("Old", "Gone"),
                ProvisionContext.staleTagKeys(current, Map.of("Keep", "same", "New", "added")));
        assertEquals(List.of(), ProvisionContext.staleTagKeys(null, Map.of("Keep", "same")),
                "no stored tags means nothing to untag");
        assertEquals(List.of("Keep", "Old", "Gone"), ProvisionContext.staleTagKeys(current, Map.of()),
                "a template with no Tags leaves the resource untagged");
    }
}
