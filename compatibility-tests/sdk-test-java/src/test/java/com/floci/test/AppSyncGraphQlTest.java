package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.appsync.AppSyncClient;
import software.amazon.awssdk.services.appsync.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GraphQL data plane: provisions an API, key, schema, DynamoDB table and VTL resolvers with the
 * AWS SDK, then executes queries against the advertised GRAPHQL URI with plain HTTP + x-api-key.
 */
@DisplayName("AppSync GraphQL execution")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppSyncGraphQlTest {

    private static final String TABLE = "sdk-gql-todos";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient http = HttpClient.newHttpClient();

    private static AppSyncClient appSync;
    private static DynamoDbClient dynamoDb;
    private static String apiId;
    private static String graphqlUri;
    private static String apiKey;

    @BeforeAll
    static void setup() {
        appSync = TestFixtures.appSyncClient();
        dynamoDb = TestFixtures.dynamoDbClient();
    }

    @AfterAll
    static void cleanup() {
        if (apiId != null) {
            try {
                appSync.deleteGraphqlApi(r -> r.apiId(apiId));
            } catch (Exception e) {
                System.err.println("cleanup: failed to delete GraphQL API " + apiId + ": " + e.getMessage());
            }
        }
        try {
            dynamoDb.deleteTable(r -> r.tableName(TABLE));
        } catch (Exception e) {
            System.err.println("cleanup: failed to delete table " + TABLE + ": " + e.getMessage());
        }
        if (appSync != null) {
            appSync.close();
        }
        if (dynamoDb != null) {
            dynamoDb.close();
        }
    }

    private static JsonNode graphql(String key, String body) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(graphqlUri))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            req.header("x-api-key", key);
        }
        HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(resp.body());
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).put("_status", resp.statusCode());
        return json;
    }

    private static String queryBody(String query) throws Exception {
        return mapper.writeValueAsString(Map.of("query", query));
    }

    @Test
    @Order(1)
    void provision() {
        dynamoDb.createTable(r -> r.tableName(TABLE)
                .keySchema(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("id").attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST));

        GraphqlApi api = appSync.createGraphqlApi(r -> r.name("sdk-gql").authenticationType(AuthenticationType.API_KEY)).graphqlApi();
        apiId = api.apiId();
        graphqlUri = api.uris().get("GRAPHQL");
        assertThat(graphqlUri).endsWith("/v1/apis/" + apiId + "/graphql");

        // On AWS the ApiKey id is the da2-... secret; the SDK exposes nothing else, so that is what a client sends.
        apiKey = appSync.createApiKey(r -> r.apiId(apiId)
                .expires(Instant.now().plus(2, ChronoUnit.DAYS).getEpochSecond())).apiKey().id();

        appSync.startSchemaCreation(r -> r.apiId(apiId).definition(SdkBytes.fromUtf8String("""
                type Todo { id: ID! title: String }
                type Query { getTodo(id: ID!): Todo }
                type Mutation { createTodo(id: ID!, title: String): Todo }
                """)));
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        SchemaStatus status = appSync.getSchemaCreationStatus(r -> r.apiId(apiId)).status();
        while (status == SchemaStatus.PROCESSING && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            status = appSync.getSchemaCreationStatus(r -> r.apiId(apiId)).status();
        }
        assertThat(status).isEqualTo(SchemaStatus.SUCCESS);

        appSync.createDataSource(r -> r.apiId(apiId).name("todos").type(DataSourceType.AMAZON_DYNAMODB)
                .dynamodbConfig(d -> d.tableName(TABLE).awsRegion("us-east-1")));

        String reraise = "#if($ctx.error)$util.error($ctx.error.message, $ctx.error.type)#end$util.toJson($ctx.result)";
        appSync.createResolver(r -> r.apiId(apiId).typeName("Mutation").fieldName("createTodo").dataSourceName("todos")
                .requestMappingTemplate("""
                        {
                          "version": "2018-05-29",
                          "operation": "PutItem",
                          "key": { "id": $util.dynamodb.toDynamoDBJson($ctx.args.id) },
                          "attributeValues": { "title": $util.dynamodb.toDynamoDBJson($ctx.args.title) },
                          "condition": { "expression": "attribute_not_exists(id)" }
                        }
                        """)
                .responseMappingTemplate(reraise));
        appSync.createResolver(r -> r.apiId(apiId).typeName("Query").fieldName("getTodo").dataSourceName("todos")
                .requestMappingTemplate("""
                        { "version": "2018-05-29", "operation": "GetItem",
                          "key": { "id": $util.dynamodb.toDynamoDBJson($ctx.args.id) } }
                        """)
                .responseMappingTemplate(reraise));
    }

    @Test
    @Order(2)
    void unauthorizedWithoutKey() throws Exception {
        JsonNode resp = graphql(null, queryBody("{ getTodo(id: \"1\") { id } }"));
        assertThat(resp.get("_status").asInt()).isEqualTo(401);
        assertThat(resp.get("errors").get(0).get("errorType").asText()).isEqualTo("UnauthorizedException");
        assertThat(resp.get("errors").get(0).get("message").asText()).isEqualTo("Missing authorization header");
    }

    @Test
    @Order(3)
    void mutationThenQueryRoundTrip() throws Exception {
        JsonNode created = graphql(apiKey, queryBody("mutation { createTodo(id: \"1\", title: \"milk\") { id title } }"));
        assertThat(created.get("_status").asInt()).isEqualTo(200);
        assertThat(created.has("errors")).isFalse();
        assertThat(created.get("data").get("createTodo").get("title").asText()).isEqualTo("milk");

        Map<String, AttributeValue> item = dynamoDb.getItem(r -> r.tableName(TABLE)
                .key(Map.of("id", AttributeValue.fromS("1")))).item();
        assertThat(item.get("title").s()).isEqualTo("milk");

        JsonNode fetched = graphql(apiKey, mapper.writeValueAsString(Map.of(
                "query", "query Get($id: ID!) { getTodo(id: $id) { id title } }",
                "variables", Map.of("id", "1"))));
        assertThat(fetched.get("data").get("getTodo").get("title").asText()).isEqualTo("milk");
    }

    @Test
    @Order(4)
    void conditionalCheckFailureSurfacesAsGraphQlError() throws Exception {
        JsonNode resp = graphql(apiKey, queryBody("mutation { createTodo(id: \"1\", title: \"dup\") { id } }"));
        assertThat(resp.get("_status").asInt()).isEqualTo(200);
        assertThat(resp.get("data").get("createTodo").isNull()).isTrue();
        assertThat(resp.get("errors").get(0).get("errorType").asText()).isEqualTo("DynamoDB:ConditionalCheckFailedException");
        assertThat(resp.get("errors").get(0).get("path").get(0).asText()).isEqualTo("createTodo");
    }
}
