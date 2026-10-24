package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Translates the supported explicit REST API authentication controls into inline OpenAPI. */
final class SamRestApiAuth {

    private static final Set<String> METHODS = Set.of(
            "get", "put", "post", "delete", "options", "head", "patch", "trace",
            "x-amazon-apigateway-any-method");
    private static final Set<String> SUPPORTED = Set.of("DefaultAuthorizer", "ApiKeyRequired");
    private static final Pattern OPENAPI_3 = Pattern.compile("3(\\.\\d)(\\.\\d)?");

    private final ObjectMapper mapper;

    SamRestApiAuth(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    void apply(JsonNode auth, JsonNode body) {
        if (auth.isMissingNode() || auth.isNull()) {
            return;
        }
        if (!auth.isObject()) {
            throw invalid("Auth must be an object.");
        }
        if (auth.isEmpty()) {
            return;
        }
        auth.fieldNames().forEachRemaining(name -> {
            if (!SUPPORTED.contains(name)) {
                throw invalid("Unsupported Auth property: " + name + ".");
            }
        });
        boolean iam = auth.has("DefaultAuthorizer");
        if (iam && (!auth.path("DefaultAuthorizer").isTextual()
                || !"AWS_IAM".equals(auth.path("DefaultAuthorizer").asText()))) {
            throw invalid("DefaultAuthorizer currently supports AWS_IAM only.");
        }
        if (auth.has("ApiKeyRequired") && !auth.path("ApiKeyRequired").isBoolean()) {
            throw invalid("ApiKeyRequired must be a boolean.");
        }
        boolean keyRequired = auth.path("ApiKeyRequired").asBoolean(false);
        if (!(body instanceof ObjectNode document) || !body.path("paths").isObject()) {
            throw invalid("Auth requires an inline DefinitionBody with a paths object.");
        }
        if (!iam && !keyRequired) {
            // Like SAM, an Auth that selects no default, such as ApiKeyRequired: false alone,
            // leaves the document as written once it has checked what SAM checks for any Auth.
            requireSamDocument(document);
            return;
        }
        // The template engine keeps only an intrinsic function's result, and the importer reads a
        // document naming both versions as OpenAPI 3: either way the defaults would be lost.
        if (CloudFormationTemplateEngine.callsIntrinsic(document)) {
            throw invalid("Auth requires a DefinitionBody written inline, not as an intrinsic function.");
        }
        if (document.has("swagger") && document.has("openapi")) {
            throw invalid("Auth requires a DefinitionBody that declares swagger or openapi, not both.");
        }
        boolean swagger = "2.0".equals(body.path("swagger").asText());
        if (!swagger && !body.path("openapi").asText().startsWith("3.0.")) {
            throw invalid("Auth currently supports Swagger 2.0 and OpenAPI 3.0 only.");
        }
        ObjectNode definitions = swagger ? object(document, "securityDefinitions")
                : object(object(document, "components"), "securitySchemes");
        if (iam) {
            addScheme(definitions, "AWS_IAM", true);
        }
        if (keyRequired) {
            addScheme(definitions, "api_key", false);
        }
        Iterator<Map.Entry<String, JsonNode>> paths = document.path("paths").fields();
        while (paths.hasNext()) {
            for (JsonNode path : branches(paths.next().getValue())) {
                if (!path.isObject() || path.has("$ref")) {
                    throw invalid("Auth requires inline path objects.");
                }
                Iterator<Map.Entry<String, JsonNode>> methods = path.fields();
                while (methods.hasNext()) {
                    Map.Entry<String, JsonNode> method = methods.next();
                    if (!METHODS.contains(method.getKey())) {
                        continue;
                    }
                    for (JsonNode branch : conditionalContents(method.getValue())) {
                        // SAM reads one level of Fn::If around an operation and skips a branch without
                        // an integration, so a nested Fn::If there is left as written, without defaults.
                        if (branch.has("Fn::If")) {
                            continue;
                        }
                        if (!(branch instanceof ObjectNode operation)) {
                            throw invalid("Auth requires inline operation objects.");
                        }
                        applyDefaults(operation, definitions, iam, keyRequired);
                    }
                }
            }
        }
    }

    private void applyDefaults(ObjectNode operation, ObjectNode definitions, boolean iam, boolean keyRequired) {
        // SAM starts from the operation's own security, never the document's root security.
        ArrayNode security = security(operation.path("security"), definitions);
        if (iam && !hasScheme(security, "AWS_IAM")) {
            security.add(mapper.createObjectNode().set("AWS_IAM", mapper.createArrayNode()));
        }
        if (keyRequired && !hasScheme(security, "api_key")) {
            security.add(mapper.createObjectNode().set("api_key", mapper.createArrayNode()));
        }
        operation.set("security", security);
    }

    /**
     * The checks SAM makes for any non-empty Auth before it applies a default: SwaggerEditor.is_valid
     * wants a truthy swagger or an openapi of 3.x, and the SwaggerEditor constructor wants every
     * paths entry, and each branch of an {@code Fn::If} around one, to be an object.
     */
    private void requireSamDocument(ObjectNode document) {
        JsonNode openapi = document.path("openapi");
        if (!SamTransformProcessor.isTruthy(document.path("swagger"))
                && !(SamTransformProcessor.isTruthy(openapi) && OPENAPI_3.matcher(openapi.asText()).matches())) {
            throw invalid("Auth requires a Swagger or OpenAPI 3 DefinitionBody.");
        }
        Iterator<JsonNode> paths = document.path("paths").elements();
        while (paths.hasNext()) {
            for (JsonNode path : conditionalContents(paths.next())) {
                if (!path.isObject()) {
                    throw invalid("Auth requires inline path objects.");
                }
            }
        }
    }

    /**
     * The values a path item may take when a default is added to its operations: as
     * {@link #conditionalContents}, refusing a nested {@code Fn::If}, as SAM does once it reads the
     * methods of the inner one. The template engine keeps only the chosen branch, so a default must
     * be written inside each branch, not beside the {@code Fn::If}.
     */
    private List<JsonNode> branches(JsonNode node) {
        List<JsonNode> branches = conditionalContents(node);
        for (JsonNode branch : branches) {
            if (branch.has("Fn::If")) {
                throw invalid("Auth does not support a nested Fn::If in paths.");
            }
        }
        return branches;
    }

    /**
     * SAM's get_conditional_contents: both branches of an {@code Fn::If} other than
     * {@code AWS::NoValue}, or the node itself.
     */
    private List<JsonNode> conditionalContents(JsonNode node) {
        if (!node.has("Fn::If")) {
            return List.of(node);
        }
        JsonNode arguments = node.get("Fn::If");
        if (!arguments.isArray()) {
            throw invalid("Fn::If must be a list.");
        }
        List<JsonNode> branches = new ArrayList<>();
        for (int i = 1; i < arguments.size(); i++) {
            JsonNode branch = arguments.get(i);
            if (!isNoValue(branch)) {
                branches.add(branch);
            }
        }
        return branches;
    }

    private static boolean isNoValue(JsonNode node) {
        return node.isObject() && node.size() == 1 && "AWS::NoValue".equals(node.path("Ref").asText());
    }

    private ArrayNode security(JsonNode value, ObjectNode definitions) {
        if (value.isMissingNode()) {
            return mapper.createArrayNode();
        }
        if (!value.isArray()) {
            throw invalid("Operation security must be an array.");
        }
        for (JsonNode requirement : value) {
            if (!requirement.isObject()) {
                throw invalid("Security requirements must be objects.");
            }
            requirement.fields().forEachRemaining(entry -> {
                JsonNode scopes = entry.getValue();
                JsonNode scheme = definitions.path(entry.getKey());
                if (!scopes.isArray() || !scheme.isObject()) {
                    throw invalid("Security requirements must reference defined schemes with an array of scopes.");
                }
                for (JsonNode scope : scopes) {
                    if (!scope.isTextual()) {
                        throw invalid("Security requirement scopes must be strings.");
                    }
                }
            });
        }
        return value.deepCopy();
    }

    private boolean hasScheme(ArrayNode security, String name) {
        for (JsonNode requirement : security) {
            if (requirement.has(name)) {
                return true;
            }
        }
        return false;
    }

    private void addScheme(ObjectNode definitions, String name, boolean iam) {
        if (definitions.has(name)) {
            if (!isScheme(definitions.get(name), iam)) {
                throw invalid("Auth conflicts with an existing security scheme.");
            }
            return;
        }
        ObjectNode scheme = mapper.createObjectNode();
        scheme.put("type", "apiKey");
        scheme.put("name", iam ? "Authorization" : "x-api-key");
        scheme.put("in", "header");
        if (iam) {
            scheme.put("x-amazon-apigateway-authtype", "awsSigv4");
        }
        definitions.set(name, scheme);
    }

    private boolean isScheme(JsonNode scheme, boolean iam) {
        return !CloudFormationTemplateEngine.callsIntrinsic(scheme)
                && "apiKey".equals(scheme.path("type").asText())
                && "header".equals(scheme.path("in").asText())
                && (iam ? "Authorization" : "x-api-key").equalsIgnoreCase(scheme.path("name").asText())
                && !scheme.has("x-amazon-apigateway-authorizer")
                && (iam ? "awsSigv4".equalsIgnoreCase(scheme.path("x-amazon-apigateway-authtype").asText())
                        : !scheme.has("x-amazon-apigateway-authtype"));
    }

    private ObjectNode object(ObjectNode parent, String name) {
        if (!parent.has(name)) {
            parent.set(name, mapper.createObjectNode());
        }
        if (!(parent.get(name) instanceof ObjectNode result)) {
            throw invalid(name + " must be an object.");
        }
        // The template engine keeps only an intrinsic function's result, so a generated scheme
        // written beside one would be dropped and the operations would name a scheme the importer
        // ignores.
        if (CloudFormationTemplateEngine.callsIntrinsic(result)) {
            throw invalid(name + " must be written inline, not as an intrinsic function.");
        }
        return result;
    }

    private AwsException invalid(String reason) {
        return new AwsException("ValidationError", "SAM AWS::Serverless::Api " + reason, 400);
    }
}
