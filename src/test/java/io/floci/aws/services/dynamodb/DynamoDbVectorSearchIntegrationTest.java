package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vector index surface over the wire: DescribeTable round-tripping, the three distance
 * functions, projection, and the exact wording of every rejected request.
 *
 * <p>Both phase durations are 0 in the test configuration, so an index added by UpdateTable is
 * ACTIVE at once here. {@link DynamoDbVectorIndexLifecycleTest} covers the two phases.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbVectorSearchIntegrationTest {

    private static final String CT = "application/x-amz-json-1.0";
    private static final String TARGET = "DynamoDB_20120810.";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DOCS = "VectorSearchDocs";
    private static final String PLAIN = "VectorPlainIndex";
    private static final String SCHEMA = "VectorSchemaIndex";
    private static final String REJECTED = "VectorRejectedTable";
    private static final String FILTERS = "VectorFilterDocs";

    private static final String QUERY_VECTOR = """
            [{"N": "1"}, {"N": "0"}, {"N": "0"}]""";

    private static int testPort;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // @AfterAll runs after Quarkus has reset the shared port, so it needs its own copy. Taken
    // per test rather than in createTables, which a filtered single-method run never reaches.
    @BeforeEach
    void capturePort() {
        testPort = RestAssured.port;
    }

    @AfterAll
    static void cleanup() {
        for (String table : List.of(DOCS, PLAIN, SCHEMA, FILTERS)) {
            given()
                    .port(testPort)
                    .header("X-Amz-Target", TARGET + "DeleteTable")
                    .contentType(CT)
                    .body("""
                        {"TableName": "%s"}
                        """.formatted(table))
                .when()
                    .post("/")
                .then()
                    .statusCode(200);
        }
    }

    private static ValidatableResponse call(String action, String body) {
        return given()
                .header("X-Amz-Target", TARGET + action)
                .contentType(CT)
                .body(body)
            .when()
                .post("/")
            .then();
    }

    private static JsonNode json(String action, String body) throws Exception {
        return MAPPER.readTree(call(action, body).statusCode(200).extract().asString());
    }

    private static void expectValidation(String action, String body, String message) {
        call(action, body)
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(message));
    }

    /** A SearchVectors body for the shared query vector, plus any further members verbatim. */
    private static String search(String table, String index, int topK, String extraMembers) {
        return """
            {
                "TableName": "%s",
                "IndexName": "%s",
                "SearchVector": %s,
                "TopK": %d%s
            }
            """.formatted(table, index, QUERY_VECTOR, topK, extraMembers);
    }

    private static List<String> keysOf(JsonNode response) {
        List<String> keys = new ArrayList<>();
        for (JsonNode result : response.path("SearchResults")) {
            keys.add(result.path("Item").path("pk").path("S").asText());
        }
        return keys;
    }

    private static List<Double> scoresOf(JsonNode response) {
        List<Double> scores = new ArrayList<>();
        for (JsonNode result : response.path("SearchResults")) {
            scores.add(result.path("Score").asDouble());
        }
        return scores;
    }

    private static List<String> vectorOf(JsonNode searchResult) {
        List<String> components = new ArrayList<>();
        for (JsonNode component : searchResult.path("Item").path("embedding").path("L")) {
            components.add(component.path("N").asText());
        }
        return components;
    }

    @Test
    @Order(1)
    void createTables() {
        call("CreateTable", """
            {
                "TableName": "%s",
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                "BillingMode": "PAY_PER_REQUEST",
                "VectorIndexes": [
                    {"IndexName": "cosine",
                     "VectorAttribute": {"AttributeName": "embedding"},
                     "Projection": {"ProjectionType": "ALL"},
                     "Dimensions": 3,
                     "DistanceFunction": "COSINE"},
                    {"IndexName": "euclid",
                     "VectorAttribute": {"AttributeName": "embedding"},
                     "Projection": {"ProjectionType": "ALL"},
                     "Dimensions": 3,
                     "DistanceFunction": "EUCLIDEAN"},
                    {"IndexName": "dotp",
                     "VectorAttribute": {"AttributeName": "embedding"},
                     "Projection": {"ProjectionType": "ALL"},
                     "Dimensions": 3,
                     "DistanceFunction": "DOT_PRODUCT"}
                ]
            }
            """.formatted(DOCS))
                .statusCode(200)
                .body("TableDescription.VectorIndexes.size()", equalTo(3))
                .body("TableDescription.VectorIndexes.IndexName",
                        contains("cosine", "euclid", "dotp"))
                .body("TableDescription.VectorIndexes.IndexStatus",
                        contains("ACTIVE", "ACTIVE", "ACTIVE"));

        call("CreateTable", """
            {
                "TableName": "%s",
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                "BillingMode": "PAY_PER_REQUEST",
                "VectorIndexes": [
                    {"IndexName": "plain",
                     "VectorAttribute": {"AttributeName": "embedding"},
                     "Projection": {"ProjectionType": "ALL"},
                     "Dimensions": 3,
                     "DistanceFunction": "COSINE"}
                ]
            }
            """.formatted(PLAIN))
                .statusCode(200);

        call("CreateTable", """
            {
                "TableName": "%s",
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "AttributeDefinitions": [
                    {"AttributeName": "pk", "AttributeType": "S"},
                    {"AttributeName": "tenant", "AttributeType": "S"},
                    {"AttributeName": "category", "AttributeType": "S"}
                ],
                "BillingMode": "PAY_PER_REQUEST",
                "VectorIndexes": [
                    {"IndexName": "schema",
                     "VectorAttribute": {"AttributeName": "embedding"},
                     "SearchSchema": [
                         {"AttributeName": "tenant", "SearchSchemaElementType": "HASH"},
                         {"AttributeName": "category", "SearchSchemaElementType": "INLINE_FILTER"}
                     ],
                     "Projection": {"ProjectionType": "ALL"},
                     "Dimensions": 3,
                     "DistanceFunction": "COSINE"}
                ]
            }
            """.formatted(SCHEMA))
                .statusCode(200);

        call("CreateTable", """
            {
                "TableName": "%s",
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "AttributeDefinitions": [
                    {"AttributeName": "pk", "AttributeType": "S"},
                    {"AttributeName": "tenant", "AttributeType": "S"},
                    {"AttributeName": "category", "AttributeType": "S"},
                    {"AttributeName": "price", "AttributeType": "N"},
                    {"AttributeName": "bin", "AttributeType": "B"}
                ],
                "BillingMode": "PAY_PER_REQUEST",
                "VectorIndexes": [
                    {"IndexName": "full",
                     "VectorAttribute": {"AttributeName": "embedding"},
                     "SearchSchema": [
                         {"AttributeName": "tenant", "SearchSchemaElementType": "HASH"},
                         {"AttributeName": "category", "SearchSchemaElementType": "INLINE_FILTER"},
                         {"AttributeName": "price", "SearchSchemaElementType": "INLINE_FILTER"},
                         {"AttributeName": "bin", "SearchSchemaElementType": "INLINE_FILTER"}
                     ],
                     "Projection": {"ProjectionType": "ALL"},
                     "Dimensions": 3,
                     "DistanceFunction": "COSINE"},
                    {"IndexName": "filters",
                     "VectorAttribute": {"AttributeName": "embedding"},
                     "SearchSchema": [
                         {"AttributeName": "category", "SearchSchemaElementType": "INLINE_FILTER"},
                         {"AttributeName": "price", "SearchSchemaElementType": "INLINE_FILTER"}
                     ],
                     "Projection": {"ProjectionType": "ALL"},
                     "Dimensions": 3,
                     "DistanceFunction": "COSINE"},
                    {"IndexName": "bare",
                     "VectorAttribute": {"AttributeName": "embedding"},
                     "Projection": {"ProjectionType": "ALL"},
                     "Dimensions": 3,
                     "DistanceFunction": "COSINE"}
                ]
            }
            """.formatted(FILTERS))
                .statusCode(200);
    }

    @Test
    @Order(2)
    void seedItems() {
        putVector("a", "1", "0", "0");
        putVector("b", "0", "1", "0");
        putVector("c", "-1", "0", "0");
        putVector("d", "0.6", "0.8", "0");

        call("PutItem", """
            {
                "TableName": "%s",
                "Item": {"pk": {"S": "novec"}, "label": {"S": "no vector"}}
            }
            """.formatted(DOCS))
                .statusCode(200);
    }

    private static void putVector(String key, String x, String y, String z) {
        call("PutItem", """
            {
                "TableName": "%s",
                "Item": {
                    "pk": {"S": "%s"},
                    "label": {"S": "item %s"},
                    "embedding": {"L": [{"N": "%s"}, {"N": "%s"}, {"N": "%s"}]}
                }
            }
            """.formatted(DOCS, key, key, x, y, z))
                .statusCode(200);
    }

    @Test
    @Order(3)
    void describeTableRoundTripsTheVectorIndex() {
        call("DescribeTable", """
            {"TableName": "%s"}
            """.formatted(SCHEMA))
                .statusCode(200)
                .body("Table.VectorIndexes.size()", equalTo(1))
                .body("Table.VectorIndexes[0].IndexName", equalTo("schema"))
                .body("Table.VectorIndexes[0].Dimensions", equalTo(3))
                .body("Table.VectorIndexes[0].DistanceFunction", equalTo("COSINE"))
                .body("Table.VectorIndexes[0].VectorAttribute.AttributeName", equalTo("embedding"))
                .body("Table.VectorIndexes[0].Projection.ProjectionType", equalTo("ALL"))
                .body("Table.VectorIndexes[0].Projection.NonKeyAttributes", nullValue())
                .body("Table.VectorIndexes[0].SearchSchema.AttributeName",
                        contains("tenant", "category"))
                .body("Table.VectorIndexes[0].SearchSchema.SearchSchemaElementType",
                        contains("HASH", "INLINE_FILTER"))
                .body("Table.VectorIndexes[0].IndexStatus", equalTo("ACTIVE"))
                .body("Table.VectorIndexes[0].IndexArn",
                        equalTo("arn:aws:dynamodb:us-east-1:000000000000:table/"
                                + SCHEMA + "/index/schema"))
                .body("Table.VectorIndexes[0].ItemCount", equalTo(0))
                .body("Table.VectorIndexes[0].IndexSizeBytes", equalTo(0))
                .body("Table.VectorIndexes[0].Backfilling", nullValue());
    }

    @Test
    @Order(4)
    void searchRanksByCosineDistance() throws Exception {
        JsonNode response = json("SearchVectors", search(DOCS, "cosine", 4, ""));

        assertEquals(List.of("a", "d", "b", "c"), keysOf(response));
        assertEquals(List.of(0.0, 0.3999999761581421, 1.0, 2.0), scoresOf(response));
    }

    @Test
    @Order(5)
    void searchRanksByEuclideanDistance() throws Exception {
        JsonNode response = json("SearchVectors", search(DOCS, "euclid", 4, ""));

        assertEquals(List.of("a", "d", "b", "c"), keysOf(response));
        assertEquals(List.of(0.0, 0.8944271802902222, 1.4142135381698608, 2.0),
                scoresOf(response));
    }

    @Test
    @Order(6)
    void searchRanksByDotProductDescending() throws Exception {
        JsonNode response = json("SearchVectors", search(DOCS, "dotp", 4, ""));

        assertEquals(List.of("a", "d", "b", "c"), keysOf(response));
        assertEquals(List.of(1.0, 0.6000000238418579, 0.0, -1.0), scoresOf(response));
    }

    @Test
    @Order(7)
    void topKCutsTheResultSet() throws Exception {
        JsonNode response = json("SearchVectors", search(DOCS, "cosine", 2, ""));

        assertEquals(List.of("a", "d"), keysOf(response));
    }

    @Test
    @Order(8)
    void itemWithoutTheVectorAttributeStaysOutOfTheIndex() throws Exception {
        JsonNode response = json("SearchVectors", search(DOCS, "cosine", 100, ""));

        assertEquals(4, response.path("SearchResults").size());
        assertFalse(keysOf(response).contains("novec"));
    }

    @Test
    @Order(9)
    void vectorAttributeIsAbsentUnlessProjected() throws Exception {
        JsonNode response = json("SearchVectors", search(DOCS, "cosine", 1, ""));
        JsonNode item = response.path("SearchResults").path(0).path("Item");

        assertEquals("a", item.path("pk").path("S").asText());
        assertEquals("item a", item.path("label").path("S").asText());
        assertTrue(item.path("embedding").isMissingNode(), "the vector is not projected by default");
    }

    @Test
    @Order(10)
    void projectionExpressionReturnsTheIndexF32Copy() throws Exception {
        JsonNode response = json("SearchVectors", search(DOCS, "cosine", 2, """
            , "ProjectionExpression": "pk, embedding"
            """));
        JsonNode results = response.path("SearchResults");

        assertTrue(results.path(0).path("Item").path("label").isMissingNode());
        assertEquals(List.of("1.0", "0.0", "0.0"), vectorOf(results.path(0)));
        // The third component was written as "0" and comes back as "0.0": a hit carries the
        // index's own f32 copy rather than the text the item stores.
        assertEquals(List.of("0.6", "0.8", "0.0"), vectorOf(results.path(1)));
    }

    @Test
    @Order(11)
    void searchReportsRequestBytesUnderTotalAndIndexesOnly() throws Exception {
        JsonNode total = json("SearchVectors", search(DOCS, "cosine", 1, """
            , "ReturnConsumedCapacity": "TOTAL"
            """));
        assertEquals(1024.0,
                total.path("ConsumedCapacity").path("VectorSearchRequestBytes").asDouble());
        assertTrue(total.path("ConsumedCapacity").path("CapacityUnits").isMissingNode());
        assertTrue(total.path("ConsumedCapacity").path("TableName").isMissingNode());

        JsonNode indexes = json("SearchVectors", search(DOCS, "cosine", 1, """
            , "ReturnConsumedCapacity": "INDEXES"
            """));
        assertEquals(1024.0,
                indexes.path("ConsumedCapacity").path("VectorSearchRequestBytes").asDouble());

        JsonNode none = json("SearchVectors", search(DOCS, "cosine", 1, """
            , "ReturnConsumedCapacity": "NONE"
            """));
        assertTrue(none.path("ConsumedCapacity").isMissingNode());
    }

    @Test
    @Order(12)
    void createTableRejectsAVectorIndexOnAProvisionedTable() {
        expectValidation("CreateTable", """
            {
                "TableName": "%s",
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                "VectorIndexes": [
                    {"IndexName": "vix",
                     "VectorAttribute": {"AttributeName": "embedding"},
                     "Projection": {"ProjectionType": "ALL"},
                     "Dimensions": 3,
                     "DistanceFunction": "COSINE"}
                ]
            }
            """.formatted(REJECTED),
                "One or more parameter values were invalid: Vector indexes are only supported "
                + "for PAY_PER_REQUEST tables");
    }

    @Test
    @Order(13)
    void createTableRejectsASearchSchemaAttributeWithNoDefinition() {
        expectValidation("CreateTable", """
            {
                "TableName": "%s",
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                "BillingMode": "PAY_PER_REQUEST",
                "VectorIndexes": [
                    {"IndexName": "vix",
                     "VectorAttribute": {"AttributeName": "embedding"},
                     "SearchSchema": [{"AttributeName": "tenant", "SearchSchemaElementType": "HASH"}],
                     "Projection": {"ProjectionType": "ALL"},
                     "Dimensions": 3,
                     "DistanceFunction": "COSINE"}
                ]
            }
            """.formatted(REJECTED),
                "One or more parameter values were invalid: One element in SearchSchema is not "
                + "defined in attribute definitions");
    }

    @Test
    @Order(14)
    void createTableRejectsDimensionsAboveTheLimit() {
        expectValidation("CreateTable", """
            {
                "TableName": "%s",
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                "BillingMode": "PAY_PER_REQUEST",
                "VectorIndexes": [
                    {"IndexName": "vix",
                     "VectorAttribute": {"AttributeName": "embedding"},
                     "Projection": {"ProjectionType": "ALL"},
                     "Dimensions": 5000,
                     "DistanceFunction": "COSINE"}
                ]
            }
            """.formatted(REJECTED),
                "One or more parameter values were invalid: Number of dimensions must be between "
                + "1 and 4096 inclusive.");
    }

    @Test
    @Order(15)
    void createTableRejectsMoreThanFiveVectorIndexes() {
        List<String> indexes = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            indexes.add("""
                {"IndexName": "vix%d",
                 "VectorAttribute": {"AttributeName": "embedding"},
                 "Projection": {"ProjectionType": "ALL"},
                 "Dimensions": 3,
                 "DistanceFunction": "COSINE"}""".formatted(i));
        }
        expectValidation("CreateTable", """
            {
                "TableName": "%s",
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                "BillingMode": "PAY_PER_REQUEST",
                "VectorIndexes": [%s]
            }
            """.formatted(REJECTED, String.join(",\n", indexes)),
                "One or more parameter values were invalid: VectorIndex count exceeds the "
                + "per-table limit of 5");
    }

    @Test
    @Order(16)
    void createTableRejectsTwoDimensionsForOneVectorAttribute() {
        expectValidation("CreateTable", """
            {
                "TableName": "%s",
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                "BillingMode": "PAY_PER_REQUEST",
                "VectorIndexes": [
                    {"IndexName": "three",
                     "VectorAttribute": {"AttributeName": "embedding"},
                     "Projection": {"ProjectionType": "ALL"},
                     "Dimensions": 3,
                     "DistanceFunction": "COSINE"},
                    {"IndexName": "four",
                     "VectorAttribute": {"AttributeName": "embedding"},
                     "Projection": {"ProjectionType": "ALL"},
                     "Dimensions": 4,
                     "DistanceFunction": "COSINE"}
                ]
            }
            """.formatted(REJECTED),
                "One or more parameter values were invalid: Conflicting attribute definition for "
                + "'embedding'. All VectorIndexes on the same vector attribute must use the same "
                + "dimensions.");
    }

    @Test
    @Order(17)
    void searchVectorsRejectsATopKAboveTheLimit() {
        expectValidation("SearchVectors", search(DOCS, "cosine", 101, ""),
                "Provided TopK value '101' is out of valid range. The value must be between 1 "
                + "and 100 inclusive");
    }

    @Test
    @Order(18)
    void searchVectorsRequiresAConditionOnTheSearchSchemaHash() {
        expectValidation("SearchVectors", search(SCHEMA, "schema", 4, ""),
                "SearchConditionExpression must be provided when SearchSchema has a HASH key");
    }

    @Test
    @Order(19)
    void searchVectorsRejectsALessThanOnTheHashAttribute() {
        expectValidation("SearchVectors", search(SCHEMA, "schema", 4, """
            ,
            "SearchConditionExpression": "tenant < :t",
            "ExpressionAttributeValues": {":t": {"S": "acme"}}
            """),
                "SearchConditionExpression must use = on the HASH attribute, not inside OR or NOT; "
                + "HASH attribute: tenant");
    }

    @Test
    @Order(19)
    void searchVectorsChecksAnOrderingOperandTypeBeforeTheComparator() {
        expectValidation("SearchVectors", search(SCHEMA, "schema", 4, """
            ,
            "SearchConditionExpression": "tenant < :t",
            "ExpressionAttributeValues": {":t": {"BOOL": true}}
            """),
                "Invalid SearchConditionExpression: Incorrect operand type for operator or function; "
                + "operator or function: <, operand type: BOOL");
    }

    @Test
    @Order(20)
    void searchVectorsRejectsADimensionMismatch() {
        expectValidation("SearchVectors", """
            {
                "TableName": "%s",
                "IndexName": "plain",
                "SearchVector": [{"N": "1"}, {"N": "0"}],
                "TopK": 4
            }
            """.formatted(PLAIN),
                "Input search vector dimension 2 does not match vector index dimension 3");
    }

    @Test
    @Order(21)
    void searchVectorsRejectsAConditionOutsideTheSearchSchema() {
        expectValidation("SearchVectors", search(PLAIN, "plain", 4, """
            ,
            "SearchConditionExpression": "tenant = :t",
            "ExpressionAttributeValues": {":t": {"S": "acme"}}
            """),
                "SearchConditionExpression must not contain any attributes that is not in "
                + "SearchSchema. Invalid attribute: tenant");
    }

    @Test
    @Order(22)
    void searchVectorsRejectsANonNumericSearchVector() {
        expectValidation("SearchVectors", """
            {
                "TableName": "%s",
                "IndexName": "plain",
                "SearchVector": [{"N": "1"}, {"S": "x"}, {"N": "0"}],
                "TopK": 4
            }
            """.formatted(PLAIN),
                "Search vector contains invalid values. All values in the search vector must be "
                + "a 32-bit floating-point number attribute");
    }

    @Test
    @Order(23)
    void putItemRejectsAVectorOfTheWrongLength() {
        expectValidation("PutItem", """
            {
                "TableName": "%s",
                "Item": {
                    "pk": {"S": "short"},
                    "embedding": {"L": [{"N": "1"}, {"N": "0"}]}
                }
            }
            """.formatted(PLAIN),
                "One or more parameter values were invalid. Invalid size for parameter embedding, "
                + "Expected: 3, Actual: 2 IndexName: plain");
    }

    @Test
    @Order(24)
    void putItemRejectsANonNumericVectorComponent() {
        expectValidation("PutItem", """
            {
                "TableName": "%s",
                "Item": {
                    "pk": {"S": "typed"},
                    "embedding": {"L": [{"N": "1"}, {"S": "x"}, {"N": "0"}]}
                }
            }
            """.formatted(PLAIN),
                "One or more parameter values were invalid. Invalid type for parameter "
                + "embedding[1], Expected: 32-bit floating point number, Actual: S. "
                + "IndexName: plain");
    }

    @Test
    @Order(25)
    void putItemRejectsAVectorAttributeThatIsNotAList() {
        expectValidation("PutItem", """
            {
                "TableName": "%s",
                "Item": {
                    "pk": {"S": "scalar"},
                    "embedding": {"S": "not a vector"}
                }
            }
            """.formatted(PLAIN),
                "One or more parameter values were invalid. Invalid type for parameter embedding, "
                + "Expected: 32-bit floating point number list IndexName: plain");
    }

    @Test
    @Order(26)
    void putItemRejectsAnEmptySearchSchemaHashValue() {
        expectValidation("PutItem", """
            {
                "TableName": "%s",
                "Item": {
                    "pk": {"S": "blank-tenant"},
                    "tenant": {"S": ""},
                    "embedding": {"L": [{"N": "1"}, {"N": "0"}, {"N": "0"}]}
                }
            }
            """.formatted(SCHEMA),
                "One or more parameter values are not valid. A value specified for a secondary "
                + "index key is not supported. The AttributeValue for a key attribute cannot "
                + "contain an empty string value. IndexName: schema, IndexKey: tenant");
    }

    /**
     * The conformance suite probes SearchVectors against a table that does not exist to decide
     * whether the operation is served at all. Any other answer turns all 52 vector tests into
     * skips rather than failures.
     */
    @Test
    @Order(27)
    void searchVectorsOnAMissingTableIsResourceNotFound() {
        call("SearchVectors", search("VectorNoSuchTable", "cosine", 1, ""))
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(28)
    void partiQLRejectsAVectorIndex() {
        expectValidation("ExecuteStatement", """
            {"Statement": "SELECT * FROM \\"%s\\".\\"plain\\" WHERE pk = 'a'"}
            """.formatted(PLAIN),
                "Scan operation not supported on this index type");
    }

    @Test
    @Order(29)
    void createTableRejectsADistanceFunctionOutsideTheEnum() {
        for (String distanceFunction : List.of("MANHATTAN", "cosine")) {
            expectValidation("CreateTable", """
                {
                    "TableName": "%s",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST",
                    "VectorIndexes": [
                        {"IndexName": "vix",
                         "VectorAttribute": {"AttributeName": "embedding"},
                         "Projection": {"ProjectionType": "ALL"},
                         "Dimensions": 3,
                         "DistanceFunction": "%s"}
                    ]
                }
                """.formatted(REJECTED, distanceFunction),
                    "1 validation error detected: Value '" + distanceFunction + "' at "
                    + "'vectorIndexes.1.member.distanceFunction' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [DOT_PRODUCT, COSINE, EUCLIDEAN]");
        }
    }

    @Test
    @Order(30)
    void updateTableRejectsADistanceFunctionOutsideTheEnum() {
        expectValidation("UpdateTable", """
            {
                "TableName": "%s",
                "VectorIndexUpdates": [
                    {"Create": {"IndexName": "vix",
                                "VectorAttribute": {"AttributeName": "embedding"},
                                "Projection": {"ProjectionType": "ALL"},
                                "Dimensions": 3,
                                "DistanceFunction": "MANHATTAN"}}
                ]
            }
            """.formatted(PLAIN),
                "1 validation error detected: Value 'MANHATTAN' at "
                + "'vectorIndexUpdates.1.member.create.distanceFunction' failed to satisfy "
                + "constraint: Member must satisfy enum value set: [DOT_PRODUCT, COSINE, EUCLIDEAN]");
    }

    @Test
    @Order(31)
    void createTableRequiresDimensionsVectorAttributeAndProjection() {
        expectValidation("CreateTable", vectorIndexTable("""
            {"IndexName": "vix",
             "VectorAttribute": {"AttributeName": "embedding"},
             "Projection": {"ProjectionType": "ALL"},
             "DistanceFunction": "COSINE"}"""),
                requiredMember("dimensions"));

        expectValidation("CreateTable", vectorIndexTable("""
            {"IndexName": "vix",
             "Projection": {"ProjectionType": "ALL"},
             "Dimensions": 3,
             "DistanceFunction": "COSINE"}"""),
                requiredMember("vectorAttribute"));

        expectValidation("CreateTable", vectorIndexTable("""
            {"IndexName": "vix",
             "VectorAttribute": {"AttributeName": "embedding"},
             "Dimensions": 3,
             "DistanceFunction": "COSINE"}"""),
                requiredMember("projection"));
    }

    /** A CreateTable body carrying the one vector index given. */
    private static String vectorIndexTable(String vectorIndex) {
        return """
            {
                "TableName": "%s",
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                "BillingMode": "PAY_PER_REQUEST",
                "VectorIndexes": [%s]
            }
            """.formatted(REJECTED, vectorIndex);
    }

    private static String requiredMember(String member) {
        return "1 validation error detected: Value null at 'vectorIndexes.1.member." + member
                + "' failed to satisfy constraint: Member must not be null";
    }

    @Test
    @Order(32)
    void createTableRejectsAVectorIndexNamedLikeAnotherIndex() {
        expectValidation("CreateTable", """
            {
                "TableName": "%s",
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "AttributeDefinitions": [
                    {"AttributeName": "pk", "AttributeType": "S"},
                    {"AttributeName": "gsk", "AttributeType": "S"}
                ],
                "BillingMode": "PAY_PER_REQUEST",
                "GlobalSecondaryIndexes": [
                    {"IndexName": "dup",
                     "KeySchema": [{"AttributeName": "gsk", "KeyType": "HASH"}],
                     "Projection": {"ProjectionType": "ALL"}}
                ],
                "VectorIndexes": [
                    {"IndexName": "dup",
                     "VectorAttribute": {"AttributeName": "embedding"},
                     "Projection": {"ProjectionType": "ALL"},
                     "Dimensions": 3,
                     "DistanceFunction": "COSINE"}
                ]
            }
            """.formatted(REJECTED),
                "One or more parameter values were invalid: Duplicate index name: dup");

        expectValidation("CreateTable", """
            {
                "TableName": "%s",
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                "BillingMode": "PAY_PER_REQUEST",
                "VectorIndexes": [
                    {"IndexName": "dup",
                     "VectorAttribute": {"AttributeName": "embedding"},
                     "Projection": {"ProjectionType": "ALL"},
                     "Dimensions": 3,
                     "DistanceFunction": "COSINE"},
                    {"IndexName": "dup",
                     "VectorAttribute": {"AttributeName": "embedding"},
                     "Projection": {"ProjectionType": "ALL"},
                     "Dimensions": 3,
                     "DistanceFunction": "COSINE"}
                ]
            }
            """.formatted(REJECTED),
                "One or more parameter values were invalid: Duplicate index name: dup");
    }

    @Test
    @Order(33)
    void searchVectorsChecksTheTopKRangeBeforeTheTableAndTheIndex() {
        String outOfRange = "Provided TopK value '1000' is out of valid range. The value must be "
                + "between 1 and 100 inclusive";

        expectValidation("SearchVectors", search("VectorNoSuchTable", "cosine", 1000, ""), outOfRange);
        expectValidation("SearchVectors", search(DOCS, "VectorNoSuchIndex", 1000, ""), outOfRange);
    }

    /**
     * AWS deserialises SearchVectors with serde and points at the body's closing brace, so the
     * column is the length of the compact JSON an SDK sends. Both bodies below are compact, which
     * is what pins the rule rather than the shape of the message.
     */
    @Test
    @Order(34)
    void searchVectorsReportsAnOmittedMemberAtTheBodyLength() {
        String withoutVector = """
            {"TableName":"%s","IndexName":"plain","TopK":1}""".formatted(PLAIN);
        expectValidation("SearchVectors", withoutVector,
                "missing field `SearchVector` at line 1 column " + withoutVector.length());

        String withoutIndex = """
            {"TableName":"%s","SearchVector":[{"N":"1"},{"N":"0"},{"N":"0"}],"TopK":1}"""
                .formatted(PLAIN);
        expectValidation("SearchVectors", withoutIndex,
                "missing field `IndexName` at line 1 column " + withoutIndex.length());
    }

    @Test
    @Order(35)
    void updateTableRejectsLeavingPayPerRequestWhileAVectorIndexExists() {
        expectValidation("UpdateTable", """
            {"TableName": "%s", "BillingMode": "PROVISIONED"}
            """.formatted(PLAIN),
                "One or more parameter values were invalid: Vector indexes are only supported "
                + "for PAY_PER_REQUEST tables");
    }

    @Test
    @Order(36)
    void updateTableReportsThePositionInTheVectorIndexUpdatesArray() {
        expectValidation("UpdateTable", """
            {
                "TableName": "%s",
                "VectorIndexUpdates": [
                    {"Delete": {"IndexName": "plain"}},
                    {"Create": {"IndexName": "ab",
                                "VectorAttribute": {"AttributeName": "embedding"},
                                "Projection": {"ProjectionType": "ALL"},
                                "Dimensions": 3,
                                "DistanceFunction": "COSINE"}}
                ]
            }
            """.formatted(PLAIN),
                "1 validation error detected: Value 'ab' at "
                + "'vectorIndexUpdates.2.member.create.indexName' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 3");

        call("DescribeTable", """
            {"TableName": "%s"}
            """.formatted(PLAIN))
                .statusCode(200)
                .body("Table.VectorIndexes.IndexName", contains("plain"));
    }

    @Test
    @Order(37)
    void createTableRejectsAnIncludeProjectionWithNoNonKeyAttributes() {
        expectValidation("CreateTable", """
            {
                "TableName": "%s",
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                "BillingMode": "PAY_PER_REQUEST",
                "VectorIndexes": [
                    {"IndexName": "vix",
                     "VectorAttribute": {"AttributeName": "embedding"},
                     "Projection": {"ProjectionType": "INCLUDE"},
                     "Dimensions": 3,
                     "DistanceFunction": "COSINE"}
                ]
            }
            """.formatted(REJECTED),
                "One or more parameter values were invalid: ProjectionType is INCLUDE, but "
                + "NonKeyAttributes is not specified");
    }

    @Test
    @Order(38)
    void updateTableRejectsAKeysOnlyProjectionWithNonKeyAttributes() {
        expectValidation("UpdateTable", """
            {
                "TableName": "%s",
                "VectorIndexUpdates": [
                    {"Create": {"IndexName": "vix",
                                "VectorAttribute": {"AttributeName": "embedding"},
                                "Projection": {"ProjectionType": "KEYS_ONLY",
                                               "NonKeyAttributes": ["label"]},
                                "Dimensions": 3,
                                "DistanceFunction": "COSINE"}}
                ]
            }
            """.formatted(PLAIN),
                "One or more parameter values were invalid: ProjectionType is KEYS_ONLY, but "
                + "NonKeyAttributes is specified");
    }

    @Test
    @Order(39)
    void createTableReportsTheNestedVectorAttributeMember() {
        expectValidation("CreateTable", """
            {
                "TableName": "vec_nested_attr",
                "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "BillingMode": "PAY_PER_REQUEST",
                "VectorIndexes": [
                    {"IndexName": "vix", "VectorAttribute": {},
                     "Projection": {"ProjectionType": "ALL"},
                     "Dimensions": 3, "DistanceFunction": "COSINE"}
                ]
            }
            """,
                "1 validation error detected: Value null at "
                + "'vectorIndexes.1.member.vectorAttribute.attributeName' failed to satisfy "
                + "constraint: Member must not be null");
    }

    @Test
    @Order(40)
    void createTableRequiresADistanceFunction() {
        expectValidation("CreateTable", """
            {
                "TableName": "vec_no_distance",
                "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "BillingMode": "PAY_PER_REQUEST",
                "VectorIndexes": [
                    {"IndexName": "vix", "VectorAttribute": {"AttributeName": "embedding"},
                     "Projection": {"ProjectionType": "ALL"}, "Dimensions": 3}
                ]
            }
            """,
                "1 validation error detected: Value null at "
                + "'vectorIndexes.1.member.distanceFunction' failed to satisfy constraint: "
                + "Member must not be null");
    }

    @Test
    @Order(41)
    void searchVectorsReportsAnOmittedTopKAtTheBodyLength() {
        String withoutTopK = """
            {"TableName":"%s","IndexName":"plain","SearchVector":[{"N":"1"},{"N":"0"},{"N":"0"}]}"""
                .formatted(PLAIN);
        expectValidation("SearchVectors", withoutTopK,
                "missing field `TopK` at line 1 column " + withoutTopK.length());
    }

    /** SearchVectors is served by its own frontend, which omits the envelope other operations add. */
    @Test
    @Order(42)
    void searchVectorsRejectsAReturnConsumedCapacityWithNoEnvelope() {
        expectValidation("SearchVectors", """
            {"TableName": "%s", "IndexName": "plain",
             "SearchVector": [{"N": "1"}, {"N": "0"}, {"N": "0"}],
             "TopK": 1, "ReturnConsumedCapacity": "BOGUS"}
            """.formatted(PLAIN),
                "Value 'BOGUS' at 'returnConsumedCapacity' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [INDEXES, TOTAL, NONE]");
    }

    /** An empty list is a length constraint on the member, the way a GSI reports it. */
    @Test
    @Order(43)
    void createTableRejectsAnEmptyNonKeyAttributesList() {
        expectValidation("CreateTable", """
            {
                "TableName": "%s",
                "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                "BillingMode": "PAY_PER_REQUEST",
                "VectorIndexes": [
                    {"IndexName": "vix",
                     "VectorAttribute": {"AttributeName": "embedding"},
                     "Projection": {"ProjectionType": "INCLUDE", "NonKeyAttributes": []},
                     "Dimensions": 3,
                     "DistanceFunction": "COSINE"}
                ]
            }
            """.formatted(REJECTED),
                "1 validation error detected: Value '[]' at "
                + "'vectorIndexes.1.member.projection.nonKeyAttributes' failed to satisfy "
                + "constraint: Member must have length greater than or equal to 1");
    }

    @Test
    @Order(44)
    void searchVectorsRejectsOverlappingAndUndefinedProjectionPaths() {
        expectValidation("SearchVectors", search(DOCS, "cosine", 1, """
            , "ProjectionExpression": "#x, label", "ExpressionAttributeNames": {"#x": "label"}
            """),
                "Invalid ProjectionExpression: Two document paths overlap with each other; "
                + "must remove or rewrite one of these paths; path one: [label], path two: [label]");
        expectValidation("SearchVectors", search(DOCS, "cosine", 1, """
            , "ProjectionExpression": "#undef"
            """),
                "Invalid ProjectionExpression: An expression attribute name used in the document "
                + "path is not defined; attribute name: #undef");
    }

    @ParameterizedTest
    @Order(45)
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            {"TableName":"sv-probe-absent-table","IndexName":null,"SearchVector":[{"N":"1"}],"TopK":1} | SerializationException | invalid type: null, expected a string at line 1 column 53
            {"TableName":null,"IndexName":"vix","SearchVector":[{"N":"1"}],"TopK":1} | SerializationException | invalid type: null, expected a string at line 1 column 17
            {"TableName":"sv-probe-absent-table","IndexName":"vix","SearchVector":null,"TopK":1} | SerializationException | invalid type: null, expected a sequence at line 1 column 74
            {"TableName":"sv-probe-absent-table","IndexName":"vix","SearchVector":[{"N":"1"}],"TopK":null} | SerializationException | invalid type: null, expected i32 at line 1 column 93
            {"TopK":null,"IndexName":null,"TableName":"sv-probe-absent-table","SearchVector":[{"N":"1"}]} | SerializationException | invalid type: null, expected i32 at line 1 column 12
            {"Foo":1,"TableName":"sv-probe-absent-table","IndexName":null,"SearchVector":[{"N":"1"}],"TopK":1} | SerializationException | invalid type: null, expected a string at line 1 column 61
            {"IndexName":null,"SearchVector":[{"N":"1"}],"TopK":1} | SerializationException | invalid type: null, expected a string at line 1 column 17
            {"TableName":null,"IndexName":1,"SearchVector":[{"N":"1"}],"TopK":1} | SerializationException | invalid type: null, expected a string at line 1 column 17
            {"IndexName":null,"TableName":"sv-probe-absent-table","SearchVector":[{"N":"1"}],"TopK":"1"} | SerializationException | invalid type: null, expected a string at line 1 column 17
            {"TableName":"a\\u0062","IndexName":null,"SearchVector":[{"N":"1"}],"TopK":1} | SerializationException | invalid type: null, expected a string at line 1 column 39
            {"TableName": "sv-probe-absent-table", "IndexName": null, "SearchVector": [{"N": "1"}], "TopK": 1} | SerializationException | invalid type: null, expected a string at line 1 column 56
            {"TableName" : null ,"IndexName":"vix","SearchVector":[{"N":"1"}],"TopK":1} | SerializationException | invalid type: null, expected a string at line 1 column 19
            {"X":{"a":[1,2,{"b":"c"}]},"TableName":null,"IndexName":"vix","SearchVector":[{"N":"1"}],"TopK":1} | SerializationException | invalid type: null, expected a string at line 1 column 43
            {"IndexName":"ab","SearchVector":[{"N":"1"}],"TopK":1} | ValidationException | missing field `TableName` at line 1 column 54
            {"IndexName":"vix","SearchVector":[{"N":"1"}],"TopK":0} | ValidationException | missing field `TableName` at line 1 column 55
            {"IndexName":"vix","SearchVector":[{"N":"1"}],"TopK":1,"ReturnConsumedCapacity":"BOGUS"} | ValidationException | missing field `TableName` at line 1 column 88
            {"IndexName":"vix","SearchVector":[{"N":"1"}]} | ValidationException | missing field `TableName` at line 1 column 46
            {"IndexName": "vix", "SearchVector": [{"N": "1"}], "TopK": 1} | ValidationException | missing field `TableName` at line 1 column 61
            `{"IndexName":"vix","SearchVector":[{"N":"1"}],"TopK":1}   ` | ValidationException | missing field `TableName` at line 1 column 55
            {"IndexName":"vix","TopK":1} | ValidationException | missing field `SearchVector` at line 1 column 28
            {} | ValidationException | missing field `IndexName` at line 1 column 2
            """)
    void searchVectorsRejectsANullRequiredMemberBeforeAnOmittedOne(String body, String type, String message) {
        call("SearchVectors", body)
                .statusCode(400)
                .body("__type", equalTo(type))
                .body("message", equalTo(message));
    }

    // ── Search condition operators ──

    private static final String FILTER_VALUES = """
            {":t": {"S": "t1"}, ":u": {"S": "t2"}, ":z": {"S": "zz"}, ":a": {"S": "a"},
             ":c": {"S": "c1"}, ":c1": {"S": "c1"}, ":c2": {"S": "c2"}, ":s": {"S": "s"},
             ":p": {"N": "3"}, ":n2": {"N": "2"}, ":n3": {"N": "3"}, ":n4": {"N": "4"},
             ":bb": {"B": "AAEC"}, ":bool": {"BOOL": true}}""";

    // AWS refuses a value the expression does not use, so each request sends only its own.
    private static String valuesFor(String expression) throws Exception {
        ObjectNode values = MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> pool = MAPPER.readTree(FILTER_VALUES).fields();
        while (pool.hasNext()) {
            Map.Entry<String, JsonNode> value = pool.next();
            if (Pattern.compile(Pattern.quote(value.getKey()) + "(?![A-Za-z0-9_])").matcher(expression).find()) {
                values.set(value.getKey(), value.getValue());
            }
        }
        return MAPPER.writeValueAsString(values);
    }

    private static final String HASH_EQUALS = "SearchConditionExpression must use = on the HASH "
            + "attribute, not inside OR or NOT; HASH attribute: tenant";
    private static final String HASH_ONCE = "SearchConditionExpression must contain only one "
            + "condition on the HASH attribute; to search more than one HASH value, make one "
            + "SearchVectors call for each value";
    private static final String HASH_MISSING = "SearchConditionExpression must have all HASH "
            + "attributes in configured SearchSchema";
    private static final String NESTED = "Invalid SearchConditionExpression: "
            + "SearchConditionExpression cannot have conditions on nested attributes";
    private static final String NO_NAMES = "Invalid SearchConditionExpression: Invalid condition in "
            + "SearchConditionExpression: No attribute names used in a condition";
    private static final String MANY_NAMES = "Invalid SearchConditionExpression: Invalid condition "
            + "in SearchConditionExpression: Multiple attribute names used in one condition";

    private static String function(String name) {
        return "Invalid SearchConditionExpression: The function is not allowed in a search "
                + "condition; function: " + name;
    }

    private static final String EXISTS_NEEDS_PATH = "Invalid SearchConditionExpression: Operator or "
            + "function requires a document path; operator or function: attribute_exists";

    private static String operandCount(String function) {
        return "Invalid SearchConditionExpression: Incorrect number of operands for operator or "
                + "function; operator or function: " + function + ", number of operands: 2";
    }

    private static String invalidFunction(String name) {
        return "Invalid SearchConditionExpression: Invalid function name; function: " + name;
    }

    private static final String RESERVED_NAME = "Invalid SearchConditionExpression: Attribute name is a "
            + "reserved keyword; reserved keyword: name";

    private static String operandType(String op, String type) {
        return "Invalid SearchConditionExpression: Incorrect operand type for operator or function; "
                + "operator or function: " + op + ", operand type: " + type;
    }

    private static String notInSchema(String attribute) {
        return "SearchConditionExpression must not contain any attributes that is not in "
                + "SearchSchema. Invalid attribute: " + attribute;
    }

    private static String operator(String op, String attribute, String type) {
        return "SearchConditionExpression allows this operator only on Number (N) attributes; "
                + "operator: " + op + ", attribute: " + attribute + ", attribute type: " + type;
    }

    private static String typeMismatch(String attribute, String valueType, String type) {
        return "Type of '" + attribute + "' attribute in SearchConditionExpression (" + valueType
                + ") does not match type in search schema (" + type + ")";
    }

    private static void putFilterItem(String key, String tenant, String category, String price) {
        StringBuilder item = new StringBuilder("""
            {"pk": {"S": "%s"}, "tenant": {"S": "%s"},
             "embedding": {"L": [{"N": "1"}, {"N": "0"}, {"N": "0"}]}""".formatted(key, tenant));
        if (category != null) {
            item.append(", \"category\": {\"S\": \"").append(category).append("\"}");
        }
        if (price != null) {
            item.append(", \"price\": {\"N\": \"").append(price).append("\"}");
        }
        item.append('}');
        call("PutItem", """
            {"TableName": "%s", "Item": %s}
            """.formatted(FILTERS, item))
                .statusCode(200);
    }

    @Test
    @Order(46)
    void seedFilterItems() {
        putFilterItem("i1", "t1", "c1", "1");
        putFilterItem("i2", "t1", "c2", "2");
        putFilterItem("i3", "t1", "c3", "3");
        putFilterItem("i4", "t1", "c1", "4");
        putFilterItem("i5", "t1", "c2", "5");
        putFilterItem("i6", "t1", null, null);
        putFilterItem("other", "t2", "c1", "3");
    }

    @ParameterizedTest
    @Order(47)
    @CsvSource(delimiter = '|', textBlock = """
            tenant = :t AND price < :p | i1,i2
            tenant = :t AND price <= :p | i1,i2,i3
            tenant = :t AND price <> :p | i1,i2,i4,i5,i6
            tenant = :t AND price BETWEEN :n2 AND :n4 | i2,i3,i4
            tenant = :t AND price IN (:n2, :n4) | i2,i4
            tenant = :t AND (price < :n2 OR price > :n4) | i1,i5
            tenant = :t AND NOT price < :p | i3,i4,i5,i6
            tenant = :t AND category <> :c | i2,i3,i5,i6
            tenant = :t AND category IN (:c1, :c2) | i1,i2,i4,i5
            tenant = :t AND NOT (category = :c OR price < :p) | i3,i5,i6
            tenant = :t AND attribute_not_exists(price) | i6
            tenant = :t AND attribute_exists(price) | i1,i2,i3,i4,i5
            price < :p AND tenant = :t | i1,i2
            (tenant = :t AND price < :p) | i1,i2
            :t = tenant AND price < :p | i1,i2
            """)
    void searchVectorsFiltersOnInlineFilterOperators(String expression, String expectedKeys) throws Exception {
        JsonNode response = json("SearchVectors", search(FILTERS, "full", 10, """
            ,
            "SearchConditionExpression": "%s",
            "ExpressionAttributeValues": %s
            """.formatted(expression, valuesFor(expression))));
        List<String> keys = keysOf(response);
        keys.sort(null);
        assertEquals(List.of(expectedKeys.split(",")), keys);
    }

    @Test
    @Order(48)
    void searchVectorsAcceptsOrderingOnANumberFilterWithoutAHash() throws Exception {
        List<String> keys = keysOf(json("SearchVectors", search(FILTERS, "filters", 10, """
            ,
            "SearchConditionExpression": "price < :p",
            "ExpressionAttributeValues": %s
            """.formatted(valuesFor("price < :p")))));
        keys.sort(null);
        assertEquals(List.of("i1", "i2"), keys);
    }

    static Stream<Arguments> searchConditionRefusals() {
        return Stream.of(
                Arguments.of("full", "tenant < :t AND zz = :z", HASH_EQUALS),
                Arguments.of("full", "zz = :z AND tenant < :t", HASH_EQUALS),
                Arguments.of("full", "price = :s AND tenant < :t", HASH_EQUALS),
                Arguments.of("full", "category < :c AND tenant < :t", HASH_EQUALS),
                Arguments.of("full", "tenant <> :t", HASH_EQUALS),
                Arguments.of("full", "tenant >= :t", HASH_EQUALS),
                Arguments.of("full", "tenant BETWEEN :t AND :u", HASH_EQUALS),
                Arguments.of("full", "tenant IN (:t, :u)", HASH_EQUALS),
                Arguments.of("full", "tenant = :t OR category = :c", HASH_EQUALS),
                Arguments.of("full", "NOT (tenant = :t)", HASH_EQUALS),
                Arguments.of("full", "tenant = :t AND tenant < :u", HASH_EQUALS),
                Arguments.of("full", "tenant = :t AND attribute_exists(tenant)", HASH_EQUALS),
                Arguments.of("full", "tenant < :t AND category.x = :c", NESTED),
                Arguments.of("full", "tenant < :t AND category = price", MANY_NAMES),
                Arguments.of("full", "begins_with(category, :c) AND tenant < :t", function("begins_with")),
                Arguments.of("full", "tenant = :t AND begins_with(category, :a) AND category.x = :c",
                        function("begins_with")),
                Arguments.of("full", "tenant = :t AND category.x = :c AND begins_with(category, :a)",
                        NESTED),
                Arguments.of("full", "tenant = :t AND :p = :p AND begins_with(category, :a)", NO_NAMES),
                Arguments.of("full", "tenant = :t AND contains(category, :a)", function("contains")),
                Arguments.of("full", "tenant = :t AND attribute_type(category, :a)",
                        function("attribute_type")),
                Arguments.of("full", "tenant = :t AND size(category) < :p", function("size")),
                Arguments.of("full", "tenant = :t AND category[0] = :c", NESTED),
                Arguments.of("full", "tenant < :bool", operandType("<", "BOOL")),
                Arguments.of("full", "tenant = :t AND begins_with(category, :p)", operandType("begins_with", "N")),
                Arguments.of("full", "tenant = :t AND attribute_exists(price, :p)", operandCount("attribute_exists")),
                Arguments.of("full", "tenant = :t AND NOT attribute_not_exists(price, :p)",
                        operandCount("attribute_not_exists")),
                Arguments.of("full", "attribute_exists(price, :p)", operandCount("attribute_exists")),
                Arguments.of("full", "tenant < :t AND attribute_exists(price, :p)", operandCount("attribute_exists")),
                Arguments.of("full", "tenant = :t AND begins_with(category, :c) AND attribute_exists(price, :p)",
                        operandCount("attribute_exists")),
                Arguments.of("full", "tenant = :t AND attribute_exists(zz, :p)", operandCount("attribute_exists")),
                Arguments.of("full", "tenant = :t AND category < :bool AND attribute_exists(price, :p)",
                        operandType("<", "BOOL")),
                Arguments.of("full", "tenant = :t AND attribute_exists(:p)", EXISTS_NEEDS_PATH),
                Arguments.of("full", "tenant = :t AND attribute_exists(size(price))", EXISTS_NEEDS_PATH),
                Arguments.of("full", "tenant = :t AND nope(category)", invalidFunction("nope")),
                Arguments.of("full", "tenant = :t AND ATTRIBUTE_EXISTS(price)", invalidFunction("ATTRIBUTE_EXISTS")),
                Arguments.of("full", "tenant = :t AND SIZE(category) = :p", invalidFunction("SIZE")),
                Arguments.of("full", "tenant = :t AND name(category)", invalidFunction("name")),
                Arguments.of("full", "tenant = :t AND nope(:c)", invalidFunction("nope")),
                Arguments.of("full", "tenant = :t AND category = nope(price)", invalidFunction("nope")),
                Arguments.of("full", "tenant = :t AND nope(category) IN (:c)", invalidFunction("nope")),
                Arguments.of("full", "tenant = :t AND nope(price) BETWEEN :n2 AND :n4", invalidFunction("nope")),
                Arguments.of("full", "tenant = :t AND attribute_exists(nope(category))", invalidFunction("nope")),
                Arguments.of("full", "tenant = :t AND nope(category) = :missing", invalidFunction("nope")),
                Arguments.of("full", "tenant = :t AND size(nope(:missing)) = :p",
                        "Invalid SearchConditionExpression: An expression attribute value used in "
                        + "expression is not defined; attribute value: :missing"),
                Arguments.of("full", "tenant = :t AND nope(name)", RESERVED_NAME),
                Arguments.of("full", "tenant = :t AND begins_with(category, :a) AND nope(category)",
                        invalidFunction("nope")),
                Arguments.of("full", "tenant = :t AND category < :bool AND nope(category)", operandType("<", "BOOL")),
                Arguments.of("full", "tenant = :t AND nope(category) AND category < :bool", invalidFunction("nope")),
                Arguments.of("full", "tenant = :t AND nope()",
                        "Invalid SearchConditionExpression: Syntax error; token: \")\", near: \"()\""),
                Arguments.of("full", "tenant = :t AND tenant = :u AND zz = :z", HASH_ONCE),
                Arguments.of("full", "zz = :z AND tenant = :t AND tenant = :u", HASH_ONCE),
                Arguments.of("full", "tenant = :t AND tenant = :p", HASH_ONCE),
                Arguments.of("full", "tenant = :t AND (tenant = :u AND category = :c)", HASH_ONCE),
                Arguments.of("full", "tenant = :t AND tenant = :u AND tenant < :t", HASH_EQUALS),
                Arguments.of("full", "zz = :z", HASH_MISSING),
                Arguments.of("full", "price = :s", HASH_MISSING),
                Arguments.of("full", "category < :c", HASH_MISSING),
                Arguments.of("full", "category = :c OR price = :p", HASH_MISSING),
                Arguments.of("full", "category.x = :c", NESTED),
                Arguments.of("full", "tenant = :t AND zz = :z AND category < :c", notInSchema("zz")),
                Arguments.of("full", "tenant = :t AND category < :c AND zz = :z",
                        operator("<", "category", "S")),
                Arguments.of("full", "tenant = :t AND price = :s AND category < :c",
                        typeMismatch("price", "S", "N")),
                Arguments.of("full", "tenant = :p AND zz = :z", typeMismatch("tenant", "N", "S")),
                Arguments.of("full", "zz = :z AND tenant = :p", notInSchema("zz")),
                Arguments.of("full", "tenant = :t AND category < :p", operator("<", "category", "S")),
                Arguments.of("full", "tenant = :t AND :c > category", operator(">", "category", "S")),
                Arguments.of("full", "tenant = :t AND category <= :c", operator("<=", "category", "S")),
                Arguments.of("full", "tenant = :t AND category >= :c", operator(">=", "category", "S")),
                Arguments.of("full", "tenant = :t AND category BETWEEN :c1 AND :c2",
                        operator("BETWEEN", "category", "S")),
                Arguments.of("full", "tenant = :t AND price IN (:p, :s)", typeMismatch("price", "S", "N")),
                Arguments.of("full", "tenant = :t AND category IN (:a, :p)",
                        typeMismatch("category", "N", "S")),
                Arguments.of("full", "tenant = :t AND price BETWEEN :s AND :z",
                        typeMismatch("price", "S", "N")),
                Arguments.of("full", "tenant = :t AND attribute_exists(zz)", notInSchema("zz")),
                Arguments.of("full", "tenant = :t AND bin < :bb", operator("<", "bin", "B")),
                Arguments.of("full", "tenant = :t AND bin BETWEEN :bb AND :bb",
                        operator("BETWEEN", "bin", "B")),
                Arguments.of("full", "tenant = :t AND bin = :a", typeMismatch("bin", "S", "B")),
                Arguments.of("bare", "tenant < :t", notInSchema("tenant")),
                Arguments.of("bare", "tenant = :t", notInSchema("tenant")),
                Arguments.of("bare", "tenant = :t OR price = :p", notInSchema("tenant")),
                Arguments.of("bare", "begins_with(tenant, :t)", function("begins_with")),
                Arguments.of("filters", "category < :c", operator("<", "category", "S")),
                Arguments.of("filters", "tenant = :t", notInSchema("tenant")),
                Arguments.of("filters", "price = :s", typeMismatch("price", "S", "N")));
    }

    @ParameterizedTest
    @Order(49)
    @MethodSource("searchConditionRefusals")
    void searchVectorsRefusesASearchConditionInTheAwsOrder(String index, String expression,
                                                           String message) throws Exception {
        expectValidation("SearchVectors", search(FILTERS, index, 10, """
            ,
            "SearchConditionExpression": "%s",
            "ExpressionAttributeValues": %s
            """.formatted(expression, valuesFor(expression))), message);
    }

    private static final String UNUSED_VALUE_X = "Value provided in ExpressionAttributeValues unused in "
            + "expressions: keys: {:x}";
    private static final String UNUSED_NAME_X = "Value provided in ExpressionAttributeNames unused in "
            + "expressions: keys: {#x}";
    private static final String EMPTY_NAMES = "ExpressionAttributeNames must not be empty";
    private static final String EMPTY_VALUES = "ExpressionAttributeValues must not be empty";
    private static final String VALUES_NEED_EXPRESSION = "ExpressionAttributeValues can only be "
            + "specified when using expressions";
    private static final String NAMES_NEED_EXPRESSION = "ExpressionAttributeNames can only be "
            + "specified when using expressions";
    private static final String UNDEFINED_ZZ = "Invalid ProjectionExpression: An expression attribute "
            + "name used in the document path is not defined; attribute name: #zz";
    private static final String MISSING_TABLE = "VectorNoSuchFilterTable";
    private static final String TENANT_EQUALS = "\"tenant = :t\"";
    private static final String T_VALUES = "{\":t\": {\"S\": \"t1\"}}";
    private static final String TX_VALUES = "{\":t\": {\"S\": \"t1\"}, \":x\": {\"S\": \"x\"}}";
    private static final String X_VALUES = "{\":x\": {\"S\": \"x\"}}";
    private static final String X_NAMES = "{\"#x\": \"x\"}";
    private static final String CX_NAMES = "{\"#c\": \"category\", \"#x\": \"x\"}";
    private static final String C_NAMES = "{\"#c\": \"category\"}";
    private static final String LONG_CONDITION = "\"tenant = :t" + " ".repeat(4086) + "\"";
    private static final String DEEP_CONDITION = "\"" + "NOT ".repeat(25000) + "tenant = :t\"";
    private static final String LONG_PROJECTION = "\"category" + " ".repeat(4089) + "\"";
    private static final String CONDITION_TOO_LONG = "Invalid SearchConditionExpression: Expression size "
            + "has exceeded the maximum allowed size;";
    private static final String PROJECTION_TOO_LONG = "Invalid ProjectionExpression: Expression size has "
            + "exceeded the maximum allowed size;";

    private static String members(String condition, String values, String names, String projection) {
        StringBuilder members = new StringBuilder();
        if (condition != null) {
            members.append(",\n\"SearchConditionExpression\": ").append(condition);
        }
        if (values != null) {
            members.append(",\n\"ExpressionAttributeValues\": ").append(values);
        }
        if (names != null) {
            members.append(",\n\"ExpressionAttributeNames\": ").append(names);
        }
        if (projection != null) {
            members.append(",\n\"ProjectionExpression\": ").append(projection);
        }
        return members.toString();
    }

    static Stream<Arguments> searchChecksBeforeTheTableLookup() {
        return Stream.of(
                Arguments.of(FILTERS, members(TENANT_EQUALS, TX_VALUES, null, null), UNUSED_VALUE_X),
                Arguments.of(FILTERS, members(TENANT_EQUALS, T_VALUES, X_NAMES, null), UNUSED_NAME_X),
                Arguments.of(FILTERS, members(TENANT_EQUALS, TX_VALUES, X_NAMES, null), UNUSED_NAME_X),
                Arguments.of(FILTERS, members("\"tenant = :t AND attribute_exists(category.x)\"", TX_VALUES,
                        null, null), NESTED),
                Arguments.of(FILTERS, members("\"attribute_exists(price)\"", X_VALUES, null, null),
                        UNUSED_VALUE_X),
                Arguments.of(FILTERS, members(TENANT_EQUALS, T_VALUES, CX_NAMES, "\"#c\""), UNUSED_NAME_X),
                Arguments.of(FILTERS, members(TENANT_EQUALS, null, "{}", null), EMPTY_NAMES),
                Arguments.of(FILTERS, members("\"tenant = :t AND\"", "{}", null, null), EMPTY_VALUES),
                Arguments.of(FILTERS, members("\"attribute_exists(price)\"", "{}", "{}", null), EMPTY_NAMES),
                Arguments.of(FILTERS, members("\"attribute_exists(price)\"", "{}", null, "\"#zz\""),
                        EMPTY_VALUES),
                Arguments.of(FILTERS, members("\"tenant = :t AND attribute_exists(category.x)\"", T_VALUES,
                        null, "\"#zz\""), UNDEFINED_ZZ),
                Arguments.of(FILTERS, members("\"tenant = :t AND\"", null, null, "\"a,,b\""),
                        "Invalid ProjectionExpression: Syntax error; token: \",\", near: \",,b\""),
                Arguments.of(FILTERS, members(null, T_VALUES, null, null), VALUES_NEED_EXPRESSION),
                Arguments.of(FILTERS, members(null, null, X_NAMES, null), NAMES_NEED_EXPRESSION),
                Arguments.of(FILTERS, members(null, T_VALUES, X_NAMES, null), NAMES_NEED_EXPRESSION),
                Arguments.of(FILTERS, members(null, T_VALUES, C_NAMES, "\"#c\""), VALUES_NEED_EXPRESSION),
                Arguments.of(FILTERS, members(null, T_VALUES, null, "\"#zz\""), VALUES_NEED_EXPRESSION),
                Arguments.of(FILTERS, members(null, null, CX_NAMES, "\"#c\""), UNUSED_NAME_X),
                Arguments.of(FILTERS, members(null, null, C_NAMES, "\"#c\""), "SearchConditionExpression "
                        + "must be provided when SearchSchema has a HASH key"),
                Arguments.of(MISSING_TABLE, members("\"tenant = :t AND nope(category)\"", T_VALUES, null, null),
                        invalidFunction("nope")),
                Arguments.of(MISSING_TABLE, members(TENANT_EQUALS, TX_VALUES, null, null), UNUSED_VALUE_X),
                Arguments.of(MISSING_TABLE, members(TENANT_EQUALS, T_VALUES, null, "\"#zz\""), UNDEFINED_ZZ),
                Arguments.of(MISSING_TABLE, members("\"attribute_exists(price)\"", "{}", null, null),
                        EMPTY_VALUES),
                Arguments.of(MISSING_TABLE, members(null, T_VALUES, null, null), VALUES_NEED_EXPRESSION),
                Arguments.of(MISSING_TABLE, members(LONG_CONDITION, T_VALUES, null, null), CONDITION_TOO_LONG),
                Arguments.of(MISSING_TABLE, members(DEEP_CONDITION, T_VALUES, null, null), CONDITION_TOO_LONG),
                Arguments.of(MISSING_TABLE, members(TENANT_EQUALS, T_VALUES, null, LONG_PROJECTION),
                        PROJECTION_TOO_LONG),
                Arguments.of(MISSING_TABLE, members(LONG_CONDITION, T_VALUES, null, LONG_PROJECTION),
                        PROJECTION_TOO_LONG),
                Arguments.of(FILTERS, members(LONG_CONDITION, T_VALUES, null, "\"#zz\""), UNDEFINED_ZZ),
                Arguments.of(FILTERS, members(LONG_CONDITION, "{}", null, null), EMPTY_VALUES));
    }

    @ParameterizedTest(name = "[{index}] {0}: {2}")
    @Order(50)
    @MethodSource("searchChecksBeforeTheTableLookup")
    void searchVectorsChecksTheExpressionsBeforeItLooksUpTheTable(String table, String extraMembers,
                                                                  String message) {
        expectValidation("SearchVectors", search(table, "full", 10, extraMembers), message);
    }

    @Test
    @Order(51)
    void searchVectorsAcceptsAProjectionThatUsesANameAlias() throws Exception {
        JsonNode response = json("SearchVectors", search(FILTERS, "full", 10,
                members(TENANT_EQUALS, T_VALUES, C_NAMES, "\"#c\"")));
        assertFalse(response.path("SearchResults").isEmpty());
        boolean projected = false;
        for (JsonNode result : response.path("SearchResults")) {
            List<String> attributes = new ArrayList<>();
            result.path("Item").fieldNames().forEachRemaining(attributes::add);
            assertTrue(List.of("category").containsAll(attributes));
            projected |= !attributes.isEmpty();
        }
        assertTrue(projected);
    }

}
