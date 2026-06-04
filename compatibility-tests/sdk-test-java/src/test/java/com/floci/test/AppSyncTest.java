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
        GetSchemaCreationStatusResponse resp = client.getSchemaCreationStatus(
                GetSchemaCreationStatusRequest.builder()
                        .apiId(apiId)
                        .build());

        assertThat(resp.statusAsString()).isEqualTo("ACTIVE");
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

    // ── Error Handling ─────────────────────────────────────────────────

    @Test
    @Order(80)
    void getNonExistentApiThrowsException() {
        assertThatThrownBy(() -> client.getGraphqlApi(GetGraphqlApiRequest.builder()
                        .apiId("nonexistent12345678901234")
                        .build()))
                .isInstanceOf(AppSyncException.class);
    }

    @Test
    @Order(81)
    void getNonExistentDataSourceThrowsException() {
        assertThatThrownBy(() -> client.getDataSource(GetDataSourceRequest.builder()
                        .apiId(apiId)
                        .name("nonexistent-ds")
                        .build()))
                .isInstanceOf(AppSyncException.class);
    }

    @Test
    @Order(82)
    void getNonExistentTypeThrowsException() {
        assertThatThrownBy(() -> client.getType(GetTypeRequest.builder()
                        .apiId(apiId)
                        .typeName("NonExistentType")
                        .build()))
                .isInstanceOf(AppSyncException.class);
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
}
