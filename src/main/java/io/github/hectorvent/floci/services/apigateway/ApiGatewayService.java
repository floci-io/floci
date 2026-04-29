package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigateway.model.*;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;

@ApplicationScoped
public class ApiGatewayService {

    private static final Logger LOG = Logger.getLogger(ApiGatewayService.class);

    private final StorageBackend<String, RestApi> apiStore;
    private final StorageBackend<String, ApiGatewayResource> resourceStore;
    private final StorageBackend<String, Deployment> deploymentStore;
    private final StorageBackend<String, Stage> stageStore;
    private final StorageBackend<String, Authorizer> authorizerStore;
    private final StorageBackend<String, ApiKey> apiKeyStore;
    private final StorageBackend<String, UsagePlan> usagePlanStore;
    private final StorageBackend<String, UsagePlanKey> usagePlanKeyStore;
    private final StorageBackend<String, RequestValidator> requestValidatorStore;
    private final StorageBackend<String, Model> modelStore;
    private final StorageBackend<String, CustomDomain> domainStore;
    private final StorageBackend<String, BasePathMapping> basePathMappingStore;
    private final RegionResolver regionResolver;

    @Inject
    public ApiGatewayService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver) {
        this.apiStore = storageFactory.create("apigateway", "apigateway-apis.json",
                new TypeReference<>() {
                });
        this.resourceStore = storageFactory.create("apigateway", "apigateway-resources.json",
                new TypeReference<>() {
                });
        this.deploymentStore = storageFactory.create("apigateway", "apigateway-deployments.json",
                new TypeReference<>() {
                });
        this.stageStore = storageFactory.create("apigateway", "apigateway-stages.json",
                new TypeReference<>() {
                });
        this.authorizerStore = storageFactory.create("apigateway", "apigateway-authorizers.json",
                new TypeReference<>() {
                });
        this.apiKeyStore = storageFactory.create("apigateway", "apigateway-apikeys.json",
                new TypeReference<>() {
                });
        this.usagePlanStore = storageFactory.create("apigateway", "apigateway-usageplans.json",
                new TypeReference<>() {
                });
        this.usagePlanKeyStore = storageFactory.create("apigateway", "apigateway-usageplankeys.json",
                new TypeReference<>() {
                });
        this.requestValidatorStore = storageFactory.create("apigateway", "apigateway-validators.json",
                new TypeReference<>() {
                });
        this.modelStore = storageFactory.create("apigateway", "apigateway-models.json",
                new TypeReference<>() {
                });
        this.domainStore = storageFactory.create("apigateway", "apigateway-domains.json",
                new TypeReference<>() {
                });
        this.basePathMappingStore = storageFactory.create("apigateway", "apigateway-mappings.json",
                new TypeReference<>() {
                });
        this.regionResolver = regionResolver;
    }

    // ──────────────────────────── REST API CRUD ────────────────────────────

    public RestApi createRestApi(String region, Map<String, Object> request) {
        String name = (String) request.get("name");
        String description = (String) request.get("description");

        @SuppressWarnings("unchecked")
        Map<String, String> tags = request.get("tags") instanceof Map<?, ?> m
                ? (Map<String, String>) m : new HashMap<>();

        String customId = tags.get("_custom_id_");
        String apiId = (customId != null && !customId.isBlank()) ? customId : shortId(10);

        RestApi api = new RestApi();
        api.setId(apiId);
        api.setName(name);
        api.setDescription(description);
        api.setCreatedDate(System.currentTimeMillis() / 1000L);
        api.setTags(tags);

        apiStore.put(apiKey(region, api.getId()), api);

        // Create root resource "/"
        ApiGatewayResource root = new ApiGatewayResource();
        root.setId(shortId(8));
        root.setPath("/");
        resourceStore.put(resourceKey(region, api.getId(), root.getId()), root);

        LOG.infov("Created REST API: {0} ({1}) in {2}", name, api.getId(), region);
        return api;
    }

    public RestApi getRestApi(String region, String apiId) {
        return apiStore.get(apiKey(region, apiId))
                .orElseThrow(() -> new AwsException("NotFoundException", "Invalid API id specified", 404));
    }

    public List<RestApi> getRestApis(String region) {
        String prefix = region + "::";
        return apiStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteRestApi(String region, String apiId) {
        getRestApi(region, apiId);
        apiStore.delete(apiKey(region, apiId));
        // Simple cascade: delete resources for this API
        String prefix = region + "::" + apiId + "::";
        resourceStore.keys().stream().filter(k -> k.startsWith(prefix)).forEach(resourceStore::delete);
        deploymentStore.keys().stream().filter(k -> k.startsWith(prefix)).forEach(deploymentStore::delete);
        stageStore.keys().stream().filter(k -> k.startsWith(prefix)).forEach(stageStore::delete);
        modelStore.keys().stream().filter(k -> k.startsWith(prefix)).forEach(modelStore::delete);
        requestValidatorStore.keys().stream().filter(k -> k.startsWith(prefix)).forEach(requestValidatorStore::delete);
        LOG.infov("Deleted REST API: {0} in {1}", apiId, region);
    }

    // ──────────────────────────── Resource CRUD ────────────────────────────

    public List<ApiGatewayResource> getResources(String region, String apiId) {
        getRestApi(region, apiId);
        String prefix = region + "::" + apiId + "::";
        return resourceStore.scan(k -> k.startsWith(prefix));
    }

    public ApiGatewayResource getResource(String region, String apiId, String resourceId) {
        return resourceStore.get(resourceKey(region, apiId, resourceId))
                .orElseThrow(() -> new AwsException("NotFoundException", "Invalid resource id specified", 404));
    }

    public ApiGatewayResource createResource(String region, String apiId, String parentId, Map<String, Object> request) {
        getRestApi(region, apiId);
        ApiGatewayResource parent = getResource(region, apiId, parentId);
        String pathPart = (String) request.get("pathPart");

        ApiGatewayResource resource = new ApiGatewayResource();
        resource.setId(shortId(8));
        resource.setParentId(parentId);
        resource.setPathPart(pathPart);
        String childPath = parent.getPath().equals("/") ? "/" + pathPart : parent.getPath() + "/" + pathPart;
        resource.setPath(childPath);

        resourceStore.put(resourceKey(region, apiId, resource.getId()), resource);
        LOG.infov("Created resource {0} path={1} in API {2}", resource.getId(), childPath, apiId);
        return resource;
    }

    public void deleteResource(String region, String apiId, String resourceId) {
        getResource(region, apiId, resourceId);
        resourceStore.delete(resourceKey(region, apiId, resourceId));
    }

    // ──────────────────────────── Method CRUD ────────────────────────────

    public MethodConfig putMethod(String region, String apiId, String resourceId, String httpMethod, Map<String, Object> request) {
        ApiGatewayResource resource = getResource(region, apiId, resourceId);
        MethodConfig method = new MethodConfig();
        method.setHttpMethod(httpMethod.toUpperCase());
        method.setAuthorizationType((String) request.getOrDefault("authorizationType", "NONE"));
        method.setAuthorizerId((String) request.get("authorizerId"));
        method.setRequestValidatorId((String) request.get("requestValidatorId"));

        @SuppressWarnings("unchecked")
        Map<String, Boolean> reqParams = (Map<String, Boolean>) request.get("requestParameters");
        if (reqParams != null) method.setRequestParameters(reqParams);

        @SuppressWarnings("unchecked")
        Map<String, String> reqModels = (Map<String, String>) request.get("requestModels");
        if (reqModels != null) method.setRequestModels(reqModels);

        resource.getResourceMethods().put(httpMethod.toUpperCase(), method);
        resourceStore.put(resourceKey(region, apiId, resourceId), resource);
        return method;
    }

    public MethodConfig getMethod(String region, String apiId, String resourceId, String httpMethod) {
        ApiGatewayResource resource = getResource(region, apiId, resourceId);
        MethodConfig method = resource.getResourceMethods().get(httpMethod.toUpperCase());
        if (method == null) {
            throw new AwsException("NotFoundException", "Invalid method specified", 404);
        }
        return method;
    }

    public void deleteMethod(String region, String apiId, String resourceId, String httpMethod) {
        ApiGatewayResource resource = getResource(region, apiId, resourceId);
        resource.getResourceMethods().remove(httpMethod.toUpperCase());
        resourceStore.put(resourceKey(region, apiId, resourceId), resource);
    }

    public MethodResponse putMethodResponse(String region, String apiId, String resourceId,
                                            String httpMethod, String statusCode,
                                            Map<String, Object> request) {
        MethodConfig method = getMethod(region, apiId, resourceId, httpMethod);
        MethodResponse mr = new MethodResponse(statusCode, new HashMap<>());
        method.getMethodResponses().put(statusCode, mr);
        resourceStore.put(resourceKey(region, apiId, resourceId), getResource(region, apiId, resourceId));
        return mr;
    }

    public MethodResponse getMethodResponse(String region, String apiId, String resourceId,
                                            String httpMethod, String statusCode) {
        MethodConfig method = getMethod(region, apiId, resourceId, httpMethod);
        MethodResponse mr = method.getMethodResponses().get(statusCode);
        if (mr == null) {
            throw new AwsException("NotFoundException", "Invalid response status code specified", 404);
        }
        return mr;
    }

    // ──────────────────────────── Integrations ────────────────────────────

    public Integration putIntegration(String region, String apiId, String resourceId, String httpMethod, Map<String, Object> request) {
        ApiGatewayResource resource = getResource(region, apiId, resourceId);
        MethodConfig method = getMethod(region, apiId, resourceId, httpMethod);

        Integration integration = new Integration();
        integration.setType((String) request.get("type"));
        integration.setHttpMethod((String) request.get("httpMethod"));
        integration.setUri((String) request.get("uri"));

        if (request.get("passthroughBehavior") != null) {
            integration.setPassthroughBehavior((String) request.get("passthroughBehavior"));
        }

        @SuppressWarnings("unchecked")
        Map<String, String> reqParams = (Map<String, String>) request.get("requestParameters");
        if (reqParams != null) integration.setRequestParameters(reqParams);

        @SuppressWarnings("unchecked")
        Map<String, String> reqTemplates = (Map<String, String>) request.get("requestTemplates");
        if (reqTemplates != null) integration.setRequestTemplates(reqTemplates);

        method.setMethodIntegration(integration);
        resourceStore.put(resourceKey(region, apiId, resourceId), resource);
        return integration;
    }

    public Integration getIntegration(String region, String apiId, String resourceId, String httpMethod) {
        MethodConfig method = getMethod(region, apiId, resourceId, httpMethod);
        if (method.getMethodIntegration() == null) {
            throw new AwsException("NotFoundException", "Integration not found", 404);
        }
        return method.getMethodIntegration();
    }

    public void deleteIntegration(String region, String apiId, String resourceId, String httpMethod) {
        ApiGatewayResource resource = getResource(region, apiId, resourceId);
        MethodConfig method = resource.getResourceMethods().get(httpMethod.toUpperCase());
        if (method == null || method.getMethodIntegration() == null) {
            throw new AwsException("NotFoundException", "Integration not found", 404);
        }
        method.setMethodIntegration(null);
        resourceStore.put(resourceKey(region, apiId, resourceId), resource);
    }

    // ──────────────────────────── Integration Responses ────────────────────────────

    public IntegrationResponse putIntegrationResponse(String region, String apiId, String resourceId,
                                                      String httpMethod, String statusCode,
                                                      Map<String, Object> request) {
        Integration integration = getIntegration(region, apiId, resourceId, httpMethod);
        @SuppressWarnings("unchecked")
        Map<String, String> respParams = (Map<String, String>) request.get("responseParameters");
        @SuppressWarnings("unchecked")
        Map<String, String> respTemplates = (Map<String, String>) request.get("responseTemplates");
        String selectionPattern = (String) request.getOrDefault("selectionPattern", "");

        IntegrationResponse ir = new IntegrationResponse(statusCode, selectionPattern,
                respParams != null ? respParams : new HashMap<>(),
                respTemplates != null ? respTemplates : new HashMap<>());

        integration.getIntegrationResponses().put(statusCode, ir);
        resourceStore.put(resourceKey(region, apiId, resourceId),
                getResource(region, apiId, resourceId));
        return ir;
    }

    public IntegrationResponse getIntegrationResponse(String region, String apiId, String resourceId,
                                                      String httpMethod, String statusCode) {
        Integration integration = getIntegration(region, apiId, resourceId, httpMethod);
        IntegrationResponse ir = integration.getIntegrationResponses().get(statusCode);
        if (ir == null) {
            throw new AwsException("NotFoundException", "Invalid response status code specified", 404);
        }
        return ir;
    }

    // ──────────────────────────── Deployments ────────────────────────────

    public Deployment createDeployment(String region, String apiId, Map<String, Object> request) {
        getRestApi(region, apiId);
        String description = (String) request.getOrDefault("description", "");
        Deployment deployment = new Deployment(shortId(10), description, System.currentTimeMillis() / 1000L);
        deploymentStore.put(deploymentKey(region, apiId, deployment.id()), deployment);
        LOG.infov("Created deployment {0} for API {1}", deployment.id(), apiId);
        return deployment;
    }

    public List<Deployment> getDeployments(String region, String apiId) {
        getRestApi(region, apiId);
        String prefix = region + "::" + apiId + "::";
        return deploymentStore.scan(k -> k.startsWith(prefix));
    }

    public Deployment getDeployment(String region, String apiId, String deploymentId) {
        return deploymentStore.get(deploymentKey(region, apiId, deploymentId))
                .orElseThrow(() -> new AwsException("NotFoundException", "Deployment not found", 404));
    }

    public void deleteDeployment(String region, String apiId, String deploymentId) {
        getDeployment(region, apiId, deploymentId);
        deploymentStore.delete(deploymentKey(region, apiId, deploymentId));
    }

    // ──────────────────────────── Stages ────────────────────────────

    public Stage createStage(String region, String apiId, Map<String, Object> request) {
        getRestApi(region, apiId);
        String stageName = (String) request.get("stageName");
        String deploymentId = (String) request.get("deploymentId");

        if (stageName == null || stageName.isBlank()) {
            throw new AwsException("BadRequestException", "stageName is required", 400);
        }
        if (deploymentId == null || deploymentId.isBlank()) {
            throw new AwsException("BadRequestException", "deploymentId is required", 400);
        }

        Stage stage = new Stage();
        stage.setStageName(stageName);
        stage.setDeploymentId(deploymentId);
        stage.setDescription((String) request.get("description"));
        stage.setCreatedDate(System.currentTimeMillis() / 1000L);
        stage.setLastUpdatedDate(stage.getCreatedDate());

        @SuppressWarnings("unchecked")
        Map<String, String> variables = (Map<String, String>) request.get("variables");
        if (variables != null) stage.setVariables(variables);

        stageStore.put(stageKey(region, apiId, stageName), stage);
        LOG.infov("Created stage {0} for API {1}", stageName, apiId);
        return stage;
    }

    public Stage getStage(String region, String apiId, String stageName) {
        getRestApi(region, apiId);
        return stageStore.get(stageKey(region, apiId, stageName))
                .orElseThrow(() -> new AwsException("NotFoundException", "Stage not found", 404));
    }

    public List<Stage> getStages(String region, String apiId) {
        getRestApi(region, apiId);
        String prefix = region + "::" + apiId + "::";
        return stageStore.scan(k -> k.startsWith(prefix));
    }

    public Stage updateStage(String region, String apiId, String stageName,
                             List<Map<String, String>> patchOperations) {
        Stage stage = getStage(region, apiId, stageName);
        LOG.infov("Updating stage {0} with {1} operations", stageName, patchOperations != null ? patchOperations.size() : 0);
        if (patchOperations != null) {
            for (Map<String, String> op : patchOperations) {
                String opType = op.get("op");
                String path = op.getOrDefault("path", "");
                String value = op.get("value");
                LOG.infov("Patch operation: op={0}, path={1}, value={2}", opType, path, value);

                if (!"replace" .equals(opType) && !"add" .equals(opType)) continue;

                if ("/description" .equals(path)) {
                    stage.setDescription(value);
                } else if ("/deploymentId" .equals(path)) {
                    stage.setDeploymentId(value);
                } else if (path.startsWith("/variables/")) {
                    String varKey = path.substring("/variables/" .length());
                    LOG.infov("Setting stage variable {0} = {1}", varKey, value);
                    stage.getVariables().put(varKey, value);
                }
            }
        }
        stage.setLastUpdatedDate(System.currentTimeMillis() / 1000L);
        stageStore.put(stageKey(region, apiId, stageName), stage);
        return stage;
    }

    public void deleteStage(String region, String apiId, String stageName) {
        getStage(region, apiId, stageName);
        stageStore.delete(stageKey(region, apiId, stageName));
    }

    // ──────────────────────────── Authorizers ────────────────────────────

    public Authorizer createAuthorizer(String region, String apiId, Map<String, Object> request) {
        getRestApi(region, apiId);
        Authorizer authorizer = new Authorizer();
        authorizer.setId(shortId(6));
        authorizer.setName((String) request.get("name"));
        authorizer.setType((String) request.get("type"));
        authorizer.setAuthorizerUri((String) request.get("authorizerUri"));
        authorizer.setIdentitySource((String) request.get("identitySource"));
        authorizer.setAuthorizerResultTtlInSeconds(String.valueOf(request.getOrDefault("authorizerResultTtlInSeconds", "300")));

        authorizerStore.put(authorizerKey(region, apiId, authorizer.getId()), authorizer);
        LOG.infov("Created authorizer {0} for API {1}", authorizer.getId(), apiId);
        return authorizer;
    }

    public Authorizer getAuthorizer(String region, String apiId, String authorizerId) {
        return authorizerStore.get(authorizerKey(region, apiId, authorizerId))
                .orElseThrow(() -> new AwsException("NotFoundException", "Authorizer not found", 404));
    }

    public List<Authorizer> getAuthorizers(String region, String apiId) {
        getRestApi(region, apiId);
        String prefix = region + "::" + apiId + "::";
        return authorizerStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteAuthorizer(String region, String apiId, String authorizerId) {
        getAuthorizer(region, apiId, authorizerId);
        authorizerStore.delete(authorizerKey(region, apiId, authorizerId));
    }

    // ──────────────────────────── API Keys ────────────────────────────

    public ApiKey createApiKey(String region, Map<String, Object> request) {
        ApiKey apiKey = new ApiKey();
        apiKey.setId(shortId(10));
        apiKey.setName((String) request.get("name"));
        apiKey.setValue((String) request.getOrDefault("value", UUID.randomUUID().toString().replace("-", "")));
        apiKey.setEnabled(!Boolean.FALSE.equals(request.get("enabled")));
        apiKey.setCreatedDate(System.currentTimeMillis() / 1000L);
        apiKey.setLastUpdatedDate(apiKey.getCreatedDate());

        apiKeyStore.put(apiKeyGlobalKey(region, apiKey.getId()), apiKey);
        LOG.infov("Created API Key {0}", apiKey.getId());
        return apiKey;
    }

    public ApiKey getApiKey(String region, String apiKeyId) {
        return apiKeyStore.get(apiKeyGlobalKey(region, apiKeyId))
                .orElseThrow(() -> new AwsException("NotFoundException", "API Key not found", 404));
    }

    public List<ApiKey> getApiKeys(String region) {
        String prefix = region + "::";
        return apiKeyStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteApiKey(String region, String apiKeyId) {
        getApiKey(region, apiKeyId);
        apiKeyStore.delete(apiKeyGlobalKey(region, apiKeyId));
    }

    // ──────────────────────────── Usage Plans ────────────────────────────

    public UsagePlan createUsagePlan(String region, Map<String, Object> request) {
        UsagePlan plan = new UsagePlan();
        plan.setId(shortId(10));
        plan.setName((String) request.get("name"));
        plan.setDescription((String) request.get("description"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> apiStages = (List<Map<String, Object>>) request.get("apiStages");
        if (apiStages != null) {
            for (Map<String, Object> as : apiStages) {
                plan.getApiStages().add(new UsagePlan.ApiStage((String) as.get("apiId"), (String) as.get("stage")));
            }
        }

        usagePlanStore.put(usagePlanKey(region, plan.getId()), plan);
        LOG.infov("Created Usage Plan {0}", plan.getId());
        return plan;
    }

    public UsagePlan getUsagePlan(String region, String usagePlanId) {
        return usagePlanStore.get(usagePlanKey(region, usagePlanId))
                .orElseThrow(() -> new AwsException("NotFoundException", "Usage Plan not found", 404));
    }

    public List<UsagePlan> getUsagePlans(String region) {
        String prefix = region + "::";
        return usagePlanStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteUsagePlan(String region, String usagePlanId) {
        getUsagePlan(region, usagePlanId);
        usagePlanStore.delete(usagePlanKey(region, usagePlanId));
    }

    // ──────────────────────────── Usage Plan Keys ────────────────────────────

    public UsagePlanKey createUsagePlanKey(String region, String usagePlanId, Map<String, Object> request) {
        getUsagePlan(region, usagePlanId);
        String keyId = (String) request.get("keyId");
        String keyType = (String) request.get("keyType");

        ApiKey apiKey = getApiKey(region, keyId);

        UsagePlanKey usagePlanKey = new UsagePlanKey();
        usagePlanKey.setId(apiKey.getId());
        usagePlanKey.setName(apiKey.getName());
        usagePlanKey.setType(keyType);
        usagePlanKey.setValue(apiKey.getValue());

        usagePlanKeyStore.put(usagePlanKeyPathKey(region, usagePlanId, keyId), usagePlanKey);
        LOG.infov("Created Usage Plan Key {0} for Usage Plan {1}", keyId, usagePlanId);
        return usagePlanKey;
    }

    public UsagePlanKey getUsagePlanKey(String region, String usagePlanId, String keyId) {
        return usagePlanKeyStore.get(usagePlanKeyPathKey(region, usagePlanId, keyId))
                .orElseThrow(() -> new AwsException("NotFoundException", "Usage Plan Key not found", 404));
    }

    public List<UsagePlanKey> getUsagePlanKeys(String region, String usagePlanId) {
        String prefix = region + "::" + usagePlanId + "::";
        return usagePlanKeyStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteUsagePlanKey(String region, String usagePlanId, String keyId) {
        getUsagePlanKey(region, usagePlanId, keyId);
        usagePlanKeyStore.delete(usagePlanKeyPathKey(region, usagePlanId, keyId));
    }

    // ──────────────────────────── Request Validators ────────────────────────────

    public RequestValidator createRequestValidator(String region, String apiId, Map<String, Object> request) {
        getRestApi(region, apiId);
        RequestValidator validator = new RequestValidator();
        validator.setId(shortId(6));
        validator.setName((String) request.get("name"));
        validator.setValidateRequestBody(Boolean.TRUE.equals(request.get("validateRequestBody")));
        validator.setValidateRequestParameters(Boolean.TRUE.equals(request.get("validateRequestParameters")));

        requestValidatorStore.put(requestValidatorKey(region, apiId, validator.getId()), validator);
        LOG.infov("Created request validator {0} for API {1}", validator.getId(), apiId);
        return validator;
    }

    public RequestValidator getRequestValidator(String region, String apiId, String validatorId) {
        return requestValidatorStore.get(requestValidatorKey(region, apiId, validatorId))
                .orElseThrow(() -> new AwsException("NotFoundException", "Request validator not found", 404));
    }

    public List<RequestValidator> getRequestValidators(String region, String apiId) {
        getRestApi(region, apiId);
        String prefix = region + "::" + apiId + "::";
        return requestValidatorStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteRequestValidator(String region, String apiId, String validatorId) {
        getRequestValidator(region, apiId, validatorId);
        requestValidatorStore.delete(requestValidatorKey(region, apiId, validatorId));
    }

    // ──────────────────────────── Models ────────────────────────────

    public Model createModel(String region, String apiId, Map<String, Object> request) {
        getRestApi(region, apiId);
        Model model = new Model();
        model.setId(shortId(6));
        model.setName((String) request.get("name"));
        model.setDescription((String) request.get("description"));
        model.setContentType((String) request.getOrDefault("contentType", "application/json"));
        model.setSchema((String) request.get("schema"));

        modelStore.put(modelKey(region, apiId, model.getName()), model);
        LOG.infov("Created model {0} for API {1}", model.getName(), apiId);
        return model;
    }

    public Model getModel(String region, String apiId, String modelName) {
        return modelStore.get(modelKey(region, apiId, modelName))
                .orElseThrow(() -> new AwsException("NotFoundException", "Invalid model name specified", 404));
    }

    public List<Model> getModels(String region, String apiId) {
        getRestApi(region, apiId);
        String prefix = region + "::" + apiId + "::";
        return modelStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteModel(String region, String apiId, String modelName) {
        getModel(region, apiId, modelName);
        modelStore.delete(modelKey(region, apiId, modelName));
    }

    // ──────────────────────────── Custom Domains ────────────────────────────

    public CustomDomain createDomainName(String region, Map<String, Object> request) {
        String domainName = (String) request.get("domainName");
        if (domainName == null) throw new AwsException("BadRequestException", "domainName is required", 400);

        CustomDomain domain = new CustomDomain();
        domain.setDomainName(domainName);
        domain.setCertificateName((String) request.get("certificateName"));
        domain.setCertificateArn((String) request.get("certificateArn"));
        domain.setRegionalDomainName(domainName + ".regional.local");
        domain.setRegionalHostedZoneId("Z2FDTNDATAQYL2");

        domainStore.put(domainKey(region, domainName), domain);
        LOG.infov("Created custom domain {0} in {1}", domainName, region);
        return domain;
    }

    public CustomDomain getDomainName(String region, String domainName) {
        return domainStore.get(domainKey(region, domainName))
                .orElseThrow(() -> new AwsException("NotFoundException", "Domain name not found", 404));
    }

    public List<CustomDomain> getDomainNames(String region) {
        String prefix = region + "::";
        return domainStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteDomainName(String region, String domainName) {
        getDomainName(region, domainName);
        domainStore.delete(domainKey(region, domainName));
        // Delete associated mappings
        String prefix = region + "::" + domainName + "::";
        basePathMappingStore.keys().stream().filter(k -> k.startsWith(prefix)).forEach(basePathMappingStore::delete);
    }

    // ──────────────────────────── Base Path Mappings ────────────────────────────

    public BasePathMapping createBasePathMapping(String region, String domainName, Map<String, Object> request) {
        getDomainName(region, domainName);
        String basePath = (String) request.getOrDefault("basePath", "(none)");
        String apiId = (String) request.get("restApiId");
        String stage = (String) request.get("stage");

        BasePathMapping mapping = new BasePathMapping(basePath, apiId, stage);
        basePathMappingStore.put(mappingKey(region, domainName, basePath), mapping);
        LOG.infov("Created mapping for {0} path={1} -> API {2}", domainName, basePath, apiId);
        return mapping;
    }

    public BasePathMapping getBasePathMapping(String region, String domainName, String basePath) {
        String path = (basePath == null || basePath.isEmpty() || "/" .equals(basePath)) ? "(none)" : basePath;
        return basePathMappingStore.get(mappingKey(region, domainName, path))
                .orElseThrow(() -> new AwsException("NotFoundException", "Base path mapping not found", 404));
    }

    public List<BasePathMapping> getBasePathMappings(String region, String domainName) {
        getDomainName(region, domainName);
        String prefix = region + "::" + domainName + "::";
        return basePathMappingStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteBasePathMapping(String region, String domainName, String basePath) {
        getBasePathMapping(region, domainName, basePath);
        String path = (basePath == null || basePath.isEmpty() || "/" .equals(basePath)) ? "(none)" : basePath;
        basePathMappingStore.delete(mappingKey(region, domainName, path));
    }

    // ──────────────────────────── Update Methods ────────────────────────────

    public RestApi updateRestApi(String region, String apiId, List<Map<String, String>> patchOperations) {
        RestApi api = getRestApi(region, apiId);
        if (patchOperations != null) {
            for (Map<String, String> op : patchOperations) {
                if (!"replace" .equals(op.get("op"))) continue;
                String path = op.getOrDefault("path", "");
                String value = op.get("value");
                if ("/name" .equals(path)) api.setName(value);
                else if ("/description" .equals(path)) api.setDescription(value);
            }
        }
        apiStore.put(apiKey(region, apiId), api);
        return api;
    }

    public ApiGatewayResource updateResource(String region, String apiId, String resourceId, List<Map<String, String>> patchOperations) {
        ApiGatewayResource resource = getResource(region, apiId, resourceId);
        // Minimal update support
        resourceStore.put(resourceKey(region, apiId, resourceId), resource);
        return resource;
    }

    public MethodConfig updateMethod(String region, String apiId, String resourceId, String httpMethod, List<Map<String, String>> patchOperations) {
        MethodConfig method = getMethod(region, apiId, resourceId, httpMethod);
        if (patchOperations != null) {
            for (Map<String, String> op : patchOperations) {
                if (!"replace" .equals(op.get("op"))) continue;
                String path = op.getOrDefault("path", "");
                String value = op.get("value");
                if ("/authorizationType" .equals(path)) method.setAuthorizationType(value);
                else if ("/authorizerId" .equals(path)) method.setAuthorizerId(value);
            }
        }
        resourceStore.put(resourceKey(region, apiId, resourceId), getResource(region, apiId, resourceId));
        return method;
    }

    public Integration updateIntegration(String region, String apiId, String resourceId, String httpMethod, List<Map<String, String>> patchOperations) {
        Integration integration = getIntegration(region, apiId, resourceId, httpMethod);
        if (patchOperations != null) {
            for (Map<String, String> op : patchOperations) {
                if (!"replace" .equals(op.get("op"))) continue;
                String path = op.getOrDefault("path", "");
                String value = op.get("value");
                if ("/type" .equals(path)) integration.setType(value);
                else if ("/httpMethod" .equals(path)) integration.setHttpMethod(value);
                else if ("/uri" .equals(path)) integration.setUri(value);
            }
        }
        resourceStore.put(resourceKey(region, apiId, resourceId), getResource(region, apiId, resourceId));
        return integration;
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> getTags(String region, String apiId) {
        return getRestApi(region, apiId).getTags();
    }

    public void tagResource(String region, String apiId, Map<String, String> tags) {
        RestApi api = getRestApi(region, apiId);
        api.getTags().putAll(tags);
        apiStore.put(apiKey(region, apiId), api);
    }

    public void untagResource(String region, String apiId, List<String> tagKeys) {
        RestApi api = getRestApi(region, apiId);
        tagKeys.forEach(api.getTags()::remove);
        apiStore.put(apiKey(region, apiId), api);
    }

    // ──────────────────────────── OpenAPI Import ────────────────────────────

    public RestApi importRestApi(String region, String specBody) {
        OpenAPI openAPI = parseOpenApiSpec(specBody);

        String name = openAPI.getInfo() != null ? openAPI.getInfo().getTitle() : "Imported API";
        String description = openAPI.getInfo() != null ? openAPI.getInfo().getDescription() : null;

        Map<String, Object> request = new HashMap<>();
        request.put("name", name);
        request.put("description", description);
        RestApi api = createRestApi(region, request);

        applyOpenApiSpec(region, api.getId(), openAPI);
        LOG.infov("Imported REST API from OpenAPI spec: {0} ({1})", name, api.getId());
        return api;
    }

    public RestApi putRestApi(String region, String apiId, String mode, String specBody) {
        // Note: mode=merge is accepted but treated as overwrite (merge semantics not yet implemented)
        RestApi api = getRestApi(region, apiId);
        OpenAPI openAPI = parseOpenApiSpec(specBody);

        // Delete all non-root resources
        List<ApiGatewayResource> existing = getResources(region, apiId);
        for (ApiGatewayResource r : existing) {
            if (!"/".equals(r.getPath())) {
                deleteResource(region, apiId, r.getId());
            }
        }
        // Clear methods on root resource
        ApiGatewayResource root = existing.stream()
                .filter(r -> "/".equals(r.getPath())).findFirst().orElse(null);
        if (root != null) {
            root.setResourceMethods(new HashMap<>());
            resourceStore.put(resourceKey(region, apiId, root.getId()), root);
        }

        // Clear existing models and validators
        String prefix = region + "::" + apiId + "::";
        modelStore.keys().stream().filter(k -> k.startsWith(prefix)).forEach(modelStore::delete);
        requestValidatorStore.keys().stream().filter(k -> k.startsWith(prefix)).forEach(requestValidatorStore::delete);

        // Update API metadata from spec
        if (openAPI.getInfo() != null) {
            if (openAPI.getInfo().getTitle() != null) api.setName(openAPI.getInfo().getTitle());
            if (openAPI.getInfo().getDescription() != null) api.setDescription(openAPI.getInfo().getDescription());
            apiStore.put(apiKey(region, apiId), api);
        }

        applyOpenApiSpec(region, apiId, openAPI);
        LOG.infov("Updated REST API from OpenAPI spec: {0} ({1})", api.getName(), apiId);
        return api;
    }

    private OpenAPI parseOpenApiSpec(String specBody) {
        SwaggerParseResult result = new io.swagger.parser.OpenAPIParser().readContents(specBody, null, null);
        if (result.getOpenAPI() == null) {
            String errors = result.getMessages() != null ? String.join(", ", result.getMessages()) : "unknown error";
            throw new AwsException("BadRequestException", "Failed to parse OpenAPI spec: " + errors, 400);
        }
        return result.getOpenAPI();
    }

    @SuppressWarnings("unchecked")
    private void applyOpenApiSpec(String region, String apiId, OpenAPI openAPI) {
        // Import schemas as Models
        if (openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
            for (var schemaEntry : openAPI.getComponents().getSchemas().entrySet()) {
                String schemaName = schemaEntry.getKey();
                var schema = schemaEntry.getValue();
                Map<String, Object> modelReq = new HashMap<>();
                modelReq.put("name", schemaName);
                modelReq.put("contentType", "application/json");
                try {
                    // Use swagger's own JSON serializer to produce clean JSON Schema
                    modelReq.put("schema", io.swagger.v3.core.util.Json.mapper().writeValueAsString(schema));
                } catch (Exception e) {
                    modelReq.put("schema", "{}");
                }
                createModel(region, apiId, modelReq);
            }
        }

        // Import x-amazon-apigateway-request-validators as RequestValidators
        Map<String, String> validatorNameToId = new HashMap<>();
        Map<String, Object> topExtensions = openAPI.getExtensions();
        if (topExtensions != null) {
            Map<String, Object> validators = (Map<String, Object>) topExtensions
                    .get("x-amazon-apigateway-request-validators");
            if (validators != null) {
                for (var entry : validators.entrySet()) {
                    String validatorName = entry.getKey();
                    Map<String, Object> validatorDef = (Map<String, Object>) entry.getValue();
                    Map<String, Object> valReq = new HashMap<>();
                    valReq.put("name", validatorName);
                    valReq.put("validateRequestBody",
                            Boolean.TRUE.equals(validatorDef.get("validateRequestBody")));
                    valReq.put("validateRequestParameters",
                            Boolean.TRUE.equals(validatorDef.get("validateRequestParameters")));
                    RequestValidator rv = createRequestValidator(region, apiId, valReq);
                    validatorNameToId.put(validatorName, rv.getId());
                }
            }

            // API-level default validator
            String defaultValidator = (String) topExtensions.get("x-amazon-apigateway-request-validator");
            if (defaultValidator != null && validatorNameToId.containsKey(defaultValidator)) {
                validatorNameToId.put("__default__", validatorNameToId.get(defaultValidator));
            }
        }

        if (openAPI.getPaths() == null) return;

        // Find the root resource
        List<ApiGatewayResource> resources = getResources(region, apiId);
        ApiGatewayResource rootResource = resources.stream()
                .filter(r -> "/".equals(r.getPath())).findFirst().orElse(null);
        if (rootResource == null) return;

        // Map of full path → resource ID for creating nested resources
        Map<String, String> pathToResourceId = new HashMap<>();
        pathToResourceId.put("/", rootResource.getId());

        for (Map.Entry<String, PathItem> pathEntry : openAPI.getPaths().entrySet()) {
            String path = pathEntry.getKey();
            PathItem pathItem = pathEntry.getValue();

            // Ensure all intermediate path segments exist
            String resourceId = ensureResourcePath(region, apiId, path, pathToResourceId);

            // Create methods for each operation on this path
            var operations = pathItem.readOperationsMap();
            if (operations == null) continue;

            for (var opEntry : operations.entrySet()) {
                String httpMethod = opEntry.getKey().name().toUpperCase();
                var operation = opEntry.getValue();

                // Create the method
                Map<String, Object> methodRequest = new HashMap<>();
                methodRequest.put("authorizationType", "NONE");

                // Link request models from operation requestBody
                if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
                    Map<String, String> requestModels = new HashMap<>();
                    for (var contentEntry : operation.getRequestBody().getContent().entrySet()) {
                        String contentType = contentEntry.getKey();
                        var mediaType = contentEntry.getValue();
                        if (mediaType.getSchema() != null && mediaType.getSchema().get$ref() != null) {
                            String ref = mediaType.getSchema().get$ref();
                            // Extract model name from #/components/schemas/ModelName
                            String modelName = ref.substring(ref.lastIndexOf('/') + 1);
                            requestModels.put(contentType, modelName);
                        }
                    }
                    if (!requestModels.isEmpty()) {
                        methodRequest.put("requestModels", requestModels);
                    }
                }

                // Map OpenAPI parameters to requestParameters
                if (operation.getParameters() != null && !operation.getParameters().isEmpty()) {
                    Map<String, Boolean> requestParameters = new HashMap<>();
                    for (var param : operation.getParameters()) {
                        String location = switch (param.getIn()) {
                            case "query" -> "method.request.querystring." + param.getName();
                            case "header" -> "method.request.header." + param.getName();
                            case "path" -> "method.request.path." + param.getName();
                            default -> null;
                        };
                        if (location != null) {
                            requestParameters.put(location, param.getRequired() != null && param.getRequired());
                        }
                    }
                    if (!requestParameters.isEmpty()) {
                        methodRequest.put("requestParameters", requestParameters);
                    }
                }

                // Link request validator (operation-level overrides API-level default)
                String opValidator = null;
                if (operation.getExtensions() != null) {
                    opValidator = (String) operation.getExtensions()
                            .get("x-amazon-apigateway-request-validator");
                }
                if (opValidator != null && validatorNameToId.containsKey(opValidator)) {
                    methodRequest.put("requestValidatorId", validatorNameToId.get(opValidator));
                } else if (validatorNameToId.containsKey("__default__")) {
                    methodRequest.put("requestValidatorId", validatorNameToId.get("__default__"));
                }

                putMethod(region, apiId, resourceId, httpMethod, methodRequest);

                // Extract x-amazon-apigateway-integration extension
                Map<String, Object> integrationExt = null;
                if (operation.getExtensions() != null) {
                    integrationExt = (Map<String, Object>) operation.getExtensions()
                            .get("x-amazon-apigateway-integration");
                }

                if (integrationExt != null) {
                    applyIntegration(region, apiId, resourceId, httpMethod, integrationExt);
                }
            }
        }
    }

    private String ensureResourcePath(String region, String apiId, String path,
                                      Map<String, String> pathToResourceId) {
        if (pathToResourceId.containsKey(path)) {
            return pathToResourceId.get(path);
        }

        // Split path into segments and create each one
        String[] segments = path.split("/");
        StringBuilder currentPath = new StringBuilder();
        String parentId = pathToResourceId.get("/");

        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty()) continue;
            currentPath.append("/").append(segment);
            String fullPath = currentPath.toString();

            if (!pathToResourceId.containsKey(fullPath)) {
                Map<String, Object> request = new HashMap<>();
                request.put("pathPart", segment);
                ApiGatewayResource resource = createResource(region, apiId, parentId, request);
                pathToResourceId.put(fullPath, resource.getId());
            }
            parentId = pathToResourceId.get(fullPath);
        }

        return parentId;
    }

    @SuppressWarnings("unchecked")
    private void applyIntegration(String region, String apiId, String resourceId,
                                  String httpMethod, Map<String, Object> integrationExt) {
        Map<String, Object> integrationRequest = new HashMap<>();
        integrationRequest.put("type", integrationExt.get("type"));
        integrationRequest.put("httpMethod", integrationExt.get("httpMethod"));
        integrationRequest.put("uri", integrationExt.get("uri"));
        integrationRequest.put("passthroughBehavior", integrationExt.get("passthroughBehavior"));

        Map<String, String> reqParams = (Map<String, String>) integrationExt.get("requestParameters");
        if (reqParams != null) integrationRequest.put("requestParameters", reqParams);

        Map<String, String> reqTemplates = (Map<String, String>) integrationExt.get("requestTemplates");
        if (reqTemplates != null) integrationRequest.put("requestTemplates", reqTemplates);

        putIntegration(region, apiId, resourceId, httpMethod, integrationRequest);

        // Process integration responses
        Map<String, Object> responses = (Map<String, Object>) integrationExt.get("responses");
        if (responses != null) {
            for (Map.Entry<String, Object> respEntry : responses.entrySet()) {
                String selectionPattern = respEntry.getKey();
                Map<String, Object> respDef = (Map<String, Object>) respEntry.getValue();

                String statusCode = String.valueOf(respDef.getOrDefault("statusCode", "200"));
                String pattern = "default".equals(selectionPattern) ? "" : selectionPattern;

                Map<String, Object> irRequest = new HashMap<>();
                irRequest.put("selectionPattern", pattern);
                irRequest.put("responseParameters", respDef.get("responseParameters"));
                irRequest.put("responseTemplates", respDef.get("responseTemplates"));

                putIntegrationResponse(region, apiId, resourceId, httpMethod, statusCode, irRequest);

                // Ensure method response exists for this status code
                putMethodResponse(region, apiId, resourceId, httpMethod, statusCode, new HashMap<>());
            }
        }
    }

    // ──────────────────────────── Key helpers ────────────────────────────

    private String apiKey(String region, String apiId) {
        return region + "::" + apiId;
    }

    private String resourceKey(String region, String apiId, String resourceId) {
        return region + "::" + apiId + "::" + resourceId;
    }

    private String deploymentKey(String region, String apiId, String deploymentId) {
        return region + "::" + apiId + "::" + deploymentId;
    }

    private String stageKey(String region, String apiId, String stageName) {
        return region + "::" + apiId + "::" + stageName;
    }

    private String authorizerKey(String region, String apiId, String authorizerId) {
        return region + "::" + apiId + "::" + authorizerId;
    }

    private String requestValidatorKey(String region, String apiId, String validatorId) {
        return region + "::" + apiId + "::" + validatorId;
    }

    private String modelKey(String region, String apiId, String modelName) {
        return region + "::" + apiId + "::" + modelName;
    }

    private String apiKeyGlobalKey(String region, String apiKeyId) {
        return region + "::" + apiKeyId;
    }

    private String usagePlanKey(String region, String usagePlanId) {
        return region + "::" + usagePlanId;
    }

    private String usagePlanKeyPathKey(String region, String usagePlanId, String keyId) {
        return region + "::" + usagePlanId + "::" + keyId;
    }

    private String domainKey(String region, String domainName) {
        return region + "::" + domainName;
    }

    private String mappingKey(String region, String domainName, String basePath) {
        return region + "::" + domainName + "::" + basePath;
    }

    private static String shortId(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }
}
