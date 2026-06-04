package io.github.hectorvent.floci.services.appsync;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appsync.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;

@ApplicationScoped
public class AppSyncService {
    private static final Logger LOG = Logger.getLogger(AppSyncService.class);

    private final StorageBackend<String, GraphqlApi> apiStore;
    private final StorageBackend<String, String> schemaStore;         // apiId -> schema SDL
    private final StorageBackend<String, SchemaCreationStatus> schemaStatusStore;
    private final StorageBackend<String, DataSource> dataSourceStore;
    private final StorageBackend<String, Resolver> resolverStore;
    private final StorageBackend<String, FunctionConfiguration> functionStore;
    private final StorageBackend<String, ApiKey> apiKeyStore;
    private final StorageBackend<String, AppSyncType> typeStore;
    private final RegionResolver regionResolver;

    @Inject
    public AppSyncService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver) {
        this.apiStore = storageFactory.create("appsync", "appsync-apis.json", new TypeReference<>() {});
        this.schemaStore = storageFactory.create("appsync", "appsync-schemas.json", new TypeReference<>() {});
        this.schemaStatusStore = storageFactory.create("appsync", "appsync-schema-status.json", new TypeReference<>() {});
        this.dataSourceStore = storageFactory.create("appsync", "appsync-datasources.json", new TypeReference<>() {});
        this.resolverStore = storageFactory.create("appsync", "appsync-resolvers.json", new TypeReference<>() {});
        this.functionStore = storageFactory.create("appsync", "appsync-functions.json", new TypeReference<>() {});
        this.apiKeyStore = storageFactory.create("appsync", "appsync-apikeys.json", new TypeReference<>() {});
        this.typeStore = storageFactory.create("appsync", "appsync-types.json", new TypeReference<>() {});
        this.regionResolver = regionResolver;
    }

    // ──────────────────────────── GraphQL API ────────────────────────────

    public GraphqlApi createGraphqlApi(Map<String, Object> request, String region) {
        String name = (String) request.get("name");
        if (name == null || name.isBlank()) {
            throw new AwsException("BadRequestException", "A GraphQL API name is required", 400);
        }
        String apiId = generateApiId();
        GraphqlApi api = new GraphqlApi();
        api.setApiId(apiId);
        api.setName(name);
        api.setAuthenticationType(parseEnum(AuthenticationType.class, request.get("authenticationType")));
        Object xrayValue = request.get("xrayEnabled");
        if (xrayValue instanceof Boolean b) {
            api.setXrayEnabled(b);
        } else if (xrayValue instanceof String s) {
            api.setXrayEnabled(Boolean.parseBoolean(s));
        } else {
            api.setXrayEnabled(false);
        }
        api.setLogConfig((Map<String, Object>) request.get("logConfig"));

        Object additionalObj = request.get("additionalAuthenticationProviders");
        if (additionalObj instanceof List<?> additionalList) {
            List<Map<String, Object>> providers = new ArrayList<>();
            for (Object item : additionalList) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> casted = (Map<String, Object>) map;
                    providers.add(casted);
                }
            }
            api.setAdditionalAuthenticationProviders(providers);
        }

        api.setArn(buildApiArn(apiId, region));

        Map<String, String> uris = new HashMap<>();
        String baseUri = "http://localhost:4566";
        uris.put("GRAPHQL", baseUri + "/v1/apis/" + apiId + "/graphql");
        uris.put("REALTIME", "ws://localhost:4566/v1/apis/" + apiId + "/graphql/realtime");
        api.setUris(uris);

        Map<String, Object> tags = (Map<String, Object>) request.get("tags");
        if (tags != null) {
            Map<String, String> tagMap = new HashMap<>();
            tags.forEach((k, v) -> tagMap.put(k, String.valueOf(v)));
            api.setTags(tagMap);
        }

        apiStore.put(apiId, api);
        LOG.infov("Created GraphQL API {0}: {1}", apiId, api.getName());
        return api;
    }

    public GraphqlApi getGraphqlApi(String apiId) {
        return apiStore.get(apiId)
                .orElseThrow(() -> new AwsException("NotFoundException", "GraphQL API not found: " + apiId, 404));
    }

    public Page<GraphqlApi> listGraphqlApis(Integer maxResults, String nextToken) {
        return paginate(apiStore.scan(k -> true), nextToken, maxResults);
    }

    public GraphqlApi updateGraphqlApi(String apiId, Map<String, Object> request, String region) {
        GraphqlApi existing = getGraphqlApi(apiId);
        if (request.containsKey("name")) existing.setName((String) request.get("name"));
        if (request.containsKey("authenticationType")) existing.setAuthenticationType(parseEnum(AuthenticationType.class, request.get("authenticationType")));
        if (request.containsKey("xrayEnabled")) {
            Object xrayValue = request.get("xrayEnabled");
            if (xrayValue instanceof Boolean b) {
                existing.setXrayEnabled(b);
            } else if (xrayValue instanceof String s) {
                existing.setXrayEnabled(Boolean.parseBoolean(s));
            }
        }
        if (request.containsKey("logConfig")) existing.setLogConfig((Map<String, Object>) request.get("logConfig"));
        if (request.containsKey("tags")) {
            Map<String, Object> tags = (Map<String, Object>) request.get("tags");
            Map<String, String> tagMap = new HashMap<>();
            tags.forEach((k, v) -> tagMap.put(k, String.valueOf(v)));
            existing.setTags(tagMap);
        }
        apiStore.put(apiId, existing);
        return existing;
    }

    public void deleteGraphqlApi(String apiId) {
        getGraphqlApi(apiId);
        apiStore.delete(apiId);
        schemaStore.delete(apiId);
        schemaStatusStore.delete(apiId);
        deleteDataSourcesForApi(apiId);
        deleteResolversForApi(apiId);
        deleteFunctionsForApi(apiId);
        deleteTypesForApi(apiId);
        deleteApiKeysForApi(apiId);
        LOG.infov("Deleted GraphQL API {0}", apiId);
    }

    // ──────────────────────────── Schema ────────────────────────────

    public void startSchemaCreation(String apiId, String definition) {
        getGraphqlApi(apiId);
        schemaStore.put(apiId, definition);
        SchemaCreationStatus status = new SchemaCreationStatus();
        status.setStatus(SchemaCreationStatusType.ACTIVE);
        schemaStatusStore.put(apiId, status);
        LOG.infov("Schema creation completed for API {0}", apiId);
    }

    public SchemaCreationStatus getSchemaCreationStatus(String apiId) {
        getGraphqlApi(apiId);
        return schemaStatusStore.get(apiId).orElseThrow(() ->
                new AwsException("NotFoundException", "Schema creation status not found for API: " + apiId, 404));
    }

    public String getIntrospectionSchema(String apiId) {
        getGraphqlApi(apiId);
        return schemaStore.get(apiId).orElse(null);
    }

    // ──────────────────────────── Data Sources ────────────────────────────

    public DataSource createDataSource(String apiId, Map<String, Object> request) {
        getGraphqlApi(apiId);
        String name = (String) request.get("name");
        if (name == null || name.isBlank()) {
            throw new AwsException("BadRequestException", "A data source name is required", 400);
        }
        if (request.get("type") == null) {
            throw new AwsException("BadRequestException", "A data source type is required", 400);
        }
        DataSource ds = new DataSource();
        ds.setName(name);
        ds.setDescription((String) request.get("description"));
        ds.setType(parseEnum(DataSourceType.class, request.get("type")));
        ds.setServiceRoleArn((String) request.get("serviceRoleArn"));
        ds.setDynamodbConfig((Map<String, Object>) request.get("dynamodbConfig"));
        ds.setLambdaConfig((Map<String, Object>) request.get("lambdaConfig"));
        ds.setHttpConfig((Map<String, Object>) request.get("httpConfig"));
        ds.setEventBridgeConfig((Map<String, Object>) request.get("eventBridgeConfig"));
        ds.setRelationalDatabaseConfig((Map<String, Object>) request.get("relationalDatabaseConfig"));
        ds.setOpenSearchServiceConfig((Map<String, Object>) request.get("openSearchServiceConfig"));
        ds.setAmazonBedrockRuntimeConfig((Map<String, Object>) request.get("amazonBedrockRuntimeConfig"));

        dataSourceStore.put(apiKey(apiId, ds.getName()), ds);
        return ds;
    }

    public DataSource getDataSource(String apiId, String dataSourceName) {
        return dataSourceStore.get(apiKey(apiId, dataSourceName))
                .orElseThrow(() -> new AwsException("NotFoundException", "Data source not found: " + dataSourceName, 404));
    }

    public Page<DataSource> listDataSources(String apiId, Integer maxResults, String nextToken) {
        return paginate(dataSourceStore.scan(k -> k.startsWith(apiId + "::")), nextToken, maxResults);
    }

    public DataSource updateDataSource(String apiId, String dataSourceName, Map<String, Object> request) {
        DataSource existing = getDataSource(apiId, dataSourceName);
        if (request.containsKey("description")) existing.setDescription((String) request.get("description"));
        if (request.containsKey("type")) existing.setType(parseEnum(DataSourceType.class, request.get("type")));
        if (request.containsKey("serviceRoleArn")) existing.setServiceRoleArn((String) request.get("serviceRoleArn"));
        if (request.containsKey("dynamodbConfig")) existing.setDynamodbConfig((Map<String, Object>) request.get("dynamodbConfig"));
        if (request.containsKey("lambdaConfig")) existing.setLambdaConfig((Map<String, Object>) request.get("lambdaConfig"));
        if (request.containsKey("httpConfig")) existing.setHttpConfig((Map<String, Object>) request.get("httpConfig"));
        if (request.containsKey("eventBridgeConfig")) existing.setEventBridgeConfig((Map<String, Object>) request.get("eventBridgeConfig"));
        if (request.containsKey("relationalDatabaseConfig")) existing.setRelationalDatabaseConfig((Map<String, Object>) request.get("relationalDatabaseConfig"));
        if (request.containsKey("openSearchServiceConfig")) existing.setOpenSearchServiceConfig((Map<String, Object>) request.get("openSearchServiceConfig"));
        if (request.containsKey("amazonBedrockRuntimeConfig")) existing.setAmazonBedrockRuntimeConfig((Map<String, Object>) request.get("amazonBedrockRuntimeConfig"));
        dataSourceStore.put(apiKey(apiId, dataSourceName), existing);
        return existing;
    }

    public void deleteDataSource(String apiId, String dataSourceName) {
        getDataSource(apiId, dataSourceName);
        dataSourceStore.delete(apiKey(apiId, dataSourceName));
    }

    private void deleteDataSourcesForApi(String apiId) {
        dataSourceStore.scan(k -> k.startsWith(apiId + "::"))
                .forEach(ds -> {
                    try {
                        dataSourceStore.delete(apiKey(apiId, ds.getName()));
                    } catch (Exception e) {
                        // ignore - already deleted
                    }
                });
    }

    // ──────────────────────────── Resolvers ────────────────────────────

    public Resolver createResolver(String apiId, Map<String, Object> request) {
        getGraphqlApi(apiId);
        String fieldName = (String) request.get("fieldName");
        if (fieldName == null || fieldName.isBlank()) {
            throw new AwsException("BadRequestException", "A resolver field name is required", 400);
        }
        String dataSourceName = (String) request.get("dataSourceName");
        if (dataSourceName == null || dataSourceName.isBlank()) {
            throw new AwsException("BadRequestException", "A data source name is required for the resolver", 400);
        }
        Resolver resolver = new Resolver();
        resolver.setApiId(apiId);
        resolver.setTypeName((String) request.get("typeName"));
        resolver.setFieldName((String) request.get("fieldName"));
        resolver.setDataSourceName((String) request.get("dataSourceName"));
        resolver.setFunctionId((String) request.get("functionId"));
        resolver.setRequestMappingTemplate((String) request.get("requestMappingTemplate"));
        resolver.setResponseMappingTemplate((String) request.get("responseMappingTemplate"));
        resolver.setKind(parseEnum(ResolverKind.class, request.getOrDefault("kind", "UNIT")));
        resolver.setCode((String) request.get("code"));

        Map<String, Object> runtime = (Map<String, Object>) request.get("runtime");
        if (runtime != null) {
            Resolver.ResolverRuntime rt = new Resolver.ResolverRuntime();
            rt.setName(parseEnum(ResolverRuntimeName.class, runtime.get("name")));
            rt.setRuntimeVersion((String) runtime.get("runtimeVersion"));
            resolver.setRuntime(rt);
        }

        String key = resolverKey(apiId, resolver.getTypeName(), resolver.getFieldName());
        resolverStore.put(key, resolver);
        return resolver;
    }

    public Resolver getResolver(String apiId, String typeName, String fieldName) {
        return resolverStore.get(resolverKey(apiId, typeName, fieldName))
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Resolver not found: " + typeName + "." + fieldName, 404));
    }

    public Page<Resolver> listResolvers(String apiId, Integer maxResults, String nextToken) {
        return paginate(resolverStore.scan(k -> k.startsWith(apiId + "::")), nextToken, maxResults);
    }

    public Page<Resolver> listResolversByType(String apiId, String typeName, Integer maxResults, String nextToken) {
        List<Resolver> filtered = resolverStore.scan(k -> k.startsWith(apiId + "::")).stream()
                .filter(r -> typeName.equals(r.getTypeName()))
                .toList();
        return paginate(filtered, nextToken, maxResults);
    }

    public Page<Resolver> listResolversByFunction(String apiId, String functionId, Integer maxResults, String nextToken) {
        getFunction(apiId, functionId);
        List<Resolver> all = resolverStore.scan(k -> k.startsWith(apiId + "::")).stream()
                .filter(r -> functionId.equals(r.getFunctionId()))
                .toList();
        return paginate(all, nextToken, maxResults);
    }

    public Resolver updateResolver(String apiId, String typeName, String fieldName, Map<String, Object> request) {
        Resolver existing = getResolver(apiId, typeName, fieldName);
        if (request.containsKey("dataSourceName")) existing.setDataSourceName((String) request.get("dataSourceName"));
        if (request.containsKey("functionId")) existing.setFunctionId((String) request.get("functionId"));
        if (request.containsKey("requestMappingTemplate")) existing.setRequestMappingTemplate((String) request.get("requestMappingTemplate"));
        if (request.containsKey("responseMappingTemplate")) existing.setResponseMappingTemplate((String) request.get("responseMappingTemplate"));
        if (request.containsKey("kind")) existing.setKind(parseEnum(ResolverKind.class, request.get("kind")));
        if (request.containsKey("code")) existing.setCode((String) request.get("code"));
        if (request.containsKey("runtime")) {
            Map<String, Object> runtime = (Map<String, Object>) request.get("runtime");
            Resolver.ResolverRuntime rt = new Resolver.ResolverRuntime();
            rt.setName(parseEnum(ResolverRuntimeName.class, runtime.get("name")));
            rt.setRuntimeVersion((String) runtime.get("runtimeVersion"));
            existing.setRuntime(rt);
        }
        resolverStore.put(resolverKey(apiId, typeName, fieldName), existing);
        return existing;
    }

    public void deleteResolver(String apiId, String typeName, String fieldName) {
        getResolver(apiId, typeName, fieldName);
        resolverStore.delete(resolverKey(apiId, typeName, fieldName));
    }

    private void deleteResolversForApi(String apiId) {
        resolverStore.scan(k -> k.startsWith(apiId + "::"))
                .forEach(r -> resolverStore.delete(resolverKey(apiId, r.getTypeName(), r.getFieldName())));
    }

    // ──────────────────────────── Functions ────────────────────────────

    public FunctionConfiguration createFunction(String apiId, Map<String, Object> request, String region) {
        getGraphqlApi(apiId);
        String name = (String) request.get("name");
        if (name == null || name.isBlank()) {
            throw new AwsException("BadRequestException", "A function name is required", 400);
        }
        FunctionConfiguration fn = new FunctionConfiguration();
        fn.setFunctionId(generateShortId());
        fn.setName((String) request.get("name"));
        fn.setDescription((String) request.get("description"));
        fn.setDataSourceName((String) request.get("dataSourceName"));
        fn.setRequestMappingTemplate((String) request.get("requestMappingTemplate"));
        fn.setResponseMappingTemplate((String) request.get("responseMappingTemplate"));
        fn.setFunctionVersion((String) request.getOrDefault("functionVersion", "2018-05-29"));
        fn.setArn(buildFunctionArn(apiId, fn.getFunctionId(), region));
        fn.setCode((String) request.get("code"));

        functionStore.put(apiKey(apiId, fn.getFunctionId()), fn);
        return fn;
    }

    public FunctionConfiguration getFunction(String apiId, String functionId) {
        return functionStore.get(apiKey(apiId, functionId))
                .orElseThrow(() -> new AwsException("NotFoundException", "Function not found: " + functionId, 404));
    }

    public Page<FunctionConfiguration> listFunctions(String apiId, Integer maxResults, String nextToken) {
        return paginate(functionStore.scan(k -> k.startsWith(apiId + "::")), nextToken, maxResults);
    }

    public FunctionConfiguration updateFunction(String apiId, String functionId, Map<String, Object> request) {
        FunctionConfiguration existing = getFunction(apiId, functionId);
        if (request.containsKey("description")) existing.setDescription((String) request.get("description"));
        if (request.containsKey("dataSourceName")) existing.setDataSourceName((String) request.get("dataSourceName"));
        if (request.containsKey("requestMappingTemplate")) existing.setRequestMappingTemplate((String) request.get("requestMappingTemplate"));
        if (request.containsKey("responseMappingTemplate")) existing.setResponseMappingTemplate((String) request.get("responseMappingTemplate"));
        if (request.containsKey("functionVersion")) existing.setFunctionVersion((String) request.get("functionVersion"));
        if (request.containsKey("code")) existing.setCode((String) request.get("code"));
        functionStore.put(apiKey(apiId, functionId), existing);
        return existing;
    }

    public void deleteFunction(String apiId, String functionId) {
        getFunction(apiId, functionId);
        functionStore.delete(apiKey(apiId, functionId));
    }

    private void deleteFunctionsForApi(String apiId) {
        functionStore.scan(k -> k.startsWith(apiId + "::"))
                .forEach(fn -> functionStore.delete(apiKey(apiId, fn.getFunctionId())));
    }

    // ──────────────────────────── Types ────────────────────────────

    public AppSyncType createType(String apiId, Map<String, Object> request) {
        getGraphqlApi(apiId);
        String name = (String) request.get("name");
        String definition = (String) request.get("definition");
        if (name == null || name.isBlank()) {
            name = extractTypeNameFromDefinition(definition);
        }
        if (name == null || name.isBlank()) {
            throw new AwsException("BadRequestException", "A type name is required", 400);
        }
        AppSyncType type = new AppSyncType();
        type.setApiId(apiId);
        type.setName(name);
        type.setDefinition((String) request.get("definition"));
        type.setDescription((String) request.get("description"));
        type.setFormat(parseEnum(TypeFormat.class, request.getOrDefault("format", "SDL")));

        typeStore.put(apiKey(apiId, type.getName()), type);
        return type;
    }

    public AppSyncType getType(String apiId, String typeName) {
        return typeStore.get(apiKey(apiId, typeName))
                .orElseThrow(() -> new AwsException("NotFoundException", "Type not found: " + typeName, 404));
    }

    public Page<AppSyncType> listTypes(String apiId, Integer maxResults, String nextToken) {
        return paginate(typeStore.scan(k -> k.startsWith(apiId + "::")), nextToken, maxResults);
    }

    public AppSyncType updateType(String apiId, String typeName, Map<String, Object> request) {
        AppSyncType existing = getType(apiId, typeName);
        if (request.containsKey("definition")) existing.setDefinition((String) request.get("definition"));
        if (request.containsKey("description")) existing.setDescription((String) request.get("description"));
        if (request.containsKey("format")) existing.setFormat(parseEnum(TypeFormat.class, request.get("format")));
        typeStore.put(apiKey(apiId, typeName), existing);
        return existing;
    }

    public void deleteType(String apiId, String typeName) {
        getType(apiId, typeName);
        typeStore.delete(apiKey(apiId, typeName));
    }

    private void deleteTypesForApi(String apiId) {
        typeStore.scan(k -> k.startsWith(apiId + "::"))
                .forEach(t -> typeStore.delete(apiKey(apiId, t.getName())));
    }

    // ──────────────────────────── API Keys ────────────────────────────

    public ApiKey createApiKey(String apiId, Map<String, Object> request) {
        getGraphqlApi(apiId);
        ApiKey key = new ApiKey();
        key.setId(generateShortId());
        key.setApiId(apiId);
        key.setDescription((String) request.get("description"));
        Object expiresValue = request.get("expires");
        if (expiresValue instanceof Long l) {
            key.setExpires(l);
        } else if (expiresValue instanceof Number n) {
            key.setExpires(n.longValue());
        } else if (expiresValue instanceof String s) {
            try {
                key.setExpires(Long.parseLong(s));
            } catch (NumberFormatException e) {
                // try parsing as ISO date and convert to epoch seconds
                key.setExpires(null);
            }
        }

        // Generate a simple API key string
        key.setApiKey("da2-" + generateShortId());

        apiKeyStore.put(apiKey(apiId, key.getId()), key);
        return key;
    }

    public Page<ApiKey> listApiKeys(String apiId, Integer maxResults, String nextToken) {
        return paginate(apiKeyStore.scan(k -> k.startsWith(apiId + "::")), nextToken, maxResults);
    }

    public ApiKey getApiKey(String apiId, String keyId) {
        return apiKeyStore.get(apiKey(apiId, keyId))
                .orElseThrow(() -> new AwsException("NotFoundException", "API key not found: " + keyId, 404));
    }

    public ApiKey updateApiKey(String apiId, String keyId, Map<String, Object> request) {
        ApiKey existing = getApiKey(apiId, keyId);
        if (request.containsKey("description")) existing.setDescription((String) request.get("description"));
        if (request.containsKey("expires")) {
            Object expiresValue = request.get("expires");
            if (expiresValue instanceof Long l) {
                existing.setExpires(l);
            } else if (expiresValue instanceof Number n) {
                existing.setExpires(n.longValue());
            } else if (expiresValue instanceof String s) {
                try {
                    existing.setExpires(Long.parseLong(s));
                } catch (NumberFormatException e) {
                    // keep existing
                }
            }
        }
        apiKeyStore.put(apiKey(apiId, keyId), existing);
        return existing;
    }

    public void deleteApiKey(String apiId, String keyId) {
        getApiKey(apiId, keyId);
        apiKeyStore.delete(apiKey(apiId, keyId));
    }

    private void deleteApiKeysForApi(String apiId) {
        apiKeyStore.scan(k -> k.startsWith(apiId + "::"))
                .forEach(k -> apiKeyStore.delete(apiKey(apiId, k.getId())));
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> getTags(String resourceArn) {
        String apiId = extractApiIdFromArn(resourceArn);
        return getGraphqlApi(apiId).getTags();
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        String apiId = extractApiIdFromArn(resourceArn);
        GraphqlApi api = getGraphqlApi(apiId);
        api.getTags().putAll(tags);
        apiStore.put(apiId, api);
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        String apiId = extractApiIdFromArn(resourceArn);
        GraphqlApi api = getGraphqlApi(apiId);
        tagKeys.forEach(api.getTags()::remove);
        apiStore.put(apiId, api);
    }

    // ──────────────────────────── Environment Variables ────────────────────────────

    public Map<String, String> getEnvironmentVariables(String apiId) {
        GraphqlApi api = getGraphqlApi(apiId);
        Map<String, String> envVars = api.getEnvironmentVariables();
        return envVars != null ? envVars : Map.of();
    }

    public Map<String, String> putEnvironmentVariables(String apiId, Map<String, String> environmentVariables) {
        GraphqlApi api = getGraphqlApi(apiId);
        api.setEnvironmentVariables(new HashMap<>(environmentVariables));
        apiStore.put(apiId, api);
        return api.getEnvironmentVariables();
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private String generateApiId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 26);
    }

    private String generateShortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 7);
    }

    private String apiKey(String apiId, String name) {
        return apiId + "::" + name;
    }

    private String resolverKey(String apiId, String typeName, String fieldName) {
        return apiId + "::" + typeName + "::" + fieldName;
    }

    private String buildApiArn(String apiId, String region) {
        return regionResolver.buildArn("appsync", region, "apis/" + apiId);
    }

    private String buildFunctionArn(String apiId, String functionId, String region) {
        return regionResolver.buildArn("appsync", region, "apis/" + apiId + "/functions/" + functionId);
    }

    private String extractApiIdFromArn(String arn) {
        if (arn == null) throw new AwsException("BadRequestException", "Invalid ARN", 400);
        String[] parts = arn.split("/");
        if (parts.length < 2) throw new AwsException("BadRequestException", "Invalid ARN format", 400);
        return parts[parts.length - 1];
    }

    private String coerceString(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        return String.valueOf(value);
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumClass, Object value) {
        if (value == null) return null;
        String str = value instanceof String s ? s : String.valueOf(value);
        try {
            return Enum.valueOf(enumClass, str);
        } catch (IllegalArgumentException e) {
            throw new AwsException("BadRequestException",
                    "Invalid value '" + str + "' for " + enumClass.getSimpleName(), 400);
        }
    }

    // ──────────────────────────── Pagination ────────────────────────────

    record Page<T>(List<T> items, String nextToken) {}

    static <T> Page<T> paginate(List<T> items, String nextToken, Integer maxResults) {
        int start = decodeToken(nextToken);
        if (start < 0 || start > items.size()) {
            throw new AwsException("InvalidNextTokenException", "Invalid NextToken.", 400);
        }
        int limit = (maxResults == null || maxResults <= 0)
                ? items.size()
                : Math.min(maxResults, items.size() - start);
        int end = Math.min(items.size(), start + limit);
        List<T> sliced = items.subList(start, end);
        String next = (end < items.size()) ? encodeToken(end) : null;
        return new Page<>(sliced, next);
    }

    static String encodeToken(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static int decodeToken(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            return Integer.parseInt(new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    static String extractTypeNameFromDefinition(String definition) {
        if (definition == null || definition.isBlank()) return null;
        String trimmed = definition.strip();
        // Match: type TypeName { ... } or input TypeName { ... } or enum TypeName { ... }
        String[] parts = trimmed.split("\\s+");
        if (parts.length >= 2) {
            return parts[1].replaceAll("[{(].*", "");
        }
        return null;
    }
}
