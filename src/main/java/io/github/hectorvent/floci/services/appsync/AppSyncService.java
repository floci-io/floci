package io.github.hectorvent.floci.services.appsync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appsync.graphql.SchemaRegistry;
import io.github.hectorvent.floci.services.appsync.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.Base64;

@ApplicationScoped
public class AppSyncService {
    private static final Logger LOG = Logger.getLogger(AppSyncService.class);

    private final StorageBackend<String, GraphqlApi> apiStore;
    private final StorageBackend<String, String> schemaStore;
    private final StorageBackend<String, SchemaCreationStatus> schemaStatusStore;
    private final StorageBackend<String, DataSource> dataSourceStore;
    private final StorageBackend<String, Resolver> resolverStore;
    private final StorageBackend<String, FunctionConfiguration> functionStore;
    private final StorageBackend<String, ApiKey> apiKeyStore;
    private final StorageBackend<String, AppSyncType> typeStore;
    private final StorageBackend<String, DomainName> domainStore;
    private final StorageBackend<String, String> associationStore;
    private final StorageBackend<String, ChannelNamespace> channelNamespaceStore;
    private final StorageBackend<String, SourceApiAssociation> mergedApiAssociationStore;
    private final RegionResolver regionResolver;
    private final SchemaRegistry schemaRegistry;
    private final ObjectMapper objectMapper;

    @Inject
    public AppSyncService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver,
                          SchemaRegistry schemaRegistry, ObjectMapper objectMapper) {
        this.apiStore = storageFactory.create("appsync", "appsync-apis.json", new TypeReference<>() {});
        this.schemaStore = storageFactory.create("appsync", "appsync-schemas.json", new TypeReference<>() {});
        this.schemaStatusStore = storageFactory.create("appsync", "appsync-schema-status.json", new TypeReference<>() {});
        this.dataSourceStore = storageFactory.create("appsync", "appsync-datasources.json", new TypeReference<>() {});
        this.resolverStore = storageFactory.create("appsync", "appsync-resolvers.json", new TypeReference<>() {});
        this.functionStore = storageFactory.create("appsync", "appsync-functions.json", new TypeReference<>() {});
        this.apiKeyStore = storageFactory.create("appsync", "appsync-apikeys.json", new TypeReference<>() {});
        this.typeStore = storageFactory.create("appsync", "appsync-types.json", new TypeReference<>() {});
        this.domainStore = storageFactory.create("appsync", "appsync-domainnames.json", new TypeReference<>() {});
        this.associationStore = storageFactory.create("appsync", "appsync-associations.json", new TypeReference<>() {});
        this.channelNamespaceStore = storageFactory.create("appsync", "appsync-channelnamespaces.json", new TypeReference<>() {});
        this.mergedApiAssociationStore = storageFactory.create("appsync", "appsync-merged-api-associations.json", new TypeReference<>() {});
        this.regionResolver = regionResolver;
        this.schemaRegistry = schemaRegistry;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── GraphQL API ────────────────────────────

    public GraphqlApi createGraphqlApi(Map<String, Object> request, String region) {
        String name = (String) request.get("name");
        if (name == null || name.isBlank()) {
            throw new AwsException("BadRequestException", "A GraphQL API name is required", 400);
        }
        String authType = (String) request.get("authenticationType");
        if (authType == null || authType.isBlank()) {
            throw new AwsException("BadRequestException", "The authenticationType is required", 400);
        }
        String apiId = generateApiId();
        GraphqlApi api = new GraphqlApi();
        api.setApiId(apiId);
        api.setName(name);
        api.setAuthenticationType(parseEnum(AuthenticationType.class, authType));
        Object xrayValue = request.get("xrayEnabled");
        if (xrayValue instanceof Boolean b) {
            api.setXrayEnabled(b);
        } else if (xrayValue instanceof String s) {
            api.setXrayEnabled(Boolean.parseBoolean(s));
        } else {
            api.setXrayEnabled(false);
        }
        api.setLogConfig(castMap(request.get("logConfig")));

        api.setApiType(coerceString(request.get("apiType"), "GRAPHQL"));
        api.setOwner(coerceString(request.get("owner")));
        api.setOwnerContact(coerceString(request.get("ownerContact")));
        api.setVisibility(coerceString(request.get("visibility"), "GLOBAL"));
        api.setIntrospectionConfig(coerceString(request.get("introspectionConfig")));
        api.setQueryDepthLimit(castInt(request.get("queryDepthLimit")));
        api.setResolverCountLimit(castInt(request.get("resolverCountLimit")));
        api.setEnhancedMetricsConfig(castMap(request.get("enhancedMetricsConfig")));
        api.setLambdaAuthorizerConfig(castMap(request.get("lambdaAuthorizerConfig")));
        api.setOpenIDConnectConfig(castMap(request.get("openIDConnectConfig")));
        api.setUserPoolConfig(castMap(request.get("userPoolConfig")));
        api.setDns(castStringMap(request.get("dns")));
        api.setWafWebAclArn(coerceString(request.get("wafWebAclArn")));
        api.setMergedApiExecutionRoleArn(coerceString(request.get("mergedApiExecutionRoleArn")));

        Object additionalObj = request.get("additionalAuthenticationProviders");
        if (additionalObj instanceof List<?> additionalList) {
            List<AdditionalAuthenticationProvider> providers = objectMapper.convertValue(
                additionalList, new TypeReference<List<AdditionalAuthenticationProvider>>() {});
            api.setAdditionalAuthenticationProviders(providers);
        }

        api.setArn(buildApiArn(apiId, region));

        Map<String, String> uris = new HashMap<>();
        String baseUri = "http://localhost:4566";
        uris.put("GRAPHQL", baseUri + "/v1/apis/" + apiId + "/graphql");
        uris.put("REALTIME", "ws://localhost:4566/v1/apis/" + apiId + "/graphql/realtime");
        api.setUris(uris);

        Map<String, Object> tags = castMap(request.get("tags"));
        if (tags != null) {
            Map<String, String> tagMap = new HashMap<>();
            tags.forEach((k, v) -> tagMap.put(k, v != null ? String.valueOf(v) : ""));
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

    @SuppressWarnings("unchecked")
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
            tags.forEach((k, v) -> tagMap.put(k, v != null ? String.valueOf(v) : ""));
            existing.setTags(tagMap);
        }
        if (request.containsKey("apiType")) existing.setApiType((String) request.get("apiType"));
        if (request.containsKey("owner")) existing.setOwner((String) request.get("owner"));
        if (request.containsKey("ownerContact")) existing.setOwnerContact((String) request.get("ownerContact"));
        if (request.containsKey("visibility")) existing.setVisibility((String) request.get("visibility"));
        if (request.containsKey("introspectionConfig")) existing.setIntrospectionConfig((String) request.get("introspectionConfig"));
        if (request.containsKey("queryDepthLimit")) existing.setQueryDepthLimit(castInt(request.get("queryDepthLimit")));
        if (request.containsKey("resolverCountLimit")) existing.setResolverCountLimit(castInt(request.get("resolverCountLimit")));
        if (request.containsKey("enhancedMetricsConfig")) existing.setEnhancedMetricsConfig((Map<String, Object>) request.get("enhancedMetricsConfig"));
        if (request.containsKey("lambdaAuthorizerConfig")) existing.setLambdaAuthorizerConfig((Map<String, Object>) request.get("lambdaAuthorizerConfig"));
        if (request.containsKey("openIDConnectConfig")) existing.setOpenIDConnectConfig((Map<String, Object>) request.get("openIDConnectConfig"));
        if (request.containsKey("userPoolConfig")) existing.setUserPoolConfig((Map<String, Object>) request.get("userPoolConfig"));
        if (request.containsKey("dns")) {
            Map<String, String> dns = (Map<String, String>) request.get("dns");
            existing.setDns(dns);
        }
        if (request.containsKey("wafWebAclArn")) existing.setWafWebAclArn((String) request.get("wafWebAclArn"));
        if (request.containsKey("mergedApiExecutionRoleArn")) existing.setMergedApiExecutionRoleArn((String) request.get("mergedApiExecutionRoleArn"));
        if (request.containsKey("additionalAuthenticationProviders")) {
            Object additionalObj = request.get("additionalAuthenticationProviders");
            if (additionalObj instanceof List<?> additionalList) {
                existing.setAdditionalAuthenticationProviders(
                    objectMapper.convertValue(additionalList, new TypeReference<List<AdditionalAuthenticationProvider>>() {}));
            }
        }
        apiStore.put(apiId, existing);
        return existing;
    }

    public void deleteGraphqlApi(String apiId) {
        getGraphqlApi(apiId);
        apiStore.delete(apiId);
        schemaStore.delete(apiId);
        schemaStatusStore.delete(apiId);
        schemaRegistry.remove(apiId);
        deleteDataSourcesForApi(apiId);
        deleteResolversForApi(apiId);
        deleteFunctionsForApi(apiId);
        deleteTypesForApi(apiId);
        deleteApiKeysForApi(apiId);
        deleteChannelNamespacesForApi(apiId);
        deleteDomainAssociationsForApi(apiId);
        LOG.infov("Deleted GraphQL API {0}", apiId);
    }

    // ──────────────────────────── Schema ────────────────────────────

    public void startSchemaCreation(String apiId, String definition) {
        getGraphqlApi(apiId);
        schemaRegistry.register(apiId, definition);
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
        return schemaStore.get(apiId)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Schema not found for API: " + apiId, 404));
    }

    // ──────────────────────────── Data Sources ────────────────────────────

    public DataSource createDataSource(String apiId, Map<String, Object> request, String region) {
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
        ds.setDynamodbConfig(castMap(request.get("dynamodbConfig")));
        ds.setLambdaConfig(castMap(request.get("lambdaConfig")));
        ds.setHttpConfig(castMap(request.get("httpConfig")));
        ds.setEventBridgeConfig(castMap(request.get("eventBridgeConfig")));
        ds.setRelationalDatabaseConfig(castMap(request.get("relationalDatabaseConfig")));
        ds.setOpenSearchServiceConfig(castMap(request.get("openSearchServiceConfig")));
        ds.setAmazonBedrockRuntimeConfig(castMap(request.get("amazonBedrockRuntimeConfig")));
        ds.setDataSourceArn(regionResolver.buildArn("appsync", region, "apis/" + apiId + "/datasources/" + name));

        String dsKey = apiKey(apiId, ds.getName());
        if (dataSourceStore.get(dsKey).isPresent()) {
            throw new AwsException("ConflictException", "Data source already exists: " + name, 409);
        }
        dataSourceStore.put(dsKey, ds);
        return ds;
    }

    public DataSource getDataSource(String apiId, String dataSourceName) {
        return dataSourceStore.get(apiKey(apiId, dataSourceName))
                .orElseThrow(() -> new AwsException("NotFoundException", "Data source not found: " + dataSourceName, 404));
    }

    public Page<DataSource> listDataSources(String apiId, Integer maxResults, String nextToken) {
        return paginate(dataSourceStore.scan(k -> k.startsWith(apiId + "::")), nextToken, maxResults);
    }

    @SuppressWarnings("unchecked")
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

    // ──────────────────────────── Resolvers ────────────────────────────

    public Resolver createResolver(String apiId, Map<String, Object> request, String region) {
        getGraphqlApi(apiId);
        String fieldName = (String) request.get("fieldName");
        if (fieldName == null || fieldName.isBlank()) {
            throw new AwsException("BadRequestException", "A resolver field name is required", 400);
        }
        String dataSourceName = (String) request.get("dataSourceName");
        if (dataSourceName == null || dataSourceName.isBlank()) {
            throw new AwsException("BadRequestException", "A data source name is required for the resolver", 400);
        }
        String typeName = (String) request.get("typeName");
        if (typeName == null || typeName.isBlank()) {
            throw new AwsException("BadRequestException", "A type name is required for the resolver", 400);
        }
        // Validate data source exists
        getDataSource(apiId, dataSourceName);
        Resolver resolver = new Resolver();
        resolver.setApiId(apiId);
        resolver.setTypeName(typeName);
        resolver.setFieldName((String) request.get("fieldName"));
        resolver.setDataSourceName((String) request.get("dataSourceName"));
        resolver.setFunctionId((String) request.get("functionId"));
        resolver.setRequestMappingTemplate((String) request.get("requestMappingTemplate"));
        resolver.setResponseMappingTemplate((String) request.get("responseMappingTemplate"));
        resolver.setKind(parseEnum(ResolverKind.class, request.getOrDefault("kind", "UNIT")));
        resolver.setCode((String) request.get("code"));
        resolver.setCachingConfig(castMap(request.get("cachingConfig")));
        resolver.setMaxBatchSize(castInt(request.get("maxBatchSize")));
        resolver.setMetricsConfig((String) request.get("metricsConfig"));
        resolver.setPipelineConfig(castMap(request.get("pipelineConfig")));
        resolver.setSyncConfig(castMap(request.get("syncConfig")));
        resolver.setResolverArn(regionResolver.buildArn("appsync", region,
            "apis/" + apiId + "/types/" + typeName + "/resolvers/" + fieldName));

        Map<String, Object> runtime = castMap(request.get("runtime"));
        if (runtime != null) {
            Resolver.ResolverRuntime rt = new Resolver.ResolverRuntime();
            rt.setName(parseEnum(ResolverRuntimeName.class, runtime.get("name")));
            rt.setRuntimeVersion((String) runtime.get("runtimeVersion"));
            resolver.setRuntime(rt);
        }

        String key = resolverKey(apiId, resolver.getTypeName(), resolver.getFieldName());
        if (resolverStore.get(key).isPresent()) {
            throw new AwsException("ConflictException",
                "Resolver already exists for " + typeName + "." + fieldName, 409);
        }
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

    @SuppressWarnings("unchecked")
    public Resolver updateResolver(String apiId, String typeName, String fieldName, Map<String, Object> request) {
        Resolver existing = getResolver(apiId, typeName, fieldName);
        if (request.containsKey("dataSourceName")) existing.setDataSourceName((String) request.get("dataSourceName"));
        if (request.containsKey("functionId")) existing.setFunctionId((String) request.get("functionId"));
        if (request.containsKey("requestMappingTemplate")) existing.setRequestMappingTemplate((String) request.get("requestMappingTemplate"));
        if (request.containsKey("responseMappingTemplate")) existing.setResponseMappingTemplate((String) request.get("responseMappingTemplate"));
        if (request.containsKey("kind")) existing.setKind(parseEnum(ResolverKind.class, request.get("kind")));
        if (request.containsKey("code")) existing.setCode((String) request.get("code"));
        if (request.containsKey("cachingConfig")) existing.setCachingConfig((Map<String, Object>) request.get("cachingConfig"));
        if (request.containsKey("maxBatchSize")) existing.setMaxBatchSize(castInt(request.get("maxBatchSize")));
        if (request.containsKey("metricsConfig")) existing.setMetricsConfig((String) request.get("metricsConfig"));
        if (request.containsKey("pipelineConfig")) existing.setPipelineConfig((Map<String, Object>) request.get("pipelineConfig"));
        if (request.containsKey("syncConfig")) existing.setSyncConfig((Map<String, Object>) request.get("syncConfig"));
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

    // ──────────────────────────── Functions ────────────────────────────

    public FunctionConfiguration createFunction(String apiId, Map<String, Object> request, String region) {
        getGraphqlApi(apiId);
        String name = (String) request.get("name");
        if (name == null || name.isBlank()) {
            throw new AwsException("BadRequestException", "A function name is required", 400);
        }
        String dsName = (String) request.get("dataSourceName");
        if (dsName != null && !dsName.isBlank()) {
            getDataSource(apiId, dsName);
        }
        FunctionConfiguration fn = new FunctionConfiguration();
        fn.setFunctionId(generateShortId());
        fn.setName((String) request.get("name"));
        fn.setDescription((String) request.get("description"));
        fn.setDataSourceName(dsName);
        fn.setRequestMappingTemplate((String) request.get("requestMappingTemplate"));
        fn.setResponseMappingTemplate((String) request.get("responseMappingTemplate"));
        fn.setFunctionVersion((String) request.getOrDefault("functionVersion", "2018-05-29"));
        fn.setFunctionArn(buildFunctionArn(apiId, fn.getFunctionId(), region));
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
        String typeKey = apiKey(apiId, name);
        if (typeStore.get(typeKey).isPresent()) {
            throw new AwsException("ConflictException", "Type already exists: " + name, 409);
        }
        AppSyncType type = new AppSyncType();
        type.setApiId(apiId);
        type.setName(name);
        type.setDefinition((String) request.get("definition"));
        type.setDescription((String) request.get("description"));
        type.setFormat(parseEnum(TypeFormat.class, request.getOrDefault("format", "SDL")));

        typeStore.put(typeKey, type);
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

    // ──────────────────────────── API Keys ────────────────────────────

    public ApiKey createApiKey(String apiId, Map<String, Object> request) {
        getGraphqlApi(apiId);
        long existingCount = apiKeyStore.scan(k -> k.startsWith(apiId + "::")).size();
        if (existingCount >= 2) {
            throw new AwsException("LimitExceededException",
                "Maximum of 2 API keys per API reached", 429);
        }
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
                try {
                    key.setExpires(java.time.Instant.parse(s).getEpochSecond());
                } catch (java.time.format.DateTimeParseException ex) {
                    throw new AwsException("BadRequestException",
                        "Invalid expires value: " + s + ". Expected epoch seconds or ISO 8601.", 400);
                }
            }
        }

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
                    try {
                        existing.setExpires(java.time.Instant.parse(s).getEpochSecond());
                    } catch (java.time.format.DateTimeParseException ex) {
                        throw new AwsException("BadRequestException",
                            "Invalid expires value: " + s + ". Expected epoch seconds or ISO 8601.", 400);
                    }
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

    // ──────────────────────────── Domain Names ────────────────────────────

    public DomainName createDomainName(Map<String, Object> request) {
        String domainName = (String) request.get("domainName");
        if (domainName == null || domainName.isBlank()) {
            throw new AwsException("BadRequestException", "A domain name is required", 400);
        }
        if (domainStore.get(domainName).isPresent()) {
            throw new AwsException("ConflictException", "Domain name already exists: " + domainName, 409);
        }
        DomainName dn = new DomainName();
        dn.setDomainName(domainName);
        dn.setDescription((String) request.get("description"));
        dn.setCertificateArn((String) request.get("certificateArn"));
        String shortId = generateShortId();
        dn.setAppsyncDomainName(shortId + ".appsync-api.us-east-1.amazonaws.com");
        dn.setHostedZoneId("Z" + generateShortId());
        dn.setDomainNameArn(regionResolver.buildArn("appsync", regionResolver.getDefaultRegion(),
            "domainnames/" + domainName));

        Object tagsObj = request.get("tags");
        if (tagsObj instanceof Map<?, ?> tagMap) {
            Map<String, String> tagStrings = new HashMap<>();
            tagMap.forEach((k, v) -> tagStrings.put(String.valueOf(k), String.valueOf(v)));
            dn.setTags(tagStrings);
        }

        domainStore.put(domainName, dn);
        return dn;
    }

    public DomainName getDomainName(String domainName) {
        return domainStore.get(domainName)
                .orElseThrow(() -> new AwsException("NotFoundException", "Domain name not found: " + domainName, 404));
    }

    public Page<DomainName> listDomainNames(Integer maxResults, String nextToken) {
        return paginate(domainStore.scan(k -> true), nextToken, maxResults);
    }

    public DomainName updateDomainName(String domainName, Map<String, Object> request) {
        DomainName existing = getDomainName(domainName);
        if (request.containsKey("description")) existing.setDescription((String) request.get("description"));
        domainStore.put(domainName, existing);
        return existing;
    }

    public void deleteDomainName(String domainName) {
        getDomainName(domainName);
        associationStore.delete(domainName);
        domainStore.delete(domainName);
    }

    public ApiAssociation associateApi(String domainName, String apiId) {
        getDomainName(domainName);
        getGraphqlApi(apiId);
        if (associationStore.get(domainName).isPresent()) {
            throw new AwsException("ConflictException",
                "Domain name already associated with an API", 409);
        }
        associationStore.put(domainName, apiId);
        ApiAssociation assoc = new ApiAssociation();
        assoc.setApiId(apiId);
        assoc.setDomainName(domainName);
        assoc.setAssociationStatus("SUCCESS");
        assoc.setDeploymentDetail("Successfully associated");
        return assoc;
    }

    public ApiAssociation getApiAssociation(String domainName) {
        getDomainName(domainName);
        String apiId = associationStore.get(domainName)
                .orElseThrow(() -> new AwsException("NotFoundException",
                    "No API associated with domain: " + domainName, 404));
        ApiAssociation assoc = new ApiAssociation();
        assoc.setDomainName(domainName);
        assoc.setApiId(apiId);
        assoc.setAssociationStatus("SUCCESS");
        return assoc;
    }

    public void disassociateApi(String domainName) {
        getDomainName(domainName);
        associationStore.delete(domainName);
    }

    // ──────────────────────────── Channel Namespaces ────────────────────────────

    public ChannelNamespace createChannelNamespace(String apiId, Map<String, Object> request) {
        getGraphqlApi(apiId);
        String name = (String) request.get("name");
        if (name == null || name.isBlank()) {
            throw new AwsException("BadRequestException", "A channel namespace name is required", 400);
        }
        String nsKey = apiKey(apiId, name);
        if (channelNamespaceStore.get(nsKey).isPresent()) {
            throw new AwsException("ConflictException",
                "Channel namespace already exists: " + name, 409);
        }
        ChannelNamespace ns = new ChannelNamespace();
        ns.setName(name);
        ns.setApiId(apiId);
        ns.setDescription((String) request.get("description"));
        ns.setChannelNamespaceArn(regionResolver.buildArn("appsync", regionResolver.getDefaultRegion(),
            "apis/" + apiId + "/channelNamespaces/" + name));
        ns.setCodeHandlers((String) request.get("codeHandlers"));
        ns.setCreated(System.currentTimeMillis());
        ns.setLastModified(System.currentTimeMillis());
        channelNamespaceStore.put(nsKey, ns);
        return ns;
    }

    public ChannelNamespace getChannelNamespace(String apiId, String name) {
        return channelNamespaceStore.get(apiKey(apiId, name))
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Channel namespace not found: " + name, 404));
    }

    public ChannelNamespace updateChannelNamespace(String apiId, String name, Map<String, Object> request) {
        ChannelNamespace existing = getChannelNamespace(apiId, name);
        if (request.containsKey("description")) existing.setDescription((String) request.get("description"));
        if (request.containsKey("codeHandlers")) existing.setCodeHandlers((String) request.get("codeHandlers"));
        existing.setLastModified(System.currentTimeMillis());
        channelNamespaceStore.put(apiKey(apiId, name), existing);
        return existing;
    }

    public void deleteChannelNamespace(String apiId, String name) {
        getChannelNamespace(apiId, name);
        channelNamespaceStore.delete(apiKey(apiId, name));
    }

    public Page<ChannelNamespace> listChannelNamespaces(String apiId, Integer maxResults, String nextToken) {
        return paginate(channelNamespaceStore.scan(k -> k.startsWith(apiId + "::")), nextToken, maxResults);
    }

    // ──────────────────────────── Merged API Associations ─────────────────

    public SourceApiAssociation createMergedApiAssociation(String sourceApiIdentifier, Map<String, Object> request, String region) {
        getGraphqlApi(sourceApiIdentifier);
        String mergedApiIdentifier = (String) request.getOrDefault("mergedApiIdentifier", request.get("mergedApiId"));
        if (mergedApiIdentifier == null || mergedApiIdentifier.isBlank()) {
            throw new AwsException("BadRequestException", "A merged API identifier is required", 400);
        }
        getGraphqlApi(mergedApiIdentifier);
        SourceApiAssociation assoc = new SourceApiAssociation();
        assoc.setAssociationId(generateShortId());
        assoc.setAssociationArn(regionResolver.buildArn("appsync", region,
            "sourceApis/" + sourceApiIdentifier + "/mergedApiAssociations/" + generateShortId()));
        assoc.setMergedApiId(mergedApiIdentifier);
        assoc.setMergedApiArn(regionResolver.buildArn("appsync", region, "apis/" + mergedApiIdentifier));
        assoc.setSourceApiId(sourceApiIdentifier);
        assoc.setSourceApiArn(regionResolver.buildArn("appsync", region, "apis/" + sourceApiIdentifier));
        assoc.setDescription((String) request.get("description"));
        assoc.setSourceApiAssociationStatus("MERGED");
        SourceApiAssociationConfig config = new SourceApiAssociationConfig();
        String mergeType = (String) request.getOrDefault("mergeType", "MERGE");
        config.setMergeType(mergeType);
        assoc.setSourceApiAssociationConfig(config);
        mergedApiAssociationStore.put(assoc.getAssociationId(), assoc);
        return assoc;
    }

    public SourceApiAssociation createSourceApiAssociation(String mergedApiIdentifier, Map<String, Object> request, String region) {
        getGraphqlApi(mergedApiIdentifier);
        String sourceApiId = (String) request.getOrDefault("sourceApiId", request.get("sourceApiIdentifier"));
        if (sourceApiId == null || sourceApiId.isBlank()) {
            throw new AwsException("BadRequestException", "A source API ID is required", 400);
        }
        getGraphqlApi(sourceApiId);
        SourceApiAssociation assoc = new SourceApiAssociation();
        assoc.setAssociationId(generateShortId());
        assoc.setAssociationArn(regionResolver.buildArn("appsync", region,
            "mergedApis/" + mergedApiIdentifier + "/sourceApiAssociations/" + generateShortId()));
        assoc.setMergedApiId(mergedApiIdentifier);
        assoc.setMergedApiArn(regionResolver.buildArn("appsync", region, "apis/" + mergedApiIdentifier));
        assoc.setSourceApiId(sourceApiId);
        assoc.setSourceApiArn(regionResolver.buildArn("appsync", region, "apis/" + sourceApiId));
        assoc.setDescription((String) request.get("description"));
        assoc.setSourceApiAssociationStatus("MERGED");
        SourceApiAssociationConfig config = new SourceApiAssociationConfig();
        String mergeType = (String) request.getOrDefault("mergeType", "MERGE");
        config.setMergeType(mergeType);
        assoc.setSourceApiAssociationConfig(config);
        mergedApiAssociationStore.put(assoc.getAssociationId(), assoc);
        return assoc;
    }

    public SourceApiAssociation getSourceApiAssociation(String mergedApiIdentifier, String associationId) {
        SourceApiAssociation assoc = mergedApiAssociationStore.get(associationId)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Source API association not found: " + associationId, 404));
        if (!mergedApiIdentifier.equals(assoc.getMergedApiId())) {
            throw new AwsException("NotFoundException",
                    "Source API association not found: " + associationId, 404);
        }
        return assoc;
    }

    public SourceApiAssociation updateSourceApiAssociation(String mergedApiIdentifier, String associationId,
                                                          Map<String, Object> request) {
        SourceApiAssociation assoc = getSourceApiAssociation(mergedApiIdentifier, associationId);
        if (request.containsKey("description")) {
            assoc.setDescription((String) request.get("description"));
        }
        Object configObj = request.get("sourceApiAssociationConfig");
        if (configObj instanceof Map<?, ?> configMap) {
            SourceApiAssociationConfig config = assoc.getSourceApiAssociationConfig();
            if (config == null) {
                config = new SourceApiAssociationConfig();
            }
            Object mergeType = configMap.get("mergeType");
            if (mergeType != null) {
                config.setMergeType((String) mergeType);
            }
            assoc.setSourceApiAssociationConfig(config);
        }
        mergedApiAssociationStore.put(associationId, assoc);
        return assoc;
    }

    public Page<SourceApiAssociationSummary> listSourceApiAssociations(String apiId, Integer maxResults, String nextToken) {
        List<SourceApiAssociationSummary> summaries = mergedApiAssociationStore.scan(k -> true).stream()
                .filter(a -> apiId.equals(a.getMergedApiId()) || apiId.equals(a.getSourceApiId()))
                .map(this::toSummary)
                .toList();
        return paginate(summaries, nextToken, maxResults);
    }

    public void deleteSourceApiAssociation(String mergedApiIdentifier, String associationId) {
        getSourceApiAssociation(mergedApiIdentifier, associationId);
        mergedApiAssociationStore.delete(associationId);
    }

    public SourceApiAssociation deleteMergedApiAssociation(String sourceApiIdentifier, String associationId) {
        SourceApiAssociation assoc = mergedApiAssociationStore.get(associationId)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Source API association not found: " + associationId, 404));
        if (!sourceApiIdentifier.equals(assoc.getSourceApiId())) {
            throw new AwsException("NotFoundException",
                    "Source API association not found: " + associationId, 404);
        }
        mergedApiAssociationStore.delete(associationId);
        assoc.setSourceApiAssociationStatus("DELETION_SCHEDULED");
        return assoc;
    }

    private SourceApiAssociationSummary toSummary(SourceApiAssociation assoc) {
        SourceApiAssociationSummary summary = new SourceApiAssociationSummary();
        summary.setAssociationArn(assoc.getAssociationArn());
        summary.setAssociationId(assoc.getAssociationId());
        summary.setDescription(assoc.getDescription());
        summary.setMergedApiArn(assoc.getMergedApiArn());
        summary.setMergedApiId(assoc.getMergedApiId());
        summary.setSourceApiArn(assoc.getSourceApiArn());
        summary.setSourceApiId(assoc.getSourceApiId());
        return summary;
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

    private String coerceString(Object value, String defaultValue) {
        String result = coerceString(value);
        return result != null ? result : defaultValue;
    }

    private Integer castInt(Object value) {
        if (value == null) return null;
        if (value instanceof Integer i) return i;
        if (value instanceof Number n) {
            long longVal = n.longValue();
            if (longVal < Integer.MIN_VALUE || longVal > Integer.MAX_VALUE) {
                throw new AwsException("BadRequestException",
                    "Integer value out of range: " + longVal, 400);
            }
            return n.intValue();
        }
        try {
            long longVal = Long.parseLong(value.toString());
            if (longVal < Integer.MIN_VALUE || longVal > Integer.MAX_VALUE) {
                throw new AwsException("BadRequestException",
                    "Integer value out of range: " + longVal, 400);
            }
            return (int) longVal;
        } catch (NumberFormatException e) {
            throw new AwsException("BadRequestException",
                "Invalid integer value: " + value, 400);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> castStringMap(Object value) {
        if (value instanceof Map) {
            Map<String, String> result = new HashMap<>();
            for (var entry : ((Map<Object, Object>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
            return result;
        }
        return null;
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

    private void deleteDataSourcesForApi(String apiId) {
        dataSourceStore.scan(k -> k.startsWith(apiId + "::"))
                .forEach(ds -> dataSourceStore.delete(apiKey(apiId, ds.getName())));
    }

    private void deleteResolversForApi(String apiId) {
        resolverStore.scan(k -> k.startsWith(apiId + "::"))
                .forEach(r -> resolverStore.delete(resolverKey(apiId, r.getTypeName(), r.getFieldName())));
    }

    private void deleteFunctionsForApi(String apiId) {
        functionStore.scan(k -> k.startsWith(apiId + "::"))
                .forEach(fn -> functionStore.delete(apiKey(apiId, fn.getFunctionId())));
    }

    private void deleteTypesForApi(String apiId) {
        typeStore.scan(k -> k.startsWith(apiId + "::"))
                .forEach(t -> typeStore.delete(apiKey(apiId, t.getName())));
    }

    private void deleteApiKeysForApi(String apiId) {
        apiKeyStore.scan(k -> k.startsWith(apiId + "::"))
                .forEach(k -> apiKeyStore.delete(apiKey(apiId, k.getId())));
    }

    private void deleteChannelNamespacesForApi(String apiId) {
        channelNamespaceStore.scan(k -> k.startsWith(apiId + "::"))
                .forEach(ns -> channelNamespaceStore.delete(apiKey(apiId, ns.getName())));
    }

    private void deleteDomainAssociationsForApi(String apiId) {
        for (String key : associationStore.keys()) {
            String assocApiId = associationStore.get(key).orElse(null);
            if (apiId.equals(assocApiId)) {
                associationStore.delete(key);
            }
        }
    }

    // ──────────────────────────── Pagination ────────────────────────────

    record Page<T>(List<T> items, String nextToken) {}

    static <T> Page<T> paginate(List<T> items, String nextToken, Integer maxResults) {
        int start = decodeToken(nextToken);
        if (start < 0 || start > items.size()) {
            throw new AwsException("BadRequestException", "Invalid NextToken.", 400);
        }
        if (maxResults != null && maxResults < 0) {
            throw new AwsException("BadRequestException", "maxResults must be non-negative", 400);
        }
        if (maxResults != null && maxResults == 0) {
            String next = (start < items.size()) ? encodeToken(start) : null;
            return new Page<>(List.of(), next);
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
        // Remove comments
        String cleaned = definition.replaceAll("#[^\n]*", "");
        String trimmed = cleaned.strip();
        String[] parts = trimmed.split("\\s+");
        if (parts.length >= 2) {
            String name = parts[1].replaceAll("[{(].*", "");
            if (!name.isEmpty() && !name.startsWith("#")) return name;
        }
        // Try next word if first was a comment artifact
        for (String part : parts) {
            if (!part.isEmpty() && !part.startsWith("#") && !part.equals("type") && !part.equals("input") && !part.equals("enum")) {
                return part.replaceAll("[{(].*", "");
            }
        }
        return null;
    }
}
