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

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.SignedRestRequests.denied;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

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

    private static String bucketName() {
        return "iam-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String bucket(String name) {
        return "{\"vectorBucketName\":\"" + name + "\"}";
    }

    private static String index(String bucket, String index) {
        return "{\"vectorBucketName\":\"" + bucket + "\",\"indexName\":\"" + index + "\"}";
    }

    private static String allow(String actions, String resource) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":" + actions
                + ",\"Resource\":\"" + resource + "\"}]}";
    }

    private String createBucket(String name) throws Exception {
        return send(admin, "/CreateVectorBucket", bucket(name)).then().statusCode(200)
                .extract().path("vectorBucketArn");
    }

    private String createIndex(String bucket, String index) throws Exception {
        return send(admin, "/CreateIndex", "{\"vectorBucketName\":\"" + bucket + "\",\"indexName\":\"" + index
                + "\",\"dataType\":\"float32\",\"dimension\":2,\"distanceMetric\":\"cosine\"}").then()
                .statusCode(200).extract().path("indexArn");
    }

    private void deleteBucket(String name) throws Exception {
        send(admin, "/DeleteVectorBucket", bucket(name)).then().statusCode(200);
    }

    private Response send(AwsRequestSigner signer, String path, String body) throws Exception {
        return SignedRestRequests.send(endpoint, signer, "POST", path, body, "application/json");
    }
}
