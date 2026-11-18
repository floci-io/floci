package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.apigateway.ApiGatewayService;
import io.floci.aws.services.apigateway.model.ApiGatewayResource;
import io.floci.aws.services.apigateway.model.Authorizer;
import io.floci.aws.services.apigateway.model.Deployment;
import io.floci.aws.services.apigateway.model.EndpointConfiguration;
import io.floci.aws.services.apigateway.model.EndpointType;
import io.floci.aws.services.apigateway.model.RestApi;
import io.floci.aws.services.apigateway.model.Stage;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.s3.S3Service;
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
    /** Where a {@link Location} names an authorizer, in place of the HTTP method. */
    private static final String AUTHORIZER_KIND = "AUTHORIZER";
    private static final String AUTHORIZER_SNAPSHOT_ATTR = CfnRollback.APIGW_AUTHORIZER_UPDATE_SNAPSHOT_ATTR;
    /** Where a {@link Location} names a stage: the stage name in place of the resource id. */
    private static final String STAGE_KIND = "STAGE";
    private static final String STAGE_SNAPSHOT_ATTR = CfnRollback.APIGW_STAGE_UPDATE_SNAPSHOT_ATTR;
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
     * it, so each removes its own entity, found through the location recorded for it. A Deployment
     * has no delete of its own yet. An Authorizer or Stage is deleted at the API recorded for it; one
     * created before its location was recorded is not deleted.
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
        if (location.stage()) {
            CfnDeletes.safeDelete("API stage", physicalId, () -> apiGatewayService.deleteStage(
                    region, location.restApiId(), location.resourceId()), "NotFoundException");
        } else if (location.authorizer()) {
            CfnDeletes.safeDelete("API authorizer", physicalId, () -> apiGatewayService.deleteAuthorizer(
                    region, location.restApiId(), location.resourceId()), "NotFoundException");
        } else if (location.httpMethod() == null) {
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
        resource.getAttributes().remove(AUTHORIZER_SNAPSHOT_ATTR);
        deleteStageMovedByUpdate(resource);
        resource.getAttributes().remove(STAGE_SNAPSHOT_ATTR);
        return ReplacementCleanup.complete(resource,
                (type, physicalId, region) -> deleteEntity(resource, physicalId, region));
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.REST_API_UPDATE_SNAPSHOT_ATTR);
        resource.getAttributes().remove(AUTHORIZER_SNAPSHOT_ATTR);
        resource.getAttributes().remove(STAGE_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * Keeps the snapshot of a kept API whose own undo did not complete. The engine restores the
     * previous resource and reports the rollback failure once; with the snapshot, a
     * ContinueUpdateRollback tries the restore again, or fails again when the update's document had
     * replaced the definition, instead of finding nothing to undo.
     */
    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        String snapshot = attempted.getAttributes().get(CfnRollback.REST_API_UPDATE_SNAPSHOT_ATTR);
        if (REST_API.equals(previous.getResourceType()) && snapshot != null
                && attempted.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR)) {
            previous.getAttributes().put(CfnRollback.REST_API_UPDATE_SNAPSHOT_ATTR, snapshot);
        }
    }

    /**
     * Puts a replaced RestApi, Resource, Method, Authorizer or Stage back when a later resource fails the
     * update, a RestApi patched in place back to its snapshot, and an Authorizer patched in place, or
     * created because the prior was gone, back through its own snapshot, and the same for a Stage.
     * Without a replacement, a Resource update
     * changed nothing, since all its properties are createOnly, so there is nothing to undo. A
     * Method the update kept was written in place, with no snapshot to put back.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        JsonNode authorizerSnapshot = AUTHORIZER.equals(resource.getResourceType())
                ? CfnRollback.updateSnapshot(resource, AUTHORIZER_SNAPSHOT_ATTR) : null;
        if (authorizerSnapshot != null) {
            return rollbackAuthorizerUpdate(resource, authorizerSnapshot);
        }
        JsonNode stageSnapshot = STAGE.equals(resource.getResourceType())
                ? CfnRollback.updateSnapshot(resource, STAGE_SNAPSHOT_ATTR) : null;
        if (stageSnapshot != null) {
            return rollbackStageUpdate(resource, stageSnapshot);
        }
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
     * Patches the API back from the snapshot taken before its in-place update. An update whose
     * OpenAPI document was applied replaced the API's resources, methods and models, which the
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

    /**
     * A resource of a REST API, or with an HTTP method, one of that resource's methods. An
     * authorizer is the API and the authorizer id, with the kind in place of the method.
     */
    private record Location(String restApiId, String resourceId, String httpMethod) {

        /** An authorizer, recorded with {@link #AUTHORIZER_KIND} where a method would name its verb. */
        boolean authorizer() {
            return AUTHORIZER_KIND.equals(httpMethod);
        }

        /** A stage, recorded with {@link #STAGE_KIND} and its name as the resource id. */
        boolean stage() {
            return STAGE_KIND.equals(httpMethod);
        }
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
        // The declared Name and Description; null clears an undeclared Description.
        List<Map<String, String>> nameAndDescription = List.of(
                replacePatchOp("/name", name), replacePatchOp("/description", description));

        RestApi api;
        if (existing != null) {
            snapshotBeforeUpdate(r, existing, region);
            List<Map<String, String>> operations = new ArrayList<>(nameAndDescription);
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
        boolean created = existing == null;
        // A step that fails once the API exists undoes this provision. An API it created is deleted
        // again; if that delete fails too, a create leaves the API marked as created by the stack, and
        // an update lists it for the stack's next cleanup. A kept API gets back what the update
        // patched (putRestApi leaves the API as it was when it rejects the document); when its
        // document was already applied, the definition cannot be put back, which is reported as a
        // rollback failure.
        CfnRollback.guarded(r, ctx, created,
                () -> delete(REST_API, api.getId(), region),
                () -> {
                    JsonNode snapshot = readSnapshot(r);
                    restore(api.getId(), snapshot);
                    if (snapshot.path("body").asBoolean(false)) {
                        throw new IllegalStateException("its OpenAPI document was already applied, and the"
                                + " update keeps no copy of the definition it replaced");
                    }
                },
                () -> {
                    r.getAttributes().put("RestApiId", api.getId());
                    // Not the first of getResources, which is the root only on a freshly created API.
                    r.getAttributes().put("RootResourceId",
                            apiGatewayService.findRootResourceId(region, api.getId()).orElseThrow());
                    if (openApiDocument != null) {
                        apiGatewayService.putRestApi(region, api.getId(), "overwrite", openApiDocument.toString());
                        markDocumentApplied(r);
                        // putRestApi overwrites Name and Description from the document's info, so the
                        // declared ones are applied again.
                        apiGatewayService.updateRestApi(region, api.getId(), nameAndDescription);
                    }
                });
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
     * configuration the API has now, so {@link #rollbackUpdate} can put them back. Its {@code body}
     * flag starts false and is set by {@link #markDocumentApplied} once the update's OpenAPI
     * document has replaced the API's definition.
     */
    private void snapshotBeforeUpdate(StackResource r, RestApi existing, String region) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("name", existing.getName());
        snapshot.put("description", existing.getDescription());
        snapshot.put("endpointType", endpointType(existing.getEndpointConfiguration()));
        ArrayNode vpcEndpointIds = snapshot.putArray("vpcEndpointIds");
        if (existing.getEndpointConfiguration() != null) {
            existing.getEndpointConfiguration().getVpcEndpointIds().forEach(vpcEndpointIds::add);
        }
        snapshot.put("body", false);
        r.getAttributes().put(CfnRollback.REST_API_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
    }

    /**
     * Records on an in-place update's snapshot that putRestApi accepted its OpenAPI document, which
     * replaced the API's resources, methods and models: the snapshot cannot put those back. An API
     * this provision created has no snapshot.
     */
    private void markDocumentApplied(StackResource r) {
        if (!r.getAttributes().containsKey(CfnRollback.REST_API_UPDATE_SNAPSHOT_ATTR)) {
            return;
        }
        ObjectNode snapshot = (ObjectNode) readSnapshot(r);
        snapshot.put("body", true);
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

    /**
     * RestApiId is the one create-only property: a change to it replaces the authorizer, and any
     * other change patches the authorizer in place, which keeps its id for the methods that name it.
     */
    private void provisionAuthorizer(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(AUTHORIZER_SNAPSHOT_ATTR);
        if (ctx.isUpdate()) {
            ReplacementCleanup.endRollbackRetry(r);
        }
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String region = ctx.region();
        String apiId = ctx.resolveOptional(props, "RestApiId");
        PropertyChanges changes = ctx.isUpdate() ? ctx.changes(AUTHORIZER, props) : null;
        boolean replaces = changes != null && changes.requiresReplacement();
        Authorizer live = ctx.isUpdate() && !replaces
                ? findPrior("API authorizer", ctx.priorPhysicalId(),
                        () -> apiGatewayService.getAuthorizer(region, apiId, ctx.priorPhysicalId()))
                : null;
        if (live != null) {
            updateAuthorizerInPlace(r, props, ctx, apiId, live);
            return;
        }
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
        Authorizer authorizer = apiGatewayService.createAuthorizer(region, apiId, req);
        r.setPhysicalId(authorizer.getId());
        r.getAttributes().put("AuthorizerId", authorizer.getId());
        recordLocation(r, ctx, new Location(apiId, authorizer.getId(), AUTHORIZER_KIND));
        if (ctx.isUpdate() && !replaces) {
            // The authorizer the update started from is gone, so this one is the update's to undo.
            CfnRollback.recordCreatedByUpdate(r, ctx, AUTHORIZER_SNAPSHOT_ATTR);
        }
        if (ctx.isUpdate()) {
            // The authorizer the prior physical id named is deleted once the update commits, and the
            // new one is removed if the update rolls back.
            ReplacementCleanup.record(r, ctx, attributesBefore, replaces);
        }
    }

    /**
     * Patches the authorizer to the template. A declared property is set; a dropped one goes back to
     * its create default (the TTL, 300) or is cleared (identity source, Cognito pools), except the URI,
     * which the patch cannot remove and which is kept; one the
     * template never declared keeps what the authorizer has.
     */
    private void updateAuthorizerInPlace(StackResource r, JsonNode props, ProvisionContext ctx, String apiId,
                                         Authorizer live) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("region", ctx.region());
        snapshot.put("apiId", apiId);
        snapshot.put("authorizerId", live.getId());
        snapshot.put("name", live.getName());
        snapshot.put("type", live.getType());
        snapshot.put("authorizerUri", live.getAuthorizerUri());
        snapshot.put("identitySource", live.getIdentitySource());
        snapshot.put("authorizerResultTtlInSeconds", live.getAuthorizerResultTtlInSeconds());
        ArrayNode providers = snapshot.putArray("providerARNs");
        providerArns(live).forEach(providers::add);
        r.getAttributes().put(AUTHORIZER_SNAPSHOT_ATTR, snapshot.toString());

        Set<String> dropped = ctx.droppedProperties(props);
        String name = ctx.resolveOptional(props, "Name");
        String type = ctx.resolveOptional(props, "Type");
        String uri = ctx.resolveOptional(props, "AuthorizerUri");
        String identity = ctx.resolveOptional(props, "IdentitySource");
        String ttl = ctx.resolveOptional(props, "AuthorizerResultTtlInSeconds");
        List<String> arns = props != null && props.has("ProviderARNs")
                ? ctx.resolveStringList(props, "ProviderARNs") : null;
        applyAuthorizer(ctx.region(), apiId, live,
                name != null ? name : live.getName(),
                type != null ? type : live.getType(),
                orLive(uri, live.getAuthorizerUri()),
                identity != null || !dropped.contains("IdentitySource")
                        ? orLive(identity, live.getIdentitySource()) : null,
                ttl != null ? ttl : dropped.contains("AuthorizerResultTtlInSeconds") ? "300"
                        : live.getAuthorizerResultTtlInSeconds(),
                arns != null ? arns : dropped.contains("ProviderARNs") ? List.of() : providerArns(live));
        r.setPhysicalId(live.getId());
        r.getAttributes().put("AuthorizerId", live.getId());
        recordLocation(r, ctx, new Location(apiId, live.getId(), AUTHORIZER_KIND));
    }

    private static String orLive(String declared, String live) {
        return declared != null ? declared : live;
    }

    private static List<String> providerArns(Authorizer authorizer) {
        return authorizer.getProviderARNs() == null ? List.of() : authorizer.getProviderARNs();
    }

    /**
     * One UpdateAuthorizer patch that takes {@code live} to the values given; a null identity
     * source clears it. Nothing is sent when the authorizer already has them.
     */
    private void applyAuthorizer(String region, String apiId, Authorizer live, String name, String type,
                                 String uri, String identitySource, String ttl, List<String> providerArns) {
        List<Map<String, String>> ops = new ArrayList<>();
        addIfChanged(ops, "/name", live.getName(), name);
        addIfChanged(ops, "/type", live.getType(), type);
        if (uri != null) {
            // The patch has no remove for the URI: a drop keeps it, and a rollback writes it back whole.
            addIfChanged(ops, "/authorizerUri", live.getAuthorizerUri(), uri);
        }
        addIfChanged(ops, "/identitySource", live.getIdentitySource(), identitySource);
        addIfChanged(ops, "/authorizerResultTtlInSeconds", live.getAuthorizerResultTtlInSeconds(), ttl);
        for (String arn : providerArns) {
            if (!providerArns(live).contains(arn)) {
                ops.add(Map.of("op", "add", "path", "/providerARNs", "value", arn));
            }
        }
        for (String arn : providerArns(live)) {
            if (!providerArns.contains(arn)) {
                ops.add(Map.of("op", "remove", "path", "/providerARNs", "value", arn));
            }
        }
        if (!ops.isEmpty()) {
            apiGatewayService.updateAuthorizer(region, apiId, live.getId(), ops);
        }
    }

    private void addIfChanged(List<Map<String, String>> ops, String path, String current, String desired) {
        if (Objects.equals(current, desired)) {
            return;
        }
        ops.add(desired == null ? Map.of("op", "remove", "path", path) : replacePatchOp(path, desired));
    }

    /**
     * Puts an authorizer the update patched back as it was, or deletes the one the update created
     * because the prior was gone. Spent only once it succeeded, so a failure can be retried.
     */
    private boolean rollbackAuthorizerUpdate(StackResource resource, JsonNode snapshot) {
        if (CfnRollback.undoCreatedByUpdate(resource, snapshot, AUTHORIZER_SNAPSHOT_ATTR, "AuthorizerId",
                (createdId, region) -> deleteEntity(resource, createdId, region))) {
            return true;
        }
        String region = snapshot.path("region").asText();
        String apiId = snapshot.path("apiId").asText();
        List<String> arns = new ArrayList<>();
        snapshot.path("providerARNs").forEach(arn -> arns.add(arn.asText()));
        Authorizer prior = new Authorizer();
        prior.setId(snapshot.path("authorizerId").asText());
        prior.setName(CfnRollback.snapshotText(snapshot, "name"));
        prior.setType(CfnRollback.snapshotText(snapshot, "type"));
        prior.setAuthorizerUri(CfnRollback.snapshotText(snapshot, "authorizerUri"));
        prior.setIdentitySource(CfnRollback.snapshotText(snapshot, "identitySource"));
        prior.setAuthorizerResultTtlInSeconds(CfnRollback.snapshotText(snapshot, "authorizerResultTtlInSeconds"));
        prior.setProviderARNs(arns);
        apiGatewayService.restoreAuthorizer(region, apiId, prior);
        resource.getAttributes().remove(AUTHORIZER_SNAPSHOT_ATTR);
        return true;
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

    /**
     * RestApiId and StageName are create-only: a change to either replaces the stage. Any other
     * change patches it in place, keeping the settings the template does not model. A change to
     * RestApiId that keeps the name leaves the physical id as it was, so the stage on the old API
     * is recorded and deleted once the update commits, unless {@code UpdateReplacePolicy: Retain}
     * keeps it ({@link ReplacementCleanup} lists one entity per physical id, so it cannot hold this
     * one). A new stage is refused when its name is already taken on the API, so a rollback never
     * deletes a stage the update did not create. A stage provisioned before its location was
     * recorded is not deleted, and is not deleted as the old stage of a move either.
     */
    private void provisionStage(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(STAGE_SNAPSHOT_ATTR);
        if (ctx.isUpdate()) {
            ReplacementCleanup.endRollbackRetry(r);
        }
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String region = ctx.region();
        String apiId = ctx.resolveOptional(props, "RestApiId");
        String stageName = ctx.resolveOptional(props, "StageName");
        PropertyChanges changes = ctx.isUpdate() ? ctx.changes(STAGE, props) : null;
        boolean replaces = changes != null && changes.requiresReplacement();
        Stage live = ctx.isUpdate() && !replaces
                ? findPrior("API stage", ctx.priorPhysicalId(),
                        () -> apiGatewayService.getStage(region, apiId, ctx.priorPhysicalId()))
                : null;
        if (live != null) {
            updateStageInPlace(r, props, ctx, apiId, live);
            return;
        }
        Location prior = ctx.isUpdate() ? locations(r).get(ctx.priorPhysicalId()) : null;
        if (ctx.isUpdate()) {
            requireStageNameFree(region, apiId, stageName);
        }
        Map<String, Object> req = new HashMap<>();
        req.put("stageName", stageName);
        req.put("deploymentId", ctx.resolveOptional(props, "DeploymentId"));
        req.put("description", ctx.resolveOptional(props, "Description"));

        apiGatewayService.createStage(region, apiId, req);
        r.setPhysicalId(stageName);
        recordLocation(r, ctx, new Location(apiId, stageName, STAGE_KIND));
        if (ctx.isUpdate() && !replaces) {
            // The stage the update started from is gone, so this one is the update's to undo.
            CfnRollback.recordCreatedByUpdate(r, ctx, STAGE_SNAPSHOT_ATTR);
        } else if (replaces && stageName.equals(ctx.priorPhysicalId()) && prior != null
                && prior.stage() && !apiId.equals(prior.restApiId())) {
            ObjectNode moved = objectMapper.createObjectNode();
            moved.put("region", region);
            moved.put("apiId", apiId);
            moved.put("stageName", stageName);
            moved.put("movedFromApiId", prior.restApiId());
            r.getAttributes().put(STAGE_SNAPSHOT_ATTR, moved.toString());
        }
        if (ctx.isUpdate()) {
            ReplacementCleanup.record(r, ctx, attributesBefore, replaces);
        }
    }

    /**
     * Patches the stage to the template: a declared DeploymentId or Description is set, a dropped
     * Description is cleared (the patch has no remove, so an empty value, which the service reads as
     * cleared) and a dropped DeploymentId is kept, since the patch has no remove for
     * it. Stage variables, method settings and the other fields are not touched.
     */
    private void updateStageInPlace(StackResource r, JsonNode props, ProvisionContext ctx, String apiId,
                                    Stage live) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("region", ctx.region());
        snapshot.put("apiId", apiId);
        snapshot.put("stageName", live.getStageName());
        snapshot.put("deploymentId", live.getDeploymentId());
        snapshot.put("description", live.getDescription());
        r.getAttributes().put(STAGE_SNAPSHOT_ATTR, snapshot.toString());

        String deploymentId = ctx.resolveOptional(props, "DeploymentId");
        String description = ctx.resolveOptional(props, "Description");
        String desiredDescription = description != null ? description
                : ctx.droppedProperties(props).contains("Description") ? null : live.getDescription();
        List<Map<String, String>> ops = new ArrayList<>();
        if (deploymentId != null && !deploymentId.equals(live.getDeploymentId())) {
            ops.add(replacePatchOp("/deploymentId", deploymentId));
        }
        if (!Objects.equals(live.getDescription(), desiredDescription)) {
            ops.add(replacePatchOp("/description", desiredDescription == null ? "" : desiredDescription));
        }
        if (!ops.isEmpty()) {
            apiGatewayService.updateStage(ctx.region(), apiId, live.getStageName(), ops);
        }
        r.setPhysicalId(live.getStageName());
        recordLocation(r, ctx, new Location(apiId, live.getStageName(), STAGE_KIND));
    }

    /**
     * CreateStage here overwrites a stage of the same name, which would make a rollback delete one
     * the update did not create, so an update refuses a name already on the API.
     */
    private void requireStageNameFree(String region, String apiId, String stageName) {
        if (stageName != null && findPrior("API stage", stageName,
                () -> apiGatewayService.getStage(region, apiId, stageName)) != null) {
            throw new AwsException("ConflictException", "Stage already exists: " + stageName, 409);
        }
    }

    /**
     * Deletes the stage a RestApiId change left on the old API, once the update has committed;
     * {@code UpdateReplacePolicy: Retain} keeps it, as it keeps any displaced entity.
     */
    private void deleteStageMovedByUpdate(StackResource resource) {
        if ("Retain".equals(resource.getUpdateReplacePolicy())) {
            return;
        }
        JsonNode snapshot = STAGE.equals(resource.getResourceType())
                ? CfnRollback.updateSnapshot(resource, STAGE_SNAPSHOT_ATTR) : null;
        String movedFrom = snapshot == null ? null : CfnRollback.snapshotText(snapshot, "movedFromApiId");
        if (movedFrom != null) {
            String stageName = snapshot.path("stageName").asText();
            deleteLocation(stageName, new Location(movedFrom, stageName, STAGE_KIND),
                    snapshot.path("region").asText());
        }
    }

    /**
     * Puts the stage back after a failed stack update: deletes the one the update created because
     * the prior was gone, or the one a RestApiId change made on the new API (the stage on the old API
     * was never touched), or writes the snapshot of a patched stage back whole. Spent only once it
     * succeeded, so a failure can be retried.
     */
    private boolean rollbackStageUpdate(StackResource resource, JsonNode snapshot) {
        if (CfnRollback.undoCreatedByUpdate(resource, snapshot, STAGE_SNAPSHOT_ATTR, null,
                (createdId, region) -> deleteEntity(resource, createdId, region))) {
            return true;
        }
        String region = snapshot.path("region").asText();
        String apiId = snapshot.path("apiId").asText();
        String stageName = snapshot.path("stageName").asText();
        String movedFrom = CfnRollback.snapshotText(snapshot, "movedFromApiId");
        if (movedFrom != null) {
            deleteLocation(stageName, new Location(apiId, stageName, STAGE_KIND), region);
            ObjectNode recorded = objectMapper.createObjectNode();
            recorded.set(stageName, locationNode(new Location(movedFrom, stageName, STAGE_KIND)));
            resource.getAttributes().put(LOCATIONS_ATTR, recorded.toString());
            ReplacementCleanup.rollback(resource, (type, physicalId, regionName) -> { });
        } else {
            Stage prior = new Stage();
            prior.setStageName(stageName);
            prior.setDeploymentId(CfnRollback.snapshotText(snapshot, "deploymentId"));
            prior.setDescription(CfnRollback.snapshotText(snapshot, "description"));
            apiGatewayService.restoreStage(region, apiId, prior);
        }
        resource.getAttributes().remove(STAGE_SNAPSHOT_ATTR);
        return true;
    }

    /** Resolves an optional property, falling back to {@code defaultValue} when absent or blank. */
}
