package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.AppSyncService;
import io.github.hectorvent.floci.services.appsync.model.ApiKey;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.FunctionConfiguration;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;
import io.github.hectorvent.floci.services.appsync.model.Resolver;
import io.github.hectorvent.floci.services.appsync.model.ResolverKind;
import io.github.hectorvent.floci.services.appsync.model.SchemaCreationStatus;
import io.github.hectorvent.floci.services.appsync.model.SchemaCreationStatusType;
import io.github.hectorvent.floci.services.cloudformation.CfnCreateOnlyProperties;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Provisions the AppSync resource types a GraphQL API is deployed from: the API itself, its schema,
 * data sources, pipeline functions, resolvers and API keys.
 *
 * <p><b>Property names are translated, not passed through.</b> Unlike the services whose wire
 * protocol is already PascalCase, the AppSync control plane is REST-JSON with camelCase members, so
 * every CloudFormation property is lower-camelised on the way in ({@code AuthenticationType} →
 * {@code authenticationType}) recursively, including inside nested config objects. One key does not
 * follow from the rule: {@code DynamoDBConfig} is {@code dynamodbConfig} on the API, not
 * {@code dynamoDBConfig}.
 *
 * <p><b>The schema resource blocks until compilation finishes.</b> {@code StartSchemaCreation} is
 * asynchronous, and while it is PROCESSING every other AppSync call for that API is refused with a
 * 409. A template creates the schema and its data sources and resolvers in one stack, so returning
 * as soon as the job is submitted would fail the resources that follow, non-deterministically,
 * depending on how fast the worker got there. Waiting for a terminal status is also what
 * CloudFormation does: {@code AWS::AppSync::GraphQLSchema} is not CREATE_COMPLETE until the schema
 * is active. A schema that fails to compile fails the resource, with the compiler's own message.
 *
 * <p><b>Physical ids follow the registry schemas' primary identifiers</b>, which for most of these
 * types is an ARN rather than a name: the data source, function and resolver are addressed by
 * {@code DataSourceArn}, {@code FunctionArn} and {@code ResolverArn}. The API is its {@code ApiId}
 * and an API key its key id. Because an ARN is not enough to address any of them on the control
 * plane, the identifying parts ({@code ApiId} plus name, function id, or type/field) are recorded as
 * attributes at create time and read back on update and delete, hence the
 * {@code delete(StackResource, String)} override.
 *
 * <p><b>Inline definitions only.</b> {@code DefinitionS3Location} and {@code CodeS3Location} are
 * refused with a clear message rather than silently producing an API with an empty schema or a
 * resolver with no code. Serverless Framework and the AppSync plugins inline both.
 *
 * <p><b>An in-place update is undone from a snapshot.</b> Before the update call of an API, data
 * source, function, resolver or API key, the members the update can change or clear are read back
 * from the service and kept on the resource ({@link CfnRollback#APPSYNC_UPDATE_SNAPSHOT_ATTR}).
 * The service applies an update member by {@code containsKey}, so replaying every recorded member,
 * an old value or an explicit null, puts back what the update changed and what it cleared. A
 * stack update that fails afterwards, or the update call itself failing part way, restores it.
 */
@ApplicationScoped
public class AppSyncCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(AppSyncCfnProvisioner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private static final String GRAPHQL_API = "AWS::AppSync::GraphQLApi";
    private static final String GRAPHQL_SCHEMA = "AWS::AppSync::GraphQLSchema";
    private static final String DATA_SOURCE = "AWS::AppSync::DataSource";
    private static final String FUNCTION_CONFIGURATION = "AWS::AppSync::FunctionConfiguration";
    private static final String RESOLVER = "AWS::AppSync::Resolver";
    private static final String API_KEY = "AWS::AppSync::ApiKey";

    /** The service's own "already gone", tolerated on delete and nothing else. */
    private static final String NOT_FOUND = "NotFoundException";

    /**
     * Properties whose value is a free-form map, not a shape with member names: their keys are data
     * and must survive verbatim. Lower-camelising them turns an environment variable called
     * {@code STRIPE_PRODUCT_SMALL_ID} into {@code sTRIPE_PRODUCT_SMALL_ID}, which the API stores
     * happily and no resolver can then read.
     */
    private static final Set<String> OPAQUE_KEY_PROPERTIES = Set.of("EnvironmentVariables");

    /**
     * Properties the provisioner fills itself, so a template that drops one still has it on the
     * request: the identifiers, and the two the provisioner handles in its own call ({@code Tags}
     * as a map, {@code EnvironmentVariables} as a second call).
     */
    private static final Set<String> NEVER_CLEARED =
            Set.of("ApiId", "Name", "TypeName", "FieldName", "Tags", "EnvironmentVariables");

    /**
     * Required properties of a type, which a template cannot drop and the service cannot hold as
     * null, {@code Expires}, whose update the service parses as a date (null is refused), and the
     * GraphQLApi {@code ApiType} and {@code Visibility}, which UpdateGraphqlApi does not take
     * (CreateGraphqlApi: visibility "cannot be changed once the API has been created"): the stored
     * value stays. A resolver's {@code DataSourceName} stays here too and is cleared only for
     * a pipeline resolver, in {@link #provisionResolver}.
     */
    private static final Map<String, Set<String>> KEPT_ON_DROP = Map.of(
            GRAPHQL_API, Set.of("AuthenticationType", "ApiType", "Visibility"),
            DATA_SOURCE, Set.of("Type"),
            FUNCTION_CONFIGURATION, Set.of("DataSourceName"),
            RESOLVER, Set.of("DataSourceName"),
            API_KEY, Set.of("Expires"));

    /**
     * What a dropped property goes back to where the service's update does not take null as a
     * clear: it ignores a null boolean or list, or the member has a default on create that a null
     * would turn into an absent value.
     */
    private static final Map<String, Map<String, Object>> DEFAULT_ON_DROP = Map.of(
            GRAPHQL_API, Map.of("XrayEnabled", false,
                    "AdditionalAuthenticationProviders", List.of()),
            FUNCTION_CONFIGURATION, Map.of("FunctionVersion", "2018-05-29"),
            RESOLVER, Map.of("Kind", "UNIT"));

    /**
     * The members an update of each type can change or clear, which a snapshot records and a
     * restore replays. A GraphQL API's {@code apiType} and {@code visibility} are here because a
     * template that changes them reaches the update request, which applies both. An API key's
     * {@code expires} joins when the update sends it.
     */
    private static final Map<String, List<String>> ROLLBACK_MEMBERS = Map.of(
            GRAPHQL_API, List.of("name", "authenticationType", "apiType", "visibility", "xrayEnabled",
                    "logConfig", "additionalAuthenticationProviders", "tags", "owner", "ownerContact",
                    "introspectionConfig", "queryDepthLimit", "resolverCountLimit",
                    "enhancedMetricsConfig", "lambdaAuthorizerConfig", "openIDConnectConfig",
                    "userPoolConfig", "dns", "wafWebAclArn", "mergedApiExecutionRoleArn"),
            DATA_SOURCE, List.of("description", "type", "serviceRoleArn", "dynamodbConfig",
                    "lambdaConfig", "httpConfig", "eventBridgeConfig", "relationalDatabaseConfig",
                    "openSearchServiceConfig", "amazonBedrockRuntimeConfig"),
            FUNCTION_CONFIGURATION, List.of("name", "description", "dataSourceName",
                    "requestMappingTemplate", "responseMappingTemplate", "functionVersion", "code",
                    "runtime", "maxBatchSize"),
            RESOLVER, List.of("dataSourceName", "functionId", "requestMappingTemplate",
                    "responseMappingTemplate", "kind", "code", "runtime", "cachingConfig",
                    "maxBatchSize", "metricsConfig", "pipelineConfig", "syncConfig"),
            API_KEY, List.of("description"));

    /**
     * How long a stack resource waits for a schema to compile. Generous: the wait is bounded only so
     * a wedged worker cannot hang a stack operation forever, and a schema that hits this is reported
     * as a resource failure rather than left in an unknown state.
     */
    private static final long SCHEMA_TIMEOUT_MILLIS = 120_000L;
    private static final long SCHEMA_POLL_MILLIS = 25L;

    private final AppSyncService appSyncService;

    @Inject
    public AppSyncCfnProvisioner(AppSyncService appSyncService) {
        this.appSyncService = appSyncService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(GRAPHQL_API, GRAPHQL_SCHEMA, DATA_SOURCE, FUNCTION_CONFIGURATION, RESOLVER, API_KEY);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(CfnRollback.APPSYNC_UPDATE_SNAPSHOT_ATTR);
        switch (r.getResourceType()) {
            case GRAPHQL_API -> provisionGraphqlApi(r, props, ctx);
            case GRAPHQL_SCHEMA -> provisionSchema(r, props, ctx);
            case DATA_SOURCE -> provisionDataSource(r, props, ctx);
            case FUNCTION_CONFIGURATION -> provisionFunction(r, props, ctx);
            case RESOLVER -> provisionResolver(r, props, ctx);
            case API_KEY -> provisionApiKey(r, props, ctx);
            default -> throw new IllegalStateException(
                    "AppSyncCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    // ── GraphQLApi ───────────────────────────────────────────────────────────

    private void provisionGraphqlApi(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, Object> req = camelisedProperties(props, ctx);
        // Handled separately from the create request: the API's tags arrive as CloudFormation's
        // [{Key, Value}] list rather than the map the service takes, and its environment variables
        // are a second call (PutGraphqlApiEnvironmentVariables) rather than a create member.
        req.remove("tags");
        Object environmentVariables = req.remove("environmentVariables");
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        // Sent on every update, empty map included: CloudFormation drives a resource to the
        // template's desired state, so a tag the template no longer declares has to go. Omitting
        // the member left the old tags in place. On a create an empty map is left out, so a
        // resource with no tags keeps a null tag map rather than an empty one.
        if (!tags.isEmpty() || ctx.isUpdate()) {
            req.put("tags", tags);
        }
        if (blank(str(req.get("name")))) {
            req.put("name", ctx.generatePhysicalName(r.getLogicalId(), 65, false));
        }
        if (ctx.isUpdate()) {
            clearDropped(req, props, ctx, GRAPHQL_API);
        }

        Map<String, String> templateEnvironment = stringMap(environmentVariables);
        // The property was dropped from the template. The call replaces the whole set, so an
        // empty map is what clears it; skipping the call left the old variables readable by
        // every resolver.
        Map<String, String> resolvedEnvironment = templateEnvironment == null && ctx.isUpdate()
                ? Map.of() : templateEnvironment;
        GraphqlApi api;
        if (ctx.isUpdate()) {
            String priorApiId = ctx.priorPhysicalId();
            snapshotBeforeUpdate(r, ctx, appSyncService.getGraphqlApi(priorApiId),
                    Map.of("apiId", priorApiId), req, appSyncService.getEnvironmentVariables(priorApiId));
            api = updateInPlace(r, ctx, () -> {
                GraphqlApi updated = appSyncService.updateGraphqlApi(priorApiId, req, ctx.region());
                if (resolvedEnvironment != null) {
                    appSyncService.putEnvironmentVariables(updated.getApiId(), resolvedEnvironment);
                }
                return updated;
            });
        } else {
            api = appSyncService.createGraphqlApi(req, ctx.region());
            if (resolvedEnvironment != null) {
                appSyncService.putEnvironmentVariables(api.getApiId(), resolvedEnvironment);
            }
        }

        r.setPhysicalId(api.getApiId());
        r.getAttributes().put("ApiId", api.getApiId());
        r.getAttributes().put("Arn", api.getArn());
        Map<String, String> uris = api.getUris() == null ? Map.of() : api.getUris();
        String graphqlUrl = uris.get("GRAPHQL");
        String realtimeUrl = uris.get("REALTIME");
        if (!blank(graphqlUrl)) {
            r.getAttributes().put("GraphQLUrl", graphqlUrl);
            // The DNS attributes are the hosts of those two endpoints, which is what they are on
            // AWS, a custom domain's CNAME target. Derived rather than stored, so they cannot
            // drift from the URL beside them.
            r.getAttributes().put("GraphQLDns", host(graphqlUrl));
        }
        if (!blank(realtimeUrl)) {
            r.getAttributes().put("RealtimeUrl", realtimeUrl);
            r.getAttributes().put("RealtimeDns", host(realtimeUrl));
        }
    }

    // ── GraphQLSchema ────────────────────────────────────────────────────────

    private void provisionSchema(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, Object> req = camelisedProperties(props, ctx);
        String apiId = requiredApiId(req, GRAPHQL_SCHEMA);
        String definition = str(req.get("definition"));
        if (blank(definition)) {
            if (req.get("definitionS3Location") != null) {
                throw new AwsException("ValidationError", GRAPHQL_SCHEMA
                        + " DefinitionS3Location is not supported by Floci; give the schema inline in"
                        + " Definition instead.", 400);
            }
            throw new AwsException("ValidationError",
                    GRAPHQL_SCHEMA + " requires Definition.", 400);
        }

        appSyncService.startSchemaCreation(apiId, definition);
        awaitSchemaCreation(apiId);

        // AWS's own id for the schema resource. Nothing references it, but it has to stay put across
        // updates, and deriving it from the API id does that without inventing a random one.
        // The schema resource's own id, which is what the registry schema calls its primary
        // identifier and its one read-only property.
        String schemaId = apiId + "GraphQLSchema";
        r.setPhysicalId(schemaId);
        r.getAttributes().put("Id", schemaId);
        r.getAttributes().put("ApiId", apiId);
    }

    /**
     * Blocks until the schema reaches a terminal status, and turns a failed compilation into a
     * failed stack resource. SUCCESS and ACTIVE both mean done: the worker writes SUCCESS, and
     * ACTIVE is the status a schema loaded from a previous run carries.
     */
    private void awaitSchemaCreation(String apiId) {
        long deadline = System.currentTimeMillis() + SCHEMA_TIMEOUT_MILLIS;
        while (true) {
            SchemaCreationStatus status = appSyncService.getSchemaCreationStatus(apiId);
            SchemaCreationStatusType type = status == null ? null : status.getStatus();
            if (type == SchemaCreationStatusType.SUCCESS || type == SchemaCreationStatusType.ACTIVE) {
                return;
            }
            if (type == SchemaCreationStatusType.FAILED) {
                String details = status.getDetails();
                throw new AwsException("ValidationError", "Schema creation failed for API " + apiId
                        + (blank(details) ? "" : ": " + details), 400);
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new AwsException("ValidationError", "Schema creation for API " + apiId
                        + " did not complete within " + (SCHEMA_TIMEOUT_MILLIS / 1000) + "s (status "
                        + type + ").", 400);
            }
            try {
                Thread.sleep(SCHEMA_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AwsException("ValidationError",
                        "Interrupted while waiting for schema creation for API " + apiId, 400);
            }
        }
    }

    // ── DataSource ───────────────────────────────────────────────────────────

    private void provisionDataSource(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, Object> req = camelisedProperties(props, ctx);
        String apiId = requiredApiId(req, DATA_SOURCE);
        String name = str(req.get("name"));
        if (blank(name)) {
            // Name is create-only, so an unnamed data source must keep the name it already has or a
            // second UpdateStack would strand the first one, resolvers and all.
            name = firstNonBlank(attribute(r, "Name"),
                    ctx.generatePhysicalName(r.getLogicalId(), 65, false));
            req.put("name", name);
        }

        boolean update = reusesEntity(r, ctx, apiId) && name.equals(attribute(r, "Name"));
        DataSource ds;
        if (update) {
            clearDropped(req, props, ctx, DATA_SOURCE);
            String dataSourceName = name;
            snapshotBeforeUpdate(r, ctx, appSyncService.getDataSource(apiId, dataSourceName),
                    Map.of("apiId", apiId, "name", dataSourceName), req, null);
            ds = updateInPlace(r, ctx, () -> appSyncService.updateDataSource(apiId, dataSourceName, req));
        } else {
            ds = appSyncService.createDataSource(apiId, req, ctx.region());
        }

        r.setPhysicalId(ds.getDataSourceArn());
        r.getAttributes().put("DataSourceArn", ds.getDataSourceArn());
        r.getAttributes().put("Name", ds.getName());
        r.getAttributes().put("ApiId", apiId);
    }

    // ── FunctionConfiguration ────────────────────────────────────────────────

    private void provisionFunction(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, Object> req = camelisedProperties(props, ctx);
        String apiId = requiredApiId(req, FUNCTION_CONFIGURATION);
        rejectS3Code(req, FUNCTION_CONFIGURATION);
        if (blank(str(req.get("name")))) {
            req.put("name", firstNonBlank(attribute(r, "Name"),
                    ctx.generatePhysicalName(r.getLogicalId(), 65, false)));
        }

        String priorFunctionId = attribute(r, "FunctionId");
        boolean update = reusesEntity(r, ctx, apiId) && !blank(priorFunctionId);
        FunctionConfiguration fn;
        if (update) {
            clearDropped(req, props, ctx, FUNCTION_CONFIGURATION);
            snapshotBeforeUpdate(r, ctx, appSyncService.getFunction(apiId, priorFunctionId),
                    Map.of("apiId", apiId, "functionId", priorFunctionId), req, null);
            fn = updateInPlace(r, ctx, () -> appSyncService.updateFunction(apiId, priorFunctionId, req));
        } else {
            fn = appSyncService.createFunction(apiId, req, ctx.region());
        }

        r.setPhysicalId(fn.getFunctionArn());
        r.getAttributes().put("FunctionArn", fn.getFunctionArn());
        r.getAttributes().put("FunctionId", fn.getFunctionId());
        r.getAttributes().put("Name", fn.getName());
        if (!blank(fn.getDataSourceName())) {
            r.getAttributes().put("DataSourceName", fn.getDataSourceName());
        }
        r.getAttributes().put("ApiId", apiId);
    }

    // ── Resolver ─────────────────────────────────────────────────────────────

    private void provisionResolver(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, Object> req = camelisedProperties(props, ctx);
        String apiId = requiredApiId(req, RESOLVER);
        rejectS3Code(req, RESOLVER);
        String typeName = str(req.get("typeName"));
        String fieldName = str(req.get("fieldName"));
        if (blank(typeName) || blank(fieldName)) {
            throw new AwsException("ValidationError",
                    RESOLVER + " requires TypeName and FieldName.", 400);
        }

        boolean sameField = typeName.equals(attribute(r, "TypeName"))
                && fieldName.equals(attribute(r, "FieldName"));
        boolean update = reusesEntity(r, ctx, apiId) && sameField;
        Resolver resolver;
        if (update) {
            clearDropped(req, props, ctx, RESOLVER);
            // A UNIT resolver needs its data source (createResolver refuses none): clear it only on a pipeline.
            if (ctx.droppedProperties(props).contains("DataSourceName")
                    && isPipeline(req, apiId, typeName, fieldName)) {
                req.put("dataSourceName", null);
            }
            snapshotBeforeUpdate(r, ctx, appSyncService.getResolver(apiId, typeName, fieldName),
                    Map.of("apiId", apiId, "typeName", typeName, "fieldName", fieldName), req, null);
            resolver = updateInPlace(r, ctx,
                    () -> appSyncService.updateResolver(apiId, typeName, fieldName, req));
        } else {
            resolver = appSyncService.createResolver(apiId, req, ctx.region());
        }

        r.setPhysicalId(resolver.getResolverArn());
        r.getAttributes().put("ResolverArn", resolver.getResolverArn());
        r.getAttributes().put("TypeName", typeName);
        r.getAttributes().put("FieldName", fieldName);
        r.getAttributes().put("ApiId", apiId);
    }

    // ── ApiKey ───────────────────────────────────────────────────────────────

    private void provisionApiKey(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, Object> req = camelisedProperties(props, ctx);
        String apiId = requiredApiId(req, API_KEY);

        boolean update = reusesEntity(r, ctx, apiId);
        ApiKey key;
        if (update) {
            clearDropped(req, props, ctx, API_KEY);
            String priorKeyId = ctx.priorPhysicalId();
            snapshotBeforeUpdate(r, ctx, appSyncService.getApiKey(apiId, priorKeyId),
                    Map.of("apiId", apiId, "keyId", priorKeyId), req, null);
            key = updateInPlace(r, ctx, () -> appSyncService.updateApiKey(apiId, priorKeyId, req));
        } else {
            key = appSyncService.createApiKey(apiId, req);
        }

        r.setPhysicalId(key.getId());
        // On AWS the key id and the key value are the same string (the da2-… secret), and both
        // attributes are published: ApiKey is what a client sends as x-api-key.
        r.getAttributes().put("ApiKey", key.getId());
        r.getAttributes().put("ApiKeyId", key.getId());
        r.getAttributes().put("Arn", AwsArnUtils.Arn.of("appsync", ctx.region(), ctx.accountId(),
                "apis/" + apiId + "/apikeys/" + key.getId()).toString());
        r.getAttributes().put("ApiId", apiId);
    }

    // ── Rollback ─────────────────────────────────────────────────────────────

    /**
     * Puts an entity this update changed in place back to the members it had before, and answers
     * false for one it did not: a resource the failed update created is deleted by the engine, and
     * a replacement leaves no snapshot. The snapshot is spent only once the restore succeeded, so
     * a restore that throws keeps it for the next attempt.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (!resource.getAttributes().containsKey(CfnRollback.APPSYNC_UPDATE_SNAPSHOT_ATTR)) {
            return false;
        }
        restoreSnapshot(resource);
        return true;
    }

    /**
     * Keeps the snapshot of an in-place update whose own undo did not complete. The engine restores
     * the previous resource and reports the rollback failure once; with the snapshot of the state
     * the update started from, a ContinueUpdateRollback restores that state.
     */
    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        String snapshot = attempted.getAttributes().get(CfnRollback.APPSYNC_UPDATE_SNAPSHOT_ATTR);
        if (snapshot != null && attempted.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR)) {
            previous.getAttributes().put(CfnRollback.APPSYNC_UPDATE_SNAPSHOT_ATTR, snapshot);
        }
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.APPSYNC_UPDATE_SNAPSHOT_ATTR);
        return UpdateCleanupResult.notApplicable();
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.APPSYNC_UPDATE_SNAPSHOT_ATTR);
    }

    /**
     * Runs an in-place update so that a failure inside it, the call or a step after it, puts the
     * entity back from the snapshot before the failure propagates. The engine discards the
     * attempted resource, snapshot included, when the resource itself fails.
     */
    private <T> T updateInPlace(StackResource r, ProvisionContext ctx, Supplier<T> update) {
        AtomicReference<T> updated = new AtomicReference<>();
        CfnRollback.guarded(r, ctx, false, () -> { }, () -> restoreSnapshot(r),
                () -> updated.set(update.get()));
        return updated.get();
    }

    /**
     * Records what the entity looks like now, before the update call about to change it. The
     * service's getters throw NotFound for an entity that is gone, so the null guard only covers a
     * service that answers with nothing, as the mocked one in the unit tests does; the update then
     * runs without a snapshot. A member the entity does not carry is recorded as null, which the
     * restore sends as a clear. {@code environment} is a GraphQL API's variables, which have a
     * call of their own.
     */
    private void snapshotBeforeUpdate(StackResource r, ProvisionContext ctx, Object current,
                                      Map<String, String> identity, Map<String, Object> request,
                                      Map<String, String> environment) {
        if (current == null) {
            return;
        }
        Map<String, Object> entity = MAPPER.convertValue(current, OBJECT_MAP);
        Map<String, Object> members = new LinkedHashMap<>();
        for (String member : ROLLBACK_MEMBERS.get(r.getResourceType())) {
            members.put(member, entity.get(member));
        }
        if (API_KEY.equals(r.getResourceType()) && request.containsKey("expires")) {
            members.put("expires", entity.get("expires"));
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("region", ctx.region());
        snapshot.putAll(identity);
        snapshot.put("members", members);
        if (environment != null) {
            snapshot.put("environmentVariables", environment);
        }
        try {
            r.getAttributes().put(CfnRollback.APPSYNC_UPDATE_SNAPSHOT_ATTR, MAPPER.writeValueAsString(snapshot));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not record the AppSync update snapshot for "
                    + r.getLogicalId(), e);
        }
    }

    /**
     * Replays the snapshot through the update call that changed the entity, if the resource carries
     * one, and spends it. A GraphQL API's {@code xrayEnabled}, {@code additionalAuthenticationProviders}
     * and tags are not cleared by a null, so an API that had none gets {@code false}, an empty list
     * and an empty map back.
     */
    @SuppressWarnings("unchecked")
    private void restoreSnapshot(StackResource r) {
        String raw = r.getAttributes().get(CfnRollback.APPSYNC_UPDATE_SNAPSHOT_ATTR);
        if (raw == null) {
            return;
        }
        Map<String, Object> snapshot;
        try {
            snapshot = MAPPER.readValue(raw, OBJECT_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the AppSync update snapshot for "
                    + r.getLogicalId(), e);
        }
        Map<String, Object> members = (Map<String, Object>) snapshot.get("members");
        String apiId = str(snapshot.get("apiId"));
        switch (r.getResourceType()) {
            case GRAPHQL_API -> {
                members.computeIfAbsent("xrayEnabled", name -> false);
                members.computeIfAbsent("additionalAuthenticationProviders", name -> List.of());
                members.computeIfAbsent("tags", name -> Map.of());
                appSyncService.updateGraphqlApi(apiId, members, str(snapshot.get("region")));
                appSyncService.putEnvironmentVariables(apiId, stringMap(snapshot.get("environmentVariables")));
            }
            case DATA_SOURCE -> appSyncService.updateDataSource(apiId, str(snapshot.get("name")), members);
            case FUNCTION_CONFIGURATION -> {
                appSyncService.updateFunction(apiId, str(snapshot.get("functionId")), members);
                r.getAttributes().put("Name", str(members.get("name")));
                if (blank(str(members.get("dataSourceName")))) {
                    r.getAttributes().remove("DataSourceName");
                } else {
                    r.getAttributes().put("DataSourceName", str(members.get("dataSourceName")));
                }
            }
            case RESOLVER -> appSyncService.updateResolver(apiId, str(snapshot.get("typeName")),
                    str(snapshot.get("fieldName")), members);
            case API_KEY -> {
                String keyId = str(snapshot.get("keyId"));
                // The service refuses an expiry inside the next day, so one that has not moved is
                // not sent again.
                Object expires = members.get("expires");
                if (expires instanceof Number prior
                        && Objects.equals(prior.longValue(), appSyncService.getApiKey(apiId, keyId).getExpires())) {
                    members.remove("expires");
                }
                appSyncService.updateApiKey(apiId, keyId, members);
            }
            default -> throw new IllegalStateException(
                    "AppSyncCfnProvisioner cannot restore " + r.getResourceType());
        }
        r.getAttributes().remove(CfnRollback.APPSYNC_UPDATE_SNAPSHOT_ATTR);
    }

    // ── Deletes ──────────────────────────────────────────────────────────────

    /**
     * Every type but the API is addressed by its API id plus a name or id, none of which the ARN
     * that is the physical id can be split back into reliably, so the delete works from the
     * attributes recorded at create time.
     */
    @Override
    public void delete(StackResource r, String region) {
        String physicalId = r.getPhysicalId();
        if (blank(physicalId)) {
            return;
        }
        String apiId = attribute(r, "ApiId");
        switch (r.getResourceType()) {
            case GRAPHQL_API -> CfnDeletes.safeDelete("GraphQL API", physicalId,
                    () -> appSyncService.deleteGraphqlApi(physicalId), NOT_FOUND);
            // The schema is part of its API and goes when the API does; AWS likewise deletes the
            // AWS::AppSync::GraphQLSchema resource without a control-plane call of its own.
            case GRAPHQL_SCHEMA -> { }
            case DATA_SOURCE -> deleteChild(r, apiId, "data source", attribute(r, "Name"),
                    name -> appSyncService.deleteDataSource(apiId, name));
            case FUNCTION_CONFIGURATION -> deleteChild(r, apiId, "function",
                    attribute(r, "FunctionId"),
                    functionId -> appSyncService.deleteFunction(apiId, functionId));
            case RESOLVER -> deleteChild(r, apiId, "resolver",
                    attribute(r, "FieldName"),
                    fieldName -> appSyncService.deleteResolver(apiId, attribute(r, "TypeName"),
                            fieldName));
            case API_KEY -> deleteChild(r, apiId, "API key", physicalId,
                    keyId -> appSyncService.deleteApiKey(apiId, keyId));
            default -> LOG.warnv("No delete implemented for {0}", r.getResourceType());
        }
    }

    /**
     * Runs a child delete when the attributes identify one. A resource whose api id or name was
     * never recorded cannot be deleted by id alone; log it rather than throwing, which would fail a
     * stack delete over a resource the emulator has no way to find.
     */
    private void deleteChild(StackResource r, String apiId, String description, String childId,
                             Consumer<String> delete) {
        if (blank(apiId) || blank(childId)) {
            LOG.warnv("Cannot delete AppSync {0} for {1}: no recorded api id/name, it is not removed",
                    description, r.getLogicalId());
            return;
        }
        CfnDeletes.safeDelete("AppSync " + description, childId, () -> delete.accept(childId), NOT_FOUND);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Whether this provision should update the entity the resource already points at rather than
     * create a new one. An API id that changed means the resource was moved to a different API,
     * which is a replacement: the old entity lives in an API this resource no longer names.
     */
    private boolean reusesEntity(StackResource r, ProvisionContext ctx, String apiId) {
        return ctx.isUpdate() && apiId.equals(attribute(r, "ApiId"));
    }

    /**
     * Puts each property the previous template declared and this one drops on an update request as
     * its API member with a null value, which the service's update reads as a clear (it applies a
     * member by {@code containsKey}), so the entity stops carrying what the template no longer
     * names. A property the stack never declared is not on the request and keeps whatever was set
     * outside it. Left out: what the provisioner fills or handles itself, a create-only property
     * (dropping one is a replacement question), and the few {@link #KEPT_ON_DROP} properties the
     * service cannot hold as null; a few {@link #DEFAULT_ON_DROP} go back to their create default.
     */
    private void clearDropped(Map<String, Object> req, JsonNode props, ProvisionContext ctx,
                              String resourceType) {
        Set<String> kept = KEPT_ON_DROP.getOrDefault(resourceType, Set.of());
        Map<String, Object> defaults = DEFAULT_ON_DROP.getOrDefault(resourceType, Map.of());
        for (String name : ctx.droppedProperties(props)) {
            if (NEVER_CLEARED.contains(name) || kept.contains(name)
                    || CfnCreateOnlyProperties.isCreateOnly(resourceType, name)) {
                continue;
            }
            req.put(apiMemberName(name), defaults.get(name));
        }
    }

    private boolean isPipeline(Map<String, Object> req, String apiId, String typeName,
                               String fieldName) {
        Object kind = req.get("kind");
        if (kind == null) {
            Resolver stored = appSyncService.getResolver(apiId, typeName, fieldName);
            return stored != null && stored.getKind() == ResolverKind.PIPELINE;
        }
        return "PIPELINE".equals(kind.toString());
    }

    private void rejectS3Code(Map<String, Object> req, String resourceType) {
        if (req.get("codeS3Location") != null) {
            throw new AwsException("ValidationError", resourceType
                    + " CodeS3Location is not supported by Floci; give the resolver code inline in"
                    + " Code instead.", 400);
        }
    }

    private String requiredApiId(Map<String, Object> req, String resourceType) {
        String apiId = str(req.remove("apiId"));
        if (blank(apiId)) {
            throw new AwsException("ValidationError", resourceType + " requires ApiId.", 400);
        }
        return apiId;
    }

    /**
     * The resource's properties with intrinsics resolved and every key lower-camelised into the
     * member name the AppSync control plane uses.
     */
    private Map<String, Object> camelisedProperties(JsonNode props, ProvisionContext ctx) {
        if (props == null || props.isNull() || props.isMissingNode()) {
            return new LinkedHashMap<>();
        }
        JsonNode resolved = ctx.engine().resolveNode(props);
        Map<String, Object> req = new LinkedHashMap<>();
        if (resolved == null || !resolved.isObject()) {
            return req;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = resolved.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            // The property name is always translated; whether the names *inside* it are depends on
            // whether they are member names or somebody's data.
            req.put(apiMemberName(field.getKey()),
                    toApiValue(field.getValue(), !OPAQUE_KEY_PROPERTIES.contains(field.getKey())));
        }
        return req;
    }

    /**
     * Recursively converts a resolved property node to the Java value the service expects,
     * translating nested object keys into API member names when {@code cameliseKeys} says they are
     * member names rather than free-form data.
     */
    private Object toApiValue(JsonNode node, boolean cameliseKeys) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = cameliseKeys ? apiMemberName(field.getKey()) : field.getKey();
                map.put(key, toApiValue(field.getValue(), cameliseKeys));
            }
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(element -> list.add(toApiValue(element, cameliseKeys)));
            return list;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        return node.asText();
    }

    /**
     * The AppSync member name for a CloudFormation property name: the same identifier with a
     * lower-case first letter, bar the one place AWS does not follow its own rule.
     */
    private static String apiMemberName(String property) {
        if (property == null || property.isEmpty()) {
            return property;
        }
        // AWS::AppSync::DataSource's DynamoDBConfig is dynamodbConfig on the API: lower-camelising
        // it would produce dynamoDBConfig and the whole table config would be dropped on the floor.
        if ("DynamoDBConfig".equals(property)) {
            return "dynamodbConfig";
        }
        return Character.toLowerCase(property.charAt(0)) + property.substring(1);
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, String> strings = new LinkedHashMap<>();
        map.forEach((k, v) -> strings.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        return strings;
    }

    private static String attribute(StackResource r, String name) {
        return r.getAttributes() == null ? null : r.getAttributes().get(name);
    }

    private static String host(String url) {
        if (blank(url)) {
            return null;
        }
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return blank(preferred) ? fallback : preferred;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
