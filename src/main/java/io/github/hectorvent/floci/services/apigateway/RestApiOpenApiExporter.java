package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.model.ApiGatewayResource;
import io.github.hectorvent.floci.services.apigateway.model.Authorizer;
import io.github.hectorvent.floci.services.apigateway.model.DeploymentExportSnapshot;
import io.github.hectorvent.floci.services.apigateway.model.GatewayResponse;
import io.github.hectorvent.floci.services.apigateway.model.Integration;
import io.github.hectorvent.floci.services.apigateway.model.IntegrationResponse;
import io.github.hectorvent.floci.services.apigateway.model.MethodConfig;
import io.github.hectorvent.floci.services.apigateway.model.MethodResponse;
import io.github.hectorvent.floci.services.apigateway.model.Model;
import io.github.hectorvent.floci.services.apigateway.model.RequestValidator;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Serializes the supported REST configuration retained by a deployment. */
final class RestApiOpenApiExporter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private RestApiOpenApiExporter() {
    }

    static ObjectNode export(DeploymentExportSnapshot snapshot, boolean swagger,
                             boolean integrations, boolean authorizers, boolean apigateway) {
        ObjectNode document = JSON.createObjectNode();
        document.put(swagger ? "swagger" : "openapi", swagger ? "2.0" : "3.0.1");
        ObjectNode info = document.putObject("info");
        info.put("title", snapshot.api().getName());
        info.put("version", "1.0");
        put(info, "description", snapshot.api().getDescription());
        ObjectNode components = swagger ? document : document.putObject("components");
        ObjectNode schemas = components.putObject(swagger ? "definitions" : "schemas");
        for (Model model : snapshot.models()) {
            if (!"application/json".equals(model.getContentType())) {
                throw new AwsException("BadRequestException", "Only found non-JSON body models for " + model.getName(), 400);
            }
            try {
                JsonNode schema = JSON.readTree(model.getSchema());
                if (schema == null || !schema.isObject()) {
                    throw new AwsException("BadRequestException", "Invalid JSON body model: " + model.getName(), 400);
                }
                schemas.set(model.getName(), schema);
            } catch (JsonProcessingException | IllegalArgumentException e) {
                throw new AwsException("BadRequestException", "Invalid JSON body model: " + model.getName(), 400);
            }
        }
        ObjectNode securitySchemes = components.putObject(swagger ? "securityDefinitions" : "securitySchemes");
        Map<String, String> authorizerNames = new HashMap<>();
        for (Authorizer authorizer : snapshot.authorizers()) {
            authorizerNames.put(authorizer.getId(), authorizer.getName());
            ObjectNode scheme = securitySchemes.putObject(authorizer.getName());
            scheme.put("type", "apiKey");
            scheme.put("in", "header");
            String source = authorizer.getIdentitySource();
            scheme.put("name", source != null && source.startsWith("method.request.header.")
                    ? source.substring("method.request.header.".length()) : "Authorization");
            scheme.put("x-amazon-apigateway-authtype", "COGNITO_USER_POOLS".equals(authorizer.getType())
                    ? "cognito_user_pools" : "custom");
            if (authorizers) {
                ObjectNode definition = scheme.putObject("x-amazon-apigateway-authorizer");
                definition.put("type", authorizer.getType().toLowerCase(Locale.ROOT));
                put(definition, "authorizerUri", authorizer.getAuthorizerUri());
                put(definition, "identitySource", source);
                if (authorizer.getAuthorizerResultTtlInSeconds() != null) {
                    definition.put("authorizerResultTtlInSeconds",
                            Integer.parseInt(authorizer.getAuthorizerResultTtlInSeconds()));
                }
                if (authorizer.getProviderARNs() != null) {
                    definition.set("providerARNs", JSON.valueToTree(authorizer.getProviderARNs()));
                }
            }
        }
        Map<String, String> validators = new HashMap<>();
        if (apigateway && !snapshot.validators().isEmpty()) {
            ObjectNode definitions = document.putObject("x-amazon-apigateway-request-validators");
            for (RequestValidator validator : snapshot.validators()) {
                validators.put(validator.getId(), validator.getName());
                ObjectNode definition = definitions.putObject(validator.getName());
                definition.put("validateRequestBody", validator.isValidateRequestBody());
                definition.put("validateRequestParameters", validator.isValidateRequestParameters());
            }
        }
        String iamScheme = availableName(securitySchemes, "sigv4");
        String keyScheme = availableName(securitySchemes, "api_key");
        ObjectNode paths = document.putObject("paths");
        for (ApiGatewayResource resource : snapshot.resources().stream()
                .sorted(Comparator.comparing(ApiGatewayResource::getPath)).toList()) {
            if (resource.getResourceMethods().isEmpty()) {
                continue;
            }
            ObjectNode path = paths.putObject(resource.getPath());
            for (Map.Entry<String, MethodConfig> entry : new TreeMap<>(resource.getResourceMethods()).entrySet()) {
                MethodConfig method = entry.getValue();
                ObjectNode operation = path.putObject("ANY".equals(entry.getKey())
                        ? "x-amazon-apigateway-any-method" : entry.getKey().toLowerCase(Locale.ROOT));
                exportParameters(operation, method, swagger);
                exportResponses(operation, method, swagger);
                ObjectNode requirement = JSON.createObjectNode();
                if ("AWS_IAM".equals(method.getAuthorizationType())) {
                    ObjectNode scheme = securitySchemes.putObject(iamScheme);
                    scheme.put("type", "apiKey");
                    scheme.put("name", "Authorization");
                    scheme.put("in", "header");
                    scheme.put("x-amazon-apigateway-authtype", "awsSigv4");
                    requirement.putArray(iamScheme);
                } else if ("CUSTOM".equals(method.getAuthorizationType())
                        || "COGNITO_USER_POOLS".equals(method.getAuthorizationType())) {
                    String name = authorizerNames.get(method.getAuthorizerId());
                    if (name == null) {
                        throw new AwsException("BadRequestException",
                                "Missing authorizer for protected method " + entry.getKey() + " " + resource.getPath(), 400);
                    }
                    requirement.set(name, JSON.valueToTree(method.getAuthorizationScopes()));
                }
                if (method.isApiKeyRequired()) {
                    ObjectNode scheme = securitySchemes.putObject(keyScheme);
                    scheme.put("type", "apiKey");
                    scheme.put("name", "x-api-key");
                    scheme.put("in", "header");
                    requirement.putArray(keyScheme);
                }
                if (!requirement.isEmpty()) {
                    operation.putArray("security").add(requirement);
                }
                put(operation, "x-amazon-apigateway-request-validator", validators.get(method.getRequestValidatorId()));
                if (integrations && method.getMethodIntegration() != null) {
                    operation.set("x-amazon-apigateway-integration", exportIntegration(method.getMethodIntegration()));
                }
            }
        }
        if (apigateway) {
            if (!snapshot.api().getBinaryMediaTypes().isEmpty()) {
                document.set("x-amazon-apigateway-binary-media-types", JSON.valueToTree(snapshot.api().getBinaryMediaTypes()));
            }
            if (snapshot.api().getPolicy() != null) {
                try {
                    document.set("x-amazon-apigateway-policy", JSON.readTree(snapshot.api().getPolicy()));
                } catch (JsonProcessingException e) {
                    throw new AwsException("BadRequestException", "Invalid JSON API policy", 400);
                }
            }
            ObjectNode responses = document.putObject("x-amazon-apigateway-gateway-responses");
            for (GatewayResponse response : snapshot.gatewayResponses()) {
                if (!response.isDefaultResponse()) {
                    ObjectNode definition = responses.putObject(response.getResponseType());
                    put(definition, "statusCode", response.getStatusCode());
                    definition.set("responseParameters", JSON.valueToTree(response.getResponseParameters()));
                    definition.set("responseTemplates", JSON.valueToTree(response.getResponseTemplates()));
                }
            }
        }
        rewriteSchemaReferences(document, swagger);
        return document;
    }

    private static void exportParameters(ObjectNode operation, MethodConfig method, boolean swagger) {
        ArrayNode parameters = operation.putArray("parameters");
        for (Map.Entry<String, Boolean> entry : new TreeMap<>(method.getRequestParameters()).entrySet()) {
            String[] parts = entry.getKey().split("\\.", 4);
            if (parts.length != 4) {
                continue;
            }
            ObjectNode parameter = parameters.addObject();
            parameter.put("name", parts[3]);
            String location = "querystring".equals(parts[2]) ? "query" : parts[2];
            parameter.put("in", location);
            parameter.put("required", "path".equals(location) || Boolean.TRUE.equals(entry.getValue()));
            if (swagger) {
                parameter.put("type", "string");
            } else {
                parameter.putObject("schema").put("type", "string");
            }
        }
        if (!method.getRequestModels().isEmpty()) {
            if (swagger) {
                if (method.getRequestModels().values().stream().distinct().count() != 1) {
                    throw new AwsException("BadRequestException", "Swagger export requires one request body model per method", 400);
                }
                ObjectNode body = parameters.addObject();
                body.put("name", "body");
                body.put("in", "body");
                body.putObject("schema").put("$ref", "#/components/schemas/" + method.getRequestModels().values().iterator().next());
                operation.set("consumes", JSON.valueToTree(method.getRequestModels().keySet()));
            } else {
                ObjectNode content = operation.putObject("requestBody").putObject("content");
                method.getRequestModels().forEach((type, name) ->
                        content.putObject(type).putObject("schema").put("$ref", "#/components/schemas/" + name));
            }
        }
    }

    private static void exportResponses(ObjectNode operation, MethodConfig method, boolean swagger) {
        ObjectNode responses = operation.putObject("responses");
        for (MethodResponse response : new TreeMap<>(method.getMethodResponses()).values()) {
            ObjectNode definition = responses.putObject(response.statusCode());
            definition.put("description", response.statusCode() + " response");
            if (response.responseParameters() != null && !response.responseParameters().isEmpty()) {
                ObjectNode headers = definition.putObject("headers");
                response.responseParameters().forEach((name, required) -> {
                    String prefix = "method.response.header.";
                    if (name.startsWith(prefix)) {
                        ObjectNode header = headers.putObject(name.substring(prefix.length()));
                        if (swagger) {
                            header.put("type", "string");
                        } else {
                            header.putObject("schema").put("type", "string");
                            header.put("required", Boolean.TRUE.equals(required));
                        }
                    }
                });
            }
        }
        if (responses.isEmpty()) {
            responses.putObject("default").put("description", "Default response");
        }
    }

    private static ObjectNode exportIntegration(Integration integration) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", integration.getType().toLowerCase(Locale.ROOT));
        put(node, "uri", integration.getUri());
        put(node, "httpMethod", integration.getHttpMethod());
        put(node, "credentials", integration.getCredentials());
        put(node, "passthroughBehavior", integration.getPassthroughBehavior() != null
                ? integration.getPassthroughBehavior().toLowerCase(Locale.ROOT) : null);
        put(node, "contentHandling", integration.getContentHandling());
        put(node, "connectionType", integration.getConnectionType());
        put(node, "connectionId", integration.getConnectionId());
        put(node, "cacheNamespace", integration.getCacheNamespace());
        node.set("cacheKeyParameters", JSON.valueToTree(integration.getCacheKeyParameters()));
        node.set("requestParameters", JSON.valueToTree(integration.getRequestParameters()));
        node.set("requestTemplates", JSON.valueToTree(integration.getRequestTemplates()));
        if (integration.getTimeoutInMillis() != null) {
            node.put("timeoutInMillis", integration.getTimeoutInMillis());
        }
        put(node, "responseTransferMode", integration.getResponseTransferMode());
        if (integration.getTlsConfig() != null) {
            node.putObject("tlsConfig").put("insecureSkipVerification", integration.getTlsConfig().isInsecureSkipVerification());
        }
        ObjectNode responses = node.putObject("responses");
        for (IntegrationResponse response : new TreeMap<>(integration.getIntegrationResponses()).values()) {
            String pattern = response.selectionPattern();
            if ("default".equals(pattern)) {
                throw new AwsException("BadRequestException",
                        "Cannot export the reserved integration response selection pattern 'default'", 400);
            }
            String key = pattern == null || pattern.isEmpty() ? "default" : pattern;
            if (responses.has(key)) {
                throw new AwsException("BadRequestException",
                        "Cannot export duplicate integration response selection pattern '" + key + "'", 400);
            }
            ObjectNode definition = responses.putObject(key);
            definition.put("statusCode", response.statusCode());
            definition.set("responseParameters", JSON.valueToTree(response.responseParameters()));
            definition.set("responseTemplates", JSON.valueToTree(response.responseTemplates()));
            put(definition, "contentHandling", response.contentHandling());
        }
        return node;
    }

    private static void rewriteSchemaReferences(JsonNode node, boolean swagger) {
        if (node instanceof ObjectNode object) {
            JsonNode ref = object.get("$ref");
            String source = swagger ? "#/components/schemas/" : "#/definitions/";
            String target = swagger ? "#/definitions/" : "#/components/schemas/";
            if (ref != null && ref.isTextual() && ref.asText().startsWith(source)) {
                object.put("$ref", ref.asText().replace(source, target));
            }
        }
        for (JsonNode child : node) {
            rewriteSchemaReferences(child, swagger);
        }
    }

    private static String availableName(ObjectNode schemes, String preferred) {
        String name = preferred;
        int suffix = 1;
        while (schemes.has(name)) {
            name = preferred + "_" + suffix++;
        }
        return name;
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }
}
