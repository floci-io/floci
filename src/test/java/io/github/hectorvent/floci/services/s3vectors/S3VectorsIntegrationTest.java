package io.github.hectorvent.floci.services.s3vectors;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3VectorsIntegrationTest {

    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String BUCKET_NAME = "my-vector-bucket";
    private static final String INDEX_NAME = "my-vector-index";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createVectorBucket() {
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s"
                }
                """.formatted(BUCKET_NAME))
        .when()
            .post("/CreateVectorBucket")
        .then()
            .statusCode(200)
            .body("vectorBucketArn", containsString("arn:aws:s3vectors:"));
    }

    @Test
    @Order(2)
    void getVectorBucket() {
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s"
                }
                """.formatted(BUCKET_NAME))
        .when()
            .post("/GetVectorBucket")
        .then()
            .statusCode(200)
            .body("vectorBucket.vectorBucketName", equalTo(BUCKET_NAME))
            .body("vectorBucket.vectorBucketArn", containsString("arn:aws:s3vectors:"));
    }

    @Test
    @Order(3)
    void listVectorBuckets() {
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/ListVectorBuckets")
        .then()
            .statusCode(200)
            .body("vectorBuckets", notNullValue())
            .body("vectorBuckets.size()", greaterThanOrEqualTo(1));
    }

    @Test
    @Order(4)
    void createIndex() {
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s",
                    "indexName": "%s",
                    "dimension": 3,
                    "distanceMetric": "cosine",
                    "dataType": "float32"
                }
                """.formatted(BUCKET_NAME, INDEX_NAME))
        .when()
            .post("/CreateIndex")
        .then()
            .statusCode(200)
            .body("indexArn", containsString("arn:aws:s3vectors:"));
    }

    @Test
    @Order(5)
    void getIndex() {
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s",
                    "indexName": "%s"
                }
                """.formatted(BUCKET_NAME, INDEX_NAME))
        .when()
            .post("/GetIndex")
        .then()
            .statusCode(200)
            .body("index.indexName", equalTo(INDEX_NAME))
            .body("index.dimension", equalTo(3))
            .body("index.distanceMetric", equalTo("cosine"));
    }

    @Test
    @Order(6)
    void listIndexes() {
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s"
                }
                """.formatted(BUCKET_NAME))
        .when()
            .post("/ListIndexes")
        .then()
            .statusCode(200)
            .body("indexes", notNullValue())
            .body("indexes.size()", greaterThanOrEqualTo(1));
    }

    @Test
    @Order(7)
    void putVectors() {
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s",
                    "indexName": "%s",
                    "vectors": [
                        {
                            "key": "v1",
                            "data": {
                                "float32": [1.0, 0.0, 0.0]
                            },
                            "metadata": {
                                "label": "first"
                            }
                        },
                        {
                            "key": "v2",
                            "data": {
                                "float32": [0.0, 1.0, 0.0]
                            },
                            "metadata": {
                                "label": "second"
                            }
                        }
                    ]
                }
                """.formatted(BUCKET_NAME, INDEX_NAME))
        .when()
            .post("/PutVectors")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(8)
    void getVectors() {
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s",
                    "indexName": "%s",
                    "keys": ["v1", "v2"],
                    "returnData": true,
                    "returnMetadata": true
                }
                """.formatted(BUCKET_NAME, INDEX_NAME))
        .when()
            .post("/GetVectors")
        .then()
            .statusCode(200)
            .body("vectors", hasSize(2))
            .body("vectors.find { it.key == 'v1' }.data.float32", contains(1.0f, 0.0f, 0.0f))
            .body("vectors.find { it.key == 'v2' }.metadata.label", equalTo("second"));
    }

    @Test
    @Order(9)
    void queryVectorsCosine() {
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s",
                    "indexName": "%s",
                    "queryVector": {
                        "float32": [1.0, 0.0, 0.0]
                    },
                    "topK": 2,
                    "returnMetadata": true,
                    "returnDistance": true
                }
                """.formatted(BUCKET_NAME, INDEX_NAME))
        .when()
            .post("/QueryVectors")
        .then()
            .statusCode(200)
            .body("vectors", hasSize(2))
            .body("vectors[0].key", equalTo("v1"))
            .body("vectors[0].distance", equalTo(0.0f))
            .body("vectors[0].metadata.label", equalTo("first"))
            .body("vectors[1].key", equalTo("v2"))
            .body("vectors[1].distance", equalTo(1.0f));
    }

    @Test
    @Order(10)
    void queryVectors_withoutReturnDistance_omitsDistance() {
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s",
                    "indexName": "%s",
                    "queryVector": {
                        "float32": [1.0, 0.0, 0.0]
                    },
                    "topK": 1
                }
                """.formatted(BUCKET_NAME, INDEX_NAME))
        .when()
            .post("/QueryVectors")
        .then()
            .statusCode(200)
            .body("vectors", hasSize(1))
            .body("vectors[0]", not(hasKey("distance")));
    }

    @Test
    @Order(11)
    void listVectors() {
        // Reproduces #2161: ListVectors previously had no route and fell through to the S3
        // REST-XML controller, returning an S3 <Error>InvalidArgument</Error> instead of a
        // ListVectors response.
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s",
                    "indexName": "%s",
                    "returnData": true,
                    "returnMetadata": true
                }
                """.formatted(BUCKET_NAME, INDEX_NAME))
        .when()
            .post("/ListVectors")
        .then()
            .contentType(JSON_CONTENT_TYPE)
            .statusCode(200)
            .body("vectors", hasSize(2))
            .body("vectors.find { it.key == 'v1' }.data.float32", contains(1.0f, 0.0f, 0.0f))
            .body("vectors.find { it.key == 'v1' }.metadata.label", equalTo("first"))
            .body("vectors.find { it.key == 'v2' }.metadata.label", equalTo("second"))
            .body("nextToken", nullValue());
    }

    @Test
    @Order(12)
    void listVectorsPaginatesWithMaxResultsAndNextToken() {
        String firstPageNextToken =
            given()
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                    {
                        "vectorBucketName": "%s",
                        "indexName": "%s",
                        "maxResults": 1
                    }
                    """.formatted(BUCKET_NAME, INDEX_NAME))
            .when()
                .post("/ListVectors")
            .then()
                .statusCode(200)
                .body("vectors", hasSize(1))
                .body("vectors[0].key", equalTo("v1"))
                .body("nextToken", notNullValue())
            .extract().path("nextToken");

        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s",
                    "indexName": "%s",
                    "maxResults": 1,
                    "nextToken": "%s"
                }
                """.formatted(BUCKET_NAME, INDEX_NAME, firstPageNextToken))
        .when()
            .post("/ListVectors")
        .then()
            .statusCode(200)
            .body("vectors", hasSize(1))
            .body("vectors[0].key", equalTo("v2"))
            .body("nextToken", nullValue());
    }

    @Test
    @Order(13)
    void deleteVectors() {
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s",
                    "indexName": "%s",
                    "keys": ["v1"]
                }
                """.formatted(BUCKET_NAME, INDEX_NAME))
        .when()
            .post("/DeleteVectors")
        .then()
            .statusCode(200);

        // Verify key is gone
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s",
                    "indexName": "%s",
                    "keys": ["v1"],
                    "returnData": true
                }
                """.formatted(BUCKET_NAME, INDEX_NAME))
        .when()
            .post("/GetVectors")
        .then()
            .statusCode(200)
            .body("vectors", hasSize(0));
    }

    @Test
    @Order(14)
    void deleteIndex() {
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s",
                    "indexName": "%s"
                }
                """.formatted(BUCKET_NAME, INDEX_NAME))
        .when()
            .post("/DeleteIndex")
        .then()
            .statusCode(200);

        // Verify index is gone
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s",
                    "indexName": "%s"
                }
                """.formatted(BUCKET_NAME, INDEX_NAME))
        .when()
            .post("/GetIndex")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(15)
    void deleteVectorBucket() {
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s"
                }
                """.formatted(BUCKET_NAME))
        .when()
            .post("/DeleteVectorBucket")
        .then()
            .statusCode(200);

        // Verify bucket is gone
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s"
                }
                """.formatted(BUCKET_NAME))
        .when()
            .post("/GetVectorBucket")
        .then()
            .statusCode(404);
    }

    // Self-contained: each test below creates its own bucket/index so it doesn't depend on the
    // ordered setup/teardown sequence above.

    @Test
    @Order(16)
    void listVectorsByIndexArnOnly() {
        String bucketName = "arn-list-vectors-bucket";
        String indexName = "arn-idx";

        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                { "vectorBucketName": "%s" }
                """.formatted(bucketName))
        .when()
            .post("/CreateVectorBucket")
        .then()
            .statusCode(200);

        String indexArn =
            given()
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                    {
                        "vectorBucketName": "%s",
                        "indexName": "%s",
                        "dimension": 2,
                        "distanceMetric": "cosine",
                        "dataType": "float32"
                    }
                    """.formatted(bucketName, indexName))
            .when()
                .post("/CreateIndex")
            .then()
                .statusCode(200)
            .extract().path("indexArn");

        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s",
                    "indexName": "%s",
                    "vectors": [{"key": "only", "data": {"float32": [1.0, 0.0]}}]
                }
                """.formatted(bucketName, indexName))
        .when()
            .post("/PutVectors")
        .then()
            .statusCode(200);

        // ListVectors identified by indexArn alone (no vectorBucketName/indexName) must resolve
        // to the same index rather than looking up a null bucket name.
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                { "indexArn": "%s" }
                """.formatted(indexArn))
        .when()
            .post("/ListVectors")
        .then()
            .statusCode(200)
            .body("vectors", hasSize(1))
            .body("vectors[0].key", equalTo("only"));
    }

    @Test
    @Order(17)
    void listVectorsPaginationRoundTripsKeysWithQuotes() {
        String bucketName = "quote-key-vector-bucket";
        String indexName = "quote-idx";

        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                { "vectorBucketName": "%s" }
                """.formatted(bucketName))
        .when()
            .post("/CreateVectorBucket")
        .then()
            .statusCode(200);

        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s",
                    "indexName": "%s",
                    "dimension": 2,
                    "distanceMetric": "cosine",
                    "dataType": "float32"
                }
                """.formatted(bucketName, indexName))
        .when()
            .post("/CreateIndex")
        .then()
            .statusCode(200);

        // One key contains a double quote — the pagination cursor must round-trip it intact
        // rather than truncating at the quote.
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s",
                    "indexName": "%s",
                    "vectors": [
                        {"key": "a\\"b", "data": {"float32": [1.0, 0.0]}},
                        {"key": "c", "data": {"float32": [0.0, 1.0]}}
                    ]
                }
                """.formatted(bucketName, indexName))
        .when()
            .post("/PutVectors")
        .then()
            .statusCode(200);

        String firstPageNextToken =
            given()
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                    {
                        "vectorBucketName": "%s",
                        "indexName": "%s",
                        "maxResults": 1
                    }
                    """.formatted(bucketName, indexName))
            .when()
                .post("/ListVectors")
            .then()
                .statusCode(200)
                .body("vectors", hasSize(1))
                .body("vectors[0].key", equalTo("a\"b"))
                .body("nextToken", notNullValue())
            .extract().path("nextToken");

        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {
                    "vectorBucketName": "%s",
                    "indexName": "%s",
                    "maxResults": 1,
                    "nextToken": "%s"
                }
                """.formatted(bucketName, indexName, firstPageNextToken))
        .when()
            .post("/ListVectors")
        .then()
            .statusCode(200)
            .body("vectors", hasSize(1))
            .body("vectors[0].key", equalTo("c"))
            .body("nextToken", nullValue());
    }

    @Test
    @Order(18)
    void listVectorsByIndexArnResolvesTheArnsOwnRegionNotTheRequestRegion() {
        String bucketName = "cross-region-vector-bucket";
        String indexName = "cross-region-idx";
        String otherRegionAuth = "Credential=AKID/20260101/us-west-2/s3vectors/aws4_request";

        // Create the bucket/index in us-west-2 (via the SigV4 credential scope region).
        given()
            .contentType(JSON_CONTENT_TYPE)
            .header("Authorization", otherRegionAuth)
            .body("""
                { "vectorBucketName": "%s" }
                """.formatted(bucketName))
        .when()
            .post("/CreateVectorBucket")
        .then()
            .statusCode(200);

        String indexArn =
            given()
                .contentType(JSON_CONTENT_TYPE)
                .header("Authorization", otherRegionAuth)
                .body("""
                    {
                        "vectorBucketName": "%s",
                        "indexName": "%s",
                        "dimension": 2,
                        "distanceMetric": "cosine",
                        "dataType": "float32"
                    }
                    """.formatted(bucketName, indexName))
            .when()
                .post("/CreateIndex")
            .then()
                .statusCode(200)
                .body("indexArn", containsString(":us-west-2:"))
            .extract().path("indexArn");

        given()
            .contentType(JSON_CONTENT_TYPE)
            .header("Authorization", otherRegionAuth)
            .body("""
                {
                    "vectorBucketName": "%s",
                    "indexName": "%s",
                    "vectors": [{"key": "only", "data": {"float32": [1.0, 0.0]}}]
                }
                """.formatted(bucketName, indexName))
        .when()
            .post("/PutVectors")
        .then()
            .statusCode(200);

        // No Authorization header here, so this request resolves to the default region — not
        // us-west-2. The lookup must still succeed because it honors the region encoded in the
        // ARN itself, not the region the ListVectors request happens to be signed for.
        given()
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                { "indexArn": "%s" }
                """.formatted(indexArn))
        .when()
            .post("/ListVectors")
        .then()
            .statusCode(200)
            .body("vectors", hasSize(1))
            .body("vectors[0].key", equalTo("only"));
    }
}
