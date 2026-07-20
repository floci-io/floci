package com.floci.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.appsync.AppSyncClient;
import software.amazon.awssdk.services.appsync.model.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AppSync")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppSyncTest {

    private static AppSyncClient client;
    private static String apiId;
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void setup() {
        client = TestFixtures.appSyncClient();
    }

    @AfterAll
    static void cleanup() {
        if (apiId != null) {
            try { client.deleteGraphqlApi(r -> r.apiId(apiId)); } catch (Exception ignored) {}
        }
        if (client != null) client.close();
    }

    // ── GraphQL API CRUD ────────────────────────────────────────────────

    @Test
    @Order(1)
    void createGraphqlApi() {
        CreateGraphqlApiResponse resp = client.createGraphqlApi(CreateGraphqlApiRequest.builder()
                .name("sdk-test-api")
                .authenticationType("API_KEY")
                .tags(Map.of("env", "sdk-test"))
                .build());

        assertThat(resp.graphqlApi()).isNotNull();
        apiId = resp.graphqlApi().apiId();
        assertThat(apiId).isNotBlank();
        assertThat(resp.graphqlApi().name()).isEqualTo("sdk-test-api");
        assertThat(resp.graphqlApi().authenticationType()).isEqualTo(AuthenticationType.API_KEY);
        assertThat(resp.graphqlApi().arn()).contains("arn:aws:appsync:");
    }

    @Test
    @Order(2)
    void getGraphqlApi() {
        GetGraphqlApiResponse resp = client.getGraphqlApi(GetGraphqlApiRequest.builder()
                .apiId(apiId)
                .build());

        assertThat(resp.graphqlApi()).isNotNull();
        assertThat(resp.graphqlApi().apiId()).isEqualTo(apiId);
        assertThat(resp.graphqlApi().name()).isEqualTo("sdk-test-api");
    }

    @Test
    @Order(3)
    void listGraphqlApis() {
        ListGraphqlApisResponse resp = client.listGraphqlApis();

        assertThat(resp.graphqlApis()).isNotEmpty();
        assertThat(resp.graphqlApis().stream()
                .anyMatch(a -> a.apiId().equals(apiId))).isTrue();
    }

    @Test
    @Order(4)
    void updateGraphqlApi() {
        UpdateGraphqlApiResponse resp = client.updateGraphqlApi(UpdateGraphqlApiRequest.builder()
                .apiId(apiId)
                .name("sdk-test-api-v2")
                .build());

        assertThat(resp.graphqlApi()).isNotNull();
        assertThat(resp.graphqlApi().name()).isEqualTo("sdk-test-api-v2");
    }

    // ── Schema ──────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void startSchemaCreation() {
        StartSchemaCreationResponse resp = client.startSchemaCreation(StartSchemaCreationRequest.builder()
                .apiId(apiId)
                .definition(SdkBytes.fromUtf8String("type Query { hello: String }"))
                .build());

        assertThat(resp.status()).isNotNull();
    }

    @Test
    @Order(11)
    void getSchemaCreationStatus() {
        // Schema creation is async in real AWS: poll until terminal (SUCCESS
        // or FAILED). All subsequent tests in this class depend on the
        // schema being ACTIVE because the 409 gate fires while PROCESSING.
        SchemaStatus status = pollSchemaStatusToTerminal();
        assertThat(status).isEqualTo(SchemaStatus.SUCCESS);
    }

    // ── API Keys ────────────────────────────────────────────────────────

    private static String keyId;

    @Test
    @Order(20)
    void createApiKey() {
        long expiresEpoch = Instant.parse("2027-01-01T00:00:00Z").getEpochSecond();
        CreateApiKeyResponse resp = client.createApiKey(CreateApiKeyRequest.builder()
                .apiId(apiId)
                .description("sdk-test-key")
                .expires(expiresEpoch)
                .build());

        assertThat(resp.apiKey()).isNotNull();
        keyId = resp.apiKey().id();
        assertThat(keyId).isNotBlank();
        assertThat(resp.apiKey().description()).isEqualTo("sdk-test-key");
    }

    @Test
    @Order(21)
    void listApiKeys() {
        ListApiKeysResponse resp = client.listApiKeys(ListApiKeysRequest.builder()
                .apiId(apiId)
                .build());

        assertThat(resp.apiKeys()).isNotEmpty();
    }

    @Test
    @Order(22)
    void updateApiKey() {
        UpdateApiKeyResponse resp = client.updateApiKey(UpdateApiKeyRequest.builder()
                .apiId(apiId)
                .id(keyId)
                .description("updated-sdk-key")
                .build());

        assertThat(resp.apiKey()).isNotNull();
        assertThat(resp.apiKey().description()).isEqualTo("updated-sdk-key");
    }

    @Test
    @Order(23)
    void deleteApiKey() {
        CreateApiKeyResponse created = client.createApiKey(CreateApiKeyRequest.builder()
                .apiId(apiId)
                .description("temp-key")
                .build());

        DeleteApiKeyResponse resp = client.deleteApiKey(DeleteApiKeyRequest.builder()
                .apiId(apiId)
                .id(created.apiKey().id())
                .build());

        assertThat(resp).isNotNull();
    }

    // ── Data Sources ────────────────────────────────────────────────────

    @Test
    @Order(30)
    void createDataSource() {
        CreateDataSourceResponse resp = client.createDataSource(CreateDataSourceRequest.builder()
                .apiId(apiId)
                .name("none-ds")
                .type("NONE")
                .build());

        assertThat(resp.dataSource()).isNotNull();
        assertThat(resp.dataSource().name()).isEqualTo("none-ds");
        assertThat(resp.dataSource().typeAsString()).isEqualTo("NONE");
    }

    @Test
    @Order(31)
    void getDataSource() {
        GetDataSourceResponse resp = client.getDataSource(GetDataSourceRequest.builder()
                .apiId(apiId)
                .name("none-ds")
                .build());

        assertThat(resp.dataSource()).isNotNull();
        assertThat(resp.dataSource().name()).isEqualTo("none-ds");
    }

    @Test
    @Order(32)
    void listDataSources() {
        ListDataSourcesResponse resp = client.listDataSources(ListDataSourcesRequest.builder()
                .apiId(apiId)
                .build());

        assertThat(resp.dataSources()).isNotEmpty();
    }

    @Test
    @Order(33)
    void updateDataSource() {
        UpdateDataSourceResponse resp = client.updateDataSource(UpdateDataSourceRequest.builder()
                .apiId(apiId)
                .name("none-ds")
                .description("updated-ds")
                .build());

        assertThat(resp.dataSource()).isNotNull();
        assertThat(resp.dataSource().description()).isEqualTo("updated-ds");
    }

    @Test
    @Order(34)
    void deleteDataSource() {
        client.createDataSource(CreateDataSourceRequest.builder()
                .apiId(apiId)
                .name("temp-ds")
                .type("NONE")
                .build());

        DeleteDataSourceResponse resp = client.deleteDataSource(DeleteDataSourceRequest.builder()
                .apiId(apiId)
                .name("temp-ds")
                .build());

        assertThat(resp).isNotNull();
    }

    // ── Resolvers ───────────────────────────────────────────────────────

    @Test
    @Order(40)
    void createResolver() {
        CreateResolverResponse resp = client.createResolver(CreateResolverRequest.builder()
                .apiId(apiId)
                .typeName("Query")
                .fieldName("hello")
                .dataSourceName("none-ds")
                .build());

        assertThat(resp.resolver()).isNotNull();
        assertThat(resp.resolver().typeName()).isEqualTo("Query");
        assertThat(resp.resolver().fieldName()).isEqualTo("hello");
    }

    @Test
    @Order(41)
    void getResolver() {
        GetResolverResponse resp = client.getResolver(GetResolverRequest.builder()
                .apiId(apiId)
                .typeName("Query")
                .fieldName("hello")
                .build());

        assertThat(resp.resolver()).isNotNull();
        assertThat(resp.resolver().fieldName()).isEqualTo("hello");
    }

    @Test
    @Order(42)
    void listResolvers() {
        ListResolversResponse resp = client.listResolvers(ListResolversRequest.builder()
                .apiId(apiId)
                .typeName("Query")
                .build());

        assertThat(resp.resolvers()).isNotEmpty();
    }

    @Test
    @Order(43)
    void updateResolver() {
        UpdateResolverResponse resp = client.updateResolver(UpdateResolverRequest.builder()
                .apiId(apiId)
                .typeName("Query")
                .fieldName("hello")
                .dataSourceName("none-ds")
                .responseMappingTemplate("$util.toJson({\"updated\": true})")
                .build());

        assertThat(resp.resolver()).isNotNull();
        assertThat(resp.resolver().responseMappingTemplate()).contains("updated");
    }

    @Test
    @Order(44)
    void deleteResolver() {
        client.createResolver(CreateResolverRequest.builder()
                .apiId(apiId)
                .typeName("Query")
                .fieldName("tempField")
                .dataSourceName("none-ds")
                .build());

        DeleteResolverResponse resp = client.deleteResolver(DeleteResolverRequest.builder()
                .apiId(apiId)
                .typeName("Query")
                .fieldName("tempField")
                .build());

        assertThat(resp).isNotNull();
    }

    // ── Functions ───────────────────────────────────────────────────────

    @Test
    @Order(50)
    void createFunction() {
        CreateFunctionResponse resp = client.createFunction(CreateFunctionRequest.builder()
                .apiId(apiId)
                .name("sdk-function")
                .dataSourceName("none-ds")
                .build());

        assertThat(resp.functionConfiguration()).isNotNull();
        assertThat(resp.functionConfiguration().name()).isEqualTo("sdk-function");
        assertThat(resp.functionConfiguration().functionId()).isNotBlank();
    }

    @Test
    @Order(51)
    void listFunctions() {
        ListFunctionsResponse resp = client.listFunctions(ListFunctionsRequest.builder()
                .apiId(apiId)
                .build());

        assertThat(resp.functions()).isNotEmpty();
    }

    @Test
    @Order(52)
    void updateFunction() {
        String fnId = client.listFunctions(ListFunctionsRequest.builder()
                .apiId(apiId)
                .build())
                .functions().get(0).functionId();

        UpdateFunctionResponse resp = client.updateFunction(UpdateFunctionRequest.builder()
                .apiId(apiId)
                .functionId(fnId)
                .name("sdk-function")
                .dataSourceName("none-ds")
                .description("updated-fn")
                .build());

        assertThat(resp.functionConfiguration()).isNotNull();
        assertThat(resp.functionConfiguration().description()).isEqualTo("updated-fn");
    }

    @Test
    @Order(53)
    void deleteFunction() {
        CreateFunctionResponse fnResp = client.createFunction(CreateFunctionRequest.builder()
                .apiId(apiId)
                .name("temp-function")
                .dataSourceName("none-ds")
                .build());

        DeleteFunctionResponse resp = client.deleteFunction(DeleteFunctionRequest.builder()
                .apiId(apiId)
                .functionId(fnResp.functionConfiguration().functionId())
                .build());

        assertThat(resp).isNotNull();
    }

    // ── Tags ────────────────────────────────────────────────────────────

    @Test
    @Order(60)
    void tagResource() {
        GetGraphqlApiResponse api = client.getGraphqlApi(r -> r.apiId(apiId));
        String arn = api.graphqlApi().arn();

        client.tagResource(TagResourceRequest.builder()
                .resourceArn(arn)
                .tags(Map.of("team", "platform"))
                .build());

        ListTagsForResourceResponse resp = client.listTagsForResource(
                ListTagsForResourceRequest.builder()
                        .resourceArn(arn)
                        .build());

        assertThat(resp.tags()).containsEntry("env", "sdk-test");
        assertThat(resp.tags()).containsEntry("team", "platform");
    }

    @Test
    @Order(61)
    void untagResource() {
        GetGraphqlApiResponse api = client.getGraphqlApi(r -> r.apiId(apiId));
        String arn = api.graphqlApi().arn();

        client.untagResource(UntagResourceRequest.builder()
                .resourceArn(arn)
                .tagKeys("team")
                .build());

        ListTagsForResourceResponse resp = client.listTagsForResource(
                ListTagsForResourceRequest.builder()
                        .resourceArn(arn)
                        .build());

        assertThat(resp.tags()).doesNotContainKey("team");
        assertThat(resp.tags()).containsEntry("env", "sdk-test");
    }

    // ── Types ──────────────────────────────────────────────────────────

    @Test
    @Order(70)
    void createType() {
        CreateTypeResponse resp = client.createType(CreateTypeRequest.builder()
                .apiId(apiId)
                .definition("type Item { id: ID!, name: String }")
                .build());

        assertThat(resp.type()).isNotNull();
        assertThat(resp.type().name()).isEqualTo("Item");
        assertThat(resp.type().definition()).contains("Item");
    }

    @Test
    @Order(71)
    void getType() {
        GetTypeResponse resp = client.getType(GetTypeRequest.builder()
                .apiId(apiId)
                .typeName("Item")
                .build());

        assertThat(resp.type()).isNotNull();
        assertThat(resp.type().name()).isEqualTo("Item");
    }

    @Test
    @Order(72)
    void listTypes() {
        ListTypesResponse resp = client.listTypes(ListTypesRequest.builder()
                .apiId(apiId)
                .build());

        assertThat(resp.types()).isNotEmpty();
    }

    @Test
    @Order(73)
    void updateType() {
        UpdateTypeResponse resp = client.updateType(UpdateTypeRequest.builder()
                .apiId(apiId)
                .typeName("Item")
                .definition("type Item { id: ID!, name: String, description: String }")
                .build());

        assertThat(resp.type()).isNotNull();
        assertThat(resp.type().definition()).contains("description");
    }

    @Test
    @Order(74)
    void deleteType() {
        client.createType(CreateTypeRequest.builder()
                .apiId(apiId)
                .definition("type TempType { id: ID }")
                .build());

        DeleteTypeResponse resp = client.deleteType(DeleteTypeRequest.builder()
                .apiId(apiId)
                .typeName("TempType")
                .build());

        assertThat(resp).isNotNull();
    }

    // ── Phase 2: Model Compatibility ───────────────────────────────────

    @Test
    @Order(90)
    void createDataSource_hasDataSourceArn() {
        CreateDataSourceResponse resp = client.createDataSource(CreateDataSourceRequest.builder()
                .apiId(apiId)
                .name("arn-check-ds")
                .type("NONE")
                .build());

        assertThat(resp.dataSource().dataSourceArn()).isNotNull();
        assertThat(resp.dataSource().dataSourceArn()).contains("arn:aws:appsync:");
        assertThat(resp.dataSource().dataSourceArn()).contains("/datasources/arn-check-ds");

        client.deleteDataSource(r -> r.apiId(apiId).name("arn-check-ds"));
    }

    @Test
    @Order(91)
    void createResolver_hasResolverArn() {
        CreateResolverResponse resp = client.createResolver(CreateResolverRequest.builder()
                .apiId(apiId)
                .typeName("Query")
                .fieldName("resolverArnCheck")
                .dataSourceName("none-ds")
                .build());

        assertThat(resp.resolver().resolverArn()).isNotNull();
        assertThat(resp.resolver().resolverArn()).contains("arn:aws:appsync:");
        assertThat(resp.resolver().resolverArn()).contains("/types/Query/resolvers/resolverArnCheck");

        client.deleteResolver(r -> r.apiId(apiId).typeName("Query").fieldName("resolverArnCheck"));
    }

    @Test
    @Order(92)
    void createFunction_hasFunctionArn() {
        CreateFunctionResponse resp = client.createFunction(CreateFunctionRequest.builder()
                .apiId(apiId)
                .name("arn-check-fn")
                .dataSourceName("none-ds")
                .build());

        assertThat(resp.functionConfiguration().functionArn()).isNotNull();
        assertThat(resp.functionConfiguration().functionArn()).contains("arn:aws:appsync:");

        client.deleteFunction(r -> r.apiId(apiId).functionId(resp.functionConfiguration().functionId()));
    }

    @Test
    @Order(93)
    void startSchemaCreation_invalidSchema() {
        StartSchemaCreationResponse resp = client.startSchemaCreation(StartSchemaCreationRequest.builder()
                .apiId(apiId)
                .definition(SdkBytes.fromUtf8String("type Query { invalid !!! }"))
                .build());
        assertThat(resp.status()).isEqualTo(SchemaStatus.PROCESSING);
        SchemaStatus terminal = pollSchemaStatusToTerminal();
        assertThat(terminal).isEqualTo(SchemaStatus.FAILED);
    }

    private SchemaStatus pollSchemaStatusToTerminal() {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        SchemaStatus s = client.getSchemaCreationStatus(r -> r.apiId(apiId)).status();
        while (s == SchemaStatus.PROCESSING && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            }
            s = client.getSchemaCreationStatus(r -> r.apiId(apiId)).status();
        }
        return s;
    }

    @Test
    @Order(94)
    void startSchemaCreation_withAWSScalars() {
        StartSchemaCreationResponse resp = client.startSchemaCreation(
                StartSchemaCreationRequest.builder()
                        .apiId(apiId)
                        .definition(SdkBytes.fromUtf8String(
                                "type Query { now: AWSDateTime, data: AWSJSON, ip: AWSIPAddress, active: AWSBoolean }"))
                        .build());

        assertThat(resp.status()).isNotNull();
    }

    // ── Channel Namespaces ─────────────────────────────────────────────

    private static String channelNsName;

    @Test
    @Order(95)
    void createChannelNamespace() {
        CreateChannelNamespaceResponse resp = client.createChannelNamespace(
                CreateChannelNamespaceRequest.builder()
                        .apiId(apiId)
                        .name("sdk-channel-ns")
                        .build());

        assertThat(resp.channelNamespace()).isNotNull();
        channelNsName = resp.channelNamespace().name();
        assertThat(channelNsName).isEqualTo("sdk-channel-ns");
        assertThat(resp.channelNamespace().apiId()).isEqualTo(apiId);
    }

    @Test
    @Order(96)
    void getChannelNamespace() {
        GetChannelNamespaceResponse resp = client.getChannelNamespace(
                GetChannelNamespaceRequest.builder()
                        .apiId(apiId)
                        .name(channelNsName)
                        .build());

        assertThat(resp.channelNamespace()).isNotNull();
        assertThat(resp.channelNamespace().name()).isEqualTo(channelNsName);
    }

    @Test
    @Order(97)
    void updateChannelNamespace() {
        UpdateChannelNamespaceResponse resp = client.updateChannelNamespace(
                UpdateChannelNamespaceRequest.builder()
                        .apiId(apiId)
                        .name(channelNsName)
                        .codeHandlers("export function handler(ctx) { return ctx; }")
                        .build());

        assertThat(resp.channelNamespace()).isNotNull();
        assertThat(resp.channelNamespace().codeHandlers()).contains("handler");
    }

    @Test
    @Order(98)
    void listChannelNamespaces() {
        ListChannelNamespacesResponse resp = client.listChannelNamespaces(
                ListChannelNamespacesRequest.builder()
                        .apiId(apiId)
                        .build());

        assertThat(resp.channelNamespaces()).isNotEmpty();
    }

    @Test
    @Order(99)
    void deleteChannelNamespace() {
        DeleteChannelNamespaceResponse resp = client.deleteChannelNamespace(
                DeleteChannelNamespaceRequest.builder()
                        .apiId(apiId)
                        .name(channelNsName)
                        .build());

        assertThat(resp).isNotNull();
    }

    // ── Domain Association ─────────────────────────────────────────────

    private static String tempDomainName;

    @Test
    @Order(100)
    void createDomainName() {
        // Use unique domain name to avoid conflicts
        String domainSuffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        CreateDomainNameResponse resp = client.createDomainName(
                CreateDomainNameRequest.builder()
                        .domainName("sdk-" + domainSuffix + ".example.com")
                        .certificateArn("arn:aws:acm:us-east-1:000000000000:certificate/sdk")
                        .build());

        assertThat(resp.domainNameConfig()).isNotNull();
        tempDomainName = resp.domainNameConfig().domainName();
        assertThat(tempDomainName).startsWith("sdk-");
        assertThat(resp.domainNameConfig().appsyncDomainName()).isNotNull();
    }

    @Test
    @Order(101)
    void getDomainName() {
        GetDomainNameResponse resp = client.getDomainName(
                GetDomainNameRequest.builder()
                        .domainName(tempDomainName)
                        .build());

        assertThat(resp.domainNameConfig()).isNotNull();
        assertThat(resp.domainNameConfig().domainName()).isEqualTo(tempDomainName);
    }

    @Test
    @Order(102)
    void updateDomainName() {
        UpdateDomainNameResponse resp = client.updateDomainName(
                UpdateDomainNameRequest.builder()
                        .domainName(tempDomainName)
                        .description("updated-domain")
                        .build());

        assertThat(resp.domainNameConfig()).isNotNull();
        assertThat(resp.domainNameConfig().domainName()).isEqualTo(tempDomainName);
        assertThat(resp.domainNameConfig().description()).isEqualTo("updated-domain");
    }

    @Test
    @Order(103)
    void associateApi() {
        AssociateApiResponse resp = client.associateApi(
                AssociateApiRequest.builder()
                        .domainName(tempDomainName)
                        .apiId(apiId)
                        .build());

        assertThat(resp.apiAssociation()).isNotNull();
        assertThat(resp.apiAssociation().domainName()).isEqualTo(tempDomainName);
        assertThat(resp.apiAssociation().apiId()).isEqualTo(apiId);
    }

    @Test
    @Order(104)
    void getApiAssociation() {
        GetApiAssociationResponse resp = client.getApiAssociation(
                GetApiAssociationRequest.builder()
                        .domainName(tempDomainName)
                        .build());

        assertThat(resp.apiAssociation()).isNotNull();
        assertThat(resp.apiAssociation().apiId()).isEqualTo(apiId);
    }

    @Test
    @Order(105)
    void disassociateApi() {
        DisassociateApiResponse resp = client.disassociateApi(
                DisassociateApiRequest.builder()
                        .domainName(tempDomainName)
                        .build());

        assertThat(resp).isNotNull();
    }

    @Test
    @Order(106)
    void deleteDomainName() {
        DeleteDomainNameResponse resp = client.deleteDomainName(
                DeleteDomainNameRequest.builder()
                        .domainName(tempDomainName)
                        .build());

        assertThat(resp).isNotNull();
    }

    // ── Source API Association ─────────────────────────────────────────

    private static String sourceApiId;
    private static String assocId;

    @Test
    @Order(107)
    void associateSourceGraphqlApi() {
        // Create source API
        CreateGraphqlApiResponse sourceResp = client.createGraphqlApi(
                CreateGraphqlApiRequest.builder()
                        .name("sdk-source-api")
                        .authenticationType("API_KEY")
                        .build());
        sourceApiId = sourceResp.graphqlApi().apiId();

        AssociateSourceGraphqlApiResponse resp = client.associateSourceGraphqlApi(
                AssociateSourceGraphqlApiRequest.builder()
                        .mergedApiIdentifier(apiId)
                        .sourceApiIdentifier(sourceApiId)
                        .build());

        assertThat(resp.sourceApiAssociation()).isNotNull();
        assocId = resp.sourceApiAssociation().associationId();
        assertThat(assocId).isNotNull();
        assertThat(resp.sourceApiAssociation().sourceApiId()).isEqualTo(sourceApiId);
    }

    @Test
    @Order(108)
    void getSourceApiAssociation() {
        GetSourceApiAssociationResponse resp = client.getSourceApiAssociation(
                GetSourceApiAssociationRequest.builder()
                        .mergedApiIdentifier(apiId)
                        .associationId(assocId)
                        .build());

        assertThat(resp.sourceApiAssociation()).isNotNull();
        assertThat(resp.sourceApiAssociation().associationId()).isEqualTo(assocId);
        assertThat(resp.sourceApiAssociation().sourceApiId()).isEqualTo(sourceApiId);
    }

    @Test
    @Order(109)
    void listSourceApiAssociations() {
        ListSourceApiAssociationsResponse resp = client.listSourceApiAssociations(
                ListSourceApiAssociationsRequest.builder()
                        .apiId(apiId)
                        .build());

        assertThat(resp.sourceApiAssociationSummaries()).isNotEmpty();
    }

    @Test
    @Order(110)
    void disassociateSourceGraphqlApi() {
        DisassociateSourceGraphqlApiResponse resp = client.disassociateSourceGraphqlApi(
                DisassociateSourceGraphqlApiRequest.builder()
                        .mergedApiIdentifier(apiId)
                        .associationId(assocId)
                        .build());

        assertThat(resp).isNotNull();
    }

    @Test
    @Order(111)
    void cleanupSourceApi() {
        client.deleteGraphqlApi(DeleteGraphqlApiRequest.builder()
                .apiId(sourceApiId)
                .build());
    }

    // ── Merged API Associations ────────────────────────────────────────

    private static String mergedAssocSourceApiId;
    private static String mergedAssocId;

    @Test
    @Order(112)
    void associateMergedGraphqlApi() {
        CreateGraphqlApiResponse sourceResp = client.createGraphqlApi(
                CreateGraphqlApiRequest.builder()
                        .name("sdk-merged-source")
                        .authenticationType("API_KEY")
                        .build());
        mergedAssocSourceApiId = sourceResp.graphqlApi().apiId();

        AssociateMergedGraphqlApiResponse resp = client.associateMergedGraphqlApi(
                AssociateMergedGraphqlApiRequest.builder()
                        .sourceApiIdentifier(mergedAssocSourceApiId)
                        .mergedApiIdentifier(apiId)
                        .build());

        assertThat(resp.sourceApiAssociation()).isNotNull();
        mergedAssocId = resp.sourceApiAssociation().associationId();
        assertThat(mergedAssocId).isNotNull();
        assertThat(resp.sourceApiAssociation().sourceApiId()).isEqualTo(mergedAssocSourceApiId);
        assertThat(resp.sourceApiAssociation().mergedApiId()).isEqualTo(apiId);
    }

    @Test
    @Order(113)
    void updateSourceApiAssociation() {
        CreateGraphqlApiResponse updateSourceResp = client.createGraphqlApi(
                CreateGraphqlApiRequest.builder()
                        .name("sdk-update-source")
                        .authenticationType("API_KEY")
                        .build());
        String updateSourceApiId = updateSourceResp.graphqlApi().apiId();

        AssociateSourceGraphqlApiResponse assocResp = client.associateSourceGraphqlApi(
                AssociateSourceGraphqlApiRequest.builder()
                        .mergedApiIdentifier(apiId)
                        .sourceApiIdentifier(updateSourceApiId)
                        .build());
        String updateAssocId = assocResp.sourceApiAssociation().associationId();

        UpdateSourceApiAssociationResponse resp = client.updateSourceApiAssociation(
                UpdateSourceApiAssociationRequest.builder()
                        .mergedApiIdentifier(apiId)
                        .associationId(updateAssocId)
                        .description("updated-association")
                        .build());

        assertThat(resp.sourceApiAssociation()).isNotNull();
        assertThat(resp.sourceApiAssociation().associationId()).isEqualTo(updateAssocId);
        assertThat(resp.sourceApiAssociation().description()).isEqualTo("updated-association");

        client.deleteGraphqlApi(DeleteGraphqlApiRequest.builder().apiId(updateSourceApiId).build());
    }

    @Test
    @Order(114)
    void disassociateMergedGraphqlApi() {
        DisassociateMergedGraphqlApiResponse resp = client.disassociateMergedGraphqlApi(
                DisassociateMergedGraphqlApiRequest.builder()
                        .sourceApiIdentifier(mergedAssocSourceApiId)
                        .associationId(mergedAssocId)
                        .build());

        assertThat(resp).isNotNull();
        assertThat(resp.sourceApiAssociationStatus()).isNotNull();
    }

    @Test
    @Order(115)
    void cleanupMergedSourceApi() {
        client.deleteGraphqlApi(DeleteGraphqlApiRequest.builder()
                .apiId(mergedAssocSourceApiId)
                .build());
    }

    // ── SDK Coverage Gaps ─────────────────────────────────────────────

    @Test
    @Order(116)
    void getIntrospectionSchema() {
        GetIntrospectionSchemaResponse resp = client.getIntrospectionSchema(
                GetIntrospectionSchemaRequest.builder()
                        .apiId(apiId)
                        .format("SDL")
                        .build());

        assertThat(resp.schema()).isNotNull();
        String schema = resp.schema().asUtf8String();
        assertThat(schema).contains("type Query");
    }

    @Test
    @Order(117)
    void listDomainNames() {
        CreateDomainNameResponse created = client.createDomainName(
                CreateDomainNameRequest.builder()
                        .domainName("list-test-" + java.util.UUID.randomUUID().toString().substring(0, 8) + ".example.com")
                        .certificateArn("arn:aws:acm:us-east-1:000000000000:certificate/test")
                        .build());

        ListDomainNamesResponse resp = client.listDomainNames(
                ListDomainNamesRequest.builder()
                        .build());

        assertThat(resp.domainNameConfigs()).isNotEmpty();

        client.deleteDomainName(DeleteDomainNameRequest.builder()
                .domainName(created.domainNameConfig().domainName())
                .build());
    }

    @Test
    @Order(118)
    void getFunction() {
        String fnId = client.listFunctions(ListFunctionsRequest.builder()
                .apiId(apiId)
                .build())
                .functions().get(0).functionId();

        GetFunctionResponse resp = client.getFunction(GetFunctionRequest.builder()
                .apiId(apiId)
                .functionId(fnId)
                .build());

        assertThat(resp.functionConfiguration()).isNotNull();
        assertThat(resp.functionConfiguration().functionId()).isEqualTo(fnId);
    }

    @Test
    @Order(119)
    void listResolversByFunction() {
        String fnId = client.listFunctions(ListFunctionsRequest.builder()
                .apiId(apiId)
                .build())
                .functions().get(0).functionId();

        ListResolversByFunctionResponse resp = client.listResolversByFunction(
                ListResolversByFunctionRequest.builder()
                        .apiId(apiId)
                        .functionId(fnId)
                        .build());

        assertThat(resp).isNotNull();
    }

    // ── Error Handling ─────────────────────────────────────────────────

    @Test
    @Order(80)
    void getNonExistentApiThrowsNotFoundException() {
        assertThatThrownBy(() -> client.getGraphqlApi(GetGraphqlApiRequest.builder()
                        .apiId("nonexistent12345678901234")
                        .build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("GraphQL API not found:");
    }

    @Test
    @Order(81)
    void getNonExistentDataSourceThrowsNotFoundException() {
        assertThatThrownBy(() -> client.getDataSource(GetDataSourceRequest.builder()
                        .apiId(apiId)
                        .name("nonexistent-ds")
                        .build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Data source not found:");
    }

    @Test
    @Order(82)
    void getNonExistentTypeThrowsNotFoundException() {
        assertThatThrownBy(() -> client.getType(GetTypeRequest.builder()
                        .apiId(apiId)
                        .typeName("NonExistentType")
                        .build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Type not found:");
    }

    @Test
    @Order(83)
    void createApiKeyMissingDescriptionSucceeds() {
        CreateApiKeyResponse resp = client.createApiKey(CreateApiKeyRequest.builder()
                .apiId(apiId)
                .build());

        assertThat(resp.apiKey()).isNotNull();
        assertThat(resp.apiKey().id()).isNotBlank();
    }

    // ── SDK Coverage Gaps: Environment Variables ───────────────────────────

    @Test
    @Order(120)
    void putGraphqlApiEnvironmentVariables() {
        PutGraphqlApiEnvironmentVariablesResponse resp = client.putGraphqlApiEnvironmentVariables(
                PutGraphqlApiEnvironmentVariablesRequest.builder()
                        .apiId(apiId)
                        .environmentVariables(Map.of("TABLE_NAME", "my-table", "REGION", "us-east-1"))
                        .build());

        assertThat(resp.environmentVariables())
                .containsEntry("TABLE_NAME", "my-table")
                .containsEntry("REGION", "us-east-1");
    }

    @Test
    @Order(121)
    void getGraphqlApiEnvironmentVariables() {
        GetGraphqlApiEnvironmentVariablesResponse resp = client.getGraphqlApiEnvironmentVariables(
                GetGraphqlApiEnvironmentVariablesRequest.builder()
                        .apiId(apiId)
                        .build());

        assertThat(resp.environmentVariables())
                .containsEntry("TABLE_NAME", "my-table")
                .containsEntry("REGION", "us-east-1");
    }

    // ── SDK Coverage Gaps: Pagination ──────────────────────────────────────

    @Test
    @Order(122)
    void listGraphqlApisWithMaxResults() {
        for (int i = 0; i < 2; i++) {
            client.createGraphqlApi(CreateGraphqlApiRequest.builder()
                    .name("pg-sdk-api-" + i + "-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                    .authenticationType("API_KEY")
                    .build());
        }

        ListGraphqlApisResponse page1 = client.listGraphqlApis(ListGraphqlApisRequest.builder()
                .maxResults(2)
                .build());

        assertThat(page1.graphqlApis()).hasSizeLessThanOrEqualTo(2);
        if (page1.nextToken() != null) {
            ListGraphqlApisResponse page2 = client.listGraphqlApis(ListGraphqlApisRequest.builder()
                    .maxResults(2)
                    .nextToken(page1.nextToken())
                    .build());
            assertThat(page2.graphqlApis()).isNotEmpty();
        }
    }

    @Test
    @Order(123)
    void listDataSourcesWithMaxResults() {
        for (int i = 0; i < 2; i++) {
            client.createDataSource(CreateDataSourceRequest.builder()
                    .apiId(apiId)
                    .name("pg-sdk-ds-" + i + "-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                    .type("NONE")
                    .build());
        }

        ListDataSourcesResponse page1 = client.listDataSources(ListDataSourcesRequest.builder()
                .apiId(apiId)
                .maxResults(1)
                .build());

        assertThat(page1.dataSources()).hasSize(1);
        assertThat(page1.nextToken()).isNotNull();

        ListDataSourcesResponse page2 = client.listDataSources(ListDataSourcesRequest.builder()
                .apiId(apiId)
                .maxResults(1)
                .nextToken(page1.nextToken())
                .build());

        assertThat(page2.dataSources()).isNotEmpty();
    }

    // ── SDK Coverage Gaps: Mirror Error Tests (409/429/404) ────────────────

    @Test
    @Order(130)
    void createDataSourceDuplicateThrowsBadRequestException() {
        String dsName = "sdk-conflict-ds-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        client.createDataSource(CreateDataSourceRequest.builder()
                .apiId(apiId)
                .name(dsName)
                .type("NONE")
                .build());

        assertThatThrownBy(() -> client.createDataSource(CreateDataSourceRequest.builder()
                        .apiId(apiId)
                        .name(dsName)
                        .type("NONE")
                        .build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Data source with name");

        client.deleteDataSource(r -> r.apiId(apiId).name(dsName));
    }

    @Test
    @Order(131)
    void createApiKeyExceedsLimitThrowsApiKeyLimitExceededException() {
        CreateGraphqlApiResponse tempApi = client.createGraphqlApi(CreateGraphqlApiRequest.builder()
                .name("sdk-limit-test-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                .authenticationType("API_KEY")
                .build());
        String tempApiId = tempApi.graphqlApi().apiId();

        try {
            client.createApiKey(CreateApiKeyRequest.builder().apiId(tempApiId).build());
            client.createApiKey(CreateApiKeyRequest.builder().apiId(tempApiId).build());

            assertThatThrownBy(() -> client.createApiKey(CreateApiKeyRequest.builder().apiId(tempApiId).build()))
                    .isInstanceOf(ApiKeyLimitExceededException.class)
                    .hasMessageContaining("The API key exceeded a limit");
        } finally {
            client.deleteGraphqlApi(r -> r.apiId(tempApiId));
        }
    }

    @Test
    @Order(132)
    void updateResolverNonExistentThrowsNotFoundException() {
        assertThatThrownBy(() -> client.updateResolver(UpdateResolverRequest.builder()
                        .apiId(apiId)
                        .typeName("Query")
                        .fieldName("nonExistentSdkField")
                        .dataSourceName("none-ds")
                        .build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Resolver not found:");
    }

    @Test
    @Order(133)
    void deleteFunctionNonExistentThrowsNotFoundException() {
        assertThatThrownBy(() -> client.deleteFunction(DeleteFunctionRequest.builder()
                        .apiId(apiId)
                        .functionId("nonexistent00000000000")
                        .build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Function not found:");
    }

    @Test
    @Order(134)
    void updateApiKeyNonExistentThrowsNotFoundException() {
        assertThatThrownBy(() -> client.updateApiKey(UpdateApiKeyRequest.builder()
                        .apiId(apiId)
                        .id("nonexistent00000000000")
                        .description("updated")
                        .build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("API key not found:");
    }

    @Test
    @Order(135)
    void updateDomainNameNonExistentThrowsNotFoundException() {
        assertThatThrownBy(() -> client.updateDomainName(UpdateDomainNameRequest.builder()
                        .domainName("nonexistent.example.com")
                        .description("updated")
                        .build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Domain name not found:");
    }

    @Test
    @Order(136)
    void createResolverMissingDataSourceThrowsNotFoundException() {
        assertThatThrownBy(() -> client.createResolver(CreateResolverRequest.builder()
                        .apiId(apiId)
                        .typeName("Query")
                        .fieldName("crossRefSdkField")
                        .dataSourceName("nonexistent-ds-for-sdk-resolver")
                        .build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Data source not found:");
    }

    @Test
    @Order(137)
    void createSourceApiAssociationDuplicateThrowsConcurrentModificationException() {
        String sourceApiId = client.createGraphqlApi(CreateGraphqlApiRequest.builder()
                .name("sdk-dup-source-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                .authenticationType("API_KEY")
                .build()).graphqlApi().apiId();

        try {
            client.associateSourceGraphqlApi(AssociateSourceGraphqlApiRequest.builder()
                    .mergedApiIdentifier(apiId)
                    .sourceApiIdentifier(sourceApiId)
                    .build());

            assertThatThrownBy(() -> client.associateSourceGraphqlApi(AssociateSourceGraphqlApiRequest.builder()
                            .mergedApiIdentifier(apiId)
                            .sourceApiIdentifier(sourceApiId)
                            .build()))
                    .isInstanceOf(ConcurrentModificationException.class)
                    .hasMessageContaining("Source API association already exists for Source API ID")
                    .hasMessageContaining("and Merged API ID");
        } finally {
            client.deleteGraphqlApi(r -> r.apiId(sourceApiId));
        }
    }

    @Test
    @Order(200)
    void startSchemaCreation_syntaxError_throwsBadRequestWithExtendedBody() throws Exception {
        String body = """
            { "definition": "type Query { invalid syntax!!! }" }
            """;
        String url = TestFixtures.endpoint() + "/v1/apis/" + apiId + "/schemacreation";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/appsync/aws4_request")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());

        // Async path: 200 + PROCESSING; the error surfaces in the FAILED details.
        assertThat(resp.statusCode()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> jsonBody = mapper.readValue(resp.body(), Map.class);
        assertThat(jsonBody.get("status")).isEqualTo("PROCESSING");

        SchemaStatus terminal = pollSchemaStatusToTerminal();
        assertThat(terminal).isEqualTo(SchemaStatus.FAILED);

        GetSchemaCreationStatusResponse status = client.getSchemaCreationStatus(r -> r.apiId(apiId));
        @SuppressWarnings("unchecked")
        Map<String, Object> extended = mapper.readValue(status.details(), Map.class);
        assertThat(extended.get("reason")).isEqualTo("CODE_ERROR");
        assertThat(extended).containsKey("detail");
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) extended.get("detail");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> codeErrors = (List<Map<String, Object>>) detail.get("codeErrors");
        assertThat(codeErrors).isNotEmpty();
        Map<String, Object> first = codeErrors.get(0);
        assertThat(first.get("errorType")).isEqualTo("PARSER_ERROR");
        assertThat(first.get("value")).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> location = (Map<String, Object>) first.get("location");
        assertThat(location.get("line")).isNotNull();
        assertThat(location.get("column")).isNotNull();
        assertThat(location.get("span")).isEqualTo(-1);
    }
}
