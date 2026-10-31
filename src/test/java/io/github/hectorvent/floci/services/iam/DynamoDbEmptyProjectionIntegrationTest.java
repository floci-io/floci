package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class DynamoDbEmptyProjectionIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());
    private static final String POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
             "Action":["dynamodb:Query","dynamodb:Scan"],"Resource":"*",
             "Condition":{"StringEquals":{"dynamodb:Select":"SPECIFIC_ATTRIBUTES"}}}]}
            """;

    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @CsvSource({
            "Query,json,expression", "Query,json,blank", "Query,json,attributes",
            "Scan,json,expression", "Scan,json,blank", "Scan,json,attributes",
            "Query,cbor,expression", "Query,cbor,attributes", "Scan,cbor,expression", "Scan,cbor,attributes",
            "Query,legacy,attributes"
    })
    void emptyProjectionCannotSatisfySpecificAttributesPolicy(String action, String protocol, String member) throws Exception {
        String table = "empty-projection-" + UUID.randomUUID();
        AwsRequestSigner root = AwsRequestSigner.signedAs("test", "test", "dynamodb");
        createTable(table, root);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "empty-projection-", "select", POLICY)) {
            ObjectNode request = read(table, action, protocol);
            if ("attributes".equals(member)) {
                request.putArray("AttributesToGet");
            } else {
                request.put("ProjectionExpression", "blank".equals(member) ? "   " : "");
            }
            JsonNode full = response(send(root, action, protocol, request), protocol,
                    "attributes".equals(member) ? 400 : 200);
            if ("attributes".equals(member)) {
                assertTrue(full.path("__type").asText().contains("ValidationException"));
            } else {
                assertEquals(1, full.path("Count").asInt());
                assertEquals("protected", full.path("Items").get(0).path("payload").path("S").asText());
            }
            JsonNode denied = response(send(user.signer("dynamodb"), action, protocol, request), protocol, "cbor".equals(protocol) ? 403 : 400);
            assertTrue(denied.path("__type").asText().contains("AccessDeniedException"));
            if (!"attributes".equals(member)) {
                request.put("Select", "SPECIFIC_ATTRIBUTES");
                JsonNode invalid = response(send(user.signer("dynamodb"), action, protocol, request), protocol, 400);
                assertTrue(invalid.path("__type").asText().contains("ValidationException"));
                request.remove("Select");
            }
            if ("attributes".equals(member)) {
                request.putArray("AttributesToGet").add("pk");
            } else {
                request.put("ProjectionExpression", "pk");
            }
            JsonNode projected = response(send(user.signer("dynamodb"), action, protocol, request), protocol, 200);
            assertEquals("one", projected.path("Items").get(0).path("pk").path("S").asText());
            assertFalse(projected.path("Items").get(0).has("payload"));
        } finally {
            response(send(root, "DeleteTable", "json", JSON.createObjectNode().put("TableName", table)), "json", 200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Query", "Scan"})
    void signedSdkReadsRequireAnEffectiveProjection(String action) throws Exception {
        String table = "sdk-projection-" + UUID.randomUUID();
        AwsRequestSigner root = AwsRequestSigner.signedAs("test", "test", "dynamodb");
        createTable(table, root);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "sdk-projection-", "select", POLICY);
             DynamoDbClient client = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(
                             AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))).build()) {
            Map<String, AttributeValue> values = Map.of(":pk", AttributeValue.fromS("one"));
            DynamoDbException denial = assertThrows(DynamoDbException.class, () -> {
                if ("Query".equals(action)) {
                    client.query(b -> b.tableName(table).keyConditionExpression("pk = :pk")
                            .expressionAttributeValues(values));
                } else {
                    client.scan(b -> b.tableName(table));
                }
            });
            assertEquals("AccessDeniedException", denial.awsErrorDetails().errorCode());
            DynamoDbException empty = assertThrows(DynamoDbException.class, () -> {
                if ("Query".equals(action)) {
                    client.query(b -> b.tableName(table).keyConditionExpression("pk = :pk")
                            .expressionAttributeValues(values).projectionExpression(""));
                } else {
                    client.scan(b -> b.tableName(table).projectionExpression(""));
                }
            });
            assertEquals("AccessDeniedException", empty.awsErrorDetails().errorCode());
            Map<String, AttributeValue> item = "Query".equals(action)
                    ? client.query(b -> b.tableName(table).keyConditionExpression("pk = :pk")
                        .expressionAttributeValues(values).projectionExpression("pk")).items().getFirst()
                    : client.scan(b -> b.tableName(table).projectionExpression("pk")).items().getFirst();
            assertEquals(Map.of("pk", AttributeValue.fromS("one")), item);
        } finally {
            response(send(root, "DeleteTable", "json", JSON.createObjectNode().put("TableName", table)), "json", 200);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "Query,json,KEYS_ONLY,GSI",
            "Query,json,KEYS_ONLY,LSI",
            "Query,json,INCLUDE,GSI",
            "Query,json,INCLUDE,LSI",
            "Query,cbor,KEYS_ONLY,GSI",
            "Query,cbor,KEYS_ONLY,LSI",
            "Query,cbor,INCLUDE,GSI",
            "Query,cbor,INCLUDE,LSI",
            "Scan,json,KEYS_ONLY,GSI",
            "Scan,json,KEYS_ONLY,LSI",
            "Scan,json,INCLUDE,GSI",
            "Scan,json,INCLUDE,LSI",
            "Scan,cbor,KEYS_ONLY,GSI",
            "Scan,cbor,KEYS_ONLY,LSI",
            "Scan,cbor,INCLUDE,GSI",
            "Scan,cbor,INCLUDE,LSI"})
    void blankIndexProjectionCannotExposeTableAttributes(String action, String protocol, String projection, String indexKind) throws Exception {
        String table = "index-projection-" + UUID.randomUUID();
        AwsRequestSigner root = AwsRequestSigner.signedAs("test", "test", "dynamodb");
        createTable(table, root, projection, indexKind);
        String policy = POLICY.replace("SPECIFIC_ATTRIBUTES", "ALL_PROJECTED_ATTRIBUTES");
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "index-projection-", "select", policy)) {
            for (String expression : new String[] {null, "", "   "}) {
                ObjectNode request = read(table, action, protocol).put("IndexName", "by-sort");
                if ("Query".equals(action) && "GSI".equals(indexKind)) {
                    request.set("KeyConditions", JSON.readTree("""
                            {"sk":{"ComparisonOperator":"EQ","AttributeValueList":[{"S":"sort"}]}}
                            """));
                }
                if (expression != null) {
                    request.put("ProjectionExpression", expression);
                }
                JsonNode result = response(send(user.signer("dynamodb"), action, protocol, request), protocol, 200);
                assertEquals(1, result.path("Count").asInt());
                JsonNode item = result.path("Items").get(0);
                assertEquals("one", item.path("pk").path("S").asText());
                assertEquals("sort", item.path("sk").path("S").asText());
                assertFalse(item.has("payload"), result.toString());
                assertEquals("INCLUDE".equals(projection), item.has("visible"));
            }
        } finally {
            response(send(root, "DeleteTable", "json", JSON.createObjectNode().put("TableName", table)), "json", 200);
        }
    }

    @ParameterizedTest
    @CsvSource({"GetItem,json", "GetItem,cbor", "BatchGetItem,json", "BatchGetItem,cbor",
            "ExecuteStatement,json", "ExecuteStatement,cbor"})
    void generatedSelectCoverageEnforcesOtherReadActions(String action, String protocol) throws Exception {
        String table = "generated-select-" + UUID.randomUUID();
        AwsRequestSigner root = AwsRequestSigner.signedAs("test", "test", "dynamodb");
        createTable(table, root);
        String policy = POLICY.replace("\"dynamodb:Query\",\"dynamodb:Scan\"",
                "\"dynamodb:GetItem\",\"dynamodb:BatchGetItem\",\"dynamodb:PartiQLSelect\"");
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "generated-select-", "select", policy)) {
            ObjectNode request = JSON.createObjectNode();
            ObjectNode selection = request;
            if ("ExecuteStatement".equals(action)) {
                request.put("Statement", "SELECT * FROM \"" + table + "\"");
            } else {
                JsonNode key = JSON.readTree("""
                        {"pk":{"S":"one"},"sk":{"S":"sort"}}
                        """);
                if ("BatchGetItem".equals(action)) {
                    selection = request.putObject("RequestItems").putObject(table);
                    selection.putArray("Keys").add(key);
                } else {
                    request.put("TableName", table).set("Key", key);
                }
            }
            JsonNode denied = response(send(user.signer("dynamodb"), action, protocol, request), protocol,
                    "cbor".equals(protocol) ? 403 : 400);
            assertTrue(denied.path("__type").asText().contains("AccessDeniedException"));
            if ("ExecuteStatement".equals(action)) {
                request.put("Statement", "SELECT pk FROM \"" + table + "\"");
            } else {
                selection.put("ProjectionExpression", "pk");
            }
            JsonNode result = response(send(user.signer("dynamodb"), action, protocol, request), protocol, 200);
            JsonNode item = "GetItem".equals(action) ? result.path("Item")
                    : "BatchGetItem".equals(action) ? result.path("Responses").path(table).get(0)
                    : result.path("Items").get(0);
            assertEquals("one", item.path("pk").path("S").asText());
            assertFalse(item.has("payload"), result.toString());
        } finally {
            response(send(root, "DeleteTable", "json", JSON.createObjectNode().put("TableName", table)), "json", 200);
        }
    }

    @ParameterizedTest
    @CsvSource({"GetItem,json", "GetItem,cbor", "BatchGetItem,json", "BatchGetItem,cbor"})
    void emptyAttributeListHasValidationErrorWireShape(String action, String protocol) throws Exception {
        String table = "empty-list-" + UUID.randomUUID();
        AwsRequestSigner root = AwsRequestSigner.signedAs("test", "test", "dynamodb");
        createTable(table, root);
        try {
            JsonNode key = JSON.readTree("""
                    {"pk":{"S":"one"},"sk":{"S":"sort"}}
                    """);
            ObjectNode request = JSON.createObjectNode();
            ObjectNode selection = request;
            if ("BatchGetItem".equals(action)) {
                selection = request.putObject("RequestItems").putObject(table);
                selection.putArray("Keys").add(key);
            } else {
                request.put("TableName", table).set("Key", key);
            }
            selection.putArray("AttributesToGet");
            JsonNode error = response(send(root, action, protocol, request), protocol, 400);
            assertTrue(error.path("__type").asText().contains("ValidationException"), error.toString());
            assertTrue(error.path("message").asText().contains("greater than or equal to 1"), error.toString());
            assertTrue(error.path("message").asText().contains("Value '[]' at 'attributesToGet'"), error.toString());
        } finally {
            response(send(root, "DeleteTable", "json", JSON.createObjectNode().put("TableName", table)), "json", 200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "cbor"})
    void partiqlCannotReadOrFilterForbiddenAttributes(String protocol) throws Exception {
        String table = "partiql-attributes-" + UUID.randomUUID();
        AwsRequestSigner root = AwsRequestSigner.signedAs("test", "test", "dynamodb");
        createTable(table, root);
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                 "Action":"dynamodb:PartiQLSelect","Resource":"*","Condition":{
                 "StringEquals":{"dynamodb:Select":"SPECIFIC_ATTRIBUTES"},
                 "ForAllValues:StringEquals":{"dynamodb:Attributes":["pk"]}}}]}
                """;
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "partiql-attributes-", "read", policy);
             DynamoDbClient client = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(
                             AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))).build()) {
            for (String statement : List.of("SELECT payload FROM \"%s\"",
                    "SELECT pk FROM \"%s\" WHERE payload = 'protected'")) {
                String sql = statement.formatted(table);
                JsonNode denied = response(send(user.signer("dynamodb"), "ExecuteStatement", protocol,
                        JSON.createObjectNode().put("Statement", sql)), protocol, "cbor".equals(protocol) ? 403 : 400);
                assertTrue(denied.path("__type").asText().contains("AccessDeniedException"));
                DynamoDbException error = assertThrows(DynamoDbException.class,
                        () -> client.executeStatement(b -> b.statement(sql)));
                assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
            }
            String allowed = "SELECT pk FROM \"" + table + "\" WHERE pk = ?";
            ObjectNode request = JSON.createObjectNode().put("Statement", allowed);
            request.putArray("Parameters").addObject().put("S", "one");
            JsonNode item = response(send(user.signer("dynamodb"), "ExecuteStatement", protocol, request),
                    protocol, 200).path("Items").get(0);
            assertEquals(JSON.readTree("{\"pk\":{\"S\":\"one\"}}"), item);
            assertEquals(Map.of("pk", AttributeValue.fromS("one")), client.executeStatement(b -> b.statement(allowed)
                    .parameters(AttributeValue.fromS("one"))).items().getFirst());
        } finally {
            response(send(root, "DeleteTable", "json", JSON.createObjectNode().put("TableName", table)), "json", 200);
        }
    }

    @ParameterizedTest
    @CsvSource({"json,false", "json,true", "cbor,false", "cbor,true"})
    void batchAliasesCannotHideUnprojectedReads(String protocol, boolean arnFirst) throws Exception {
        String table = "batch-alias-" + UUID.randomUUID();
        AwsRequestSigner root = AwsRequestSigner.signedAs("test", "test", "dynamodb");
        createTable(table, root);
        String arn = response(send(root, "DescribeTable", "json", JSON.createObjectNode().put("TableName", table)),
                "json", 200).path("Table").path("TableArn").asText();
        String policy = POLICY.replace("\"dynamodb:Query\",\"dynamodb:Scan\"", "\"dynamodb:BatchGetItem\"");
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "batch-alias-", "read", policy);
             DynamoDbClient client = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(
                             AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))).build()) {
            ObjectNode request = JSON.createObjectNode();
            ObjectNode entries = request.putObject("RequestItems");
            String first = arnFirst ? arn : table;
            String second = arnFirst ? table : arn;
            JsonNode key = JSON.readTree("{\"pk\":{\"S\":\"one\"},\"sk\":{\"S\":\"sort\"}}");
            entries.putObject(first).put("ProjectionExpression", "pk").putArray("Keys").add(key);
            ObjectNode unprojected = entries.putObject(second);
            unprojected.putArray("Keys").add(key);
            JsonNode rootRead = response(send(root, "BatchGetItem", protocol, request), protocol, 200);
            assertEquals("protected", rootRead.path("Responses").path(second).get(0).path("payload").path("S").asText());
            JsonNode denied = response(send(user.signer("dynamodb"), "BatchGetItem", protocol, request), protocol,
                    "cbor".equals(protocol) ? 403 : 400);
            assertTrue(denied.path("__type").asText().contains("AccessDeniedException"));
            Map<String, AttributeValue> sdkKey = Map.of("pk", AttributeValue.fromS("one"), "sk", AttributeValue.fromS("sort"));
            Map<String, KeysAndAttributes> sdkEntries = new LinkedHashMap<>();
            sdkEntries.put(first, KeysAndAttributes.builder().keys(sdkKey).projectionExpression("pk").build());
            sdkEntries.put(second, KeysAndAttributes.builder().keys(sdkKey).build());
            DynamoDbException error = assertThrows(DynamoDbException.class,
                    () -> client.batchGetItem(b -> b.requestItems(sdkEntries)));
            assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
            unprojected.put("ProjectionExpression", "pk");
            JsonNode allowed = response(send(user.signer("dynamodb"), "BatchGetItem", protocol, request), protocol, 200);
            for (String reference : List.of(first, second)) {
                assertEquals(JSON.readTree("{\"pk\":{\"S\":\"one\"}}"),
                        allowed.path("Responses").path(reference).get(0));
            }
            sdkEntries.put(second, KeysAndAttributes.builder().keys(sdkKey).projectionExpression("pk").build());
            assertEquals(2, client.batchGetItem(b -> b.requestItems(sdkEntries)).responses().size());
        } finally {
            response(send(root, "DeleteTable", "json", JSON.createObjectNode().put("TableName", table)), "json", 200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "cbor"})
    void transactionSelectionIgnoresUnsupportedGetMembers(String protocol) throws Exception {
        String table = "transaction-projection-" + UUID.randomUUID();
        AwsRequestSigner root = AwsRequestSigner.signedAs("test", "test", "dynamodb");
        createTable(table, root);
        String policy = POLICY.replace("\"dynamodb:Query\",\"dynamodb:Scan\"", "\"dynamodb:GetItem\"");
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "transaction-projection-", "read", policy);
             DynamoDbClient client = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(
                             AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))).build()) {
            ObjectNode request = JSON.createObjectNode();
            ObjectNode get = request.putArray("TransactItems").addObject().putObject("Get").put("TableName", table);
            get.set("Key", JSON.readTree("{\"pk\":{\"S\":\"one\"},\"sk\":{\"S\":\"sort\"}}"));
            get.putArray("AttributesToGet").add("pk");
            JsonNode full = response(send(root, "TransactGetItems", protocol, request), protocol, 200)
                    .path("Responses").get(0).path("Item");
            assertEquals("protected", full.path("payload").path("S").asText());
            JsonNode denied = response(send(user.signer("dynamodb"), "TransactGetItems", protocol, request),
                    protocol, "cbor".equals(protocol) ? 403 : 400);
            assertTrue(denied.path("__type").asText().contains("AccessDeniedException"));
            get.put("ProjectionExpression", "#key");
            get.putObject("ExpressionAttributeNames").put("#key", "pk");
            JsonNode projected = response(send(user.signer("dynamodb"), "TransactGetItems", protocol, request),
                    protocol, 200).path("Responses").get(0).path("Item");
            assertEquals(JSON.readTree("{\"pk\":{\"S\":\"one\"}}"), projected);
            Map<String, AttributeValue> key = Map.of("pk", AttributeValue.fromS("one"),
                    "sk", AttributeValue.fromS("sort"));
            DynamoDbException error = assertThrows(DynamoDbException.class, () -> client.transactGetItems(b ->
                    b.transactItems(t -> t.get(g -> g.tableName(table).key(key)))));
            assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
            assertEquals(Map.of("pk", AttributeValue.fromS("one")), client.transactGetItems(b ->
                    b.transactItems(t -> t.get(g -> g.tableName(table).key(key).projectionExpression("pk"))))
                    .responses().getFirst().item());
        } finally {
            response(send(root, "DeleteTable", "json", JSON.createObjectNode().put("TableName", table)), "json", 200);
        }
    }

    private void createTable(String table, AwsRequestSigner root) throws Exception {
        createTable(table, root, null, null);
    }

    private void createTable(String table, AwsRequestSigner root, String projection, String indexKind) throws Exception {
        ObjectNode request = (ObjectNode) JSON.readTree("""
                {"KeySchema":[{"AttributeName":"pk","KeyType":"HASH"},{"AttributeName":"sk","KeyType":"RANGE"}],
                 "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"},{"AttributeName":"sk","AttributeType":"S"}],
                 "BillingMode":"PAY_PER_REQUEST"}
                """);
        request.put("TableName", table);
        if (projection != null) {
            boolean local = "LSI".equals(indexKind);
            ObjectNode index = request.putArray(local ? "LocalSecondaryIndexes" : "GlobalSecondaryIndexes")
                    .addObject().put("IndexName", "by-sort");
            if (local) {
                request.withArray("AttributeDefinitions").addObject().put("AttributeName", "idx").put("AttributeType", "S");
                index.putArray("KeySchema").addObject().put("AttributeName", "pk").put("KeyType", "HASH");
                index.withArray("KeySchema").addObject().put("AttributeName", "idx").put("KeyType", "RANGE");
            } else {
                index.putArray("KeySchema").addObject().put("AttributeName", "sk").put("KeyType", "HASH");
            }
            ObjectNode definition = index.putObject("Projection").put("ProjectionType", projection);
            if ("INCLUDE".equals(projection)) {
                definition.putArray("NonKeyAttributes").add("visible");
            }
        }
        response(send(root, "CreateTable", "json", request), "json", 200);
        try {
            ObjectNode put = (ObjectNode) JSON.readTree("""
                    {"Item":{"pk":{"S":"one"},"sk":{"S":"sort"},"payload":{"S":"protected"},"visible":{"S":"public"},"idx":{"S":"local"}}}
                    """);
            put.put("TableName", table);
            response(send(root, "PutItem", "json", put), "json", 200);
        } catch (Exception | AssertionError failure) {
            try {
                response(send(root, "DeleteTable", "json", JSON.createObjectNode().put("TableName", table)), "json", 200);
            } catch (Exception | AssertionError cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private ObjectNode read(String table, String action, String protocol) throws Exception {
        ObjectNode request = JSON.createObjectNode().put("TableName", table);
        if ("Query".equals(action)) {
            if ("legacy".equals(protocol)) {
                request.putObject("HashKeyValue").put("S", "one");
            } else {
                request.set("KeyConditions", JSON.readTree("""
                        {"pk":{"ComparisonOperator":"EQ","AttributeValueList":[{"S":"one"}]}}
                        """));
            }
        }
        return request;
    }

    private Response send(AwsRequestSigner signer, String action, String protocol, JsonNode request) throws Exception {
        boolean cbor = "cbor".equals(protocol);
        URI uri = cbor ? endpoint.resolve("/service/DynamoDB_20120810/operation/" + action) : endpoint;
        String contentType = cbor ? "application/cbor" : "application/x-amz-json-1.0";
        Map<String, String> headers = cbor ? Map.of("content-type", contentType, "accept", contentType)
                : Map.of("content-type", contentType, "x-amz-target",
                        ("legacy".equals(protocol) ? "DynamoDB_20111205." : "DynamoDB_20120810.") + action);
        byte[] body = (cbor ? CBOR : JSON).writeValueAsBytes(request);
        return given().filter(signer).headers(headers).body(body).post(uri);
    }

    private JsonNode response(Response response, String protocol, int status) throws Exception {
        assertEquals(status, response.statusCode(), response.asString());
        return ("cbor".equals(protocol) ? CBOR : JSON).readTree(response.asByteArray());
    }
}
