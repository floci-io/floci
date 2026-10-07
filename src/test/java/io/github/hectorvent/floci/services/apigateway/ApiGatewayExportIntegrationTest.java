package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.github.hectorvent.floci.services.apigateway.model.ApiGatewayResource;
import io.github.hectorvent.floci.services.apigateway.model.Authorizer;
import io.github.hectorvent.floci.services.apigateway.model.Deployment;
import io.github.hectorvent.floci.services.apigateway.model.MethodConfig;
import io.github.hectorvent.floci.services.apigateway.model.RequestValidator;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ApiGatewayExportIntegrationTest {
    private static final String REGION = "us-east-1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TEMPLATE = "{\"quote\":\"a\\\"b\",\"path\":\"$input.path('$')\"}";

    @Inject
    ApiGatewayService service;
    private String apiId;
    private String resourceId;
    private String deploymentId;
    private final List<String> imported = new ArrayList<>();

    @BeforeEach
    void createDeployedApi() {
        apiId = service.createRestApi(REGION, Map.of("name", "export-fixture", "description", "Synthetic export",
                "binaryMediaTypes", List.of("application/octet-stream"),
                "policy", "{\"Version\":\"2012-10-17\",\"Statement\":[]}")).getId();
        String rootId = service.getResources(REGION, apiId).get(0).getId();
        resourceId = service.createResource(REGION, apiId, rootId, Map.of("pathPart", "widgets")).getId();
        service.createModel(REGION, apiId, Map.of("name", "Widget", "contentType", "application/json",
                "schema", "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}"));
        RequestValidator validator = service.createRequestValidator(REGION, apiId,
                Map.of("name", "body-and-parameters", "validateRequestBody", true, "validateRequestParameters", true));
        service.putMethod(REGION, apiId, resourceId, "POST", Map.of("authorizationType", "AWS_IAM", "apiKeyRequired", true,
                "requestValidatorId", validator.getId(), "requestModels", Map.of("application/json", "Widget"),
                "requestParameters", Map.of("method.request.header.X-Trace", true, "method.request.querystring.limit", false)));
        service.putIntegration(REGION, apiId, resourceId, "POST", Map.of("type", "MOCK", "timeoutInMillis", 9000,
                "requestTemplates", Map.of("application/json", TEMPLATE)));
        service.putMethodResponse(REGION, apiId, resourceId, "POST", "201",
                Map.of("responseParameters", Map.of("method.response.header.X-Trace", false)));
        service.putIntegrationResponse(REGION, apiId, resourceId, "POST", "201", Map.of("selectionPattern", "",
                "responseParameters", Map.of("method.response.header.X-Trace", "'trace'"),
                "responseTemplates", Map.of("application/json", TEMPLATE), "contentHandling", "CONVERT_TO_TEXT"));
        Authorizer authorizer = service.createAuthorizer(REGION, apiId, Map.of("name", "TokenAuth", "type", "TOKEN",
                "identitySource", "method.request.header.Authorization", "authorizerResultTtlInSeconds", 0,
                "authorizerUri", "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:auth/invocations"));
        service.putMethod(REGION, apiId, rootId, "ANY", Map.of("authorizationType", "CUSTOM", "authorizerId", authorizer.getId()));
        service.putIntegration(REGION, apiId, rootId, "ANY", Map.of("type", "MOCK"));
        service.putGatewayResponse(REGION, apiId, "DEFAULT_4XX", Map.of("responseTemplates", Map.of("application/json", TEMPLATE)));
        service.putMethod(REGION, apiId, resourceId, "GET", Map.of("authorizationType", "NONE"));
        service.putIntegration(REGION, apiId, resourceId, "GET", Map.of("type", "MOCK",
                "requestTemplates", Map.of("application/json", "{\"statusCode\":200}")));
        service.putMethodResponse(REGION, apiId, resourceId, "GET", "200", Map.of());
        service.putIntegrationResponse(REGION, apiId, resourceId, "GET", "200",
                Map.of("responseTemplates", Map.of("application/json", "{\"exported\":true}")));
        deploymentId = service.createDeployment(REGION, apiId, Map.of("stageName", "dev")).id();
    }

    @AfterEach
    void cleanup() {
        imported.forEach(id -> service.deleteRestApi(REGION, id));
        service.deleteRestApi(REGION, apiId);
    }

    @ParameterizedTest
    @CsvSource({"oas30,application/json", "swagger,application/json", "oas30,application/yaml", "swagger,application/yaml"})
    void exportedStageReimportsSupportedConfiguration(String type, String accepts) throws Exception {
        Response response = given().accept(accepts).queryParam("extensions", "apigateway")
                .get(exportPath("dev", type)).then().statusCode(200)
                .header("Content-Type", containsString(accepts)).header("Content-Disposition", containsString("attachment"))
                .extract().response();
        JsonNode document = parse(response.asString(), accepts);
        assertEquals(type.equals("swagger") ? "2.0" : "3.0.1", document.path(type.equals("swagger") ? "swagger" : "openapi").asText());
        assertEquals(TEMPLATE, document.at("/paths/~1widgets/post/x-amazon-apigateway-integration/requestTemplates/application~1json").asText());
        assertTrue(document.at("/paths/~1/x-amazon-apigateway-any-method").isObject());
        String restoredId = given().contentType(accepts).queryParam("mode", "import").body(response.asByteArray())
                .post("/restapis").then().statusCode(201).extract().path("id");
        imported.add(restoredId);
        ApiGatewayResource widgets = service.getResources(REGION, restoredId).stream()
                .filter(resource -> "/widgets".equals(resource.getPath())).findFirst().orElseThrow();
        MethodConfig method = widgets.getResourceMethods().get("POST");
        assertEquals(List.of("application/octet-stream"), service.getRestApi(REGION, restoredId).getBinaryMediaTypes());
        assertEquals(JSON.readTree(service.getRestApi(REGION, apiId).getPolicy()),
                JSON.readTree(service.getRestApi(REGION, restoredId).getPolicy()));
        assertEquals("AWS_IAM", method.getAuthorizationType());
        assertTrue(method.isApiKeyRequired());
        assertEquals(Map.of("method.request.header.X-Trace", true, "method.request.querystring.limit", false), method.getRequestParameters());
        assertEquals(Map.of("application/json", "Widget"), method.getRequestModels());
        assertEquals("body-and-parameters", service.getRequestValidator(REGION, restoredId, method.getRequestValidatorId()).getName());
        assertEquals(Map.of("method.response.header.X-Trace", false), method.getMethodResponses().get("201").responseParameters());
        assertEquals(TEMPLATE, method.getMethodIntegration().getRequestTemplates().get("application/json"));
        assertEquals(9000, method.getMethodIntegration().getTimeoutInMillis());
        assertEquals("CONVERT_TO_TEXT", method.getMethodIntegration().getIntegrationResponses().get("201").contentHandling());
        ApiGatewayResource root = service.getResources(REGION, restoredId).stream()
                .filter(resource -> "/".equals(resource.getPath())).findFirst().orElseThrow();
        MethodConfig any = root.getResourceMethods().get("ANY");
        assertEquals("CUSTOM", any.getAuthorizationType());
        assertEquals("TokenAuth", service.getAuthorizer(REGION, restoredId, any.getAuthorizerId()).getName());
        assertEquals(TEMPLATE, service.getGatewayResponse(REGION, restoredId, "DEFAULT_4XX").getResponseTemplates().get("application/json"));
        service.createDeployment(REGION, restoredId, Map.of("stageName", "dev"));
        given().accept("application/json").get("/restapis/" + restoredId + "/dev/_user_request_/widgets")
                .then().statusCode(200).body("exported", equalTo(true));
        assertTrue(JSON.readTree(service.getModel(REGION, restoredId, "Widget").getSchema()).path("properties").has("name"));
    }

    @Test
    void extensionSelectionAndDefaultJsonAreRespected() throws Exception {
        JsonNode basic = JSON.readTree(given().get(exportPath("dev", "oas30")).then().statusCode(200).extract().asString());
        assertFalse(basic.at("/paths/~1widgets/post").has("x-amazon-apigateway-integration"));
        assertFalse(basic.at("/components/securitySchemes/TokenAuth").has("x-amazon-apigateway-authorizer"));
        assertFalse(basic.has("x-amazon-apigateway-request-validators"));
        JsonNode integrations = JSON.readTree(given().queryParam("extensions", "integrations")
                .get(exportPath("dev", "oas30")).then().statusCode(200).extract().asString());
        assertTrue(integrations.at("/paths/~1widgets/post").has("x-amazon-apigateway-integration"));
        assertFalse(integrations.at("/components/securitySchemes/TokenAuth").has("x-amazon-apigateway-authorizer"));
        JsonNode auth = JSON.readTree(given().queryParam("extensions", "authorizers")
                .get(exportPath("dev", "oas30")).then().statusCode(200).extract().asString());
        assertTrue(auth.at("/components/securitySchemes/TokenAuth").has("x-amazon-apigateway-authorizer"));
        assertFalse(auth.at("/paths/~1widgets/post").has("x-amazon-apigateway-integration"));
    }

    @Test
    void exportUsesSelectedDeploymentAndSurvivesDescriptionPatch() throws Exception {
        service.updateDeployment(REGION, apiId, deploymentId, List.of(Map.of("op", "replace", "path", "/description", "value", "new description")));
        service.updateModel(REGION, apiId, "Widget", List.of(Map.of("op", "replace", "path", "/schema", "value", "{\"type\":\"integer\"}")));
        assertEquals("object", JSON.readTree(given().queryParam("extensions", "apigateway").get(exportPath("dev", "oas30"))
                .then().statusCode(200).extract().asString()).at("/components/schemas/Widget/type").asText());
        service.deleteMethod(REGION, apiId, resourceId, "POST");
        assertTrue(JSON.readTree(given().get(exportPath("dev", "oas30")).then().statusCode(200).extract().asString())
                .at("/paths/~1widgets/post").isObject());
        service.deleteMethod(REGION, apiId, resourceId, "GET");
        service.createDeployment(REGION, apiId, Map.of("stageName", "next"));
        assertFalse(JSON.readTree(given().get(exportPath("next", "oas30")).then().statusCode(200).extract().asString())
                .path("paths").has("/widgets"));
        service.updateStage(REGION, apiId, "dev", List.of(Map.of("op", "replace", "path", "/deploymentId", "value",
                service.getStage(REGION, apiId, "next").getDeploymentId())));
        assertFalse(JSON.readTree(given().get(exportPath("dev", "oas30")).then().statusCode(200).extract().asString())
                .path("paths").has("/widgets"));
    }

    @Test
    void invalidFormatsAndMissingResourcesReturnManagementErrors() {
        given().get(exportPath("dev", "invalid")).then().statusCode(400);
        given().queryParam("extensions", "invalid").get(exportPath("dev", "oas30")).then().statusCode(400);
        given().get(exportPath("missing", "oas30")).then().statusCode(404);
        given().get("/restapis/missing/stages/dev/exports/oas30").then().statusCode(404);
    }

    @Test
    void deploymentSnapshotSurvivesJacksonStorageRoundTrip() throws Exception {
        Deployment original = service.getDeployment(REGION, apiId, deploymentId);
        Deployment restored = JSON.readValue(JSON.writeValueAsString(original), Deployment.class);
        assertEquals(original.exportSnapshot(), restored.exportSnapshot());
        Deployment legacy = JSON.readValue("{\"id\":\"old\",\"description\":\"old\",\"createdDate\":1}", Deployment.class);
        assertNull(legacy.exportSnapshot());
    }

    @Test
    void nonJsonModelsFailAtExportWithoutPreventingDeployment() {
        service.createModel(REGION, apiId, Map.of("name", "XmlBody", "contentType", "application/xml", "schema", "{}"));
        service.createDeployment(REGION, apiId, Map.of("stageName", "xml"));
        given().get(exportPath("xml", "oas30")).then().statusCode(400).body("message", containsString("non-JSON"));
        given().get("/restapis/" + apiId + "/deployments/" + deploymentId).then().statusCode(200)
                .body("exportSnapshot", org.hamcrest.Matchers.nullValue());
    }

    private String exportPath(String stage, String type) {
        return "/restapis/" + apiId + "/stages/" + stage + "/exports/" + type;
    }

    private JsonNode parse(String body, String type) throws Exception {
        return type.equals("application/yaml") ? new YAMLMapper().readTree(body) : JSON.readTree(body);
    }
}
