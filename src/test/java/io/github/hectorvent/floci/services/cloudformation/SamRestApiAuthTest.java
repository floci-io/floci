package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SamRestApiAuthTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SamTransformProcessor processor = new SamTransformProcessor(mapper);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void translatesIamAndApiKeyWithoutChangingIntegrationPolicyOrSource(boolean swagger) throws Exception {
        ObjectNode template = template(swagger);
        JsonNode original = template.deepCopy();
        JsonNode inputBody = template.at("/Resources/Api/Properties/DefinitionBody");
        JsonNode result = processor.expandSamTemplate(template);
        JsonNode body = result.at("/Resources/Api/Properties/Body");

        assertEquals("AWS::ApiGateway::RestApi", result.at("/Resources/Api/Type").asText());
        assertEquals(inputBody.at("/paths/~1items/get/x-amazon-apigateway-integration"),
                body.at("/paths/~1items/get/x-amazon-apigateway-integration"));
        assertEquals(inputBody.get("x-amazon-apigateway-policy"), body.get("x-amazon-apigateway-policy"));
        assertEquals(original, template);
        assertEquals(original.at("/Resources/Permission"), result.at("/Resources/Permission"));
        for (String method : new String[] {"get", "x-amazon-apigateway-any-method"}) {
            JsonNode security = body.path("paths").path("/items").path(method).path("security");
            assertEquals(2, security.size());
            assertTrue(security.toString().contains("AWS_IAM"));
            assertTrue(security.toString().contains("api_key"));
        }
        JsonNode schemes = swagger ? body.path("securityDefinitions")
                : body.path("components").path("securitySchemes");
        assertEquals("awsSigv4", schemes.path("AWS_IAM").path("x-amazon-apigateway-authtype").asText());
        assertEquals("x-api-key", schemes.path("api_key").path("name").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "null", "{}", "{\"ApiKeyRequired\":false}"})
    void relocatesDeclaredSchemesWithoutAuthDefaults(String auth) throws Exception {
        ObjectNode template = template(false);
        ObjectNode properties = (ObjectNode) template.at("/Resources/Api/Properties");
        if ("missing".equals(auth)) {
            properties.remove("Auth");
        } else {
            properties.set("Auth", mapper.readTree(auth));
        }
        ObjectNode body = (ObjectNode) properties.get("DefinitionBody");
        body.set("components", mapper.readTree("""
                {"schemas":{"Example":{"type":"string"}},"securitySchemes":{"old":{"type":"apiKey"}}}
                """));
        JsonNode definitions = existingSchemes();
        body.set("securityDefinitions", definitions.deepCopy());
        body.set("security", mapper.readTree("[{\"existingKey\":[]}]"));
        JsonNode original = template.deepCopy();
        JsonNode expanded = processor.expandSamTemplate(template).at("/Resources/Api/Properties/Body");
        assertFalse(expanded.has("securityDefinitions"));
        assertEquals(definitions, expanded.at("/components/securitySchemes"));
        assertEquals(body.at("/components/schemas"), expanded.at("/components/schemas"));
        assertEquals(body.get("security"), expanded.get("security"));
        assertEquals(body.get("paths"), expanded.get("paths"));
        assertEquals(original, template);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.0.1", "3.1.0", "3.0"})
    void relocatesSchemesIntoAbsentOrNullComponents(String version) throws Exception {
        ObjectNode template = template(false);
        ObjectNode properties = (ObjectNode) template.at("/Resources/Api/Properties");
        properties.remove("Auth");
        ObjectNode body = (ObjectNode) properties.get("DefinitionBody");
        body.put("openapi", version);
        for (boolean nullComponents : new boolean[] {false, true}) {
            if (nullComponents) {
                body.putNull("components");
            } else {
                body.remove("components");
            }
            body.set("securityDefinitions", existingSchemes());
            JsonNode expanded = processor.expandSamTemplate(template).at("/Resources/Api/Properties/Body");
            assertEquals(existingSchemes(), expanded.at("/components/securitySchemes"));
            assertFalse(expanded.has("securityDefinitions"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"swagger", "both", "invalidVersion", "nullVersion", "emptyDefinitions",
            "nullDefinitions", "missingDefinitions", "intrinsic"})
    void leavesDocumentsOutsideRelocationScopeUnchanged(String shape) throws Exception {
        ObjectNode template = template(false);
        ObjectNode properties = (ObjectNode) template.at("/Resources/Api/Properties");
        properties.remove("Auth");
        ObjectNode body = (ObjectNode) properties.get("DefinitionBody");
        body.set("securityDefinitions", existingSchemes());
        switch (shape) {
            case "swagger" -> {
                body.remove("openapi");
                body.put("swagger", "2.0");
            }
            case "both" -> body.put("swagger", "2.0");
            case "invalidVersion" -> body.put("openapi", "3.invalid");
            case "nullVersion" -> body.putNull("openapi");
            case "emptyDefinitions" -> body.set("securityDefinitions", mapper.createObjectNode());
            case "nullDefinitions" -> body.putNull("securityDefinitions");
            case "missingDefinitions" -> body.remove("securityDefinitions");
            default -> properties.set("DefinitionBody", mapper.readTree("{\"Ref\":\"Document\"}"));
        }
        JsonNode expanded = processor.expandSamTemplate(template).at("/Resources/Api/Properties/Body");
        assertEquals(properties.get("DefinitionBody"), expanded);
    }

    @Test
    void leavesOperationsAndRootSecurityAsWrittenWhenNoDefaultApplies() throws Exception {
        ObjectNode template = template(false);
        ObjectNode properties = (ObjectNode) template.at("/Resources/Api/Properties");
        properties.set("Auth", mapper.readTree("{\"ApiKeyRequired\":false}"));
        ObjectNode body = (ObjectNode) properties.get("DefinitionBody");
        setSchemes(body, false, existingSchemes());
        body.set("security", mapper.readTree("[{\"existingSigV4\":[]},{\"existingKey\":[]}]"));
        JsonNode expanded = processor.expandSamTemplate(template).at("/Resources/Api/Properties/Body");
        assertEquals(body.get("security"), expanded.get("security"));
        for (String method : new String[] {"get", "x-amazon-apigateway-any-method"}) {
            assertFalse(expanded.path("paths").path("/items").path(method).has("security"), method);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rootSecurityIsNotMergedIntoTheSamDefaults(boolean swagger) throws Exception {
        ObjectNode template = template(swagger);
        ObjectNode body = (ObjectNode) template.at("/Resources/Api/Properties/DefinitionBody");
        setSchemes(body, swagger, existingSchemes());
        body.set("security", mapper.readTree("[{\"existingSigV4\":[]},{\"existingKey\":[]}]"));
        JsonNode original = template.deepCopy();
        JsonNode expanded = processor.expandSamTemplate(template).at("/Resources/Api/Properties/Body");
        assertEquals(original, template);
        assertEquals(body.get("security"), expanded.get("security"));
        for (String method : new String[] {"get", "x-amazon-apigateway-any-method"}) {
            assertEquals(mapper.readTree("[{\"AWS_IAM\":[]},{\"api_key\":[]}]"),
                    expanded.path("paths").path("/items").path(method).path("security"), method);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"Authorizers\":{}}", "{\"Authorizers\":{\"custom\":{\"FunctionArn\":\"example\"}}}",
            "{\"ResourcePolicy\":{}}", "{\"UsagePlan\":{}}",
            "{\"InvokeRole\":\"CALLER_CREDENTIALS\"}", "{\"AddDefaultAuthorizerToCorsPreflight\":false}",
            "{\"DefaultAuthorizer\":\"CUSTOM\"}", "{\"DefaultAuthorizer\":null}",
            "{\"ApiKeyRequired\":\"true\"}", "\"AWS_IAM\""
    })
    void refusesUnsupportedAuthInsteadOfRemovingIt(String auth) throws Exception {
        ObjectNode template = template(false);
        ((ObjectNode) template.at("/Resources/Api/Properties")).set("Auth", mapper.readTree(auth));
        JsonNode original = template.deepCopy();
        AwsException failure = assertThrows(AwsException.class, () -> processor.expandSamTemplate(template));
        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals(original, template);
    }

    @Test
    void dropsUndeclaredOpenApiCustomSchemeAndAddsTheNamedDefault() throws Exception {
        ObjectNode template = template(false);
        ObjectNode body = (ObjectNode) template.at("/Resources/Api/Properties/DefinitionBody");
        ((ObjectNode) body.at("/paths/~1items/get")).set("security",
                mapper.readTree("[{\"custom\":[\"read\"]}]"));
        body.set("components", mapper.readTree("""
                {"securitySchemes":{"custom":{"type":"apiKey","name":"Authorization","in":"header",
                  "x-amazon-apigateway-authorizer":{"type":"request","authorizerUri":"unused"}}}}
                """));
        JsonNode original = template.deepCopy();
        JsonNode expanded = processor.expandSamTemplate(template).at("/Resources/Api/Properties/Body");
        assertEquals(original, template);
        assertTrue(expanded.at("/components/securitySchemes/custom").isMissingNode());
        assertEquals(mapper.readTree("[{\"custom\":[\"read\"]},{\"AWS_IAM\":[]},{\"api_key\":[]}]"),
                expanded.at("/paths/~1items/get/security"));
    }

    @ParameterizedTest
    @CsvSource({"false,iam,false", "false,key,false", "false,none,true",
            "true,iam,true", "true,key,true", "true,none,true"})
    void customSchemePruningDependsOnDocumentAndSelectedDefault(boolean swagger, String defaults,
                                                               boolean retained) throws Exception {
        ObjectNode template = template(swagger);
        ObjectNode properties = (ObjectNode) template.at("/Resources/Api/Properties");
        properties.set("Auth", mapper.readTree(switch (defaults) {
            case "iam" -> "{\"DefaultAuthorizer\":\"AWS_IAM\"}";
            case "key" -> "{\"ApiKeyRequired\":true}";
            default -> "{\"ApiKeyRequired\":false}";
        }));
        ObjectNode body = (ObjectNode) properties.get("DefinitionBody");
        JsonNode custom = mapper.readTree("""
                {"type":"apiKey","name":"Authorization","in":"header",
                  "x-amazon-apigateway-authorizer":{"type":"request","authorizerUri":"unused"}}
                """);
        setSchemes(body, swagger, mapper.createObjectNode().set("custom", custom));
        JsonNode security = mapper.readTree("[{\"custom\":[\"read\"]},{}]");
        body.set("security", security.deepCopy());
        ((ObjectNode) body.at("/paths/~1items/get")).set("security", security.deepCopy());
        JsonNode original = template.deepCopy();
        JsonNode expanded = processor.expandSamTemplate(template).at("/Resources/Api/Properties/Body");
        JsonNode schemes = swagger ? expanded.path("securityDefinitions")
                : expanded.at("/components/securitySchemes");
        assertEquals(retained, schemes.has("custom"));
        assertEquals(security, expanded.path("security"));
        assertEquals(security.get(0), expanded.at("/paths/~1items/get/security/0"));
        assertEquals(security.get(1), expanded.at("/paths/~1items/get/security/1"));
        assertEquals(original, template);
    }

    @Test
    void keepsCustomSchemeAlsoDeclaredInSamSecurityDefinitions() throws Exception {
        ObjectNode template = template(false);
        ObjectNode body = (ObjectNode) template.at("/Resources/Api/Properties/DefinitionBody");
        JsonNode definitions = mapper.readTree("""
                {"custom":{"type":"apiKey","name":"Authorization","in":"header",
                  "x-amazon-apigateway-authorizer":{"type":"request","authorizerUri":"unused"}}}
                """);
        setSchemes(body, false, definitions.deepCopy());
        body.set("securityDefinitions", definitions.deepCopy());
        JsonNode expanded = processor.expandSamTemplate(template).at("/Resources/Api/Properties/Body");
        assertEquals(definitions.get("custom"), expanded.at("/components/securitySchemes/custom"));
        assertFalse(expanded.has("securityDefinitions"));
    }

    @ParameterizedTest
    @CsvSource({"false,iam,false", "false,key,false", "false,none,true",
            "true,iam,true", "true,key,true", "true,none,true"})
    void replacesInlineSchemesOnlyForOpenApiDefaults(boolean swagger, String defaults, boolean retained)
            throws Exception {
        ObjectNode template = template(swagger);
        ObjectNode properties = (ObjectNode) template.at("/Resources/Api/Properties");
        properties.set("Auth", mapper.readTree(switch (defaults) {
            case "iam" -> "{\"DefaultAuthorizer\":\"AWS_IAM\"}";
            case "key" -> "{\"ApiKeyRequired\":true}";
            default -> "{\"ApiKeyRequired\":false}";
        }));
        ObjectNode body = (ObjectNode) properties.get("DefinitionBody");
        JsonNode schemes = mapper.readTree("""
                {"inlineKey":{"type":"apiKey","name":"x-inline-key","in":"header"},
                 "inlineOAuth":{"type":"oauth2","flows":{}}}
                """);
        setSchemes(body, swagger, (ObjectNode) schemes.deepCopy());
        body.set("security", mapper.readTree("[{\"inlineOAuth\":[\"read\"]}]"));
        ((ObjectNode) body.at("/paths/~1items/get")).set("security",
                mapper.readTree("[{\"inlineKey\":[]}]"));
        JsonNode original = template.deepCopy();
        JsonNode expanded = processor.expandSamTemplate(template).at("/Resources/Api/Properties/Body");
        JsonNode result = swagger ? expanded.path("securityDefinitions")
                : expanded.at("/components/securitySchemes");
        assertEquals(retained, result.has("inlineKey"));
        assertEquals(retained, result.has("inlineOAuth"));
        assertEquals(body.get("security"), expanded.get("security"));
        assertEquals(body.at("/paths/~1items/get/security/0"), expanded.at("/paths/~1items/get/security/0"));
        assertEquals(original, template);
    }

    @Test
    void replacesInlineMetadataWithDeclaredDefinitionsAndPreservesOtherComponents() throws Exception {
        ObjectNode template = template(false);
        ObjectNode body = (ObjectNode) template.at("/Resources/Api/Properties/DefinitionBody");
        body.set("components", mapper.readTree("""
                {"schemas":{"Example":{"type":"string"}},
                 "securitySchemes":{"declared":{"type":"apiKey","name":"old","in":"header"}}}
                """));
        JsonNode declared = mapper.readTree("""
                {"declared":{"type":"apiKey","name":"new","in":"header"},
                 "onlyDeclared":{"type":"oauth2","flows":{}}}
                """);
        body.set("securityDefinitions", declared.deepCopy());
        JsonNode original = template.deepCopy();
        JsonNode expanded = processor.expandSamTemplate(template).at("/Resources/Api/Properties/Body");
        assertEquals(declared.get("declared"), expanded.at("/components/securitySchemes/declared"));
        assertEquals(declared.get("onlyDeclared"), expanded.at("/components/securitySchemes/onlyDeclared"));
        assertEquals(body.at("/components/schemas"), expanded.at("/components/schemas"));
        assertFalse(expanded.has("securityDefinitions"));
        assertTrue(expanded.at("/components/securitySchemes/AWS_IAM").isObject());
        assertTrue(expanded.at("/components/securitySchemes/api_key").isObject());
        assertEquals(original, template);
    }

    @ParameterizedTest
    @ValueSource(strings = {"custom-authentication", "awsSigv4"})
    void existingSchemeMetadataDoesNotSuppressTheNamedIamDefault(String authType) throws Exception {
        ObjectNode template = template(false);
        ObjectNode body = (ObjectNode) template.at("/Resources/Api/Properties/DefinitionBody");
        ObjectNode scheme = mapper.createObjectNode()
                .put("type", "apiKey").put("name", "Authorization").put("in", "header")
                .put("x-amazon-apigateway-authtype", authType);
        body.set("components", mapper.createObjectNode().set("securitySchemes",
                mapper.createObjectNode().set("existing", scheme)));
        ((ObjectNode) body.at("/paths/~1items/get")).set("security",
                mapper.readTree("[{\"existing\":[]}]"));
        JsonNode original = template.deepCopy();
        JsonNode expanded = processor.expandSamTemplate(template).at("/Resources/Api/Properties/Body");
        assertEquals(original, template);
        assertTrue(expanded.at("/components/securitySchemes/existing").isMissingNode());
        assertEquals(mapper.readTree("[{\"existing\":[]},{\"AWS_IAM\":[]},{\"api_key\":[]}]"),
                expanded.at("/paths/~1items/get/security"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[{\"missing\":[]}]", "[{\"existing\":\"scope\"}]", "[{\"existing\":[42]}]"})
    void refusesUndefinedSchemesAndMalformedScopes(String security) throws Exception {
        ObjectNode template = template(false);
        ObjectNode body = (ObjectNode) template.at("/Resources/Api/Properties/DefinitionBody");
        body.set("components", mapper.readTree("""
                {"securitySchemes":{"existing":{"type":"oauth2","flows":{}}}}
                """));
        ((ObjectNode) body.at("/paths/~1items/get")).set("security", mapper.readTree(security));
        JsonNode original = template.deepCopy();
        assertThrows(AwsException.class, () -> processor.expandSamTemplate(template));
        assertEquals(original, template);
    }

    @Test
    void refusesExternalDefinitionAndConflictingGeneratedScheme() throws Exception {
        ObjectNode external = template(false);
        ObjectNode properties = (ObjectNode) external.at("/Resources/Api/Properties");
        properties.remove("DefinitionBody");
        properties.put("DefinitionUri", "s3://example/spec.json");
        assertThrows(AwsException.class, () -> processor.expandSamTemplate(external));

        ObjectNode conflicting = template(false);
        ObjectNode body = (ObjectNode) conflicting.at("/Resources/Api/Properties/DefinitionBody");
        body.set("components", mapper.readTree("""
                {"securitySchemes":{"AWS_IAM":{"type":"apiKey","name":"x-api-key","in":"header"}}}
                """));
        assertThrows(AwsException.class, () -> processor.expandSamTemplate(conflicting));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void addsTheDefaultsInsideEachFnIfBranch(boolean swagger) throws Exception {
        ObjectNode template = template(swagger);
        ObjectNode paths = (ObjectNode) template.at("/Resources/Api/Properties/DefinitionBody/paths");
        ObjectNode items = (ObjectNode) paths.get("/items");
        JsonNode get = items.get("get");
        items.set("get", conditional(get, mapper.readTree("{\"Ref\":\"AWS::NoValue\"}")));
        items.set("put", conditional(get, items.get("x-amazon-apigateway-any-method")));
        paths.set("/conditional", conditional(mapper.createObjectNode().set("post", get),
                mapper.readTree("{\"Ref\":\"AWS::NoValue\"}")));
        JsonNode original = template.deepCopy();
        JsonNode expanded = processor.expandSamTemplate(template).at("/Resources/Api/Properties/Body/paths");
        assertEquals(original, template);
        JsonNode defaults = mapper.readTree("[{\"AWS_IAM\":[]},{\"api_key\":[]}]");
        for (String branch : new String[] {
                "/~1items/get/Fn::If/1", "/~1items/put/Fn::If/1", "/~1items/put/Fn::If/2",
                "/~1conditional/Fn::If/1/post"}) {
            assertEquals(defaults, expanded.at(branch + "/security"), branch);
        }
        assertFalse(expanded.at("/~1items/get").has("security"));
        assertFalse(expanded.at("/~1items/put").has("security"));
        assertEquals(mapper.readTree("{\"Ref\":\"AWS::NoValue\"}"), expanded.at("/~1items/get/Fn::If/2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"Fn::If\":[\"Enabled\",{\"Fn::If\":[\"Other\",{\"get\":{}},{}]},{}]}",
            "{\"get\":{\"Fn::If\":\"Enabled\"}}", "{\"get\":{\"Fn::If\":[\"Enabled\",\"get\",{}]}}"
    })
    void refusesFnIfShapesItCannotReachInside(String pathItem) throws Exception {
        ObjectNode template = template(false);
        ((ObjectNode) template.at("/Resources/Api/Properties/DefinitionBody/paths"))
                .set("/conditional", mapper.readTree(pathItem));
        JsonNode original = template.deepCopy();
        AwsException failure = assertThrows(AwsException.class, () -> processor.expandSamTemplate(template));
        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals(original, template);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void leavesANestedFnIfAroundAnOperationAsWrittenAsSamDoes(boolean swagger) throws Exception {
        ObjectNode template = template(swagger);
        ObjectNode items = (ObjectNode) template.at("/Resources/Api/Properties/DefinitionBody/paths/~1items");
        JsonNode get = items.get("get");
        JsonNode nested = conditional(get, mapper.readTree("{\"Ref\":\"AWS::NoValue\"}"));
        items.set("get", conditional(nested, get));
        JsonNode branches = processor.expandSamTemplate(template)
                .at("/Resources/Api/Properties/Body/paths/~1items/get/Fn::If");
        assertEquals(nested, branches.get(1));
        assertEquals(mapper.readTree("[{\"AWS_IAM\":[]},{\"api_key\":[]}]"), branches.at("/2/security"));
    }

    @Test
    void changesNothingWhenNoDefaultIsSelected() throws Exception {
        ObjectNode template = template(false);
        ObjectNode properties = (ObjectNode) template.at("/Resources/Api/Properties");
        properties.set("Auth", mapper.readTree("{\"ApiKeyRequired\":false}"));
        ((ObjectNode) properties.get("DefinitionBody")).put("openapi", "3.1.0");
        ObjectNode paths = (ObjectNode) properties.at("/DefinitionBody/paths");
        JsonNode get = paths.at("/~1items/get");
        paths.set("/nested", mapper.createObjectNode().set("get",
                conditional(conditional(get, mapper.readTree("{\"Ref\":\"AWS::NoValue\"}")), get)));
        ((ObjectNode) paths.at("/~1items/get")).set("security", mapper.readTree("[{\"undefined\":[]}]"));
        paths.set("/nestedPath", conditional(conditional(mapper.createObjectNode().set("get", get),
                mapper.readTree("{\"Ref\":\"AWS::NoValue\"}")), mapper.readTree("{\"Ref\":\"AWS::NoValue\"}")));
        paths.set("/referenced", mapper.readTree("{\"$ref\":\"#/components/pathItems/shared\"}"));
        JsonNode expanded = processor.expandSamTemplate(template).at("/Resources/Api/Properties/Body");
        assertEquals(properties.get("DefinitionBody"), expanded);
    }

    @ParameterizedTest
    @ValueSource(strings = {"noVersion", "pathsIf", "textPath", "textFnIf"})
    void refusesWithoutADefaultWhatSamRefusesForAnyAuth(String shape) throws Exception {
        ObjectNode template = template(false);
        ObjectNode properties = (ObjectNode) template.at("/Resources/Api/Properties");
        properties.set("Auth", mapper.readTree("{\"ApiKeyRequired\":false}"));
        ObjectNode body = (ObjectNode) properties.get("DefinitionBody");
        switch (shape) {
            case "noVersion" -> body.remove("openapi");
            case "pathsIf" -> body.set("paths", conditional(body.get("paths"), body.get("paths")));
            case "textPath" -> ((ObjectNode) body.get("paths")).put("/text", "not a path item");
            default -> ((ObjectNode) body.get("paths")).set("/text", mapper.readTree("{\"Fn::If\":\"Enabled\"}"));
        }
        JsonNode original = template.deepCopy();
        AwsException failure = assertThrows(AwsException.class, () -> processor.expandSamTemplate(template));
        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals(original, template);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "false|components|{\"Fn::If\":[\"Enabled\",{\"securitySchemes\":{}},{\"Ref\":\"AWS::NoValue\"}]}",
            "false|components|{\"securitySchemes\":{\"Fn::If\":[\"Enabled\",{},{}]}}",
            "true|securityDefinitions|{\"Fn::If\":[\"Enabled\",{},{}]}"
    })
    void refusesSchemeMapsWrittenAsIntrinsics(boolean swagger, String member, String value) throws Exception {
        ObjectNode template = template(swagger);
        ((ObjectNode) template.at("/Resources/Api/Properties/DefinitionBody")).set(member, mapper.readTree(value));
        JsonNode original = template.deepCopy();
        AwsException failure = assertThrows(AwsException.class, () -> processor.expandSamTemplate(template));
        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals(original, template);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bothVersions", "bodyIf", "iamSchemeIf", "keySchemeIf"})
    void refusesBodiesWhoseDefaultsWouldNotReachTheImporter(String shape) throws Exception {
        ObjectNode template = template(false);
        ObjectNode body = (ObjectNode) template.at("/Resources/Api/Properties/DefinitionBody");
        JsonNode arguments = mapper.readTree("[\"Enabled\",{},{}]");
        switch (shape) {
            case "bothVersions" -> body.put("swagger", "2.0");
            case "bodyIf" -> body.set("Fn::If", arguments);
            default -> {
                boolean iam = "iamSchemeIf".equals(shape);
                ObjectNode scheme = mapper.createObjectNode().put("type", "apiKey").put("in", "header")
                        .put("name", iam ? "Authorization" : "x-api-key");
                if (iam) {
                    scheme.put("x-amazon-apigateway-authtype", "awsSigv4");
                }
                scheme.set("Fn::If", arguments);
                setSchemes(body, false, mapper.createObjectNode().set(iam ? "AWS_IAM" : "api_key", scheme));
            }
        }
        JsonNode original = template.deepCopy();
        AwsException failure = assertThrows(AwsException.class, () -> processor.expandSamTemplate(template));
        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals(original, template);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"ApiKeyRequired\":false}", "{\"DefaultAuthorizer\":\"AWS_IAM\"}"})
    void refusesADefinitionBodyWrittenAsFnIfAsSamDoes(String auth) throws Exception {
        ObjectNode template = template(false);
        ObjectNode properties = (ObjectNode) template.at("/Resources/Api/Properties");
        properties.set("Auth", mapper.readTree(auth));
        JsonNode body = properties.get("DefinitionBody");
        properties.set("DefinitionBody", conditional(body, body));
        JsonNode original = template.deepCopy();
        AwsException failure = assertThrows(AwsException.class, () -> processor.expandSamTemplate(template));
        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals(original, template);
    }

    private ObjectNode conditional(JsonNode whenTrue, JsonNode whenFalse) {
        ObjectNode conditional = mapper.createObjectNode();
        conditional.putArray("Fn::If").add("Enabled").add(whenTrue.deepCopy()).add(whenFalse.deepCopy());
        return conditional;
    }

    private ObjectNode existingSchemes() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"existingSigV4":{"type":"apiKey","name":"Authorization","in":"header",
                                  "x-amazon-apigateway-authtype":"awsSigv4"},
                 "existingKey":{"type":"apiKey","name":"x-api-key","in":"header"}}
                """);
    }

    private void setSchemes(ObjectNode body, boolean swagger, ObjectNode schemes) {
        if (swagger) {
            body.set("securityDefinitions", schemes);
        } else {
            body.set("components", mapper.createObjectNode().set("securitySchemes", schemes));
        }
    }

    private ObjectNode template(boolean swagger) throws Exception {
        ObjectNode template = (ObjectNode) mapper.readTree("""
                {
                  "Transform":"AWS::Serverless-2016-10-31",
                  "Resources":{
                    "Api":{"Type":"AWS::Serverless::Api","Properties":{
                      "StageName":"test","Auth":{"DefaultAuthorizer":"AWS_IAM","ApiKeyRequired":true},
                      "DefinitionBody":{
                        "openapi":"3.0.1","info":{"title":"example","version":"1"},
                        "x-amazon-apigateway-policy":{"Version":"2012-10-17","Statement":[]},
                        "paths":{"/items":{
                          "parameters":[],
                          "get":{"x-amazon-apigateway-integration":{
                            "type":"aws_proxy","httpMethod":"POST",
                            "uri":{"Fn::Sub":"arn:${AWS::Partition}:apigateway:${AWS::Region}:lambda:path/2015-03-31/functions/${Function.Arn}:live/invocations"},
                            "credentials":{"Fn::GetAtt":["ExecutionRole","Arn"]}
                          }},
                          "x-amazon-apigateway-any-method":{"x-amazon-apigateway-integration":{"type":"mock"}}
                        }}
                      }
                    }},
                    "Permission":{"Type":"AWS::Lambda::Permission","Properties":{
                      "Action":"lambda:InvokeFunction","FunctionName":{"Ref":"Function"},
                      "Principal":"apigateway.amazonaws.com","SourceArn":{"Fn::Sub":"arn:${AWS::Partition}:execute-api:${AWS::Region}:${AWS::AccountId}:${Api}/*"}
                    }}
                  }
                }
                """);
        if (swagger) {
            ObjectNode body = (ObjectNode) template.at("/Resources/Api/Properties/DefinitionBody");
            body.remove("openapi");
            body.put("swagger", "2.0");
        }
        return template;
    }
}
