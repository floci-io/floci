package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testing.S3EnforceAuthProfile;
import io.github.hectorvent.floci.testutil.S3RequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * S3's own bucket-policy check, with IAM enforcement off, checks an object request as the actions
 * the enforcement filter does: the version action a versionId selects, s3:GetObject beside
 * s3:GetObjectAttributes and beside a write carrying If-Match, and the actions an object created
 * with tags, an ACL or Object Lock settings, or copied from a tagged object, needs besides
 * s3:PutObject. The policy is offered the tag and Object Lock keys the request sets, and
 * s3:BypassGovernanceRetention is asked for only by a request that overrides a governance lock.
 * Each case is an explicit Deny in the bucket policy refusing the bucket owner.
 */
@QuarkusTest
@TestProfile(S3EnforceAuthProfile.class)
class S3ObjectOperationsBucketPolicyIntegrationTest {

    private static final S3RequestSigner OWNER = S3RequestSigner.signedAs("test", "test");

    @Test
    void aRequestNamingAVersionIsCheckedAsTheVersionAction() {
        String bucket = bucket(true);
        String versionId = owner().header("x-amz-tagging", "env=a").body("v1").when().put(bucket + "/doc")
                .then().statusCode(200).extract().header("x-amz-version-id");
        owner().queryParam("annotation", "").queryParam("annotationName", "labels").body("x")
                .when().put(bucket + "/doc").then().statusCode(200);
        policy(bucket, deny("s3:GetObjectVersionTagging", "s3:PutObjectVersionTagging",
                "s3:DeleteObjectVersionTagging", "s3:GetObjectVersionAcl", "s3:GetObjectVersionAnnotation",
                "s3:PutObjectVersionAnnotation", "s3:DeleteObjectVersionAnnotation"));

        owner().queryParam("tagging", "").queryParam("versionId", versionId).when().get(bucket + "/doc")
                .then().statusCode(403);
        owner().queryParam("tagging", "").queryParam("versionId", versionId)
                .body("<Tagging><TagSet/></Tagging>").when().put(bucket + "/doc").then().statusCode(403);
        owner().queryParam("tagging", "").queryParam("versionId", versionId).when().delete(bucket + "/doc")
                .then().statusCode(403);
        owner().queryParam("acl", "").queryParam("versionId", versionId).when().get(bucket + "/doc")
                .then().statusCode(403);
        owner().queryParam("annotation", "").queryParam("annotationName", "labels")
                .queryParam("versionId", versionId).when().get(bucket + "/doc").then().statusCode(403);
        owner().queryParam("annotation", "").queryParam("annotationName", "labels")
                .queryParam("versionId", versionId).body("y").when().put(bucket + "/doc").then().statusCode(403);
        owner().queryParam("annotation", "").queryParam("annotationName", "labels")
                .queryParam("versionId", versionId).when().delete(bucket + "/doc").then().statusCode(403);

        // Without a versionId the base actions are checked, which the policy leaves alone.
        owner().queryParam("tagging", "").when().get(bucket + "/doc").then().statusCode(200);
        owner().queryParam("acl", "").when().get(bucket + "/doc").then().statusCode(200);
        owner().queryParam("annotation", "").queryParam("annotationName", "labels")
                .when().get(bucket + "/doc").then().statusCode(200);
    }

    @Test
    void getObjectAttributesNeedsTheReadAsWell() {
        String bucket = bucket(false);
        owner().body("data").when().put(bucket + "/doc").then().statusCode(200);

        policy(bucket, deny("s3:GetObject"));
        attributes(bucket + "/doc").then().statusCode(403);

        policy(bucket, deny("s3:GetObjectAttributes"));
        attributes(bucket + "/doc").then().statusCode(403);
        owner().when().get(bucket + "/doc").then().statusCode(200);
    }

    @Test
    void aWriteCarryingIfMatchNeedsTheReadAsWell() {
        String bucket = bucket(false);
        String source = bucket(false);
        String eTag = owner().body("v1").when().put(bucket + "/doc").then().statusCode(200).extract().header("ETag");
        owner().body("copied").when().put(source + "/doc").then().statusCode(200);
        policy(bucket, deny("s3:GetObject"));

        owner().header("If-Match", eTag).body("v2").when().put(bucket + "/doc").then().statusCode(403);
        owner().header("If-Match", eTag).header("x-amz-copy-source", source + "/doc")
                .when().put(bucket + "/doc").then().statusCode(403);
        String uploadId = owner().queryParam("uploads", "").when().post(bucket + "/doc")
                .then().statusCode(200).extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
        String partETag = owner().queryParam("uploadId", uploadId).queryParam("partNumber", 1).body("v3")
                .when().put(bucket + "/doc").then().statusCode(200).extract().header("ETag");
        String parts = "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>" + partETag
                + "</ETag></Part></CompleteMultipartUpload>";
        owner().header("If-Match", eTag).queryParam("uploadId", uploadId).body(parts)
                .when().post(bucket + "/doc").then().statusCode(403);

        // The same writes without the condition need only the write.
        owner().queryParam("uploadId", uploadId).body(parts).when().post(bucket + "/doc").then().statusCode(200);
        owner().body("v4").when().put(bucket + "/doc").then().statusCode(200);
    }

    @Test
    void anObjectCreatedWithTagsOrAnAclNeedsThoseActions() {
        String bucket = bucket(false);
        policy(bucket, deny("s3:PutObjectTagging", "s3:PutObjectAcl"));

        owner().header("x-amz-tagging", "env=a").body("tagged").when().put(bucket + "/tagged")
                .then().statusCode(403);
        owner().header("x-amz-acl", "bucket-owner-full-control").body("acl").when().put(bucket + "/acl")
                .then().statusCode(403);
        owner().header("x-amz-tagging", "env=a").queryParam("uploads", "").when().post(bucket + "/upload")
                .then().statusCode(403);
        owner().body("plain").when().put(bucket + "/plain").then().statusCode(200);
        // A multipart upload ignores x-amz-copy-source and applies its own tags, so the header does not
        // make it a copy whose tags come from a source.
        owner().header("x-amz-tagging", "env=a").header("x-amz-copy-source", bucket + "/plain")
                .queryParam("uploads", "").when().post(bucket + "/upload").then().statusCode(403);
    }

    @Test
    void aCopyOfATaggedObjectNeedsPutObjectTagging() {
        String bucket = bucket(false);
        owner().header("x-amz-tagging", "env=a").body("tagged").when().put(bucket + "/tagged")
                .then().statusCode(200);
        owner().body("plain").when().put(bucket + "/plain").then().statusCode(200);
        policy(bucket, deny("s3:PutObjectTagging"));

        owner().header("x-amz-copy-source", bucket + "/tagged").when().put(bucket + "/copy")
                .then().statusCode(403);
        owner().header("x-amz-copy-source", bucket + "/plain").header("x-amz-tagging-directive", "REPLACE")
                .header("x-amz-tagging", "env=b").when().put(bucket + "/copy").then().statusCode(403);
        owner().header("x-amz-copy-source", bucket + "/plain").when().put(bucket + "/copy")
                .then().statusCode(200);
    }

    @Test
    void aReplacingCopyAppliesTheTagsItIsCheckedForFromAPresignedQuery() throws Exception {
        // A presigner may move x-amz-tagging into the query, where PutObject already reads it; a copy
        // replacing its source's tags reads it there too, so the object gets the tags the policy saw.
        String bucket = bucket(false);
        owner().body("plain").when().put(bucket + "/plain").then().statusCode(200);
        policy(bucket, denyWhen("{\"StringEquals\":{\"s3:RequestObjectTag/env\":\"secret\"}}", "s3:PutObject"));

        presignedCopy(bucket + "/plain", bucket + "/secret?x-amz-tagging=env%3Dsecret").then().statusCode(403);
        presignedCopy(bucket + "/plain", bucket + "/copy?x-amz-tagging=env%3Db").then().statusCode(200);
        owner().queryParam("tagging", "").when().get(bucket + "/copy").then().statusCode(200)
                .body(containsString("<Value>b</Value>"));
    }

    @Test
    void anAnonymousCreationAPublicBucketAclAdmitsMayApplyItsSettings() {
        // The bucket ACL's WRITE grant admits the creation with its ACL and tags, as before they were
        // checked; a public bucket policy that names a setting's action still decides it.
        String bucket = bucket(false);
        owner().body("<OwnershipControls><Rule><ObjectOwnership>ObjectWriter</ObjectOwnership></Rule></OwnershipControls>")
                .when().put(bucket + "?ownershipControls").then().statusCode(200);
        owner().when().delete(bucket + "?publicAccessBlock").then().statusCode(204);
        owner().header("x-amz-acl", "public-read-write").when().put(bucket + "?acl").then().statusCode(200);

        given().header("x-amz-acl", "bucket-owner-full-control").body("acl").when().put(bucket + "/acl")
                .then().statusCode(200);
        given().header("x-amz-tagging", "env=a").body("tagged").when().put(bucket + "/tagged")
                .then().statusCode(200);

        policy(bucket, deny("s3:PutObjectTagging"));
        given().header("x-amz-tagging", "env=a").body("tagged").when().put(bucket + "/denied")
                .then().statusCode(403);
        given().body("plain").when().put(bucket + "/plain").then().statusCode(200);
    }

    @Test
    void anObjectCreationOffersItsTagAndObjectLockKeys() {
        String bucket = lockedBucket();
        policy(bucket, denyWhen("{\"StringEquals\":{\"s3:RequestObjectTag/env\":\"secret\"}}",
                "s3:PutObjectTagging")
                + "," + denyWhen("{\"StringEquals\":{\"s3:object-lock-mode\":\"COMPLIANCE\"}}",
                "s3:PutObjectRetention")
                + "," + denyWhen("{\"StringEquals\":{\"s3:object-lock-legal-hold\":\"ON\"}}",
                "s3:PutObjectLegalHold")
                + "," + denyWhen("{\"ForAnyValue:StringEquals\":{\"s3:RequestObjectTagKeys\":\"blocked\"}}",
                "s3:PutObject"));

        owner().header("x-amz-tagging", "env=secret").body("x").when().put(bucket + "/secret")
                .then().statusCode(403);
        owner().header("x-amz-tagging", "env=secret").queryParam("uploads", "").when().post(bucket + "/upload")
                .then().statusCode(403);
        owner().header("x-amz-tagging", "env=open").body("x").when().put(bucket + "/open")
                .then().statusCode(200);
        owner().header("x-amz-tagging", "blocked=yes").body("x").when().put(bucket + "/blocked")
                .then().statusCode(403);
        owner().header("x-amz-object-lock-mode", "COMPLIANCE")
                .header("x-amz-object-lock-retain-until-date", "2031-01-01T00:00:00Z")
                .body("x").when().put(bucket + "/compliance").then().statusCode(403);
        owner().header("x-amz-object-lock-mode", "GOVERNANCE")
                .header("x-amz-object-lock-retain-until-date", "2031-01-01T00:00:00Z")
                .body("x").when().put(bucket + "/governance").then().statusCode(200);
        owner().header("x-amz-object-lock-legal-hold", "ON").body("x").when().put(bucket + "/held")
                .then().statusCode(403);
        owner().header("x-amz-object-lock-legal-hold", "OFF").body("x").when().put(bucket + "/released")
                .then().statusCode(200);
    }

    @Test
    void theTaggingRetentionAndLegalHoldBodiesOfferTheirKeys() {
        String bucket = lockedBucket();
        owner().body("x").when().put(bucket + "/doc").then().statusCode(200);
        policy(bucket, denyWhen("{\"StringEquals\":{\"s3:RequestObjectTag/env\":\"secret\"}}",
                "s3:PutObjectTagging")
                + "," + denyWhen("{\"StringEquals\":{\"s3:object-lock-mode\":\"COMPLIANCE\"}}",
                "s3:PutObjectRetention")
                + "," + denyWhen("{\"NumericGreaterThan\":{\"s3:object-lock-remaining-retention-days\":\"3650\"}}",
                "s3:PutObjectRetention")
                + "," + denyWhen("{\"StringEquals\":{\"s3:object-lock-legal-hold\":\"ON\"}}",
                "s3:PutObjectLegalHold"));

        owner().queryParam("tagging", "").body(tagging("secret")).when().put(bucket + "/doc").then().statusCode(403);
        owner().queryParam("tagging", "").body(tagging("open")).when().put(bucket + "/doc").then().statusCode(200);
        owner().queryParam("retention", "").body(retention("COMPLIANCE", "2031-01-01T00:00:00Z"))
                .when().put(bucket + "/doc").then().statusCode(403);
        owner().queryParam("retention", "").body(retention("GOVERNANCE", "2045-01-01T00:00:00Z"))
                .when().put(bucket + "/doc").then().statusCode(403);
        owner().queryParam("retention", "").body(retention("GOVERNANCE", "2031-01-01T00:00:00Z"))
                .when().put(bucket + "/doc").then().statusCode(200);
        owner().queryParam("legal-hold", "").body(legalHold("ON")).when().put(bucket + "/doc").then().statusCode(403);
        owner().queryParam("legal-hold", "").body(legalHold("OFF")).when().put(bucket + "/doc").then().statusCode(200);
    }

    @Test
    void onlyARequestOverridingAGovernanceLockNeedsTheBypassPermission() {
        // The permissions table requires s3:BypassGovernanceRetention "if you want to delete an object
        // that's protected by governance mode" and to "bypass the governance mode of an Object Lock
        // retention configuration".
        String bucket = lockedBucket();
        String locked = owner().header("x-amz-object-lock-mode", "GOVERNANCE")
                .header("x-amz-object-lock-retain-until-date", "2031-01-01T00:00:00Z")
                .body("locked").when().put(bucket + "/locked").then().statusCode(200)
                .extract().header("x-amz-version-id");
        String free = owner().body("free").when().put(bucket + "/free").then().statusCode(200)
                .extract().header("x-amz-version-id");
        owner().header("x-amz-object-lock-mode", "GOVERNANCE")
                .header("x-amz-object-lock-retain-until-date", "2031-01-01T00:00:00Z")
                .body("batch").when().put(bucket + "/batch").then().statusCode(200);
        policy(bucket, deny("s3:BypassGovernanceRetention"));

        // Extending the lock, deleting an unlocked version, and adding a delete marker override nothing.
        bypass().queryParam("retention", "").queryParam("versionId", locked)
                .body(retention("GOVERNANCE", "2032-01-01T00:00:00Z"))
                .when().put(bucket + "/locked").then().statusCode(200);
        bypass().queryParam("versionId", free).when().delete(bucket + "/free").then().statusCode(204);
        bypass().when().delete(bucket + "/locked").then().statusCode(204);
        bypass().contentType("application/xml").body("<Delete><Object><Key>batch</Key></Object></Delete>")
                .when().post(bucket + "?delete").then().statusCode(200)
                .body(containsString("<Deleted><Key>batch</Key>"));

        // Shortening the lock and deleting the locked version override it.
        bypass().queryParam("retention", "").queryParam("versionId", locked)
                .body(retention("GOVERNANCE", "2030-01-01T00:00:00Z"))
                .when().put(bucket + "/locked").then().statusCode(403);
        bypass().queryParam("versionId", locked).when().delete(bucket + "/locked").then().statusCode(403);
    }

    @Test
    void aRetentionChangeOnAMissingObjectOrBucketIsReportedMissing() {
        String bucket = lockedBucket();
        bypass().queryParam("retention", "").body(retention("GOVERNANCE", "2031-01-01T00:00:00Z"))
                .when().put(bucket + "/missing").then().statusCode(404).body(containsString("NoSuchKey"));
        bypass().queryParam("retention", "").body(retention("GOVERNANCE", "2031-01-01T00:00:00Z"))
                .when().put("/s3ops-policy-missing-" + UUID.randomUUID() + "/doc").then().statusCode(404)
                .body(containsString("NoSuchBucket"));
    }

    @Test
    void deletingAnAnnotationWithTheBypassHeaderNeedsNoBypassPermission() {
        // The user guide: "The BypassGovernanceRetention permission does not apply to annotation
        // operations."
        String bucket = lockedBucket();
        owner().body("x").when().put(bucket + "/doc").then().statusCode(200);
        owner().queryParam("annotation", "").queryParam("annotationName", "labels").body("x")
                .when().put(bucket + "/doc").then().statusCode(200);
        owner().queryParam("retention", "").body(retention("GOVERNANCE", "2031-01-01T00:00:00Z"))
                .when().put(bucket + "/doc").then().statusCode(200);
        policy(bucket, deny("s3:BypassGovernanceRetention"));

        bypass().queryParam("annotation", "").queryParam("annotationName", "labels")
                .when().delete(bucket + "/doc").then().statusCode(204);
    }

    private static RequestSpecification owner() {
        return given().filter(OWNER);
    }

    private static RequestSpecification bypass() {
        return owner().header("x-amz-bypass-governance-retention", "true");
    }

    /** A CopyObject replacing the source's tags, presigned by the owner over the destination's own query. */
    private static Response presignedCopy(String source, String destinationAndQuery) throws Exception {
        String query = OWNER.presignedQuery("PUT",
                URI.create("http://localhost:" + RestAssured.port + destinationAndQuery), 300);
        String destination = destinationAndQuery.substring(0, destinationAndQuery.indexOf('?'));
        return given().urlEncodingEnabled(false).header("x-amz-copy-source", source)
                .header("x-amz-tagging-directive", "REPLACE").when().put(destination + "?" + query);
    }

    private static Response attributes(String path) {
        return owner().queryParam("attributes", "").header("x-amz-object-attributes", "ETag").when().get(path);
    }

    /** A new bucket, with versioning on when asked, as its path. */
    private static String bucket(boolean versioned) {
        String bucket = "/s3ops-policy-" + UUID.randomUUID();
        owner().when().put(bucket).then().statusCode(200);
        if (versioned) {
            owner().body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                    .when().put(bucket + "?versioning").then().statusCode(200);
        }
        return bucket;
    }

    /** A new bucket with Object Lock enabled, as its path. */
    private static String lockedBucket() {
        String bucket = "/s3ops-policy-" + UUID.randomUUID();
        owner().header("x-amz-bucket-object-lock-enabled", "true").when().put(bucket).then().statusCode(200);
        return bucket;
    }

    private static String tagging(String env) {
        return "<Tagging><TagSet><Tag><Key>env</Key><Value>" + env + "</Value></Tag></TagSet></Tagging>";
    }

    private static String retention(String mode, String until) {
        return "<Retention><Mode>" + mode + "</Mode><RetainUntilDate>" + until + "</RetainUntilDate></Retention>";
    }

    private static String legalHold(String status) {
        return "<LegalHold><Status>" + status + "</Status></LegalHold>";
    }

    private static String denyWhen(String condition, String... actions) {
        String deny = deny(actions);
        return deny.substring(0, deny.length() - 1) + ",\"Condition\":" + condition + "}";
    }

    private static String deny(String... actions) {
        return "{\"Effect\":\"Deny\",\"Principal\":\"*\",\"Action\":[\"" + String.join("\",\"", actions)
                + "\"],\"Resource\":\"arn:aws:s3:::%s/*\"}";
    }

    private static void policy(String bucket, String statement) {
        owner().contentType("application/json")
                .body("{\"Version\":\"2012-10-17\",\"Statement\":[" + statement.replace("%s", bucket.substring(1))
                        + "]}")
                .when().put(bucket + "?policy").then().statusCode(200);
    }
}
