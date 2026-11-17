package io.floci.aws.services.s3vectors;

import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.floci.aws.testing.SignedRestRequests;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.model.AccessDeniedException;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.IamTestPolicies.allow;
import static io.floci.aws.testing.SignedRestRequests.denied;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * S3 Vectors requests are checked against the caller's policies through its generated routes: a vector bucket call
 * on the bucket its {@code vectorBucketName} or {@code vectorBucketArn} names, and an index or vector call on the
 * index its bucket and {@code indexName}, or its {@code indexArn}, name.
 */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class S3VectorsIamIntegrationTest {

    private final AwsRequestSigner admin = AwsRequestSigner.signedAs("test", "test", "s3vectors");

    @TestHTTPResource("/")
    URI endpoint;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tagOperationsRequireTheirOwnPermissionsOnTheExactResource(boolean index) throws Exception {
        String name = bucketName();
        String bucketArn = createBucket(name, "{\"team\":\"data\"}");
        try {
            String indexArn = createIndex(name, "events", "{\"team\":\"web\"}");
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3v-tags-", "access",
                    allow("\"s3vectors:ListTagsForResource\"", index ? indexArn : bucketArn));
                    S3VectorsClient client = S3VectorsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                            .credentialsProvider(StaticCredentialsProvider.create(
                                    AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey())))
                            .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
                String arn = index ? indexArn : bucketArn;
                String otherArn = index ? bucketArn : indexArn;
                Map<String, String> original = Map.of("team", index ? "web" : "data");
                assertEquals(original, client.listTagsForResource(request -> request.resourceArn(arn)).tags());
                assertThrows(AccessDeniedException.class, () -> client.tagResource(request -> request
                        .resourceArn(arn).tags(Map.of("team", "changed"))));
                assertThrows(AccessDeniedException.class, () -> client.untagResource(request -> request
                        .resourceArn(arn).tagKeys("team")));
                assertEquals(original, client.listTagsForResource(request -> request.resourceArn(arn)).tags());

                SignedIamUsers.iam(endpoint, "PutUserPolicy", Map.of("UserName", user.name(), "PolicyName", "access",
                        "PolicyDocument", allow("[\"s3vectors:ListTagsForResource\",\"s3vectors:TagResource\","
                                + "\"s3vectors:UntagResource\"]", arn))).then().statusCode(200);
                client.tagResource(request -> request.resourceArn(arn).tags(Map.of("owner", "allowed")));
                assertEquals("allowed", client.listTagsForResource(request -> request.resourceArn(arn)).tags().get("owner"));
                client.untagResource(request -> request.resourceArn(arn).tagKeys("owner"));
                assertEquals(original, client.listTagsForResource(request -> request.resourceArn(arn)).tags());
                assertThrows(AccessDeniedException.class, () -> client.listTagsForResource(request -> request
                        .resourceArn(otherArn)));
                assertThrows(AccessDeniedException.class, () -> client.tagResource(request -> request
                        .resourceArn(otherArn).tags(Map.of("team", "changed"))));
                assertThrows(AccessDeniedException.class, () -> client.untagResource(request -> request
                        .resourceArn(otherArn).tagKeys("team")));
            } finally {
                send(admin, "/DeleteIndex", index(name, "events")).then().statusCode(200);
            }
        } finally {
            deleteBucket(name);
        }
    }

    @Test
    void callerWithoutPermissionsIsRefusedBeforeAVectorBucketIsCreated() throws Exception {
        String name = "iam-none-" + UUID.randomUUID().toString().substring(0, 8);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3v-iam-")) {
            denied(send(user.signer("s3vectors"), "/CreateVectorBucket", "{\"vectorBucketName\":\"" + name + "\"}"),
                    "s3vectors:CreateVectorBucket");
            denied(send(user.signer("s3vectors"), "/ListVectorBuckets", "{}"), "s3vectors:ListVectorBuckets");
        }
        send(admin, "/ListVectorBuckets", "{}").then().statusCode(200).body(not(containsString(name)));
    }

    @Test
    void aBucketScopedAllowReadsThatBucketByNameOrArnAndRefusesAnother() throws Exception {
        String allowed = bucketName();
        String other = bucketName();
        String allowedArn = createBucket(allowed);
        createBucket(other);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3v-iam-", "access",
                allow("[\"s3vectors:GetVectorBucket\",\"s3vectors:GetVectorBucketPolicy\"]", allowedArn))) {
            send(user.signer("s3vectors"), "/GetVectorBucket", bucket(allowed)).then().statusCode(200)
                    .body("vectorBucket.vectorBucketArn", equalTo(allowedArn));
            denied(send(user.signer("s3vectors"), "/GetVectorBucket", bucket(other)), "s3vectors:GetVectorBucket");
            denied(send(user.signer("s3vectors"), "/DeleteVectorBucket", bucket(allowed)),
                    "s3vectors:DeleteVectorBucket");
            // By its ARN: the call is checked on the bucket the ARN names, and passes the check to answer that
            // the bucket has no policy.
            send(user.signer("s3vectors"), "/GetVectorBucketPolicy", "{\"vectorBucketArn\":\"" + allowedArn + "\"}")
                    .then().statusCode(404).body("__type", equalTo("NotFoundException"));
            denied(send(user.signer("s3vectors"), "/GetVectorBucketPolicy",
                    "{\"vectorBucketArn\":\"" + allowedArn.replace(allowed, other) + "\"}"),
                    "s3vectors:GetVectorBucketPolicy");
        } finally {
            deleteBucket(allowed);
            deleteBucket(other);
        }
    }

    @Test
    void anIndexScopedAllowReadsThatIndexByNameOrArnAndRefusesAnother() throws Exception {
        String name = bucketName();
        createBucket(name);
        String events = createIndex(name, "events");
        String orders = createIndex(name, "orders");
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3v-iam-", "access",
                allow("[\"s3vectors:GetIndex\",\"s3vectors:ListVectors\"]", events))) {
            send(user.signer("s3vectors"), "/GetIndex", index(name, "events")).then().statusCode(200)
                    .body("index.indexArn", equalTo(events));
            denied(send(user.signer("s3vectors"), "/GetIndex", index(name, "orders")), "s3vectors:GetIndex");
            denied(send(user.signer("s3vectors"), "/DeleteIndex", index(name, "events")), "s3vectors:DeleteIndex");
            send(user.signer("s3vectors"), "/ListVectors", "{\"indexArn\":\"" + events + "\"}").then()
                    .statusCode(200);
            denied(send(user.signer("s3vectors"), "/ListVectors", "{\"indexArn\":\"" + orders + "\"}"),
                    "s3vectors:ListVectors");
        } finally {
            send(admin, "/DeleteIndex", index(name, "events")).then().statusCode(200);
            send(admin, "/DeleteIndex", index(name, "orders")).then().statusCode(200);
            deleteBucket(name);
        }
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags S3 Vectors holds on the vector bucket or the index a request
     * names, an index's own and not its bucket's, so an allow on one team's tag lets the caller read that team's
     * bucket and index and not another's.
     */
    @Test
    void aResourceTagConditionReadsTheTagsOfTheBucketOrTheIndex() throws Exception {
        String tagged = bucketName();
        String other = bucketName();
        createBucket(tagged, "{\"team\":\"data\"}");
        createBucket(other, "{\"team\":\"web\"}");
        createIndex(other, "events", "{\"team\":\"data\"}");
        createIndex(other, "orders", "{\"team\":\"web\"}");
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3v-iam-", "access",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Action\":[\"s3vectors:GetVectorBucket\",\"s3vectors:GetIndex\"],\"Resource\":\"*\","
                        + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}]}")) {
            send(user.signer("s3vectors"), "/GetVectorBucket", bucket(tagged)).then().statusCode(200);
            denied(send(user.signer("s3vectors"), "/GetVectorBucket", bucket(other)), "s3vectors:GetVectorBucket");
            send(user.signer("s3vectors"), "/GetIndex", index(other, "events")).then().statusCode(200);
            denied(send(user.signer("s3vectors"), "/GetIndex", index(other, "orders")), "s3vectors:GetIndex");
        } finally {
            send(admin, "/DeleteIndex", index(other, "events")).then().statusCode(200);
            send(admin, "/DeleteIndex", index(other, "orders")).then().statusCode(200);
            deleteBucket(tagged);
            deleteBucket(other);
        }
    }

    private static String bucketName() {
        return "iam-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String bucket(String name) {
        return "{\"vectorBucketName\":\"" + name + "\"}";
    }

    private static String index(String bucket, String index) {
        return "{\"vectorBucketName\":\"" + bucket + "\",\"indexName\":\"" + index + "\"}";
    }

    private String createBucket(String name) throws Exception {
        return createBucket(name, null);
    }

    /** The ARN of the vector bucket {@code name} the administrator creates, with the {@code tags} object if any. */
    private String createBucket(String name, String tags) throws Exception {
        return send(admin, "/CreateVectorBucket", "{\"vectorBucketName\":\"" + name + "\""
                + (tags == null ? "" : ",\"tags\":" + tags) + "}").then().statusCode(200)
                .extract().path("vectorBucketArn");
    }

    private String createIndex(String bucket, String index) throws Exception {
        return createIndex(bucket, index, null);
    }

    /** The ARN of the index the administrator creates in {@code bucket}, with the {@code tags} object if any. */
    private String createIndex(String bucket, String index, String tags) throws Exception {
        return send(admin, "/CreateIndex", "{\"vectorBucketName\":\"" + bucket + "\",\"indexName\":\"" + index
                + "\",\"dataType\":\"float32\",\"dimension\":2,\"distanceMetric\":\"cosine\""
                + (tags == null ? "" : ",\"tags\":" + tags) + "}").then()
                .statusCode(200).extract().path("indexArn");
    }

    private void deleteBucket(String name) throws Exception {
        send(admin, "/DeleteVectorBucket", bucket(name)).then().statusCode(200);
    }

    private Response send(AwsRequestSigner signer, String path, String body) throws Exception {
        return SignedRestRequests.send(endpoint, signer, "POST", path, body, "application/json");
    }
}
