package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailService;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
class S3LegacyHeaderAccountIntegrationTest {

    private static final String ACCOUNT = "000000000001";
    private static final String LEGACY = "AWS " + ACCOUNT + ":local-signature";
    private static final String MODERN = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
            + "/20261020/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=local-signature";

    @Inject
    RequestContext requestContext;

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void activeIamKeysSelectTheirOwnerAndInactiveKeysFallBack() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String user = "legacy-routing-" + suffix;
        String bucket = "legacy-iam-routing-" + suffix;
        String iamHeader = MODERN.replace("/s3/", "/iam/");
        cleanup.register(() -> EnforcementFixtures.removeUser(endpoint.getPort(), iamHeader, user));
        cleanup.register(() -> EnforcementFixtures.removeBucket(endpoint.getPort(), MODERN, bucket));
        given().header("Authorization", iamHeader).formParam("Action", "CreateUser")
                .formParam("UserName", user).post("/").then().statusCode(200);
        String accessKey = given().header("Authorization", iamHeader).formParam("Action", "CreateAccessKey")
                .formParam("UserName", user).post("/").then().statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
        String legacy = "AWS " + accessKey + ":local-signature";
        try {
            given().header("Authorization", legacy).put("/" + bucket).then().statusCode(200);
            given().header("Authorization", legacy).body("iam-owned")
                    .put("/" + bucket + "/object.txt").then().statusCode(200);
            given().header("Authorization", MODERN).get("/" + bucket + "/object.txt")
                    .then().statusCode(200).body(equalTo("iam-owned"));
            given().header("Authorization", iamHeader).formParam("Action", "UpdateAccessKey")
                    .formParam("UserName", user).formParam("AccessKeyId", accessKey)
                    .formParam("Status", "Inactive").post("/").then().statusCode(200);
            given().header("Authorization", legacy).get("/" + bucket + "/object.txt")
                    .then().statusCode(404).body(containsString("NoSuchBucket"));
        } finally {
            given().header("Authorization", MODERN).delete("/" + bucket + "/object.txt");
            given().header("Authorization", MODERN).delete("/" + bucket);
            given().header("Authorization", iamHeader).formParam("Action", "DeleteAccessKey")
                    .formParam("UserName", user).formParam("AccessKeyId", accessKey).post("/");
            given().header("Authorization", iamHeader).formParam("Action", "DeleteUser")
                    .formParam("UserName", user).post("/");
        }
    }

    @Test
    void legacyS3HeadersShareTheModernOwnerAndDoNotLeakIntoTheNextRequest() {
        String bucket = "legacy-account-" + UUID.randomUUID();
        cleanup.register(() -> EnforcementFixtures.removeBucket(endpoint.getPort(), MODERN, bucket));
        given().header("Authorization", LEGACY).put("/" + bucket).then().statusCode(200);
        try {
            given().header("Authorization", LEGACY).body("owned-data")
                    .put("/" + bucket + "/object.txt").then().statusCode(200);
            given().header("Authorization", MODERN).get("/" + bucket + "/object.txt")
                    .then().statusCode(200).body(equalTo("owned-data"));
            given().get("/" + bucket + "/object.txt")
                    .then().statusCode(404).body(containsString("NoSuchBucket"));
            given().header("Authorization", "AWS unknown-key:signature").get("/" + bucket + "/object.txt")
                    .then().statusCode(404).body(containsString("NoSuchBucket"));
            given().header("Authorization", MODERN).get("/" + bucket + "/object.txt")
                    .then().statusCode(200).body(equalTo("owned-data"));
        } finally {
            given().header("Authorization", MODERN).delete("/" + bucket + "/object.txt");
            given().header("Authorization", MODERN).delete("/" + bucket);
        }
    }

    @Test
    void legacyS3DataEventsCarryTheSameCredentialAndRequestAccount() {
        AtomicReference<CloudTrailService.S3EventInput> recorded = new AtomicReference<>();
        AtomicReference<String> eventAccount = new AtomicReference<>();
        CloudTrailService cloudTrail = mock(CloudTrailService.class);
        when(cloudTrail.hasTrails()).thenReturn(true);
        doAnswer(invocation -> {
            recorded.set(invocation.getArgument(0));
            eventAccount.set(requestContext.getAccountId());
            return null;
        }).when(cloudTrail).emitS3DataEvent(any());
        QuarkusMock.installMockForType(cloudTrail, CloudTrailService.class);
        String bucket = "legacy-event-" + UUID.randomUUID();
        cleanup.register(() -> EnforcementFixtures.removeBucket(endpoint.getPort(), MODERN, bucket));
        given().header("Authorization", LEGACY).put("/" + bucket).then().statusCode(200);
        try {
            given().header("Authorization", LEGACY).body("owned-data")
                    .put("/" + bucket + "/object.txt").then().statusCode(200);
            assertEquals(ACCOUNT, eventAccount.get());
            assertEquals(ACCOUNT, recorded.get().accessKeyId());
            assertEquals("PutObject", recorded.get().eventName());
        } finally {
            given().header("Authorization", MODERN).delete("/" + bucket + "/object.txt");
            given().header("Authorization", MODERN).delete("/" + bucket);
        }
    }
}
