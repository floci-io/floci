package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigateway.model.ApiGatewayResource;
import io.github.hectorvent.floci.services.apigateway.model.Authorizer;
import io.github.hectorvent.floci.services.apigateway.model.Deployment;
import io.github.hectorvent.floci.services.apigateway.model.RestApi;
import io.github.hectorvent.floci.services.apigateway.model.Stage;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for the REST API Gateway core types. RestApi, Resource, Method,
 * Deployment, Stage and Authorizer are one coupled family (a resource belongs to an api, a method
 * to a resource, a stage to a deployment), so they share one provisioner over {@link
 * ApiGatewayService}. RestApi also accepts an OpenAPI {@code Body}/{@code BodyS3Location}, resolved
 * through {@link OpenApiDocuments} (shared with {@code AWS::ApiGatewayV2::Api}), which is why
 * {@link S3Service} and {@link ObjectMapper} are injected.
 */
@ApplicationScoped
public class ApiGatewayRestApiCfnProvisioner implements CfnResourceProvisioner {

    private static final String REST_API = "AWS::ApiGateway::RestApi";
    private static final String RESOURCE = "AWS::ApiGateway::Resource";
    private static final String METHOD = "AWS::ApiGateway::Method";
    private static final String DEPLOYMENT = "AWS::ApiGateway::Deployment";
    private static final String STAGE = "AWS::ApiGateway::Stage";
    private static final String AUTHORIZER = "AWS::ApiGateway::Authorizer";

    private final ApiGatewayService apiGatewayService;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;

    @Inject
    public ApiGatewayRestApiCfnProvisioner(ApiGatewayService apiGatewayService, S3Service s3Service,
                                           ObjectMapper objectMapper) {
        this.apiGatewayService = apiGatewayService;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(REST_API, RESOURCE, METHOD, DEPLOYMENT, STAGE, AUTHORIZER);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case REST_API -> provisionRestApi(r, props, ctx);
            case RESOURCE -> provisionResource(r, props, ctx);
            case METHOD -> provisionMethod(r, props, ctx);
            case DEPLOYMENT -> provisionDeployment(r, props, ctx);
            case STAGE -> provisionStage(r, props, ctx);
            case AUTHORIZER -> provisionAuthorizer(r, props, ctx);
            default -> throw new IllegalStateException("Unhandled type: " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        // Only the RestApi has a backing delete; deleting it cascades to its resources, methods,
        // deployments, stages and authorizers, so the child types own no separate delete, the same
        // as the legacy switch (their delete fell through to a no-op).
        if (REST_API.equals(resourceType)) {
            // Tolerate an API already removed out of band so DeleteStack does not fail on it;
            // deleteRestApi resolves the id first and raises NotFoundException when it is gone.
            CfnDeletes.safeDelete("REST API", physicalId,
                    () -> apiGatewayService.deleteRestApi(region, physicalId), "NotFoundException");
        }
    }

    private void provisionRestApi(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        CloudFormationTemplateEngine engine = ctx.engine();
        String name = ctx.resolveOptional(props, "Name");
        if (name == null || name.isBlank()) {
            name = ctx.generatePhysicalName(r.getLogicalId(), 255, false);
        }
        String description = ctx.resolveOptional(props, "Description");
        Map<String, Object> req = new HashMap<>();
        req.put("name", name);
        req.put("description", description);

        if (props != null && props.has("EndpointConfiguration")) {
            JsonNode epNode = props.get("EndpointConfiguration");
            Map<String, Object> epReq = new HashMap<>();
            epReq.put("types", ctx.resolveStringList(epNode, "Types"));
            epReq.put("vpcEndpointIds", ctx.resolveStringList(epNode, "VpcEndpointIds"));
            req.put("endpointConfiguration", epReq);
        }

        RestApi api = apiGatewayService.createRestApi(region, req);
        r.setPhysicalId(api.getId());
        r.getAttributes().put("RestApiId", api.getId());
        r.getAttributes().put("RootResourceId",
                apiGatewayService.getResources(region, api.getId()).get(0).getId());

        // A declared Body or BodyS3Location is the whole OpenAPI document. Measured on real AWS
        // it becomes the RestApi's Body with no synthesized Resource or Method, so putRestApi plus
        // applyOpenApiSpec is the only place that turns it into resources and methods. putRestApi
        // overwrites Name and Description from the document's info, so the declared Name and
        // Description (null clearing an undeclared Description) are re-applied immediately after.
        JsonNode openApiDocument = OpenApiDocuments.resolve(props, engine, s3Service, objectMapper);
        if (openApiDocument != null) {
            apiGatewayService.putRestApi(region, api.getId(), "overwrite", openApiDocument.toString());
            apiGatewayService.updateRestApi(region, api.getId(),
                    List.of(replacePatchOp("/name", name), replacePatchOp("/description", description)));
        }
    }

    /**
     * A {@code replace} patch operation for {@link ApiGatewayService#updateRestApi}, allowing a
     * {@code null} value ({@code Map.of} rejects one) so an undeclared property can still be
     * cleared rather than left at whatever {@code putRestApi} last wrote to it.
     */
    private Map<String, String> replacePatchOp(String path, String value) {
        Map<String, String> op = new HashMap<>();
        op.put("op", "replace");
        op.put("path", path);
        op.put("value", value);
        return op;
    }

    private void provisionResource(StackResource r, JsonNode props, ProvisionContext ctx) {
        String apiId = ctx.resolveOptional(props, "RestApiId");
        String parentId = ctx.resolveOptional(props, "ParentId");
        String pathPart = ctx.resolveOptional(props, "PathPart");

        Map<String, Object> req = new HashMap<>();
        req.put("pathPart", pathPart);

        ApiGatewayResource res = apiGatewayService.createResource(ctx.region(), apiId, parentId, req);
        r.setPhysicalId(res.getId());
        r.getAttributes().put("ResourceId", res.getId());
    }

    private void provisionAuthorizer(StackResource r, JsonNode props, ProvisionContext ctx) {
        String apiId = ctx.resolveOptional(props, "RestApiId");
        Map<String, Object> req = new HashMap<>();
        req.put("name", ctx.resolveOptional(props, "Name"));
        req.put("type", ctx.resolveOptional(props, "Type"));
        req.put("authorizerUri", ctx.resolveOptional(props, "AuthorizerUri"));
        req.put("identitySource", ctx.resolveOptional(props, "IdentitySource"));
        String ttl = ctx.resolveOptional(props, "AuthorizerResultTtlInSeconds");
        if (ttl != null) {
            req.put("authorizerResultTtlInSeconds", ttl);
        }
        Authorizer authorizer = apiGatewayService.createAuthorizer(ctx.region(), apiId, req);
        r.setPhysicalId(authorizer.getId());
        r.getAttributes().put("AuthorizerId", authorizer.getId());
    }

    private void provisionMethod(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        CloudFormationTemplateEngine engine = ctx.engine();
        String apiId = ctx.resolveOptional(props, "RestApiId");
        String resourceId = ctx.resolveOptional(props, "ResourceId");
        String httpMethod = ctx.resolveOptional(props, "HttpMethod");

        Map<String, Object> req = new HashMap<>();
        req.put("authorizationType", ctx.resolveOrDefault(props, "AuthorizationType", "NONE"));
        String authorizerId = ctx.resolveOptional(props, "AuthorizerId");
        if (authorizerId != null) {
            req.put("authorizerId", authorizerId);
        }
        req.put("apiKeyRequired", Boolean.parseBoolean(ctx.resolveOrDefault(props, "ApiKeyRequired", "false")));

        apiGatewayService.putMethod(region, apiId, resourceId, httpMethod, req);
        r.setPhysicalId(apiId + "-" + resourceId + "-" + httpMethod);

        if (props != null && props.has("MethodResponses")) {
            JsonNode responses = engine.resolveNode(props.get("MethodResponses"));
            if (responses != null && responses.isArray()) {
                for (JsonNode response : responses) {
                    String statusCode = ctx.resolveOptional(response, "StatusCode");
                    Map<String, Object> responseReq = new HashMap<>();
                    Map<String, Boolean> responseParameters = new LinkedHashMap<>();
                    JsonNode parameters = engine.resolveNode(response.path("ResponseParameters"));
                    if (parameters != null && parameters.isObject()) {
                        parameters.fields().forEachRemaining(entry -> responseParameters.put(
                                entry.getKey(), Boolean.parseBoolean(engine.resolve(entry.getValue()))));
                    }
                    responseReq.put("responseParameters", responseParameters);
                    apiGatewayService.putMethodResponse(region, apiId, resourceId, httpMethod,
                            statusCode, responseReq);
                }
            }
        }

        if (props != null && props.has("Integration")) {
            JsonNode integNode = engine.resolveNode(props.get("Integration"));
            Map<String, Object> integReq = new HashMap<>();
            integReq.put("type", ctx.resolveOptional(integNode, "Type"));
            integReq.put("httpMethod", ctx.resolveOptional(integNode, "IntegrationHttpMethod"));
            integReq.put("uri", ctx.resolveOptional(integNode, "Uri"));
            integReq.put("requestTemplates", resolveStringMap(integNode, "RequestTemplates", engine));
            integReq.put("requestParameters", resolveStringMap(integNode, "RequestParameters", engine));
            integReq.put("passthroughBehavior", ctx.resolveOptional(integNode, "PassthroughBehavior"));
            integReq.put("contentHandling", ctx.resolveOptional(integNode, "ContentHandling"));

            apiGatewayService.putIntegration(region, apiId, resourceId, httpMethod, integReq);

            JsonNode responses = engine.resolveNode(integNode.path("IntegrationResponses"));
            if (responses != null && responses.isArray()) {
                for (JsonNode response : responses) {
                    String statusCode = ctx.resolveOptional(response, "StatusCode");
                    Map<String, Object> responseReq = new HashMap<>();
                    responseReq.put("responseParameters", resolveStringMap(response, "ResponseParameters", engine));
                    responseReq.put("responseTemplates", resolveStringMap(response, "ResponseTemplates", engine));
                    responseReq.put("selectionPattern", ctx.resolveOptional(response, "SelectionPattern"));
                    responseReq.put("contentHandling", ctx.resolveOptional(response, "ContentHandling"));
                    apiGatewayService.putIntegrationResponse(region, apiId, resourceId, httpMethod,
                            statusCode, responseReq);
                }
            }
        }
    }

    private Map<String, String> resolveStringMap(JsonNode props, String name,
                                                  CloudFormationTemplateEngine engine) {
        Map<String, String> result = new LinkedHashMap<>();
        JsonNode node = engine.resolveNode(props.path(name));
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry ->
                    result.put(entry.getKey(), engine.resolve(entry.getValue())));
        }
        return result;
    }

    private void provisionDeployment(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        String apiId = ctx.resolveOptional(props, "RestApiId");
        Map<String, Object> req = new HashMap<>();
        req.put("description", ctx.resolveOptional(props, "Description"));

        Deployment deployment = apiGatewayService.createDeployment(region, apiId, req);
        r.setPhysicalId(deployment.id());
        r.getAttributes().put("DeploymentId", deployment.id());

        // AWS::ApiGateway::Deployment accepts an inline StageName: when present, AWS creates that
        // stage pointing at this deployment, with no separate AWS::ApiGateway::Stage resource.
        String stageName = ctx.resolveOptional(props, "StageName");
        if (stageName != null && !stageName.isBlank()) {
            Map<String, Object> stageReq = new HashMap<>();
            stageReq.put("stageName", stageName);
            stageReq.put("deploymentId", deployment.id());
            JsonNode stageDescription = props != null ? props.get("StageDescription") : null;
            if (stageDescription != null && stageDescription.has("Description")) {
                stageReq.put("description", ctx.resolveOptional(stageDescription, "Description"));
            }
            apiGatewayService.createStage(region, apiId, stageReq);
        }
    }

    private void provisionStage(StackResource r, JsonNode props, ProvisionContext ctx) {
        String apiId = ctx.resolveOptional(props, "RestApiId");
        String stageName = ctx.resolveOptional(props, "StageName");
        String deploymentId = ctx.resolveOptional(props, "DeploymentId");

        Map<String, Object> req = new HashMap<>();
        req.put("stageName", stageName);
        req.put("deploymentId", deploymentId);
        req.put("description", ctx.resolveOptional(props, "Description"));

        apiGatewayService.createStage(ctx.region(), apiId, req);
        r.setPhysicalId(stageName);
    }

    /** Resolves an optional property, falling back to {@code defaultValue} when absent or blank. */
}
