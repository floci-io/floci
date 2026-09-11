package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.AppSyncService;
import io.github.hectorvent.floci.services.appsync.model.ApiKey;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.FunctionConfiguration;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;
import io.github.hectorvent.floci.services.appsync.model.Resolver;
import io.github.hectorvent.floci.services.appsync.model.SchemaCreationStatus;
import io.github.hectorvent.floci.services.appsync.model.SchemaCreationStatusType;
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
import java.util.Set;

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
 * attributes at create time and read back on update and delete — hence the
 * {@code delete(StackResource, String)} override.
 *
 * <p><b>Inline definitions only.</b> {@code DefinitionS3Location} and {@code CodeS3Location} are
 * refused with a clear message rather than silently producing an API with an empty schema or a
 * resolver with no code. Serverless Framework and the AppSync plugins inline both.
 */
@ApplicationScoped
public class AppSyncCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(AppSyncCfnProvisioner.class);

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

        GraphqlApi api = ctx.isUpdate()
                ? appSyncService.updateGraphqlApi(ctx.priorPhysicalId(), req, ctx.region())
                : appSyncService.createGraphqlApi(req, ctx.region());

        Map<String, String> resolvedEnvironment = stringMap(environmentVariables);
        if (resolvedEnvironment == null && ctx.isUpdate()) {
            // The property was dropped from the template. The call replaces the whole set, so an
            // empty map is what clears it; skipping the call left the old variables readable by
            // every resolver.
            resolvedEnvironment = Map.of();
        }
        if (resolvedEnvironment != null) {
            appSyncService.putEnvironmentVariables(api.getApiId(), resolvedEnvironment);
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
            // AWS — a custom domain's CNAME target. Derived rather than stored, so they cannot
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

        DataSource ds = reusesEntity(r, ctx, apiId) && name.equals(attribute(r, "Name"))
                ? appSyncService.updateDataSource(apiId, name, req)
                : appSyncService.createDataSource(apiId, req, ctx.region());

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
        FunctionConfiguration fn = reusesEntity(r, ctx, apiId) && !blank(priorFunctionId)
                ? appSyncService.updateFunction(apiId, priorFunctionId, req)
                : appSyncService.createFunction(apiId, req, ctx.region());

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
        Resolver resolver = reusesEntity(r, ctx, apiId) && sameField
                ? appSyncService.updateResolver(apiId, typeName, fieldName, req)
                : appSyncService.createResolver(apiId, req, ctx.region());

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

        ApiKey key = reusesEntity(r, ctx, apiId)
                ? appSyncService.updateApiKey(apiId, ctx.priorPhysicalId(), req)
                : appSyncService.createApiKey(apiId, req);

        r.setPhysicalId(key.getId());
        // On AWS the key id and the key value are the same string (the da2-… secret), and both
        // attributes are published: ApiKey is what a client sends as x-api-key.
        r.getAttributes().put("ApiKey", key.getId());
        r.getAttributes().put("ApiKeyId", key.getId());
        r.getAttributes().put("Arn", AwsArnUtils.Arn.of("appsync", ctx.region(), ctx.accountId(),
                "apis/" + apiId + "/apikeys/" + key.getId()).toString());
        r.getAttributes().put("ApiId", apiId);
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
                             java.util.function.Consumer<String> delete) {
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
        // AWS::AppSync::DataSource's DynamoDBConfig is dynamodbConfig on the API — lower-camelising
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
