package io.floci.aws.services.s3vectors;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

/**
 * Every S3 Vectors call that names a vector bucket takes its {@code vectorBucketName} or its {@code vectorBucketArn},
 * and every call that names an index takes its bucket's name and its {@code indexName}, or its {@code indexArn}, as
 * AWS does. A request that gives neither, or mixes them, is refused with the message AWS gives (measured on AWS).
 */
@QuarkusTest
class S3VectorsArnAddressingIntegrationTest {

    private static final String BUCKET_CHOICE = "Must specify either vectorBucketName or vectorBucketArn but not both";
    private static final String INDEX_CHOICE = "Must specify either indexArn or both vectorBucketName and indexName";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aVectorBucketIsReadListedAndDeletedByItsArn() {
        String bucket = bucketName();
        String arn = post("/CreateVectorBucket", "{\"vectorBucketName\":\"" + bucket + "\"}").statusCode(200)
                .extract().path("vectorBucketArn");

        post("/GetVectorBucket", byBucketArn(arn)).statusCode(200)
                .body("vectorBucket.vectorBucketName", equalTo(bucket))
                .body("vectorBucket.vectorBucketArn", equalTo(arn));
        post("/CreateIndex", "{\"vectorBucketArn\":\"" + arn + "\",\"indexName\":\"events\",\"dataType\":\"float32\","
                + "\"dimension\":2,\"distanceMetric\":\"cosine\"}").statusCode(200)
                .body("indexArn", equalTo(arn + "/index/events"));
        post("/ListIndexes", byBucketArn(arn)).statusCode(200)
                .body("indexes.indexName", equalTo(List.of("events")));
        post("/DeleteIndex", "{\"indexArn\":\"" + arn + "/index/events\"}").statusCode(200);
        post("/DeleteVectorBucket", byBucketArn(arn)).statusCode(200);

        post("/GetVectorBucket", "{\"vectorBucketName\":\"" + bucket + "\"}").statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void anIndexIsReadAndItsVectorsWrittenQueriedAndDeletedByItsArn() {
        String bucket = bucketName();
        post("/CreateVectorBucket", "{\"vectorBucketName\":\"" + bucket + "\"}").statusCode(200);
        String index = post("/CreateIndex", "{\"vectorBucketName\":\"" + bucket + "\",\"indexName\":\"events\","
                + "\"dataType\":\"float32\",\"dimension\":2,\"distanceMetric\":\"cosine\"}").statusCode(200)
                .extract().path("indexArn");
        try {
            post("/GetIndex", byIndexArn(index)).statusCode(200)
                    .body("index.indexName", equalTo("events"))
                    .body("index.vectorBucketName", equalTo(bucket));
            post("/PutVectors", "{\"indexArn\":\"" + index + "\",\"vectors\":[{\"key\":\"v1\","
                    + "\"data\":{\"float32\":[1.0,0.0]}}]}").statusCode(200);
            post("/GetVectors", "{\"indexArn\":\"" + index + "\",\"keys\":[\"v1\"]}").statusCode(200)
                    .body("vectors.key", equalTo(List.of("v1")));
            post("/QueryVectors", "{\"indexArn\":\"" + index + "\",\"topK\":1,"
                    + "\"queryVector\":{\"float32\":[1.0,0.0]}}").statusCode(200)
                    .body("vectors", hasSize(1))
                    .body("distanceMetric", equalTo("cosine"));
            post("/DeleteVectors", "{\"indexArn\":\"" + index + "\",\"keys\":[\"v1\"]}").statusCode(200);
            post("/GetVectors", "{\"indexArn\":\"" + index + "\",\"keys\":[\"v1\"]}").statusCode(200)
                    .body("vectors", hasSize(0));
        } finally {
            post("/DeleteIndex", "{\"vectorBucketName\":\"" + bucket + "\",\"indexName\":\"events\"}").statusCode(200);
            post("/DeleteVectorBucket", "{\"vectorBucketName\":\"" + bucket + "\"}").statusCode(200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/GetVectorBucket", "/DeleteVectorBucket", "/ListIndexes", "/CreateIndex"})
    void aBucketCallGivingNeitherOrBothIsRefused(String path) {
        String bucket = bucketName();
        String arn = "arn:aws:s3vectors:us-east-1:000000000000:bucket/" + bucket;

        refused(post(path, "{}"), BUCKET_CHOICE);
        refused(post(path, "{\"vectorBucketName\":\"" + bucket + "\",\"vectorBucketArn\":\"" + arn + "\"}"),
                BUCKET_CHOICE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/GetIndex", "/DeleteIndex", "/PutVectors", "/GetVectors", "/DeleteVectors", "/QueryVectors",
        "/ListVectors"})
    void anIndexCallGivingNeitherOrMixingThemIsRefused(String path) {
        String bucket = bucketName();
        String index = "arn:aws:s3vectors:us-east-1:000000000000:bucket/" + bucket + "/index/events";

        refused(post(path, "{\"keys\":[\"v1\"]}"), INDEX_CHOICE);
        refused(post(path, "{\"vectorBucketName\":\"" + bucket + "\",\"keys\":[\"v1\"]}"), INDEX_CHOICE);
        refused(post(path, "{\"indexName\":\"events\",\"indexArn\":\"" + index + "\",\"keys\":[\"v1\"]}"),
                INDEX_CHOICE);
    }

    /** An index ARN is checked as a bucket ARN is: it names a Region and an account, of the Region's partition. */
    @ParameterizedTest
    @ValueSource(strings = {"arn:aws:s3vectors:us-east-1::bucket/vectors/index/events",
        "arn:aws-cn:s3vectors:us-east-1:000000000000:bucket/vectors/index/events"})
    void aMalformedIndexArnIsRefused(String arn) {
        refused(post("/GetIndex", byIndexArn(arn)), "Invalid S3 Vectors ARN: " + arn);
        refused(post("/ListVectors", byIndexArn(arn)), "Invalid S3 Vectors ARN: " + arn);
    }

    /**
     * CreateVectorBucket and CreateIndex refuse a name outside S3 Vectors' naming rules, which are the same for both,
     * with "Invalid vector bucket name" or "Invalid index name", as it does (measured on AWS). The index name is
     * checked before its bucket is looked for.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Arn-name", "arn_name", "-arn-name", "arn-name-", ".arn-name", "arn-name.", "arn..name",
            "arn.-name", "arn-.name", "192.168.5.4", "sthree-arn-name", "arn-name-s3alias", "arn-name--x-s3"})
    void aNameOutsideTheNamingRulesIsRefusedOnCreate(String name) {
        refused(post("/CreateVectorBucket", "{\"vectorBucketName\":\"" + name + "\"}"), "Invalid vector bucket name");
        refused(post("/CreateIndex", index(bucketName(), name)), "Invalid index name");
        post("/ListVectorBuckets", "{}").statusCode(200).body("vectorBuckets.vectorBucketName", not(hasItem(name)));
    }

    /** A name of fewer than 3 or more than 63 characters gets the length message first (measured on AWS). */
    @ParameterizedTest
    @ValueSource(ints = {2, 64})
    void aNameOfAnotherLengthIsRefusedOnCreate(int length) {
        String name = "a".repeat(length);
        String limit = " failed to satisfy constraint: Member must have length between 3 and 63, inclusive";
        refused(post("/CreateVectorBucket", "{\"vectorBucketName\":\"" + name + "\"}"),
                "1 validation error detected. Value with length " + length + " at '/vectorBucketName'" + limit);
        refused(post("/CreateIndex", index(bucketName(), name)),
                "1 validation error detected. Value with length " + length + " at '/indexName'" + limit);
    }

    /**
     * Names the rules take, though their documentation lists only letters, numbers and hyphens for a bucket: dots
     * between labels, hyphens running together, labels of numbers only, and S3's reserved prefix {@code xn--}
     * (measured on AWS).
     */
    @Test
    void aNameOfDottedLabelsIsTakenOnCreate() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        List<String> names = List.of("arn." + id + ".v2", "ar--n-" + id, "123." + id, "xn--arn-" + id);
        try {
            for (String name : names) {
                post("/CreateVectorBucket", "{\"vectorBucketName\":\"" + name + "\"}").statusCode(200);
            }
            post("/CreateIndex", index(names.getFirst(), "1.2.3")).statusCode(200);
            post("/ListIndexes", "{\"vectorBucketName\":\"" + names.getFirst() + "\"}").statusCode(200)
                    .body("indexes.indexName", equalTo(List.of("1.2.3")));
        } finally {
            post("/DeleteIndex", "{\"vectorBucketName\":\"" + names.getFirst() + "\",\"indexName\":\"1.2.3\"}");
            for (String name : names) {
                post("/DeleteVectorBucket", "{\"vectorBucketName\":\"" + name + "\"}");
            }
        }
    }

    /**
     * An ARN of another account is refused with an AccessDeniedException that carries no message, as S3 Vectors does
     * (measured on AWS), even when the caller has a bucket and an index of those names.
     */
    @Test
    void anArnOfAnotherAccountIsRefused() {
        String bucket = bucketName();
        post("/CreateVectorBucket", "{\"vectorBucketName\":\"" + bucket + "\"}").statusCode(200);
        post("/CreateIndex", "{\"vectorBucketName\":\"" + bucket + "\",\"indexName\":\"events\","
                + "\"dataType\":\"float32\",\"dimension\":2,\"distanceMetric\":\"cosine\"}").statusCode(200);
        String other = "arn:aws:s3vectors:us-east-1:111122223333:bucket/" + bucket;
        try {
            for (ValidatableResponse response : List.of(post("/GetVectorBucket", byBucketArn(other)),
                    post("/ListIndexes", byBucketArn(other)), post("/GetIndex", byIndexArn(other + "/index/events")))) {
                response.statusCode(403).body("__type", equalTo("AccessDeniedException")).body("message", equalTo(""));
            }
        } finally {
            post("/DeleteIndex", "{\"vectorBucketName\":\"" + bucket + "\",\"indexName\":\"events\"}").statusCode(200);
            post("/DeleteVectorBucket", "{\"vectorBucketName\":\"" + bucket + "\"}").statusCode(200);
        }
    }

    /**
     * A name no call could reach the bucket by, blank or with a slash, is refused on create, as S3 Vectors refuses it
     * (measured on AWS), the length first.
     */
    @Test
    void aBucketNameNoCallCouldReachIsRefusedOnCreate() {
        refused(post("/CreateVectorBucket", "{\"vectorBucketName\":\"\"}"), "1 validation error detected. "
                + "Value with length 0 at '/vectorBucketName' failed to satisfy constraint: "
                + "Member must have length between 3 and 63, inclusive");
        for (String name : List.of("a/b", "   ", bucketName() + "/index/events")) {
            refused(post("/CreateVectorBucket", "{\"vectorBucketName\":\"" + name + "\"}"), "Invalid vector bucket name");
            post("/ListVectorBuckets", "{}").statusCode(200).body("vectorBuckets.vectorBucketName", not(hasItem(name)));
        }
    }

    /**
     * An index name no call could reach the index by is refused before the bucket is looked for, as S3 Vectors does
     * (measured on AWS for a slash), and nothing is left that would keep its bucket from being deleted.
     */
    @Test
    void anIndexNameNoCallCouldReachIsRefusedOnCreate() {
        refused(post("/CreateIndex", index(bucketName(), "a/b")), "Invalid index name");
        String bucket = bucketName();
        post("/CreateVectorBucket", "{\"vectorBucketName\":\"" + bucket + "\"}").statusCode(200);
        try {
            refused(post("/CreateIndex", index(bucket, "a/b")), "Invalid index name");
            refused(post("/CreateIndex", index(bucket, "   ")), "Invalid index name");
            refused(post("/CreateIndex", index(bucket, "")), "1 validation error detected. "
                    + "Value with length 0 at '/indexName' failed to satisfy constraint: "
                    + "Member must have length between 3 and 63, inclusive");
            post("/ListIndexes", "{\"vectorBucketName\":\"" + bucket + "\"}").statusCode(200)
                    .body("indexes", hasSize(0));
        } finally {
            post("/DeleteVectorBucket", "{\"vectorBucketName\":\"" + bucket + "\"}").statusCode(200);
        }
    }

    /**
     * An ARN of another Region than the request's, or of another partition, is refused as S3 Vectors refuses it
     * (measured on AWS), by calls of each kind that take one (bucket, index, ListVectors and policy), the partition
     * checked first.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/GetVectorBucket", "/DeleteVectorBucket", "/ListIndexes", "/GetIndex", "/DeleteIndex",
            "/ListVectors", "/GetVectorBucketPolicy"})
    void anArnOfAnotherRegionOrPartitionIsRefused(String path) {
        boolean byIndex = List.of("/GetIndex", "/DeleteIndex", "/ListVectors").contains(path);
        String bucket = bucketName();
        String west = "arn:aws:s3vectors:us-west-2:000000000000:bucket/" + bucket;
        String china = "arn:aws-cn:s3vectors:cn-north-1:000000000000:bucket/" + bucket;
        refused(post(path, byIndex ? byIndexArn(west + "/index/events") : byBucketArn(west)),
                "Unexpected value for ARN region component");
        refused(post(path, byIndex ? byIndexArn(china + "/index/events") : byBucketArn(china)),
                "Unexpected value for ARN partition component");
    }

    private static String index(String bucket, String index) {
        return "{\"vectorBucketName\":\"" + bucket + "\",\"indexName\":\"" + index + "\",\"dataType\":\"float32\","
                + "\"dimension\":2,\"distanceMetric\":\"cosine\"}";
    }

    private static String bucketName() {
        return "arn-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String byBucketArn(String arn) {
        return "{\"vectorBucketArn\":\"" + arn + "\"}";
    }

    private static String byIndexArn(String arn) {
        return "{\"indexArn\":\"" + arn + "\"}";
    }

    private static ValidatableResponse post(String path, String body) {
        return given().contentType("application/json").body(body).when().post(path).then();
    }

    private static void refused(ValidatableResponse response, String message) {
        response.statusCode(400).body("__type", equalTo("ValidationException")).body("message", equalTo(message));
    }
}
