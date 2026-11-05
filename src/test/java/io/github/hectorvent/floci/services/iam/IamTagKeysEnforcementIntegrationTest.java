package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.github.hectorvent.floci.testing.EnforcementFixtures.createUser;
import static io.github.hectorvent.floci.testing.EnforcementFixtures.removeBucket;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamTagKeysEnforcementIntegrationTest {

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    @Test
    void ec2CreateTagsRejectsAnUnlistedKeyWithoutNullGuard() {
        String id = given().header("Authorization", auth("test", "ec2"))
                .formParam("Action", "CreateVpc").formParam("CidrBlock", "10.89.0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        int port = endpoint.getPort();
        cleanup.register(() -> given().port(port).header("Authorization", auth("test", "ec2"))
                .formParam("Action", "DeleteVpc").formParam("VpcId", id).post("/"));
        String key = scopedUser("ec2:CreateTags", "aws:TagKeys");
        given().header("Authorization", auth(key, "ec2"))
                .formParam("Action", "CreateTags").formParam("ResourceId.1", id)
                .formParam("Tag.1.Key", "Team").formParam("Tag.1.Value", "payments")
                .post("/").then().statusCode(200);
        given().header("Authorization", auth(key, "ec2"))
                .formParam("Action", "CreateTags").formParam("ResourceId.1", id)
                .formParam("Tag.1.Key", "Team").formParam("Tag.1.Value", "payments")
                .formParam("Tag.2.Key", "Admin").formParam("Tag.2.Value", "true")
                .post("/").then().statusCode(403)
                .body("Response.Errors.Error.Code", equalTo("UnauthorizedOperation"));
    }

    @Test
    void s3BucketTaggingRejectsAnUnlistedKeyWithoutNullGuard() {
        String bucket = bucket();
        String key = scopedUser("s3:PutBucketTagging", "aws:TagKeys");
        String allowed = "<Tag><Key>Team</Key><Value>payments</Value></Tag>";
        given().header("Authorization", auth(key, "s3")).queryParam("tagging", "")
                .body("<Tagging><TagSet>" + allowed + "</TagSet></Tagging>")
                .put("/" + bucket).then().statusCode(204);
        given().header("Authorization", auth(key, "s3")).queryParam("tagging", "")
                .body("<Tagging><TagSet>" + allowed
                        + "<Tag><Key>Admin</Key><Value>true</Value></Tag></TagSet></Tagging>")
                .put("/" + bucket).then().statusCode(403).body(containsString("AccessDenied"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Admin", "team"})
    void s3ObjectUploadRestrictsAllRequestedKeysWithoutNullGuard(String forbiddenKey) {
        String bucket = bucket();
        String key = scopedUploader();
        String path = "/" + bucket + "/object";
        given().header("Authorization", auth(key, "s3")).header("x-amz-tagging", "%54eam=payments")
                .body("original").put(path).then().statusCode(200);
        given().header("Authorization", auth(key, "s3"))
                .header("x-amz-tagging", "Team=payments&" + forbiddenKey + "=true")
                .body("forbidden replacement").put(path).then().statusCode(403)
                .body(containsString("AccessDenied"));
        given().header("Authorization", auth("test", "s3")).get(path)
                .then().statusCode(200).body(equalTo("original"));
        given().header("Authorization", auth("test", "s3")).queryParam("tagging", "").get(path)
                .then().statusCode(200).body(containsString("<Key>Team</Key>"))
                .body(not(containsString("<Key>" + forbiddenKey + "</Key>")));
        // No tags retain the vacuous ForAllValues match; there is no Null guard.
        given().header("Authorization", auth(key, "s3")).body("untagged").put(path)
                .then().statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Admin", "team"})
    void s3ObjectTaggingRestrictsAllRequestedKeysWithoutNullGuard(String forbiddenKey) {
        String bucket = bucket();
        String path = "/" + bucket + "/object";
        given().header("Authorization", auth("test", "s3")).body("original").put(path)
                .then().statusCode(200);
        String key = scopedUser("s3:PutObjectTagging", "s3:RequestObjectTagKeys");
        String allowed = "<Tag><Key>Team</Key><Value>payments</Value></Tag>";
        given().header("Authorization", auth(key, "s3")).queryParam("tagging", "")
                .body("<Tagging><TagSet>" + allowed + "</TagSet></Tagging>")
                .put(path).then().statusCode(200);
        given().header("Authorization", auth(key, "s3")).queryParam("tagging", "")
                .body("<Tagging><TagSet>" + allowed + "<Tag><Key>" + forbiddenKey
                        + "</Key><Value>true</Value></Tag></TagSet></Tagging>")
                .put(path).then().statusCode(403).body(containsString("AccessDenied"));
        given().header("Authorization", auth("test", "s3")).queryParam("tagging", "").get(path)
                .then().statusCode(200).body(containsString("<Key>Team</Key>"))
                .body(not(containsString("<Key>" + forbiddenKey + "</Key>")));
        // An empty tag set removes the tags and still matches ForAllValues.
        given().header("Authorization", auth(key, "s3")).queryParam("tagging", "")
                .body("<Tagging><TagSet/></Tagging>").put(path).then().statusCode(200);
        given().header("Authorization", auth("test", "s3")).queryParam("tagging", "").get(path)
                .then().statusCode(200).body(not(containsString("<Key>Team</Key>")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Admin", "team"})
    void s3QueryTagKeysAreCheckedBeforeAnUpload(String forbiddenKey) {
        String bucket = bucket();
        String key = scopedUploader();
        String path = "/" + bucket + "/object";
        given().header("Authorization", auth(key, "s3")).queryParam("x-amz-tagging", "%54eam=payments")
                .body("original").put(path).then().statusCode(200);
        given().header("Authorization", auth(key, "s3"))
                .queryParam("x-amz-tagging", "Team=payments&" + forbiddenKey + "=true")
                .body("forbidden").put(path).then().statusCode(403);
        given().header("Authorization", auth("test", "s3")).get(path)
                .then().statusCode(200).body(equalTo("original"));
        given().header("Authorization", auth("test", "s3")).queryParam("tagging", "").get(path)
                .then().statusCode(200).body(containsString("<Key>Team</Key>"))
                .body(not(containsString("<Key>" + forbiddenKey + "</Key>")));
    }

    @Test
    void s3TagHeaderTakesPrecedenceOverTheQuery() {
        String bucket = bucket();
        String key = scopedUploader();
        String path = "/" + bucket + "/object";
        given().header("Authorization", auth(key, "s3")).header("x-amz-tagging", "Team=payments")
                .queryParam("x-amz-tagging", "Admin=true").body("original").put(path).then().statusCode(200);
        given().header("Authorization", auth(key, "s3")).header("x-amz-tagging", "Admin=true")
                .queryParam("x-amz-tagging", "Team=payments").body("forbidden").put(path).then().statusCode(403);
        given().header("Authorization", auth("test", "s3")).get(path)
                .then().statusCode(200).body(equalTo("original"));
        given().header("Authorization", auth("test", "s3")).queryParam("tagging", "").get(path)
                .then().statusCode(200).body(containsString("<Key>Team</Key>"))
                .body(not(containsString("<Key>Admin</Key>")));
    }

    @Test
    void conditionalUploadCannotUseRequestedKeysToGrantReadAccess() {
        String bucket = bucket();
        String path = "/" + bucket + "/object";
        String etag = given().header("Authorization", auth("test", "s3")).body("original")
                .put(path).then().statusCode(200).extract().header("ETag");
        String key = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:PutObject","s3:PutObjectTagging"],"Resource":"*"},
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"*",
                   "Condition":{"ForAnyValue:StringEquals":{"s3:RequestObjectTagKeys":["Team"]}}}]}
                """);
        given().header("Authorization", auth(key, "s3")).header("x-amz-tagging", "Team=payments")
                .header("If-Match", etag).body("forbidden").put(path).then().statusCode(403);
        given().header("Authorization", auth("test", "s3")).get(path)
                .then().statusCode(200).body(equalTo("original"));
        String allowedKey = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Action":["s3:PutObject","s3:PutObjectTagging","s3:GetObject"],"Resource":"*"}]}
                """);
        given().header("Authorization", auth(allowedKey, "s3")).header("x-amz-tagging", "Team=payments")
                .header("If-Match", etag).body("allowed").put(path).then().statusCode(200);
        given().header("Authorization", auth("test", "s3")).get(path)
                .then().statusCode(200).body(equalTo("allowed"));
    }

    private String bucket() {
        String bucket = "tagkeys-" + UUID.randomUUID();
        int port = endpoint.getPort();
        cleanup.register(() -> removeBucket(port, auth("test", "s3"), bucket));
        given().header("Authorization", auth("test", "s3")).put("/" + bucket).then().statusCode(200);
        return bucket;
    }

    private String scopedUser(String action, String conditionKey) {
        return userWithPolicy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"%s",
                 "Resource":"*","Condition":{"ForAllValues:StringEquals":{"%s":["Team"]}}}]}
                """.formatted(action, conditionKey));
    }

    /** A tagged upload needs s3:PutObjectTagging beside s3:PutObject, and both are held to the keys. */
    private String scopedUploader() {
        return userWithPolicy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                 "Action":["s3:PutObject","s3:PutObjectTagging"],"Resource":"*",
                 "Condition":{"ForAllValues:StringEquals":{"s3:RequestObjectTagKeys":["Team"]}}}]}
                """);
    }

    private String userWithPolicy(String policy) {
        String user = "tagkeys-" + UUID.randomUUID();
        String accessKey = createUser(cleanup, endpoint.getPort(), auth("test", "iam"), user).accessKeyId();
        given().header("Authorization", auth("test", "iam"))
                .formParam("Action", "PutUserPolicy").formParam("UserName", user).formParam("PolicyName", "tags")
                .formParam("PolicyDocument", policy).post("/").then().statusCode(200);
        return accessKey;
    }
}
