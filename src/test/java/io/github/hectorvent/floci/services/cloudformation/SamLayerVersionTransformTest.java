package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SamLayerVersionTransformTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SamTransformProcessor processor = new SamTransformProcessor(mapper);

    private JsonNode template(String properties) throws Exception {
        return mapper.readTree("""
                {"Transform":"AWS::Serverless-2016-10-31","Resources":{
                  "Layer":{"Type":"AWS::Serverless::LayerVersion","Properties":%s}},
                 "Outputs":{"LayerArn":{"Value":{"Ref":"Layer"}}}}
                """.formatted(properties));
    }

    @Test
    void packagedLayerIsTransformedAndReferencesFollowItsVersion() throws Exception {
        JsonNode result = processor.expandSamTemplate(template("""
                {"ContentUri":{"Bucket":{"Ref":"Bucket"},"Key":"layer.zip","Version":"v1"},
                 "CompatibleRuntimes":["nodejs22.x"],"CompatibleArchitectures":["arm64"],
                 "Description":"shared code","LicenseInfo":"MIT","RetentionPolicy":"Delete"}
                """));
        String id = result.path("Resources").fieldNames().next();
        JsonNode layer = result.path("Resources").path(id);
        assertEquals("AWS::Lambda::LayerVersion", layer.path("Type").asText());
        assertTrue(id.matches("Layer[0-9a-f]{10}"));
        assertEquals(id, result.at("/Outputs/LayerArn/Value/Ref").asText());
        assertEquals("Layer", layer.at("/Properties/LayerName").asText());
        assertEquals("Bucket", layer.at("/Properties/Content/S3Bucket/Ref").asText());
        assertEquals("layer.zip", layer.at("/Properties/Content/S3Key").asText());
        assertEquals("v1", layer.at("/Properties/Content/S3ObjectVersion").asText());
        assertEquals("arm64", layer.at("/Properties/CompatibleArchitectures/0").asText());
        assertEquals("nodejs22.x", layer.at("/Properties/CompatibleRuntimes/0").asText());
        assertEquals("MIT", layer.at("/Properties/LicenseInfo").asText());
        assertEquals("Delete", layer.path("DeletionPolicy").asText());
    }

    @Test
    void stringContentUriAndDefaultRetentionAreSupported() throws Exception {
        JsonNode result = processor.expandSamTemplate(template("{\"ContentUri\":\"s3://layers/code.zip?versionId=abc\"}"));
        JsonNode layer = result.path("Resources").elements().next();
        assertEquals("AWS::Lambda::LayerVersion", layer.path("Type").asText());
        assertEquals("layers", layer.at("/Properties/Content/S3Bucket").asText());
        assertEquals("abc", layer.at("/Properties/Content/S3ObjectVersion").asText());
        assertEquals("Retain", layer.path("DeletionPolicy").asText());
    }

    @Test
    void wholeContentUriParameterReferenceIsResolvedBeforeSplitting() throws Exception {
        ObjectNode input = (ObjectNode) template("{\"ContentUri\":{\"Ref\":\"PackageUri\"}}");
        input.putObject("Parameters").putObject("PackageUri").put("Type", "String")
                .put("Default", "s3://layers/code.zip?versionId=v9");
        JsonNode content = processor.expandSamTemplate(input).path("Resources").elements().next()
                .path("Properties").path("Content");
        assertEquals("layers", content.path("S3Bucket").asText());
        assertEquals("code.zip", content.path("S3Key").asText());
        assertEquals("v9", content.path("S3ObjectVersion").asText());
    }

    @Test
    void changedContentProducesANewLogicalId() throws Exception {
        JsonNode first = processor.expandSamTemplate(template("{\"ContentUri\":\"s3://layers/one.zip\"}"));
        JsonNode second = processor.expandSamTemplate(template("{\"ContentUri\":\"s3://layers/two.zip\"}"));
        assertNotEquals(first.path("Resources").fieldNames().next(), second.path("Resources").fieldNames().next());
    }

    @Test
    void referencesDependenciesAndSubstitutionVariablesArePreserved() throws Exception {
        JsonNode input = mapper.readTree("""
                {"Transform":"AWS::Serverless-2016-10-31","Resources":{
                  "Layer":{"Type":"AWS::Serverless::LayerVersion","Condition":"UseLayer",
                    "Metadata":{"purpose":"shared"},"DeletionPolicy":"Delete",
                    "Properties":{"ContentUri":"s3://layers/code.zip"}},
                  "Consumer":{"Type":"AWS::Lambda::Function","DependsOn":["Layer"],
                    "Properties":{"Layers":[{"Ref":"Layer"}]}},
                  "Other":{"Type":"AWS::S3::Bucket","DependsOn":"Layer"}},
                 "Outputs":{
                   "Direct":{"Value":{"Fn::Sub":"${Layer}-${Layer.Arn}-${!Layer}"}},
                   "Shadowed":{"Value":{"Fn::Sub":["${Layer}-${Layer.Arn}",{"Layer":"literal"}]}},
                   "Attribute":{"Value":{"Fn::GetAtt":["Layer","Arn"]}},
                   "Dotted":{"Value":{"Fn::GetAtt":"Layer.Arn"}}}}
                """);
        JsonNode result = processor.expandSamTemplate(input);
        String id = result.path("Resources").properties().stream()
                .filter(entry -> "AWS::Lambda::LayerVersion".equals(entry.getValue().path("Type").asText()))
                .findFirst().orElseThrow().getKey();
        assertEquals("Layer", input.at("/Resources/Consumer/DependsOn/0").asText());
        assertEquals(id, result.at("/Resources/Consumer/DependsOn/0").asText());
        assertEquals(id, result.at("/Resources/Other/DependsOn").asText());
        assertEquals(id, result.at("/Resources/Consumer/Properties/Layers/0/Ref").asText());
        assertEquals("${" + id + "}-${" + id + ".Arn}-${!Layer}", result.at("/Outputs/Direct/Value/Fn::Sub").asText());
        assertEquals("${Layer}-${" + id + ".Arn}", result.at("/Outputs/Shadowed/Value/Fn::Sub/0").asText());
        assertEquals(id, result.at("/Outputs/Attribute/Value/Fn::GetAtt/0").asText());
        assertEquals(id + ".Arn", result.at("/Outputs/Dotted/Value/Fn::GetAtt").asText());
        assertEquals("UseLayer", result.path("Resources").path(id).path("Condition").asText());
        assertEquals("Delete", result.path("Resources").path(id).path("DeletionPolicy").asText());
    }

    @Test
    void metadataAndResourceRetentionDoNotChangeVersionIdentity() throws Exception {
        JsonNode first = template("{\"ContentUri\":\"s3://layers/code.zip\"}");
        JsonNode second = first.deepCopy();
        ((ObjectNode) second.at("/Resources/Layer"))
                .putObject("Metadata").put("note", "updated documentation");
        ((ObjectNode) second.at("/Resources/Layer"))
                .put("DeletionPolicy", "Delete");
        assertEquals(processor.expandSamTemplate(first).path("Resources").fieldNames().next(),
                processor.expandSamTemplate(second).path("Resources").fieldNames().next());
    }

    @Test
    void inheritedGlobalLayersFollowTheGeneratedLayer() throws Exception {
        ObjectNode input = (ObjectNode) template("{\"ContentUri\":\"s3://layers/code.zip\"}");
        input.putObject("Globals").putObject("Function").putArray("Layers").addObject().put("Ref", "Layer");
        ObjectNode function = ((ObjectNode) input.path("Resources")).putObject("Function");
        function.put("Type", "AWS::Serverless::Function");
        function.putObject("Properties").put("Runtime", "nodejs22.x").put("Handler", "index.handler")
                .put("InlineCode", "exports.handler=async()=>({})");
        JsonNode result = processor.expandSamTemplate(input);
        String id = result.at("/Outputs/LayerArn/Value/Ref").asText();
        assertEquals(id, result.at("/Resources/Function/Properties/Layers/0/Ref").asText());
    }

    @Test
    void listParameterReferencesRemainTypedForTheProvisioner() throws Exception {
        ObjectNode input = (ObjectNode) template("""
                {"ContentUri":"s3://layers/code.zip","CompatibleRuntimes":{"Ref":"Runtimes"},
                 "CompatibleArchitectures":{"Ref":"Architectures"}}
                """);
        input.putObject("Parameters").putObject("Runtimes").put("Type", "CommaDelimitedList")
                .put("Default", "nodejs22.x,python3.14");
        ((ObjectNode) input.path("Parameters")).putObject("Architectures").put("Type", "CommaDelimitedList")
                .put("Default", "arm64");
        JsonNode layer = processor.expandSamTemplate(input).path("Resources").elements().next();
        assertEquals("Runtimes", layer.at("/Properties/CompatibleRuntimes/Ref").asText());
        assertEquals("Architectures", layer.at("/Properties/CompatibleArchitectures/Ref").asText());
    }

    @Test
    void subOverlayVariablesWinOverSameNamedParameters() throws Exception {
        ObjectNode input = (ObjectNode) template("""
                {"ContentUri":{"Bucket":{"Fn::Sub":["${Bucket}-${Env}",{"Bucket":"layers"}]},
                 "Key":{"Fn::Sub":["${Env}/${Key}.zip",{"Key":"layer"}]}}}
                """);
        input.putObject("Parameters").putObject("Bucket").put("Type", "String").put("Default", "other-bucket");
        ((ObjectNode) input.path("Parameters")).putObject("Env").put("Type", "String").put("Default", "staging");
        JsonNode content = processor.expandSamTemplate(input).path("Resources").elements().next()
                .path("Properties").path("Content");
        // The deployed Content keeps the original !Sub expressions, so CloudFormation resolves the
        // parameter once at deploy and names the same !Sub declares locally stay local.
        assertEquals("${Bucket}-${Env}", content.at("/S3Bucket/Fn::Sub/0").asText());
        assertEquals("layers", content.at("/S3Bucket/Fn::Sub/1/Bucket").asText());
        assertEquals("${Env}/${Key}.zip", content.at("/S3Key/Fn::Sub/0").asText());
        assertEquals("layer", content.at("/S3Key/Fn::Sub/1/Key").asText());
    }

    @Test
    void deployedContentKeepsOriginalSubExpressionsForTheTemplateEngine() throws Exception {
        // A parameter whose text itself contains ${Env} must not be re-substituted at deploy:
        // CloudFormation resolves ${PackageKey} once, so the literal stays intact for the engine.
        ObjectNode input = (ObjectNode) template("""
                {"ContentUri":{"Bucket":"layers","Key":{"Fn::Sub":"${PackageKey}"}}}
                """);
        input.putObject("Parameters").putObject("PackageKey").put("Type", "String").put("Default", "layers/${Env}.zip");
        ((ObjectNode) input.path("Parameters")).putObject("Env").put("Type", "String").put("Default", "staging");
        JsonNode content = processor.expandSamTemplate(input).path("Resources").elements().next()
                .path("Properties").path("Content");
        assertEquals("${PackageKey}", content.at("/S3Key/Fn::Sub").asText());

        // The deployed bucket and key keep their !Sub forms, but the generated logical id hashes the
        // resolved copy, so two parameter values that select different packages get two distinct ids.
        ObjectNode first = (ObjectNode) template("""
                {"ContentUri":{"Bucket":{"Fn::Sub":"${Bucket}"},"Key":{"Fn::Sub":"${Key}"}}}
                """);
        addSubParams(first, "layers", "one.zip");
        ObjectNode second = (ObjectNode) template("""
                {"ContentUri":{"Bucket":{"Fn::Sub":"${Bucket}"},"Key":{"Fn::Sub":"${Key}"}}}
                """);
        addSubParams(second, "layers", "two.zip");
        assertNotEquals(processor.expandSamTemplate(first).path("Resources").fieldNames().next(),
                processor.expandSamTemplate(second).path("Resources").fieldNames().next());
    }

    private static void addSubParams(ObjectNode input, String bucket, String key) {
        input.putObject("Parameters").putObject("Bucket").put("Type", "String").put("Default", bucket);
        ((ObjectNode) input.path("Parameters")).putObject("Key").put("Type", "String").put("Default", key);
    }

    @Test
    void subLocalVariableNamedRefIsNotTreatedAsAResourceReference() throws Exception {
        ObjectNode input = (ObjectNode) mapper.readTree("""
                {"Transform":"AWS::Serverless-2016-10-31","Resources":{
                  "Consumer":{"Type":"AWS::Lambda::Function",
                    "Properties":{"Layers":[{"Ref":"Layer"}],
                      "Environment":{"Variables":{"Url":{"Fn::Sub":["${Name}-${Layer}-${Ref}",{"Name":"n","Ref":"Layer"}]}}}}},
                  "Layer":{"Type":"AWS::Serverless::LayerVersion",
                    "Properties":{"ContentUri":"s3://layers/code.zip"}}}}
                """);
        JsonNode result = processor.expandSamTemplate(input);
        String id = result.at("/Resources/Consumer/Properties/Layers/0/Ref").asText();
        assertTrue(id.startsWith("Layer") && id.length() > "Layer".length());
        JsonNode url = result.at("/Resources/Consumer/Properties/Environment/Variables/Url");
        // The real ${Layer} use follows the renamed layer, while ${Name} and ${Ref} stay local and
        // the map value under the variable named Ref remains the literal Layer, never the hashed id.
        assertEquals("${Name}-${" + id + "}-${Ref}", url.at("/Fn::Sub/0").asText());
        assertEquals("n", url.at("/Fn::Sub/1/Name").asText());
        assertEquals("Layer", url.at("/Fn::Sub/1/Ref").asText());
    }

    @Test
    void literalDependsOnValuesAreNotResourceDependencies() throws Exception {
        ObjectNode input = (ObjectNode) template("{\"ContentUri\":\"s3://layers/code.zip\"}");
        ObjectNode function = ((ObjectNode) input.path("Resources")).putObject("Function");
        function.put("Type", "AWS::Serverless::Function");
        ObjectNode properties = function.putObject("Properties");
        properties.put("Runtime", "nodejs22.x").put("Handler", "index.handler")
                .put("InlineCode", "exports.handler=async()=>({})");
        properties.putObject("Environment").putObject("Variables").put("DependsOn", "Layer");
        JsonNode result = processor.expandSamTemplate(input);
        assertEquals("Layer", result.at("/Resources/Function/Properties/Environment/Variables/DependsOn").asText());
    }

    @Test
    void unpackagedOrIncompleteContentIsRejectedBeforeProvisioning() throws Exception {
        for (String props : new String[]{"{}", "{\"ContentUri\":\"./layer\"}",
                "{\"ContentUri\":{\"Bucket\":\"layers\"}}",
                "{\"ContentUri\":\"s3://layers/code.zip\",\"RetentionPolicy\":\"invalid\"}"}) {
            JsonNode input = template(props);
            assertThrows(AwsException.class, () -> processor.expandSamTemplate(input));
        }
    }
}
