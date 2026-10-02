package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigateway.model.ApiGatewayResource;
import io.github.hectorvent.floci.services.apigateway.model.Authorizer;
import io.github.hectorvent.floci.services.apigateway.model.Deployment;
import io.github.hectorvent.floci.services.apigateway.model.EndpointConfiguration;
import io.github.hectorvent.floci.services.apigateway.model.EndpointType;
import io.github.hectorvent.floci.services.apigateway.model.RestApi;
import io.github.hectorvent.floci.services.apigateway.model.Stage;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

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

    private static final Logger LOG = Logger.getLogger(ApiGatewayRestApiCfnProvisioner.class);

    private static final String REST_API = "AWS::ApiGateway::RestApi";
    private static final String RESOURCE = "AWS::ApiGateway::Resource";
    private static final String METHOD = "AWS::ApiGateway::Method";
    private static final String DEPLOYMENT = "AWS::ApiGateway::Deployment";
    private static final String STAGE = "AWS::ApiGateway::Stage";
    private static final String AUTHORIZER = "AWS::ApiGateway::Authorizer";
    /**
     * Where the entities a Resource or Method stack resource has named live, keyed by physical id:
     * the API of a Resource, and the API, resource and HTTP method of a Method. The physical id
     * cannot say it: a resource id does not name its API, and a method id joins parts that can
     * themselves contain the joining hyphen. The entry of a replaced entity stays, so the cleanup
     * after the update commits, or a rollback, deletes the right one.
     */
    private static final String LOCATIONS_ATTR = "__FlociApiGatewayLocations";
    private static final String VPC_ENDPOINT_IDS_PATH = "/endpointConfiguration/vpcEndpointIds";

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

    /**
     * A Resource or Method can sit on an API outside the stack, where no RestApi delete cascades to
     * it, so each removes its own entity, found through the location recorded for it. Deployment,
     * Stage and Authorizer have no delete of their own yet.
     */
    @Override
    public void delete(StackResource resource, String region) {
        deleteEntity(resource, resource.getPhysicalId(), region);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (REST_API.equals(resourceType)) {
            // Tolerate an API already removed out of band so DeleteStack does not fail on it;
            // deleteRestApi resolves the id first and raises NotFoundException when it is gone.
            CfnDeletes.safeDelete("REST API", physicalId,
                    () -> apiGatewayService.deleteRestApi(region, physicalId), "NotFoundException");
        } else if (METHOD.equals(resourceType)) {
            Location location = parseMethodId(physicalId);
            if (location != null) {
                deleteLocation(physicalId, location, region);
            }
        }
    }

    /**
     * Deletes the entity {@code physicalId} names, at the location recorded for it on
     * {@code resource}. A resource provisioned before locations were recorded has only its id to go
     * by, which names a method but not the API of a resource.
     */
    private void deleteEntity(StackResource resource, String physicalId, String region) {
        Location location = locations(resource).get(physicalId);
        if (location == null) {
            delete(resource.getResourceType(), physicalId, region);
            return;
        }
        deleteLocation(physicalId, location, region);
    }

    private void deleteLocation(String physicalId, Location location, String region) {
        if (location.httpMethod() == null) {
            CfnDeletes.safeDelete("API resource", physicalId, () -> apiGatewayService.deleteResource(
                    region, location.restApiId(), location.resourceId()), "NotFoundException");
        } else {
            CfnDeletes.safeDelete("API method", physicalId, () -> apiGatewayService.deleteMethod(
                    region, location.restApiId(), location.resourceId(), location.httpMethod()), "NotFoundException");
        }
    }

    /**
     * The location in a method's physical id, {@code <RestApiId>-<ResourceId>-<HttpMethod>}, for a
     * method provisioned before locations were recorded. Split from the right, which is right unless
     * the HTTP method has a hyphen: a custom API id can contain one, a resource id cannot.
     */
    private static Location parseMethodId(String physicalId) {
        int methodStart = physicalId == null ? -1 : physicalId.lastIndexOf('-');
        int resourceStart = methodStart > 0 ? physicalId.lastIndexOf('-', methodStart - 1) : -1;
        if (resourceStart <= 0) {
            return null;
        }
        return new Location(physicalId.substring(0, resourceStart),
                physicalId.substring(resourceStart + 1, methodStart), physicalId.substring(methodStart + 1));
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    /**
     * Deletes the Resource or Method a replacement displaced, once the stack update has committed.
     * {@link ReplacementCleanup} keeps it under {@code UpdateReplacePolicy: Retain}.
     */
    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource,
                (type, physicalId, region) -> deleteEntity(resource, physicalId, region));
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.REST_API_UPDATE_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * Puts a replaced RestApi, Resource or Method back when a later resource fails the update, and a
     * RestApi patched in place back to its snapshot. Without a replacement, a Resource update
     * changed nothing, since all its properties are createOnly, so there is nothing to undo. A
     * Method the update kept was written in place, with no snapshot to put back.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource,
                (type, physicalId, region) -> deleteEntity(resource, physicalId, region))) {
            return true;
        }
        if (REST_API.equals(resource.getResourceType())) {
            return rollbackRestApiUpdate(resource);
        }
        return RESOURCE.equals(resource.getResourceType());
    }

    /**
     * Patches the API back from the snapshot taken before its in-place update. An update that also
     * re-applied an OpenAPI document replaced the API's resources, methods and models, which the
     * snapshot does not hold, so its rollback is not implemented. With no snapshot, nothing changed.
     */
    private boolean rollbackRestApiUpdate(StackResource resource) {
        if (!resource.getAttributes().containsKey(CfnRollback.REST_API_UPDATE_SNAPSHOT_ATTR)) {
            return true;
        }
        JsonNode snapshot = readSnapshot(resource);
        if (snapshot.path("body").asBoolean(false)) {
            return false;
        }
        // Spent only once the restore succeeded, so a restore that throws can be tried again.
        restore(resource.getPhysicalId(), snapshot);
        resource.getAttributes().remove(CfnRollback.REST_API_UPDATE_SNAPSHOT_ATTR);
        return true;
    }

    /** A resource of a REST API, or with an HTTP method, one of that resource's methods. */
    private record Location(String restApiId, String resourceId, String httpMethod) {
    }

    private Map<String, Location> locations(StackResource r) {
        Map<String, Location> locations = new HashMap<>();
        String recorded = r.getAttributes().get(LOCATIONS_ATTR);
        if (recorded == null) {
            return locations;
        }
        try {
            objectMapper.readTree(recorded).fields().forEachRemaining(entry -> locations.put(entry.getKey(),
                    new Location(entry.getValue().path(0).asText(), entry.getValue().path(1).asText(),
                            entry.getValue().path(2).asText(null))));
        } catch (JsonProcessingException e) {
            LOG.warnv("Unreadable API Gateway locations on {0}, ignoring them: {1}", r.getLogicalId(), e.getMessage());
        }
        return locations;
    }

    /**
     * Records where the entity {@code r} now names lives, keeping the entry of the one this update
     * replaced until the next provision.
     */
    private void recordLocation(StackResource r, ProvisionContext ctx, Location location) {
        ObjectNode recorded = objectMapper.createObjectNode();
        Location prior = ctx.isUpdate() ? locations(r).get(ctx.priorPhysicalId()) : null;
        if (prior != null && !ctx.priorPhysicalId().equals(r.getPhysicalId())) {
            recorded.set(ctx.priorPhysicalId(), locationNode(prior));
        }
        recorded.set(r.getPhysicalId(), locationNode(location));
        r.getAttributes().put(LOCATIONS_ATTR, recorded.toString());
    }

    private ArrayNode locationNode(Location location) {
        ArrayNode node = objectMapper.createArrayNode().add(location.restApiId()).add(location.resourceId());
        return location.httpMethod() == null ? node : node.add(location.httpMethod());
    }

    private void provisionRestApi(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(CfnRollback.REST_API_UPDATE_SNAPSHOT_ATTR);
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String region = ctx.region();
        CloudFormationTemplateEngine engine = ctx.engine();
        // No RestApi property requires replacement, so an update keeps the API, and with it the id
        // and the invoke URL. An API removed out of band is created again.
        RestApi existing = ctx.isUpdate()
                ? findPrior("REST API", ctx.priorPhysicalId(),
                        () -> apiGatewayService.getRestApi(region, ctx.priorPhysicalId()))
                : null;
        String name = ctx.resolveOptional(props, "Name");
        if (name == null || name.isBlank()) {
            name = existing != null
                    ? existing.getName()
                    : ctx.generatePhysicalName(r.getLogicalId(), 255, false);
        }
        String description = ctx.resolveOptional(props, "Description");
        JsonNode endpoint = props != null ? props.get("EndpointConfiguration") : null;
        List<String> endpointTypes = endpoint != null ? ctx.resolveStringList(endpoint, "Types") : List.of();
        // CreateRestApi rejects more than one type. An update rejects them too, before anything changes.
        if (endpointTypes.size() > 1) {
            throw new AwsException("BadRequestException",
                    "Endpoint configuration types must contain exactly one value.", 400);
        }
        // A declared Body or BodyS3Location is the whole OpenAPI document. Measured on real AWS
        // it becomes the RestApi's Body with no synthesized Resource or Method, so putRestApi plus
        // applyOpenApiSpec is the only place that turns it into resources and methods. Resolved
        // before anything changes, so a document that cannot be read leaves the API as it was.
        JsonNode openApiDocument = OpenApiDocuments.resolve(props, engine, s3Service, objectMapper);

        RestApi api;
        if (existing != null) {
            snapshotBeforeUpdate(r, existing, region, openApiDocument != null);
            List<Map<String, String>> operations = new ArrayList<>(List.of(
                    replacePatchOp("/name", name), replacePatchOp("/description", description)));
            if (endpoint != null) {
                operations.addAll(endpointPatchOps(existing.getEndpointConfiguration(),
                        endpointTypes.isEmpty() ? null : endpointTypes.getFirst(),
                        ctx.resolveStringList(endpoint, "VpcEndpointIds")));
            }
            api = apiGatewayService.updateRestApi(region, existing.getId(), operations);
        } else {
            Map<String, Object> req = new HashMap<>();
            req.put("name", name);
            req.put("description", description);
            if (endpoint != null) {
                Map<String, Object> epReq = new HashMap<>();
                epReq.put("types", endpointTypes);
                epReq.put("vpcEndpointIds", ctx.resolveStringList(endpoint, "VpcEndpointIds"));
                req.put("endpointConfiguration", epReq);
            }
            api = apiGatewayService.createRestApi(region, req);
        }
        r.setPhysicalId(api.getId());
        r.getAttributes().put("RestApiId", api.getId());
        // Not the first of getResources, which is the root only on a freshly created API.
        r.getAttributes().put("RootResourceId",
                apiGatewayService.findRootResourceId(region, api.getId()).orElseThrow());

        // putRestApi overwrites Name and Description from the document's info, so the declared Name
        // and Description (null clearing an undeclared Description) are re-applied immediately after.
        if (openApiDocument != null) {
            try {
                apiGatewayService.putRestApi(region, api.getId(), "overwrite", openApiDocument.toString());
            } catch (RuntimeException failure) {
                if (existing != null) {
                    unwind(r, failure);
                } else if (ctx.isUpdate()) {
                    discard(r, api.getId(), region, failure);
                }
                throw failure;
            }
            apiGatewayService.updateRestApi(region, api.getId(),
                    List.of(replacePatchOp("/name", name), replacePatchOp("/description", description)));
        }
        // An API created in place of one removed out of band is a replacement: a rollback deletes it.
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * The patch operations that take the endpoint configuration {@code current} to {@code type} and
     * {@code vpcEndpointIds}: the type through the path that names the one the API has now, as
     * UpdateRestApi requires, then each VPC endpoint to associate or disassociate. A null
     * {@code type} keeps the current one.
     */
    private List<Map<String, String>> endpointPatchOps(EndpointConfiguration current, String type,
                                                       List<String> vpcEndpointIds) {
        String currentType = endpointType(current);
        List<String> currentIds = current == null ? List.of() : current.getVpcEndpointIds();
        List<Map<String, String>> operations = new ArrayList<>();
        if (type != null && !type.equalsIgnoreCase(currentType)) {
            operations.add(replacePatchOp("/endpointConfiguration/types/" + currentType, type));
        }
        for (String id : vpcEndpointIds) {
            if (!currentIds.contains(id)) {
                operations.add(Map.of("op", "add", "path", VPC_ENDPOINT_IDS_PATH, "value", id));
            }
        }
        for (String id : currentIds) {
            if (!vpcEndpointIds.contains(id)) {
                operations.add(Map.of("op", "remove", "path", VPC_ENDPOINT_IDS_PATH, "value", id));
            }
        }
        return operations;
    }

    /** The API's endpoint type; one stored without an endpoint configuration is REGIONAL. */
    private static String endpointType(EndpointConfiguration endpoint) {
        return endpoint == null || endpoint.getTypes().isEmpty()
                ? EndpointType.REGIONAL.name()
                : endpoint.getTypes().getFirst().name();
    }

    /**
     * Keeps what an in-place update is about to patch, the name, description and endpoint
     * configuration the API has now, so {@link #rollbackUpdate} can put them back, and whether the
     * update also re-applies an OpenAPI document.
     */
    private void snapshotBeforeUpdate(StackResource r, RestApi existing, String region, boolean body) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("name", existing.getName());
        snapshot.put("description", existing.getDescription());
        snapshot.put("endpointType", endpointType(existing.getEndpointConfiguration()));
        ArrayNode vpcEndpointIds = snapshot.putArray("vpcEndpointIds");
        if (existing.getEndpointConfiguration() != null) {
            existing.getEndpointConfiguration().getVpcEndpointIds().forEach(vpcEndpointIds::add);
        }
        snapshot.put("body", body);
        r.getAttributes().put(CfnRollback.REST_API_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
    }

    private JsonNode readSnapshot(StackResource r) {
        try {
            return objectMapper.readTree(r.getAttributes().get(CfnRollback.REST_API_UPDATE_SNAPSHOT_ATTR));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the REST API update snapshot for "
                    + r.getLogicalId(), e);
        }
    }

    /** Patches the API back to the name, description and endpoint configuration {@code snapshot} holds. */
    private void restore(String apiId, JsonNode snapshot) {
        String region = snapshot.path("region").asText();
        List<String> vpcEndpointIds = new ArrayList<>();
        snapshot.path("vpcEndpointIds").forEach(id -> vpcEndpointIds.add(id.asText()));
        List<Map<String, String>> operations = new ArrayList<>(List.of(
                replacePatchOp("/name", snapshot.path("name").asText(null)),
                replacePatchOp("/description", snapshot.path("description").asText(null))));
        operations.addAll(endpointPatchOps(apiGatewayService.getRestApi(region, apiId).getEndpointConfiguration(),
                snapshot.path("endpointType").asText(), vpcEndpointIds));
        apiGatewayService.updateRestApi(region, apiId, operations);
    }

    /**
     * An in-place update whose OpenAPI document putRestApi rejected puts back the name, description
     * and endpoint configuration it had already patched; putRestApi left the definition as it was.
     * CloudFormationService restores the resource the stack held before the attempt, which never
     * carried the snapshot, so the rollback walker would leave the patch in place. A restore that
     * fails is recorded as a rollback failure, so the stack ends in UPDATE_ROLLBACK_FAILED instead
     * of reporting the API intact.
     */
    private void unwind(StackResource r, RuntimeException failure) {
        try {
            restore(r.getPhysicalId(), readSnapshot(r));
        } catch (RuntimeException unwindFailure) {
            r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR,
                    "Could not roll back the update of REST API " + r.getPhysicalId() + ": "
                            + unwindFailure.getMessage());
            failure.addSuppressed(unwindFailure);
        }
    }

    /**
     * Deletes the API an update created in place of one removed out of band, when putRestApi
     * rejected its OpenAPI document. CloudFormationService restores the resource the stack held
     * before the attempt, so nothing else tracks the new API. An API that cannot be deleted is
     * listed for the next cleanup, which the restored resource inherits, and the update reports a
     * rollback failure. {@code failure} stays the reported error.
     */
    private void discard(StackResource r, String apiId, String region, RuntimeException failure) {
        try {
            delete(REST_API, apiId, region);
        } catch (RuntimeException deleteFailure) {
            failure.addSuppressed(deleteFailure);
            LOG.warnv("Could not remove REST API {0}, created for {1} by a failed update: {2}",
                    apiId, r.getLogicalId(), deleteFailure.getMessage());
            r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, "Could not remove REST API " + apiId
                    + ", created for " + r.getLogicalId() + " by a failed update: " + deleteFailure.getMessage());
            ReplacementCleanup.recordOrphan(r, apiId, REST_API, region);
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
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String region = ctx.region();
        String apiId = ctx.resolveOptional(props, "RestApiId");
        String parentId = ctx.resolveOptional(props, "ParentId");
        String pathPart = ctx.resolveOptional(props, "PathPart");

        // RestApiId, ParentId and PathPart are all createOnly, so an update that keeps them keeps
        // the resource. Creating it again collides with itself under the same parent.
        ApiGatewayResource res = ctx.isUpdate()
                ? findPrior("API resource", ctx.priorPhysicalId(),
                        () -> apiGatewayService.getResource(region, apiId, ctx.priorPhysicalId()))
                : null;
        if (res == null || !Objects.equals(res.getParentId(), parentId)
                || !Objects.equals(res.getPathPart(), pathPart)) {
            Map<String, Object> req = new HashMap<>();
            req.put("pathPart", pathPart);
            res = apiGatewayService.createResource(region, apiId, parentId, req);
        }
        r.setPhysicalId(res.getId());
        r.getAttributes().put("ResourceId", res.getId());
        recordLocation(r, ctx, new Location(apiId, res.getId(), null));
        // A replaced resource is deleted, with its methods, once the stack update commits.
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /** What {@code lookup} finds, or null when the entity from the previous provision is gone. */
    private static <T> T findPrior(String description, String id, Supplier<T> lookup) {
        try {
            return lookup.get();
        } catch (AwsException e) {
            if (!"NotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("{0} {1} from the previous provision is gone, creating a new one", description, id);
            return null;
        }
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
        if (props != null && props.has("ProviderARNs")) {
            req.put("providerARNs", ctx.resolveStringList(props, "ProviderARNs"));
        }
        Authorizer authorizer = apiGatewayService.createAuthorizer(ctx.region(), apiId, req);
        r.setPhysicalId(authorizer.getId());
        r.getAttributes().put("AuthorizerId", authorizer.getId());
    }

    private void provisionMethod(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
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
        // putMethod upper-cases the method, so a change of case alone names the same method. The
        // prior id stays, or the cleanup would delete the method as the one this update replaced.
        String physicalId = apiId + "-" + resourceId + "-" + httpMethod;
        r.setPhysicalId(ctx.isUpdate() && ctx.priorPhysicalId().equalsIgnoreCase(physicalId)
                ? ctx.priorPhysicalId() : physicalId);

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

        // RestApiId, ResourceId and HttpMethod are createOnly, so a different one is a new method,
        // and the one it replaced is deleted once the stack update commits.
        recordLocation(r, ctx, new Location(apiId, resourceId, httpMethod));
        ReplacementCleanup.record(r, ctx, attributesBefore);
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
